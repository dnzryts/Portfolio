package com.uliptracker;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Home-screen widget: total value, today's change and a 1-month chart.
 * Drawn into a bitmap so it matches the app's look; the layout is a single ImageView.
 */
public class Widget extends AppWidgetProvider {

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    /** Debounced refresh of all widgets (called after every data save). */
    public static void requestUpdate(final Context c) {
        final Context app = c.getApplicationContext();
        H.post(new Runnable() {
            @Override public void run() {
                if (pending != null) H.removeCallbacks(pending);
                pending = new Runnable() { @Override public void run() { pending = null; updateAll(app); } };
                H.postDelayed(pending, 1200);
            }
        });
    }

    public static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        if (ids == null || ids.length == 0) return;
        Store store = new Store(c);
        for (int id : ids) update(c, m, id, store);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Store store = new Store(c);
        for (int id : ids) update(c, m, id, store);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        update(c, m, id, new Store(c));
    }

    private static void update(Context c, AppWidgetManager m, int id, Store store) {
        try {
            int layout = c.getResources().getIdentifier("widget", "layout", c.getPackageName());
            int img = c.getResources().getIdentifier("img", "id", c.getPackageName());
            if (layout == 0 || img == 0) return;
            Bundle o = m.getAppWidgetOptions(id);
            float d = c.getResources().getDisplayMetrics().density;
            int wDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) : 250;
            int hDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 120) : 120;
            wDp = Math.max(180, Math.min(wDp, 420)); hDp = Math.max(90, Math.min(hDp, 260));
            Bitmap bmp = draw(c, store, (int) (wDp * d), (int) (hDp * d), d);
            RemoteViews rv = new RemoteViews(c.getPackageName(), layout);
            rv.setImageViewBitmap(img, bmp);
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            rv.setOnClickPendingIntent(img, PendingIntent.getActivity(c, 1, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            m.updateAppWidget(id, rv);
        } catch (Throwable t) {
            Crash.save(c, t);
        }
    }

    static boolean hideAmounts(Context c) {
        return Alerts.prefs(c).getBoolean("widgetPrivate", false) || Alerts.prefs(c).getBoolean("privacyStart", false);
    }

    private static Bitmap draw(Context c, Store store, int w, int h, float d) {
        Ui.applyTheme(c);
        boolean hide = hideAmounts(c);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF(0, 0, w, h);
        p.setShader(new LinearGradient(0, 0, w, h, Ui.HERO1, Ui.HERO2, Shader.TileMode.CLAMP));
        cv.drawRoundRect(r, 22 * d, 22 * d, p);
        p.setShader(null);

        List<Store.Asset> all = store.visible(-1);
        double total = Store.value(all), change = Store.lastChange(all);
        double base = total - change;
        Double pct = base > 0 && change != 0 ? change / base * 100 : null;
        float pad = 16 * d;

        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Ui.HERO_SUB);
        t.setTextSize(12 * d);
        cv.drawText("Portfolio", pad, pad + 10 * d, t);
        Long day = store.latestDay(-1);
        if (day != null) {
            t.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(java.time.LocalDate.ofEpochDay(day).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)),
                    w - pad, pad + 10 * d, t);
            t.setTextAlign(Paint.Align.LEFT);
        }
        t.setColor(Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float big = Math.min(28 * d, h * 0.24f);
        t.setTextSize(big);
        String val = hide ? Ui.MASK : Ui.rupeesRaw(total).replaceAll("\\.\\d+$", "");
        cv.drawText(val, pad, pad + 14 * d + big, t);

        int col = pct == null ? Ui.SUB : pct >= 0 ? Ui.GREEN : Ui.RED;
        t.setTextSize(12.5f * d);
        t.setColor(col);
        String ch = pct == null ? "No change yet" : (pct >= 0 ? "▲ " : "▼ ")
                + (hide ? "" : (change >= 0 ? "+" : "") + Ui.rupeesRaw(change).replaceAll("\\.\\d+$", "") + "  ")
                + Ui.pct(pct) + " today";
        float chY = pad + 14 * d + big + 20 * d;
        cv.drawText(ch, pad, chY, t);

        // 1-month value chart along the bottom
        TreeMap<Long, Double> hist = Store.valueHistory(all);
        if (hist.size() >= 2) {
            Map<Long, Double> sub = hist.tailMap(hist.lastKey() - 30, true);
            if (sub.size() >= 2) {
                float top = chY + 8 * d, bottom = h - 10 * d, left = pad, right = w - pad;
                if (bottom - top > 18 * d) {
                    double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
                    for (double v : sub.values()) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
                    if (mx - mn < 1e-9) { mx += 1; mn -= 1; }
                    long d0 = sub.keySet().iterator().next(), d1 = hist.lastKey();
                    Path line = new Path();
                    boolean first = true;
                    float lx = left;
                    for (Map.Entry<Long, Double> e : sub.entrySet()) {
                        float x = left + (right - left) * (e.getKey() - d0) / (float) Math.max(1, d1 - d0);
                        float y = (float) (bottom - (bottom - top) * (e.getValue() - mn) / (mx - mn));
                        if (first) { line.moveTo(x, y); first = false; } else line.lineTo(x, y);
                        lx = x;
                    }
                    double firstV = sub.values().iterator().next(), lastV = hist.lastEntry().getValue();
                    int lc = lastV >= firstV ? Ui.GREEN : Ui.RED;
                    Path fill = new Path(line);
                    fill.lineTo(lx, bottom); fill.lineTo(left, bottom); fill.close();
                    Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
                    f.setShader(new LinearGradient(0, top, 0, bottom, (lc & 0x00FFFFFF) | 0x55000000, lc & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    cv.drawPath(fill, f);
                    Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
                    ln.setStyle(Paint.Style.STROKE);
                    ln.setStrokeWidth(2 * d);
                    ln.setStrokeJoin(Paint.Join.ROUND);
                    ln.setColor(lc);
                    cv.drawPath(line, ln);
                }
            }
        }
        return b;
    }
}
