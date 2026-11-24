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
public class CardStack extends FrameLayout implements android.hardware.SensorEventListener {

    /** Tilt the stack with the phone (set from settings). */
    public static boolean TILT = true;

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
                        if (dragDir > 0) prepareIncoming(); else prepareOutgoing();
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
        for (int k = 0; k <= PEEK && k < n - 1; k++) {
            View v = cards.get((index + k) % n);
            v.animate().cancel();
            v.setLayerType(LAYER_TYPE_HARDWARE, null);
        }
    }

    /** Opacity of a card at a (fractional) slot: front 1, then 0.85, 0.6, then hidden. */
    private static float slotAlpha(float s) {
        float[] a = {1f, 0.85f, 0.6f, 0f};
        if (s <= 0) return 1f;
        if (s >= a.length - 1) return 0f;
        int i = (int) Math.floor(s);
        float f = s - i;
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    /** Puts a card at a fractional slot (0 = front). */
    private void place(View v, float s) {
        float c = Math.max(0, Math.min(PEEK, s));
        v.setTranslationY(c * 10 * dp);
        v.setScaleX(1 - 0.05f * c); v.setScaleY(1 - 0.05f * c);
        v.setAlpha(slotAlpha(s));
        v.setRotation(0);
    }

    /** Downward drag progress p (0..1): incoming slides down from above, every other card moves back one slot. */
    private void placeDown(float p) {
        p = Math.max(0, Math.min(1, p));
        if (incoming != null) incoming.setTranslationY((-cardH - 8 * dp) * (1 - p));
        int n = cards.size();
        for (int k = 0; k <= PEEK && k < n - 1; k++) place(cards.get((index + k) % n), k + p);
    }

    /** Upward drag: the mirror of a downward one. The front card slides up and away; the others move forward one slot. */
    private View outgoing;

    private void prepareOutgoing() {
        int n = cards.size();
        outgoing = front();
        if (outgoing == null) return;
        outgoing.animate().cancel();
        outgoing.setRotation(0);
        outgoing.setAlpha(1f);
        outgoing.setLayerType(LAYER_TYPE_HARDWARE, null);
        for (int k = 1; k <= PEEK + 1 && k < n; k++) {
            View v = cards.get((index + k) % n);
            v.animate().cancel();
            v.setLayerType(LAYER_TYPE_HARDWARE, null);
        }
    }

    private void placeUp(float p) {
        p = Math.max(0, Math.min(1, p));
        if (outgoing != null) {
            outgoing.setTranslationY((-cardH - 8 * dp) * p);
            outgoing.setScaleX(1f); outgoing.setScaleY(1f);
            outgoing.setAlpha(1f);
        }
        int n = cards.size();
        for (int k = 1; k <= PEEK + 1 && k < n; k++) place(cards.get((index + k) % n), k - p);
    }

    /** Finish (or undo) an upward drag, with the same easing as a downward one. */
    private void settleUp(float from, final boolean commit, float vy) {
        animating = true;
        float to = commit ? 1f : 0f;
        float dist = Math.abs(to - from) * (cardH + 8 * dp);
        long dur = (long) Math.max(160, Math.min(320, Math.abs(vy) > 1 ? dist / Math.abs(vy) * 1000 * 2.4f : dist / (cardH + 8 * dp) * 320));
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
        va.setDuration(dur);
        va.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) { placeUp((Float) a.getAnimatedValue()); }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                for (View v : cards) v.setLayerType(LAYER_TYPE_NONE, null);
                if (commit) index = (index + 1) % cards.size();
                outgoing = null;
                arrangeQuiet();
                animating = false;
                if (commit && listener != null) listener.onChanged(index);
            }
        });
        va.start();
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
                    placeUp(-dy / (cardH + 8 * dp));
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
                    float p = Math.max(0, Math.min(1, -dy / (cardH + 8 * dp)));
                    boolean commit = upOk && (p > 0.3f || (vy < -minFling && dy < -slop));
                    settleUp(p, commit, vy);
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
                for (View v : cards) v.setLayerType(LAYER_TYPE_NONE, null);
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

    /** The front card slides up and away; the next one comes forward. */
    public void next() {
        if (cards.size() < 2 || animating) return;
        prepareOutgoing();
        settleUp(0f, true, 0f);
    }

    /** The previous card slides in from above onto the front. */
    public void previous() {
        if (cards.size() < 2 || animating) return;
        prepareIncoming();
        settleDown(0f, true, 0f);
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

    // ------------------------------------------------------------------ tilt with the phone
    private android.hardware.SensorManager sensors;
    private float baseX = Float.NaN, baseY, rotX, rotY;
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path sheenPath = new android.graphics.Path();

    private void listen(boolean on) {
        if (on && TILT && sensors == null) {
            sensors = (android.hardware.SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensors == null) return;
            android.hardware.Sensor g = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
            if (g == null) g = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
            if (g == null) { sensors = null; return; }
            sensors.registerListener(this, g, android.hardware.SensorManager.SENSOR_DELAY_UI);
            setCameraDistance(9000 * dp);
        } else if (!on && sensors != null) {
            sensors.unregisterListener(this);
            sensors = null;
            baseX = Float.NaN;
        }
    }

    @Override
    protected void onAttachedToWindow() { super.onAttachedToWindow(); listen(getWindowVisibility() == VISIBLE); }

    @Override
    protected void onDetachedFromWindow() { super.onDetachedFromWindow(); listen(false); }

    /** Stop the sensor while the app is in the background. */
    @Override
    protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); listen(v == VISIBLE && isAttachedToWindow()); }

    @Override
    public void onSensorChanged(android.hardware.SensorEvent e) {
        float gx = e.values[0], gy = e.values[1];
        if (Float.isNaN(baseX)) { baseX = gx; baseY = gy; }
        // the resting angle follows slowly, so only fresh tilts move the cards
        baseX += (gx - baseX) * 0.015f;
        baseY += (gy - baseY) * 0.015f;
        float ty = Math.max(-6f, Math.min(6f, (gx - baseX) * -1.6f));
        float tx = Math.max(-6f, Math.min(6f, (gy - baseY) * 1.4f));
        rotX += (tx - rotX) * 0.18f;
        rotY += (ty - rotY) * 0.18f;
        if (dragging || animating) return;
        setPivotX(getWidth() / 2f);
        setPivotY(cardH / 2f);
        setRotationX(rotX);
        setRotationY(rotY);
        invalidate();
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor s, int a) { }

    /** Moving highlight over the front card, following the tilt. */
    private void drawSheen(Canvas c) {
        if (!TILT || sensors == null || cards.isEmpty()) return;
        View f = cards.get(index);
        if (f.getParent() != this || f.getAlpha() < 0.9f || f.getVisibility() != VISIBLE) return;
        float w = f.getWidth(), h = f.getHeight(), r = 24 * dp;
        float pos = 0.5f + rotY / 12f + rotX / 18f;          // 0..1 across the card
        float cx = w * pos, band = w * 0.45f;
        int a = (int) (0x30 * Math.min(1f, 0.35f + (Math.abs(rotX) + Math.abs(rotY)) / 6f));
        sheen.setShader(new android.graphics.LinearGradient(cx - band, 0, cx + band, h,
                new int[]{0, (a << 24) | 0xFFFFFF, 0}, new float[]{0, 0.5f, 1}, android.graphics.Shader.TileMode.CLAMP));
        sheenPath.reset();
        sheenPath.addRoundRect(0, 0, w, h, r, r, android.graphics.Path.Direction.CW);
        sheenPath.transform(f.getMatrix());
        sheenPath.offset(f.getLeft(), f.getTop());
        c.save();
        c.clipPath(sheenPath);
        c.translate(f.getLeft() + f.getTranslationX(), f.getTop() + f.getTranslationY());
        c.drawRect(0, 0, w, h, sheen);
        c.restore();
    }

    /** Position dots on the right edge. */
    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        drawSheen(c);
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
