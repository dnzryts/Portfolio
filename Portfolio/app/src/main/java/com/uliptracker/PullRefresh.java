package com.uliptracker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * Pull down from the top of a page to refresh. A round spinner follows the finger (the page itself doesn't move
 * or stretch); letting go past the threshold starts a refresh and the spinner keeps turning until stop().
 */
public class PullRefresh extends FrameLayout {

    public interface Listener {
        /** True when the visible page is scrolled to the top and a pull may start at this point. */
        boolean canPull(float x, float y);
        void onRefresh();
    }

    private final float dp, slop, size, rest, trigger, max;
    private final Spinner spin;
    private Listener listener;
    private float downX, downY, lastPull;
    private boolean pulling, maybe, spinning;
    private ValueAnimator settle;

    public PullRefresh(Context c) {
        super(c);
        dp = Ui.dp(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        size = 40 * dp;
        rest = 18 * dp;        // where the spinner sits while refreshing (top edge)
        trigger = 78 * dp;     // finger travel (after resistance) needed to refresh
        max = 120 * dp;
        spin = new Spinner(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Ui.CARD);
        spin.setBackground(bg);
        spin.setElevation(6 * dp);
        spin.setVisibility(INVISIBLE);
        LayoutParams lp = new LayoutParams((int) size, (int) size, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addView(spin, lp);
        spin.setTranslationY(-size);
    }

    public void setListener(Listener l) { listener = l; }

    /** Keeps the spinner below the status bar when the app draws full screen. */
    public void setTopInset(int px) {
        LayoutParams lp = (LayoutParams) spin.getLayoutParams();
        lp.topMargin = px;
        spin.setLayoutParams(lp);
    }

    /** Content goes underneath the spinner. */
    public void setContent(View v) {
        addView(v, 0, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public boolean isSpinning() { return spinning; }

    /** Show the spinner at rest, turning (also used when refresh starts from the ↻ button). */
    public void start() {
        if (spinning) return;
        spinning = true;
        spin.setVisibility(VISIBLE);
        spin.bringToFront();
        moveTo(rest, 1f, 220);
        spin.setSpinning(true);
    }

    public void stop() {
        if (!spinning && !pulling) return;
        spinning = false;
        spin.setSpinning(false);
        spin.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() {
                if (spinning) return;
                spin.setVisibility(INVISIBLE);
                spin.setScaleX(1f); spin.setScaleY(1f);
                spin.setTranslationY(-size);
            }
        }).start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (spinning || listener == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                maybe = listener.canPull(e.getX(), e.getY());
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!maybe) break;
                float dy = e.getY() - downY, dx = Math.abs(e.getX() - downX);
                if (dy < -slop || dx > slop * 1.5f && dx > dy) { maybe = false; break; }   // scrolling up or sideways
                if (dy > slop && dy > dx * 1.6f && listener.canPull(downX, downY)) {
                    pulling = true;
                    downY = e.getY();     // start from here so the spinner doesn't jump
                    if (settle != null) settle.cancel();
                    spin.animate().cancel();
                    spin.setScaleX(1f); spin.setScaleY(1f);
                    spin.setVisibility(VISIBLE);
                    spin.bringToFront();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                maybe = false;
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!pulling) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float d = Math.max(0, e.getY() - downY);
                // resistance: easy at first, firmer near the end
                float pull = max * (1 - (float) Math.exp(-d / (max * 1.4f)));
                lastPull = pull;
                spin.setTranslationY(pull - size);
                float p = Math.min(1f, pull / trigger);
                spin.setProgress(p, pull >= trigger);
                spin.setAlpha(0.35f + 0.65f * p);
                return true;
            }
            case MotionEvent.ACTION_UP:
                pulling = false;
                if (lastPull >= trigger) {
                    spinning = true;
                    spin.setAlpha(1f);
                    moveTo(rest, 1f, 180);
                    spin.setSpinning(true);
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.onRefresh();
                } else {
                    moveTo(-size, spin.getAlpha(), 200);
                }
                lastPull = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                moveTo(-size, spin.getAlpha(), 200);
                lastPull = 0;
                return true;
        }
        return true;
    }

    private void moveTo(final float y, float alpha, long ms) {
        if (settle != null) settle.cancel();
        final float from = spin.getTranslationY() + size;
        final float to = y + size;
        settle = ValueAnimator.ofFloat(0f, 1f);
        settle.setDuration(ms);
        settle.setInterpolator(new DecelerateInterpolator(1.6f));
        settle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                spin.setTranslationY(from + (to - from) * f - size);
            }
        });
        settle.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (to <= 0 && !spinning) spin.setVisibility(INVISIBLE);
            }
        });
        if (y >= 0) spin.setAlpha(alpha);
        settle.start();
    }

    /** The arc inside the circle: grows with the pull, then turns while refreshing. */
    private static class Spinner extends View {
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float progress, angle;
        private boolean armed, turning;
        private ValueAnimator rot;

        Spinner(Context c) {
            super(c);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setStrokeWidth(3 * Ui.dp(c));
        }

        void setProgress(float p, boolean armed) { progress = p; this.armed = armed; angle = p * 270; invalidate(); }

        void setSpinning(boolean on) {
            turning = on;
            if (rot != null) rot.cancel();
            if (!on) return;
            rot = ValueAnimator.ofFloat(0f, 1f);
            rot.setDuration(900);
            rot.setRepeatCount(ValueAnimator.INFINITE);
            rot.setInterpolator(new LinearInterpolator());
            rot.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) { angle = (Float) a.getAnimatedValue() * 360; invalidate(); }
            });
            rot.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (rot != null) rot.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), pad = w * 0.28f;
            r.set(pad, pad, w - pad, w - pad);
            arc.setColor(armed || turning ? Ui.ACCENT : Ui.SUB);
            if (turning) {
                long t = System.currentTimeMillis() % 1600;
                float sweep = 60 + 200 * (float) Math.abs(Math.sin(Math.PI * t / 1600.0));
                c.drawArc(r, angle - 90, sweep, false, arc);
            } else {
                c.drawArc(r, angle - 90, Math.max(8, 300 * progress), false, arc);
            }
        }
    }
}
