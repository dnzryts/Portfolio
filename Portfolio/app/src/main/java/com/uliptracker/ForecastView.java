package com.uliptracker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Fan chart of a forecast: the 10–90% and 25–75% ranges as shaded bands, the median as a line and the money put in
 * as a dashed line. Drag sideways (or hold) to read any year; an optional goal is drawn as a marker.
 */
public class ForecastView extends View {

    public interface Listener { void onYear(int year); }

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            dash = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
    private double[] p10, p25, p50, p75, p90, put;
    private int startYear;
    private double goal = -1;
    private int goalYear = -1;
    private int color = Ui.ACCENT;
    private float progress = 1f;
    private int touch = -1;
    private Listener listener;
    private float downX, downY;
    private boolean scrubbing;

    public ForecastView(Context c) {
        super(c);
        dp = Ui.dp(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.4f * dp);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(1.6f * dp);
        dash.setPathEffect(new DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
        text.setTextSize(10.5f * dp);
        grid.setStrokeWidth(Math.max(1, dp * 0.8f));
    }

    public void setListener(Listener l) { listener = l; }

    public void setData(double[] p10, double[] p25, double[] p50, double[] p75, double[] p90, double[] put, int startYear, int color) {
        this.p10 = p10; this.p25 = p25; this.p50 = p50; this.p75 = p75; this.p90 = p90; this.put = put;
        this.startYear = startYear; this.color = color;
        invalidate();
    }

    public void setGoal(double amount, int year) { goal = amount; goalYear = year; invalidate(); }

    public void play() {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(800);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) { progress = (Float) v.getAnimatedValue(); invalidate(); }
        });
        a.start();
    }

    private float left() { return 8 * dp; }
    private float right() { return getWidth() - 52 * dp; }
    private float top() { return 10 * dp; }
    private float bottom() { return getHeight() - 22 * dp; }

    private double maxY() {
        double m = 1;
        for (double v : p90) m = Math.max(m, v);
        if (goal > 0) m = Math.max(m, goal * 1.05);
        return niceCeil(m);
    }

    private static double niceCeil(double v) {
        double e = Math.pow(10, Math.floor(Math.log10(v)));
        double[] steps = {1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10};
        for (double s : steps) if (s * e >= v) return s * e;
        return 10 * e;
    }

    private float x(int i) { return left() + (right() - left()) * i / Math.max(1, p50.length - 1); }
    private float y(double v, double max) { return (float) (bottom() - (bottom() - top()) * v / max); }

    @Override
    protected void onDraw(Canvas c) {
        if (p50 == null || p50.length < 2) return;
        double max = maxY();
        int n = p50.length;
        // grid + labels on the right
        text.setColor(Ui.SUB);
        text.setTextAlign(Paint.Align.LEFT);
        grid.setColor(Ui.GRID);
        for (int g = 0; g <= 3; g++) {
            double v = max * g / 3;
            float yy = y(v, max);
            c.drawLine(left(), yy, right(), yy, grid);
            c.drawText(Analytics.shortRupees(v), right() + 6 * dp, yy + 4 * dp, text);
        }
        text.setTextAlign(Paint.Align.CENTER);
        int step = n - 1 <= 10 ? (n - 1 <= 5 ? 1 : 2) : 5;
        for (int i = 0; i < n; i += step) c.drawText(String.valueOf(startYear + i), x(i), getHeight() - 6 * dp, text);
        c.save();
        c.clipRect(0, 0, left() + (right() - left()) * progress + 1, getHeight());
        band(c, p10, p90, max, 0x24);
        band(c, p25, p75, max, 0x3D);
        // money put in
        Path pp = new Path();
        for (int i = 0; i < n; i++) { if (i == 0) pp.moveTo(x(i), y(put[i], max)); else pp.lineTo(x(i), y(put[i], max)); }
        dash.setColor(Ui.SUB);
        c.drawPath(pp, dash);
        // median
        Path mp = new Path();
        for (int i = 0; i < n; i++) { if (i == 0) mp.moveTo(x(i), y(p50[i], max)); else mp.lineTo(x(i), y(p50[i], max)); }
        line.setColor(color);
        c.drawPath(mp, line);
        c.restore();
        // goal marker
        if (goal > 0 && goalYear >= 0 && goalYear < n) {
            float gy = y(goal, max), gx = x(goalYear);
            Paint gp = new Paint(dash);
            gp.setColor(Ui.GREEN);
            c.drawLine(left(), gy, gx, gy, gp);
            fill.setColor(Ui.GREEN);
            c.drawCircle(gx, gy, 5 * dp, fill);
            text.setColor(Ui.GREEN);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("Goal", left() + 2 * dp, gy - 5 * dp, text);
        }
        // scrub marker
        if (touch >= 0 && touch < n) {
            float tx = x(touch);
            grid.setColor(Ui.ZERO);
            c.drawLine(tx, top(), tx, bottom(), grid);
            fill.setColor(color);
            c.drawCircle(tx, y(p50[touch], max), 5 * dp, fill);
            fill.setColor(Ui.CARD);
            c.drawCircle(tx, y(p50[touch], max), 2.4f * dp, fill);
        }
    }

    private void band(Canvas c, double[] lo, double[] hi, double max, int alpha) {
        Path p = new Path();
        int n = lo.length;
        for (int i = 0; i < n; i++) { if (i == 0) p.moveTo(x(i), y(hi[i], max)); else p.lineTo(x(i), y(hi[i], max)); }
        for (int i = n - 1; i >= 0; i--) p.lineTo(x(i), y(lo[i], max));
        p.close();
        fill.setColor((color & 0x00FFFFFF) | (alpha << 24));
        c.drawPath(p, fill);
    }

    private final Runnable hold = new Runnable() { @Override public void run() { scrubbing = true; getParent().requestDisallowInterceptTouchEvent(true); } };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (p50 == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); scrubbing = false;
                postDelayed(hold, 250);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (!scrubbing && dy > 10 * dp && dy > dx) { removeCallbacks(hold); return false; }   // vertical: let the page scroll
                if (!scrubbing && dx > 8 * dp) { scrubbing = true; getParent().requestDisallowInterceptTouchEvent(true); }
                if (scrubbing) pick(e.getX());
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(hold);
                pick(e.getX());
                scrubbing = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                scrubbing = false;
                return true;
        }
        return true;
    }

    private void pick(float px) {
        int n = p50.length;
        int i = Math.round((px - left()) / (right() - left()) * (n - 1));
        i = Math.max(0, Math.min(n - 1, i));
        if (i != touch) {
            touch = i;
            invalidate();
            if (listener != null) listener.onYear(i);
        }
    }
}
