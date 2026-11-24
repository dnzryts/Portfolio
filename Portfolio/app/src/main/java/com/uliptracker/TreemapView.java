package com.uliptracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Heat-map of holdings: each tile's area is its value, its colour its change (green up, red down, grey flat).
 * Squarified layout. Tap a tile to open the holding.
 */
public class TreemapView extends View {

    public static class Item {
        public final String label;
        public final double value;
        public final Double change;   // %, null if unknown
        public final Object tag;
        public final RectF rect = new RectF();
        public Item(String label, double value, Double change, Object tag) { this.label = label; this.value = value; this.change = change; this.tag = tag; }
    }

    public interface Listener { void onTap(Item it); }

    private final List<Item> items = new ArrayList<>();
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double scale = 3;    // % change that gives full colour
    private Listener listener;
    private float appear = 1f;
    private Item pressed;

    public TreemapView(Context c) {
        super(c);
        dp = Ui.dp(c);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(12.5f * dp);
        text.setFakeBoldText(true);
        text.setShadowLayer(3 * dp, 0, dp, 0x66000000);
        small.setColor(0xE6FFFFFF);
        small.setTextSize(11 * dp);
        small.setShadowLayer(3 * dp, 0, dp, 0x66000000);
    }

    public void setListener(Listener l) { listener = l; }

    public void setItems(List<Item> list, double fullColourAt) {
        items.clear();
        for (Item i : list) if (i.value > 0) items.add(i);
        Collections.sort(items, new Comparator<Item>() { @Override public int compare(Item a, Item b) { return Double.compare(b.value, a.value); } });
        scale = fullColourAt;
        requestLayout();
        invalidate();
    }

    public void play() {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(700);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator v) { appear = (Float) v.getAnimatedValue(); invalidate(); }
        });
        appear = 0f;
        a.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { layoutTiles(w, h); }

    private void layoutTiles(int w, int h) {
        if (w <= 0 || h <= 0 || items.isEmpty()) return;
        double[] v = new double[items.size()];
        for (int i = 0; i < v.length; i++) v[i] = items.get(i).value;
        float[][] r = squarify(v, w, h);
        for (int i = 0; i < v.length; i++) items.get(i).rect.set(r[i][0], r[i][1], r[i][2], r[i][3]);
    }

    /**
     * Squarified treemap (Bruls, Huizing, van Wijk) of values sorted largest first, in a w×h box.
     * Rows are laid along the shorter side while each new tile keeps the worst aspect ratio from getting worse.
     * Returns [left, top, right, bottom] per value.
     */
    static float[][] squarify(double[] values, float w, float h) {
        int n = values.length;
        float[][] out = new float[n][4];
        double total = 0;
        for (double v : values) total += v;
        if (total <= 0) return out;
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = values[i] * w * h / total;
        float L = 0, T = 0, R = w, B = h;
        int start = 0;
        while (start < n) {
            float bw = R - L, bh = B - T, side = Math.min(bw, bh);
            int end = start + 1;
            double best = worst(a, start, end, side);
            while (end < n) {
                double wr = worst(a, start, end + 1, side);
                if (wr > best) break;
                best = wr;
                end++;
            }
            double sum = 0;
            for (int i = start; i < end; i++) sum += a[i];
            if (bw >= bh) {                          // wide box: a column on the left
                float colW = end == n ? bw : (float) (sum / bh);
                float y = T;
                for (int i = start; i < end; i++) {
                    float hh = (float) (a[i] / colW);
                    out[i] = new float[]{L, y, L + colW, i == end - 1 ? B : y + hh};
                    y += hh;
                }
                L += colW;
            } else {                                 // tall box: a row along the top
                float rowH = end == n ? bh : (float) (sum / bw);
                float x = L;
                for (int i = start; i < end; i++) {
                    float ww = (float) (a[i] / rowH);
                    out[i] = new float[]{x, T, i == end - 1 ? R : x + ww, T + rowH};
                    x += ww;
                }
                T += rowH;
            }
            start = end;
        }
        return out;
    }

    private static double worst(double[] a, int s, int e, double side) {
        double sum = 0, mx = 0, mn = Double.MAX_VALUE;
        for (int i = s; i < e; i++) { sum += a[i]; mx = Math.max(mx, a[i]); mn = Math.min(mn, a[i]); }
        double s2 = side * side, sum2 = sum * sum;
        return Math.max(s2 * mx / sum2, sum2 / (s2 * mn));
    }

    private int colourFor(Double ch) {
        int flat = 0xFF4B5160;
        if (ch == null) return 0xFF3A3F4A;
        float t = (float) Math.min(1, Math.abs(ch) / scale);
        t = (float) Math.sqrt(t);          // small moves still show some colour
        return Fx.mix(flat, ch >= 0 ? 0xFF149A5B : 0xFFD43C3C, t);
    }

    @Override
    protected void onDraw(Canvas c) {
        float gap = 1.5f * dp, r = 7 * dp;
        RectF t = new RectF();
        for (Item it : items) {
            RectF b = it.rect;
            if (b.width() < 1 || b.height() < 1) continue;
            float cx = b.centerX(), cy = b.centerY();
            float s = 0.85f + 0.15f * appear;
            t.set(cx - (b.width() / 2) * s + gap, cy - (b.height() / 2) * s + gap, cx + (b.width() / 2) * s - gap, cy + (b.height() / 2) * s - gap);
            if (t.width() <= 0 || t.height() <= 0) continue;
            int col = colourFor(it.change);
            if (it == pressed) col = Fx.mix(col, 0xFFFFFFFF, 0.18f);
            fill.setColor(col);
            fill.setAlpha((int) (255 * appear));
            c.drawRoundRect(t, r, r, fill);
            if (t.width() > 52 * dp && t.height() > 34 * dp && appear > 0.6f) {
                float pad = 7 * dp;
                float avail = t.width() - 2 * pad;
                String name = fit(it.label, text, avail);
                float y = t.top + pad + text.getTextSize();
                c.drawText(name, t.left + pad, y, text);
                if (t.height() > 46 * dp) {
                    String pc = it.change == null ? "—" : String.format(Locale.US, "%+.2f%%", it.change);
                    c.drawText(pc, t.left + pad, y + small.getTextSize() + 3 * dp, small);
                }
            }
        }
    }

    private static String fit(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, w - p.measureText("…"), null);
        return n <= 1 ? s.substring(0, Math.min(1, s.length())) : s.substring(0, n).trim() + "…";
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        Item hit = null;
        for (Item it : items) if (it.rect.contains(e.getX(), e.getY())) { hit = it; break; }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: pressed = hit; invalidate(); return true;
            case MotionEvent.ACTION_MOVE: if (hit != pressed) { pressed = null; invalidate(); } return true;
            case MotionEvent.ACTION_UP:
                if (hit != null && hit == pressed && listener != null) listener.onTap(hit);
                pressed = null; invalidate(); return true;
            case MotionEvent.ACTION_CANCEL: pressed = null; invalidate(); return true;
        }
        return true;
    }
}
