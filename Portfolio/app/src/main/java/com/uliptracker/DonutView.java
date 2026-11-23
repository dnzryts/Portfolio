package com.uliptracker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Animated allocation donut. Tap a slice to show its share in the centre. */
public class DonutView extends View {
    private final List<double[]> slices = new ArrayList<>(); // [value, color]
    private final List<String> labels = new ArrayList<>();
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;
    private float progress = 0f;
    private int selected = -1;
    private String centerTop = "", centerBottom = "";

    public DonutView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.BUTT);
        center.setColor(Ui.TEXT);
        center.setTextAlign(Paint.Align.CENTER);
        center.setFakeBoldText(true);
        sub.setColor(Ui.SUB);
        sub.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(List<String> names, List<Double> values, int[] colors, String top, String bottom) {
        slices.clear(); labels.clear();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) <= 0) continue;
            slices.add(new double[]{values.get(i), colors[i % colors.length]});
            labels.add(names.get(i));
        }
        centerTop = top; centerBottom = bottom; selected = -1;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1000);
        a.setInterpolator(new DecelerateInterpolator(1.8f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) { progress = (Float) v.getAnimatedValue(); invalidate(); }
        });
        a.start();
    }

    private double total() { double t = 0; for (double[] s : slices) t += s[0]; return t; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN || slices.isEmpty()) return e.getAction() == MotionEvent.ACTION_DOWN;
        double ang = Math.toDegrees(Math.atan2(e.getY() - getHeight() / 2f, e.getX() - getWidth() / 2f)) + 90;
        if (ang < 0) ang += 360;
        double acc = 0, t = total();
        int hit = -1;
        for (int i = 0; i < slices.size(); i++) {
            acc += slices.get(i)[0] / t * 360;
            if (ang <= acc) { hit = i; break; }
        }
        selected = hit == selected ? -1 : hit;
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = size * 0.13f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = size / 2 - stroke / 2 - 4 * dp;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        double t = total();
        arc.setStrokeWidth(stroke);
        if (t <= 0) {
            arc.setColor(Ui.EMPTY);
            c.drawArc(rect, 0, 360, false, arc);
        } else {
            float start = -90, sweepTotal = 360 * progress, gap = slices.size() > 1 ? 2.2f : 0;
            for (int i = 0; i < slices.size(); i++) {
                float sweep = (float) (slices.get(i)[0] / t * sweepTotal);
                arc.setColor((int) slices.get(i)[1]);
                arc.setAlpha(selected < 0 || selected == i ? 255 : 90);
                arc.setStrokeWidth(selected == i ? stroke * 1.18f : stroke);
                c.drawArc(rect, start + gap / 2, Math.max(0.1f, sweep - gap), false, arc);
                start += sweep;
            }
            arc.setAlpha(255);
        }
        String top = centerTop, bottom = centerBottom;
        if (selected >= 0) {
            top = String.format(Locale.US, "%.1f%%", slices.get(selected)[0] / t * 100);
            bottom = labels.get(selected);
        }
        center.setTextSize(size * 0.12f);
        sub.setTextSize(size * 0.068f);
        c.drawText(top, cx, cy + size * 0.02f, center);
        String b = bottom.length() > 18 ? bottom.substring(0, 17) + "…" : bottom;
        c.drawText(b, cx, cy + size * 0.12f, sub);
    }
}
