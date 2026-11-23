package com.uliptracker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Animated chart. Modes: AREA, LINE, BARS (monthly/weekly % change).
 * Geometry is built once per data/size change, so scrolling and animation only redraw cached paths.
 * Vertical drags scroll the page; drag sideways (or hold) to read values.
 */
public class ChartView extends View {

    public static final int AREA = 0, LINE = 1, BARS = 2;

    public interface Formatter { String format(double v); }

    public static class Series {
        final String label; final int color; final long[] days; final double[] vals;
        public Series(String label, int color, TreeMap<Long, Double> data, long fromDay) {
            this.label = label; this.color = color;
            Map<Long, Double> sub = data.tailMap(fromDay, true);
            days = new long[sub.size()]; vals = new double[sub.size()];
            int i = 0;
            for (Map.Entry<Long, Double> e : sub.entrySet()) { days[i] = e.getKey(); vals[i] = e.getValue(); i++; }
        }
    }

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH);
    private static final DateTimeFormatter MF = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    private final List<Series> series = new ArrayList<>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), zero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG), tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final boolean spark;
    private final float dp;
    private final int slop;
    private int mode = AREA;
    private boolean percent;
    private float progress = 1f;
    private ValueAnimator anim;
    private Formatter fmt = new Formatter() {
        @Override public String format(double v) { return String.format(Locale.US, "%.2f", v); }
    };

    // touch
    private float touchX = -1, downX, downY;
    private boolean scrubbing;
    private final Runnable hold = new Runnable() {
        @Override public void run() {
            scrubbing = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
        }
    };

    // cached geometry
    private boolean dirty = true;
    private int cw, chh;
    private final List<Path> paths = new ArrayList<>();
    private final List<float[]> pxs = new ArrayList<>(), pys = new ArrayList<>();
    private Path fillPath;
    private Shader fillShader;
    private double cMin, cMax;
    private long cD0, cD1;
    private float cTop, cBottom, cLeft, cRight;
    private String[] gridLabels = new String[0];
    private String dateL = "", dateR = "";
    // bars cache
    private double[] bars = new double[0];
    private long[] barDays = new long[0];
    private boolean weekly;

    public ChartView(Context c, boolean spark) {
        super(c);
        this.spark = spark;
        dp = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth((spark ? 1.6f : 2.4f) * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        grid.setColor(Ui.GRID);
        grid.setStrokeWidth(dp);
        zero.setColor(Ui.ZERO);
        zero.setStrokeWidth(dp);
        text.setColor(Ui.SUB);
        text.setTextSize(10.5f * dp);
        tip.setTextSize(12 * dp);
        tip.setFakeBoldText(true);
        tipBg.setColor(Ui.TIP);
    }

    public boolean isSpark() { return spark; }

    public void setFormatter(Formatter f) { fmt = f; dirty = true; }

    public void setData(TreeMap<Long, Double> data, long fromDay, int color) {
        List<Series> l = new ArrayList<>();
        l.add(new Series("", color, data, fromDay));
        setSeries(l, mode, percent, false);
    }

    public void setSeries(List<Series> s, int mode, boolean percent, boolean animate) {
        series.clear();
        series.addAll(s);
        this.mode = mode;
        this.percent = percent;
        touchX = -1;
        dirty = true;
        if (animate) play(); else { progress = 1f; invalidate(); }
    }

    public void play() {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(spark ? 500 : 750);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { progress = (Float) a.getAnimatedValue(); invalidate(); }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
        removeCallbacks(hold);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); dirty = true; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (spark || series.isEmpty()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); scrubbing = false;
                touchX = e.getX();
                postDelayed(hold, 280);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (!scrubbing) {
                    if (dy > slop && dy >= dx) { removeCallbacks(hold); touchX = -1; return false; }
                    if (dx > slop) {
                        scrubbing = true;
                        removeCallbacks(hold);
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (scrubbing) { touchX = e.getX(); invalidate(); }
                return true;
            }
            default:
                removeCallbacks(hold);
                scrubbing = false;
                touchX = -1;
                invalidate();
                return true;
        }
    }

    private double y(Series s, int i) {
        return percent && s.vals.length > 0 ? (s.vals[i] / s.vals[0] - 1) * 100 : s.vals[i];
    }

    // ------------------------------------------------------------------ build caches
    private void build(float w, float h) {
        dirty = false; cw = (int) w; chh = (int) h;
        paths.clear(); pxs.clear(); pys.clear(); fillPath = null; fillShader = null;
        if (series.isEmpty()) return;
        if (mode == BARS) { buildBars(series.get(0)); return; }
        long d0 = Long.MAX_VALUE, d1 = Long.MIN_VALUE;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Series s : series) {
            if (s.days.length < 2) continue;
            d0 = Math.min(d0, s.days[0]); d1 = Math.max(d1, s.days[s.days.length - 1]);
            for (int i = 0; i < s.days.length; i++) { double v = y(s, i); min = Math.min(min, v); max = Math.max(max, v); }
        }
        if (d0 == Long.MAX_VALUE) return;
        if (max - min < 1e-9) { max += 1; min -= 1; }
        double pad = (max - min) * 0.1;
        min -= pad; max += pad;
        float legendH = (!spark && series.size() > 1) ? 18 * dp : 0;
        cTop = spark ? 2 * dp : 26 * dp + legendH;
        cBottom = h - (spark ? 2 * dp : 22 * dp);
        cLeft = spark ? 0 : 2 * dp;
        cRight = w - (spark ? 0 : 2 * dp);
        cMin = min; cMax = max; cD0 = d0; cD1 = d1;
        float span = Math.max(1, d1 - d0);
        for (Series s : series) {
            Path p = new Path();
            int n = s.days.length;
            float[] xs = new float[n], ys = new float[n];
            int step = Math.max(1, (int) (n / Math.max(1f, (cRight - cLeft) / dp)));  // ~1 point per dp
            boolean first = true;
            for (int i = 0; i < n; i++) {
                xs[i] = cLeft + (cRight - cLeft) * (s.days[i] - d0) / span;
                ys[i] = (float) (cBottom - (cBottom - cTop) * (y(s, i) - min) / (max - min));
                if (i % step != 0 && i != n - 1) continue;
                if (first) { p.moveTo(xs[i], ys[i]); first = false; } else p.lineTo(xs[i], ys[i]);
            }
            paths.add(n >= 2 ? p : null);
            pxs.add(xs); pys.add(ys);
        }
        if (mode == AREA && series.size() == 1 && paths.get(0) != null) {
            float[] xs = pxs.get(0);
            fillPath = new Path(paths.get(0));
            fillPath.lineTo(xs[xs.length - 1], cBottom);
            fillPath.lineTo(xs[0], cBottom);
            fillPath.close();
            int col = series.get(0).color;
            fillShader = new LinearGradient(0, cTop, 0, cBottom, (col & 0x00FFFFFF) | 0x66000000,
                    col & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
        if (!spark) {
            gridLabels = new String[4];
            for (int g = 0; g <= 3; g++) {
                double val = max - (max - min) * g / 3.0;
                gridLabels[g] = percent ? Ui.pct(val) : fmt.format(val);
            }
            dateL = LocalDate.ofEpochDay(d0).format(DF);
            dateR = LocalDate.ofEpochDay(d1).format(DF);
        }
    }

    private void buildBars(Series s) {
        bars = new double[0]; barDays = new long[0];
        if (s.days.length < 2) return;
        weekly = s.days[s.days.length - 1] - s.days[0] < 120;
        List<long[]> buckets = new ArrayList<>();
        long prevKey = Long.MIN_VALUE;
        for (int i = 0; i < s.days.length; i++) {
            LocalDate d = LocalDate.ofEpochDay(s.days[i]);
            long key = weekly ? (s.days[i] + 3) / 7 : d.getYear() * 12L + d.getMonthValue();
            if (key != prevKey) { buckets.add(new long[]{key, i}); prevKey = key; }
            else buckets.get(buckets.size() - 1)[1] = i;
        }
        int n = buckets.size() - 1;
        int start = Math.max(0, n - 24);
        bars = new double[n - start]; barDays = new long[n - start];
        for (int b = start + 1; b <= n; b++) {
            double a = s.vals[(int) buckets.get(b - 1)[1]], z = s.vals[(int) buckets.get(b)[1]];
            bars[b - 1 - start] = (z / a - 1) * 100;
            barDays[b - 1 - start] = s.days[(int) buckets.get(b)[1]];
        }
    }

    // ------------------------------------------------------------------ draw
    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (dirty || cw != (int) w || chh != (int) h) build(w, h);
        Series main = series.isEmpty() ? null : series.get(0);
        if (main == null || main.days.length < 2) {
            if (!spark) {
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(main == null || main.days.length == 0 ? "No prices yet" : "Need 2+ dates for a chart", w / 2, h / 2, text);
            }
            return;
        }
        if (mode == BARS) { drawBars(c, w, h); return; }
        if (paths.isEmpty()) return;

        if (!spark) {
            text.setTextAlign(Paint.Align.LEFT);
            for (int g = 0; g <= 3; g++) {
                float yy = cTop + (cBottom - cTop) * g / 3f;
                c.drawLine(cLeft, yy, cRight, yy, grid);
                c.drawText(gridLabels[g], cLeft + 2 * dp, yy - 4 * dp, text);
            }
            c.drawText(dateL, cLeft, h - 6 * dp, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(dateR, cRight, h - 6 * dp, text);
            if (series.size() > 1) {
                float lx = cLeft;
                text.setTextAlign(Paint.Align.LEFT);
                for (Series s : series) {
                    dot.setColor(s.color);
                    c.drawCircle(lx + 4 * dp, 10 * dp, 4 * dp, dot);
                    c.drawText(s.label, lx + 11 * dp, 14 * dp, text);
                    lx += 20 * dp + text.measureText(s.label);
                    if (lx > cRight - 40 * dp) break;
                }
            }
            if (percent) {
                float zy = (float) (cBottom - (cBottom - cTop) * (0 - cMin) / (cMax - cMin));
                if (zy > cTop && zy < cBottom) c.drawLine(cLeft, zy, cRight, zy, zero);
            }
        }

        int save = c.save();
        if (progress < 1f) c.clipRect(cLeft - 4 * dp, 0, cLeft + (cRight - cLeft) * progress + 4 * dp, h);
        if (fillPath != null) { fill.setShader(fillShader); c.drawPath(fillPath, fill); fill.setShader(null); }
        for (int si = series.size() - 1; si >= 0; si--) {
            Path p = paths.get(si);
            if (p == null) continue;
            line.setColor(series.get(si).color);
            c.drawPath(p, line);
        }
        c.restoreToCount(save);

        if (!spark && scrubbing && touchX >= 0 && progress >= 1f) drawTip(c);
    }

    private void drawTip(Canvas c) {
        double day = cD0 + (touchX - cLeft) / (cRight - cLeft) * (cD1 - cD0);
        float cx = Math.max(cLeft, Math.min(cRight, touchX));
        c.drawLine(cx, cTop, cx, cBottom, zero);
        List<String> lines = new ArrayList<>();
        lines.add(LocalDate.ofEpochDay(Math.round(day)).format(DF));
        for (int si = 0; si < series.size(); si++) {
            Series s = series.get(si);
            int best = nearest(s, day);
            if (best < 0) continue;
            float x = pxs.get(si)[best], yy = pys.get(si)[best];
            dot.setColor(Ui.CARD); c.drawCircle(x, yy, 6 * dp, dot);
            dot.setColor(s.color); c.drawCircle(x, yy, 4 * dp, dot);
            String v = percent ? Ui.pct(y(s, best)) : fmt.format(s.vals[best]);
            lines.add((s.label.isEmpty() ? "" : s.label + "  ") + v);
        }
        float tw = 0;
        for (String l : lines) tw = Math.max(tw, tip.measureText(l));
        float bw = tw + 16 * dp, bh = lines.size() * 16 * dp + 8 * dp;
        float bx = cx + 10 * dp + bw > cRight ? cx - 10 * dp - bw : cx + 10 * dp;
        tmp.set(bx, cTop, bx + bw, cTop + bh);
        c.drawRoundRect(tmp, 8 * dp, 8 * dp, tipBg);
        for (int i = 0; i < lines.size(); i++) {
            tip.setColor(i == 0 ? (Ui.LIGHT ? 0xFFB8C0CC : Ui.SUB) : (Ui.LIGHT ? 0xFFFFFFFF : Ui.TEXT));
            c.drawText(lines.get(i), bx + 8 * dp, cTop + 16 * dp * (i + 1), tip);
        }
    }

    private int nearest(Series s, double day) {
        int n = s.days.length;
        if (n == 0) return -1;
        int lo = 0, hi = n - 1;                      // binary search
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (s.days[mid] < day) lo = mid + 1; else hi = mid; }
        if (lo > 0 && Math.abs(s.days[lo - 1] - day) < Math.abs(s.days[lo] - day)) lo--;
        return lo;
    }

    private void drawBars(Canvas c, float w, float h) {
        if (bars.length == 0) { text.setTextAlign(Paint.Align.CENTER); c.drawText("Not enough history", w / 2, h / 2, text); return; }
        double m = 0.5;
        for (double v : bars) m = Math.max(m, Math.abs(v));
        float top = 24 * dp, bottom = h - 22 * dp, mid = (top + bottom) / 2;
        float bw = w / bars.length;
        c.drawLine(0, mid, w, mid, zero);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText((weekly ? "Weekly" : "Monthly") + " change  ·  max " + Ui.pct(m), 2 * dp, 12 * dp, text);
        int sel = scrubbing && touchX >= 0 ? (int) Math.min(bars.length - 1, Math.max(0, touchX / bw)) : -1;
        for (int i = 0; i < bars.length; i++) {
            double v = bars[i];
            float bh = (float) (Math.abs(v) / m * (bottom - top) / 2) * progress;
            fill.setColor(v >= 0 ? Ui.GREEN : Ui.RED);
            fill.setAlpha(sel < 0 || sel == i ? 255 : 110);
            if (v >= 0) tmp.set(i * bw + bw * 0.18f, mid - bh, (i + 1) * bw - bw * 0.18f, mid);
            else tmp.set(i * bw + bw * 0.18f, mid, (i + 1) * bw - bw * 0.18f, mid + bh);
            c.drawRoundRect(tmp, 3 * dp, 3 * dp, fill);
        }
        fill.setAlpha(255);
        c.drawText(LocalDate.ofEpochDay(barDays[0]).format(MF), 2 * dp, h - 6 * dp, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(LocalDate.ofEpochDay(barDays[barDays.length - 1]).format(MF), w - 2 * dp, h - 6 * dp, text);
        if (sel >= 0) {
            String l = (weekly ? "Week to " : "") + LocalDate.ofEpochDay(barDays[sel]).format(weekly ? DF : MF) + "   " + Ui.pct(bars[sel]);
            tip.setColor(Ui.LIGHT ? 0xFFFFFFFF : Ui.TEXT);
            float tw = tip.measureText(l);
            float bx = Math.max(0, Math.min(w - tw - 16 * dp, sel * bw - tw / 2));
            tmp.set(bx, 16 * dp, bx + tw + 16 * dp, 38 * dp);
            c.drawRoundRect(tmp, 8 * dp, 8 * dp, tipBg);
            c.drawText(l, bx + 8 * dp, 32 * dp, tip);
        }
    }
}
