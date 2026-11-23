package com.uliptracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Wallet-style stack of cards. The front card is fully visible; up to two cards peek out below it.
 * Swipe up: the front card flies up and goes to the back. Swipe down: the previous card drops in on top.
 * Horizontal swipes are left to the page (tab switching); taps go to the front card.
 */
public class CardStack extends FrameLayout {

    public interface Listener { void onChanged(int index); }

    private final List<View> cards = new ArrayList<>();
    private final float dp;
    private final int slop, minFling;
    private int index;
    private int cardH;
    private Listener listener;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float downX, downY;
    private boolean dragging, decided, animating;
    private int dragDir;          // -1 up (next), +1 down (previous)
    private View incoming;        // previous card sliding in from above during a downward drag
    private VelocityTracker vt;

    private static final int PEEK = 2;

    public CardStack(Context c, int cardHeightDp) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        cardH = (int) (cardHeightDp * dp);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = (int) (500 * dp);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
    }

    public void setListener(Listener l) { listener = l; }

    public int index() { return index; }

    public void setCards(List<View> views, int start) {
        removeAllViews();
        cards.clear();
        cards.addAll(views);
        index = cards.isEmpty() ? 0 : Math.max(0, Math.min(start, cards.size() - 1));
        for (View v : cards) addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, cardH));
        arrange(false);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int peek = (int) (Math.min(PEEK, Math.max(0, cards.size() - 1)) * 10 * dp);
        super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(cardH + peek + (int) (4 * dp), MeasureSpec.EXACTLY));
    }

    private int slot(View v) {
        int i = cards.indexOf(v);
        return ((i - index) % cards.size() + cards.size()) % cards.size();   // 0 = front
    }

    /** Positions every card for the current front index. */
    private void arrange(boolean animate) {
        int n = cards.size();
        for (int k = n - 1; k >= 0; k--) {          // back to front, so the front card is on top
            View v = cards.get((index + k) % n);
            v.bringToFront();
        }
        for (View v : cards) {
            int s = slot(v);
            float ty = s <= PEEK ? s * 10 * dp : PEEK * 10 * dp;
            float sc = s <= PEEK ? 1f - s * 0.05f : 1f - PEEK * 0.05f;
            float al = s == 0 ? 1f : s <= PEEK ? 0.85f - (s - 1) * 0.25f : 0f;
            v.setPivotY(cardH);
            v.setPivotX(getWidth() > 0 ? getWidth() / 2f : v.getWidth() / 2f);
            if (animate) {
                v.animate().translationY(ty).scaleX(sc).scaleY(sc).alpha(al).rotation(0).setDuration(320)
                        .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            } else {
                v.animate().cancel();
                v.setTranslationY(ty); v.setScaleX(sc); v.setScaleY(sc); v.setAlpha(al); v.setRotation(0);
            }
            v.setClickable(s == 0);
        }
        invalidate();
    }

    private View front() { return cards.isEmpty() ? null : cards.get(index); }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (cards.size() < 2 || animating) return animating;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); dragging = false; decided = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                getParent().requestDisallowInterceptTouchEvent(true);   // keep the page from scrolling for now
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (!decided && (dx > slop || dy > slop)) {
                    decided = true;
                    if (dy > dx * 1.2f) {
                        dragging = true;
                        dragDir = e.getY() > downY ? 1 : -1;
                        if (dragDir > 0) prepareIncoming();
                        return true;
                    }
                    getParent().requestDisallowInterceptTouchEvent(false);    // horizontal: let the pager have it
                }
                return dragging;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return false;
        }
        return false;
    }

    private void prepareIncoming() {
        int n = cards.size();
        incoming = cards.get((index - 1 + n) % n);
        incoming.animate().cancel();
        incoming.bringToFront();
        incoming.setScaleX(1f); incoming.setScaleY(1f);
        incoming.setRotation(0);
        incoming.setAlpha(1f);
        incoming.setTranslationY(-cardH - 8 * dp);
        incoming.setLayerType(LAYER_TYPE_HARDWARE, null);
        View f = front();
        if (f != null) f.setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Downward drag progress p (0..1): incoming slides down, front card eases back into slot 1. */
    private void placeDown(float p) {
        p = Math.max(0, Math.min(1, p));
        if (incoming != null) incoming.setTranslationY((-cardH - 8 * dp) * (1 - p));
        View f = front();
        if (f != null) {
            f.setTranslationY(10 * dp * p);
            f.setScaleX(1 - 0.05f * p); f.setScaleY(1 - 0.05f * p);
            f.setAlpha(1 - 0.15f * p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return cards.size() >= 2;
        if (vt != null) vt.addMovement(e);
        getParent().requestDisallowInterceptTouchEvent(true);
        View f = front();
        float dy = e.getY() - downY;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dragDir < 0) {
                    if (f != null) {
                        float t = Math.min(0, dy);        // only upward
                        f.setTranslationY(t);
                        f.setRotation(t / cardH * -4f);
                    }
                } else {
                    placeDown(dy / (cardH + 8 * dp));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vy = 0;
                if (vt != null) { vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                boolean upOk = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (dragDir < 0) {
                    if (upOk && (dy < -cardH * 0.25f || (vy < -minFling && dy < -slop))) next();
                    else arrange(true);
                } else {
                    float p = Math.max(0, Math.min(1, dy / (cardH + 8 * dp)));
                    boolean commit = upOk && (p > 0.3f || (vy > minFling && dy > slop));
                    settleDown(p, commit, vy);
                }
                return true;
            }
        }
        return true;
    }

    /** Finish (or undo) a downward drag from progress p with a speed-matched, bounce-free ease. */
    private void settleDown(float from, final boolean commit, float vy) {
        animating = true;
        float to = commit ? 1f : 0f;
        float dist = Math.abs(to - from) * (cardH + 8 * dp);
        long dur = (long) Math.max(160, Math.min(320, Math.abs(vy) > 1 ? dist / Math.abs(vy) * 1000 * 2.4f : dist / (cardH + 8 * dp) * 320));
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
        va.setDuration(dur);
        va.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) { placeDown((Float) a.getAnimatedValue()); }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                View f = front();
                if (f != null) f.setLayerType(LAYER_TYPE_NONE, null);
                if (incoming != null) incoming.setLayerType(LAYER_TYPE_NONE, null);
                if (commit) index = (index - 1 + cards.size()) % cards.size();
                incoming = null;
                arrangeQuiet();
                animating = false;
                if (commit && listener != null) listener.onChanged(index);
            }
        });
        va.start();
    }

    /** Snap every card to its slot without animating (positions already match after a drag). */
    private void arrangeQuiet() {
        int n = cards.size();
        for (int k = n - 1; k >= 0; k--) cards.get((index + k) % n).bringToFront();
        for (View v : cards) {
            int s = slot(v);
            v.animate().cancel();
            float ty = s <= PEEK ? s * 10 * dp : PEEK * 10 * dp;
            float sc = s <= PEEK ? 1f - s * 0.05f : 1f - PEEK * 0.05f;
            v.setTranslationY(ty); v.setScaleX(sc); v.setScaleY(sc); v.setRotation(0);
            v.setAlpha(s == 0 ? 1f : s <= PEEK ? 0.85f - (s - 1) * 0.25f : 0f);
            v.setClickable(s == 0);
        }
        // cards further back fade in smoothly to their slots
        invalidate();
    }

    /** Front card flies up, then tucks in at the back. */
    public void next() {
        if (cards.size() < 2 || animating) return;
        final View f = front();
        animating = true;
        f.animate().translationY(-cardH * 0.9f).rotation(-5).alpha(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                    @Override public void run() {
                        index = (index + 1) % cards.size();
                        f.setTranslationY(PEEK * 10 * dp + 20 * dp);
                        f.setRotation(0);
                        arrange(true);
                        animating = false;
                        if (listener != null) listener.onChanged(index);
                    }
                }).start();
        nudgeOthers(-1);
    }

    /** The previous card slides in from above onto the front. */
    public void previous() {
        if (cards.size() < 2 || animating) return;
        prepareIncoming();
        settleDown(0f, true, 0f);
    }

    /** Cards behind move up one slot while the front leaves. */
    private void nudgeOthers(int dir) {
        int n = cards.size();
        for (int k = 1; k < n && k <= PEEK + 1; k++) {
            View v = cards.get((index + k) % n);
            int s = k + dir;
            float ty = Math.max(0, Math.min(PEEK, s)) * 10 * dp;
            float sc = 1f - Math.max(0, Math.min(PEEK, s)) * 0.05f;
            v.animate().translationY(ty).scaleX(sc).scaleY(sc).alpha(s <= 0 ? 1f : 0.85f).setDuration(220).start();
        }
    }

    private final android.graphics.Path clipPath = new android.graphics.Path();

    /**
     * Cards are see-through, so a card only draws where no (mostly opaque) card sits on top of it.
     * This keeps back cards from showing through the front one; only their peeking edges are seen.
     */
    @Override
    protected boolean drawChild(Canvas c, View child, long time) {
        int i = indexOfChild(child), n = getChildCount();
        boolean saved = false;
        float r = 24 * dp;
        for (int j = i + 1; j < n; j++) {
            View o = getChildAt(j);
            if (o.getVisibility() != VISIBLE || o.getAlpha() < 0.5f) continue;
            if (!saved) { c.save(); saved = true; }
            clipPath.reset();
            clipPath.addRoundRect(0, 0, o.getWidth(), o.getHeight(), r, r, android.graphics.Path.Direction.CW);
            clipPath.transform(o.getMatrix());
            clipPath.offset(o.getLeft(), o.getTop());
            c.clipOutPath(clipPath);
        }
        boolean res = super.drawChild(c, child, time);
        if (saved) c.restore();
        return res;
    }

    /** Position dots on the right edge. */
    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        int n = cards.size();
        if (n < 2) return;
        float r = 3 * dp, gap = 9 * dp;
        float x = getWidth() - 12 * dp, y0 = cardH / 2f - (n - 1) * gap / 2;
        for (int i = 0; i < n; i++) {
            dotPaint.setColor(i == index ? 0xFFFFFFFF : 0x66FFFFFF);
            c.drawCircle(x, y0 + i * gap, i == index ? r * 1.2f : r * 0.9f, dotPaint);
        }
    }
}
