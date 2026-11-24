package com.uliptracker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * A one-line TextView whose changed characters roll like an odometer when the value updates: old digits slide
 * out and new ones slide in (upwards when the value rose, downwards when it fell), column by column.
 * Remembers the last text shown for each key, so a freshly built screen still rolls from the previous value.
 */
public class RollText extends TextView {

    private static final Map<String, String> LAST = new HashMap<>();
    private static final Map<String, Double> LAST_V = new HashMap<>();

    private String from, to;
    private int dir = 1;
    private float p = 1f;

    public RollText(Context c) {
        super(c);
        setSingleLine(true);
    }

    /**
     * Shows `text` for `value`. Rolls from the previous text under `key` if it differs.
     * @return true if it rolled (so callers can skip a count-up)
     */
    public boolean show(String key, String text, double value) {
        String old = LAST.get(key);
        Double ov = LAST_V.get(key);
        LAST.put(key, text);
        LAST_V.put(key, value);
        setText(text);
        if (old == null || old.equals(text) || Ui.PRIVATE || old.contains("•") || text.contains("•")) return false;
        from = old;
        to = text;
        dir = ov != null && value < ov ? -1 : 1;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setStartDelay(250);
        a.setDuration(650 + 22L * text.length());
        a.setInterpolator(new DecelerateInterpolator(1.4f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) { p = (Float) v.getAnimatedValue(); invalidate(); }
        });
        p = 0f;
        a.start();
        return true;
    }

    /** True if `key` has been shown before (so a count-up from zero would look wrong). */
    public static boolean seen(String key) { return LAST.containsKey(key); }

    @Override
    protected void onDraw(Canvas c) {
        if (p >= 1f || from == null || to == null || getLayout() == null) { super.onDraw(c); return; }
        Paint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        Layout l = getLayout();
        float x0 = getCompoundPaddingLeft() + l.getLineLeft(0);
        float base = getExtendedPaddingTop() + l.getLineBaseline(0);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float top = base + fm.ascent, bottom = base + fm.descent, h = bottom - top;
        c.save();
        c.clipRect(0, top - 2, getWidth(), bottom + 2);
        if (from.length() != to.length()) {
            // different lengths: slide the whole value as one
            float e = p;
            drawAlpha(c, from, x0, base - dir * e * h, 1 - e, paint);
            drawAlpha(c, to, x0, base + dir * (1 - e) * h, e, paint);
        } else {
            int n = to.length();
            float x = x0;
            for (int i = 0; i < n; i++) {
                String nc = to.substring(i, i + 1), oc = from.substring(i, i + 1);
                float w = paint.measureText(nc);
                if (nc.equals(oc)) {
                    c.drawText(nc, x, base, paint);
                } else {
                    // later columns start a little later, like a mechanical counter
                    float start = 0.35f * i / Math.max(1, n - 1);
                    float e = Math.max(0, Math.min(1, (p - start) / (1 - start)));
                    drawAlpha(c, oc, x, base - dir * e * h, 1 - e, paint);
                    drawAlpha(c, nc, x, base + dir * (1 - e) * h, e, paint);
                }
                x += w;
            }
        }
        c.restore();
    }

    private static void drawAlpha(Canvas c, String s, float x, float y, float a, Paint p) {
        int old = p.getAlpha();
        p.setAlpha((int) (old * Math.max(0, Math.min(1, a))));
        c.drawText(s, x, y, p);
        p.setAlpha(old);
    }
}
