package com.uliptracker;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

/**
 * Pager-style container. When a horizontal drag starts, the neighbouring page is built and both
 * pages move together under the finger (translation only, on hardware layers). On release it
 * settles with a duration matched to the fling speed; otherwise it springs back.
 */
public class SwipeFrame extends FrameLayout {

    public interface Listener {
        /** dir = +1 next page (finger moves left), -1 previous (finger moves right). */
        boolean canSwipe(int dir);
        /** Build the page that would appear for dir (not attached yet). */
        View buildNeighbour(int dir);
        /** The neighbour is now fully on screen and the old page has been removed. */
        void onSwitched(int dir, View newPage);
    }

    private static final Interpolator SETTLE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private Listener listener;
    private final int slop, minFling, startDist, minFlingDist;
    private boolean blocked;   // gesture began on something that handles its own horizontal drags
    private float downX, downY, startX;
    private boolean dragging, allowed, animating;
    private int dir;
    private View current, neighbour;
    private VelocityTracker vt;

    public SwipeFrame(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        float d = c.getResources().getDisplayMetrics().density;
        slop = vc.getScaledTouchSlop();
        minFling = (int) (600 * d);
        startDist = Math.max(slop * 2, (int) (22 * d));   // a tap with a little drift never starts a swipe
        minFlingDist = (int) (56 * d);                     // a flick must also travel this far
    }

    public void setListener(Listener l) { listener = l; }

    public boolean isBusy() { return dragging || animating; }

    private View top() { return getChildCount() > 0 ? getChildAt(getChildCount() - 1) : null; }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (listener == null || animating) return animating;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); dragging = false;
                blocked = touchesOwnHorizontal(this, e.getX(), e.getY());
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (blocked) return false;
                if (!dragging && Math.abs(dx) > startDist && Math.abs(dx) > Math.abs(dy) * 2.2f) {
                    startDrag(dx < 0 ? 1 : -1, e.getX());
                }
                return dragging;
            }
            default:
                return false;
        }
    }

    private void startDrag(int d, float x) {
        dir = d;
        dragging = true;
        startX = x;
        getParent().requestDisallowInterceptTouchEvent(true);
        current = top();
        allowed = listener.canSwipe(dir);
        neighbour = null;
        if (allowed) {
            neighbour = listener.buildNeighbour(dir);
            if (neighbour != null) {
                addView(neighbour, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                neighbour.setTranslationX(dir * getWidth());
                neighbour.setLayerType(LAYER_TYPE_HARDWARE, null);
            } else allowed = false;
        }
        if (current != null) current.setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void place(float t) {
        if (current != null) current.setTranslationX(t);
        if (neighbour != null) neighbour.setTranslationX(t + dir * getWidth());
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return true;
        if (vt != null) vt.addMovement(e);
        float dx = e.getX() - startX;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (allowed) {
                    // only move toward the neighbour; a little rubber-band the other way
                    place(Math.signum(dx) == -dir ? dx : dx * 0.2f);
                } else {
                    place(dx * 0.22f);   // edge: resist
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vx = 0;
                if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                dragging = false;
                float t = current != null ? current.getTranslationX() : 0;
                boolean far = Math.abs(t) > getWidth() * 0.33f && Math.signum(t) == -dir;
                boolean fling = Math.abs(vx) > minFling && Math.signum(vx) == -dir && Math.abs(t) > minFlingDist;
                boolean reverseFling = Math.abs(vx) > minFling && Math.signum(vx) == dir;
                if (allowed && e.getActionMasked() == MotionEvent.ACTION_UP && (fling || (far && !reverseFling))) {
                    settle(t, -dir * getWidth(), vx, true);
                } else {
                    settle(t, 0, vx, false);
                }
                return true;
            }
        }
        return true;
    }

    /** Animate from the current offset to `to`; duration follows the remaining distance and speed. */
    private void settle(float from, final float to, float velocity, final boolean switching) {
        float dist = Math.abs(to - from);
        int w = Math.max(1, getWidth());
        long dur = (long) (dist / w * 320);
        if (Math.abs(velocity) > 0) dur = Math.min(dur, (long) (dist / Math.abs(velocity) * 1000 * 2.2f));
        dur = Math.max(140, Math.min(320, dur));
        animating = true;
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(dur);
        a.setInterpolator(switching ? SETTLE : new DecelerateInterpolator(1.8f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) { place((Float) v.getAnimatedValue()); }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) { finish(switching); }
        });
        a.start();
    }

    private void finish(boolean switched) {
        animating = false;
        if (switched && neighbour != null) {
            if (current != null) removeView(current);
            neighbour.setLayerType(LAYER_TYPE_NONE, null);
            neighbour.setTranslationX(0);
            View n = neighbour;
            current = n; neighbour = null;
            listener.onSwitched(dir, n);
        } else {
            if (neighbour != null) removeView(neighbour);
            neighbour = null;
            if (current != null) { current.setTranslationX(0); current.setLayerType(LAYER_TYPE_NONE, null); }
        }
    }

    /**
     * True if the touch lands on a view with its own horizontal gesture: a chip row that can scroll,
     * a full chart (drag to read values) or the allocation donut.
     */
    private static boolean touchesOwnHorizontal(android.view.ViewGroup g, float x, float y) {
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
            float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
            if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight()) continue;
            if (c instanceof android.widget.HorizontalScrollView
                    && (c.canScrollHorizontally(1) || c.canScrollHorizontally(-1))) return true;
            if (c instanceof ChartView && !((ChartView) c).isSpark()) return true;
            if (c instanceof DonutView) return true;
            if (c instanceof ForecastView) return true;
            if (c instanceof android.view.ViewGroup) return touchesOwnHorizontal((android.view.ViewGroup) c, cx, cy);
            return false;
        }
        return false;
    }

    /** Programmatic slide (bottom bar taps, back): same motion as a swipe. */
    public void slideTo(int d, View newPage) {
        if (animating || dragging) return;
        dir = d;
        current = top();
        neighbour = newPage;
        addView(neighbour, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        neighbour.setTranslationX(dir * (getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels));
        neighbour.setLayerType(LAYER_TYPE_HARDWARE, null);
        if (current != null) current.setLayerType(LAYER_TYPE_HARDWARE, null);
        settle(0, -dir * getWidth(), 0, true);
    }
}
