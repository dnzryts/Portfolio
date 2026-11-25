package com.uliptracker;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Galaxy Watch (Wear OS) companion: total value, today's change, overall gain, totals by type and the top holdings.
 * Holdings and profile come from the phone over Bluetooth; prices refresh on the watch over its own connection.
 */
public class WatchActivity extends Activity {

    private static final int REQ_BT = 7;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Store store;
    private ScrollView sv;
    private float dp;
    private boolean busy, autoSyncTried;
    private String status;

    private SharedPreferences prefs() { return Alerts.prefs(this); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = Ui.dp(this);
        if (!prefs().contains("theme")) prefs().edit().putInt("theme", Ui.THEME_DARK).apply();
        Ui.applyTheme(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
        store = new Store(this);
        String owner = prefs().getString("w_owner", "");
        store.ownerFilter = owner.isEmpty() ? null : owner;
        Ui.PRIVATE = prefs().getBoolean("w_hide", false);
        sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(true);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true);
        sv.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override public boolean onGenericMotion(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    float d = -e.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(WatchActivity.this).getScaledVerticalScrollFactor();
                    sv.smoothScrollBy(0, Math.round(d));
                    return true;
                }
                return false;
            }
        });
        setContentView(sv);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sv.requestFocus();
        if (synced() && System.currentTimeMillis() - store.refreshedAt > 15 * 60_000L) refreshPrices();
        else if (!synced() && !autoSyncTried && hasBtPermission()) { autoSyncTried = true; sync(true); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    private boolean synced() { return prefs().getLong("w_synced", 0) > 0; }

    // ------------------------------------------------------------------ screen
    private void render() {
        Ui.setCurrency(store.viewCcy());
        int w = getResources().getDisplayMetrics().widthPixels;
        boolean round = getResources().getConfiguration().isScreenRound();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = (int) (w * (round ? 0.13f : 0.05f));
        col.setPadding(side, (int) (w * 0.12f), side, (int) (w * 0.22f));

        LinearLayout hdr = new LinearLayout(this);
        hdr.setGravity(Gravity.CENTER);
        View logo = IntroView.logo(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams((int) (20 * dp), (int) (20 * dp));
        llp.setMargins(0, 0, (int) (6 * dp), 0);
        hdr.addView(logo, llp);
        String name = prefs().getString("w_name", "");
        hdr.addView(text(name.isEmpty() ? "Portfolio" : "Hi, " + name, 13, Ui.SUB, true));
        col.addView(hdr);

        if (!synced()) {
            col.addView(space(14));
            col.addView(centered("Connect to your phone", 16, Ui.TEXT, true));
            col.addView(space(6));
            col.addView(centered("On your phone: Portfolio › More › Galaxy Watch › turn on Sync with watch. "
                    + "Keep the app open, then tap Sync.", 12, Ui.SUB, false));
            col.addView(space(12));
            col.addView(button("⇄  Sync with phone", true, new Runnable() { @Override public void run() { sync(false); } }));
            if (status != null) { col.addView(space(8)); col.addView(centered(status, 11, Ui.SUB, false)); }
            sv.removeAllViews();
            sv.addView(col);
            return;
        }

        final List<String> owners = store.owners();
        if (owners.size() > 1) {
            col.addView(space(6));
            TextView chip = text("👤 " + (store.ownerFilter == null ? "Everyone" : store.ownerFilter) + "  ▾", 12, Ui.TEXT, true);
            chip.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
            GradientDrawable cg = new GradientDrawable();
            cg.setColor(Ui.CARD2); cg.setCornerRadius(20 * dp);
            chip.setBackground(cg);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int i = store.ownerFilter == null ? -1 : owners.indexOf(store.ownerFilter);
                    store.ownerFilter = i + 1 < owners.size() ? owners.get(i + 1) : null;
                    prefs().edit().putString("w_owner", store.ownerFilter == null ? "" : store.ownerFilter).apply();
                    render();
                }
            });
            col.addView(chip);
        }

        List<Store.Asset> all = store.visible(-1);
        double total = Store.value(all), day = Store.lastChange(all);
        double[] cv = store.costAndValue(all);
        col.addView(space(8));
        TextView big = centered(compact(total), 30, Ui.TEXT, true);
        big.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.PRIVATE = !Ui.PRIVATE; render(); }
        });
        col.addView(big);
        col.addView(centered(Ui.PRIVATE ? "Tap to show amounts" : "Total value · tap to hide", 10, Ui.HINT, false));
        col.addView(space(6));
        double base = total - day;
        col.addView(centered("Today  " + signedCompact(day) + pct(base > 0 ? day / base * 100 : 0), 14, Ui.colorFor(day), true));
        if (cv[0] > 0) {
            double g = cv[1] - cv[0];
            col.addView(centered("Overall  " + signedCompact(g) + pct(g / cv[0] * 100), 13, Ui.colorFor(g), false));
        }

        // by type
        col.addView(space(12));
        col.addView(section("By type"));
        int[] types = {Store.STOCK, Store.MF, Store.ULIP, Store.OTHER};
        String[] tn = {"Stocks", "Mutual funds", "ULIP", "Other assets"};
        for (int i = 0; i < types.length; i++) {
            List<Store.Asset> l = store.visible(types[i]);
            double v = Store.value(l);
            if (v <= 0) continue;
            double c = Store.lastChange(l);
            col.addView(row(tn[i], compact(v), v - c > 0 ? c / (v - c) * 100 : 0, types[i] != Store.OTHER));
        }

        // top holdings, same instrument on several platforms counted once
        Map<String, double[]> sum = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Store.Asset a : all) {
            if (!a.held() || a.type == Store.OTHER) continue;
            String k = a.type + ":" + (a.code.isEmpty() ? a.name.toLowerCase(Locale.ROOT) : a.code);
            double[] s = sum.get(k);
            if (s == null) { s = new double[2]; sum.put(k, s); names.put(k, shortName(a)); }
            s[0] += a.value();
            s[1] += a.lastChangeValue();
        }
        List<String> keys = new ArrayList<>(sum.keySet());
        final Map<String, double[]> fs = sum;
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String x, String y) { return Double.compare(fs.get(y)[0], fs.get(x)[0]); }
        });
        if (!keys.isEmpty()) {
            col.addView(space(12));
            col.addView(section("Top holdings"));
            for (int i = 0; i < Math.min(10, keys.size()); i++) {
                double[] s = sum.get(keys.get(i));
                col.addView(row(names.get(keys.get(i)), compact(s[0]), s[0] - s[1] > 0 ? s[1] / (s[0] - s[1]) * 100 : 0, true));
            }
        }

        col.addView(space(14));
        col.addView(button(busy ? "Updating…" : "↻  Refresh prices", true, new Runnable() { @Override public void run() { refreshPrices(); } }));
        col.addView(space(6));
        col.addView(button("⇄  Sync with phone", false, new Runnable() { @Override public void run() { sync(false); } }));
        col.addView(space(10));
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US);
        String line = "";
        if (store.refreshedAt > 0) line += "Prices " + Instant.ofEpochMilli(store.refreshedAt).atZone(ZoneId.systemDefault()).format(f);
        line += (line.isEmpty() ? "" : "\n") + "Synced " + Instant.ofEpochMilli(prefs().getLong("w_synced", 0)).atZone(ZoneId.systemDefault()).format(f);
        if (status != null) line = status + "\n" + line;
        try { line += "\nVersion " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        col.addView(centered(line, 10, Ui.HINT, false));
        int y = sv.getScrollY();
        sv.removeAllViews();
        sv.addView(col);
        sv.scrollTo(0, y);
    }

    private String shortName(Store.Asset a) {
        String n = a.name;
        if (a.type == Store.MF) n = n.replaceAll("(?i)\\s*-?\\s*(direct|regular)?\\s*(plan)?\\s*-?\\s*(growth|idcw).*$", "").replaceAll("(?i) fund$", "");
        return n.length() > 26 ? n.substring(0, 25).trim() + "…" : n;
    }

    private String compact(double v) { return Analytics.shortRupees(v); }

    private String signedCompact(double v) { return Ui.PRIVATE ? Ui.SYM.trim() + "•••" : (v >= 0 ? "+" : "") + Analytics.shortRupees(v); }

    private static String pct(double p) { return String.format(Locale.US, "  %+.2f%%", p); }

    private View row(String label, String value, double change, boolean showChange) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, (int) (5 * dp), 0, (int) (5 * dp));
        TextView l = text(label, 12, Ui.TEXT, false);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(text(value, 12, Ui.TEXT, true));
        if (showChange) right.addView(text(String.format(Locale.US, "%+.2f%%", change), 10, Ui.colorFor(change), false));
        r.addView(right);
        return r;
    }

    private View section(String s) {
        TextView t = text(s.toUpperCase(Locale.ROOT), 10, Ui.SUB, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, (int) (2 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    private View button(String label, boolean primary, final Runnable r) {
        TextView b = text(label, 13, primary ? Ui.ON_ACCENT : Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? Ui.ACCENT : Ui.CARD2);
        g.setCornerRadius(24 * dp);
        b.setBackground(g);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!busy) r.run(); } });
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        return t;
    }

    private TextView centered(String s, float sp, int color, boolean bold) {
        TextView t = text(s, sp, color, bold);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, (int) (h * dp)));
        return v;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ prices (watch's own connection)
    private void refreshPrices() {
        if (busy) return;
        final List<Store.Asset> todo = Updater.online(store, null);
        if (todo.isEmpty()) return;
        busy = true;
        status = null;
        render();
        final Map<String, String[]> symbols = Updater.symbols(todo);
        exec.execute(new Runnable() {
            @Override public void run() {
                final Map<String, Updater.Result> res = Updater.fetch(symbols, null);
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (isFinishing()) return;
                        int[] r = Updater.apply(store, todo, res);
                        trim();
                        if (r[0] == 0 && r[1] > 0) status = "No internet. Showing last prices.";
                        render();
                    }
                });
            }
        });
    }

    /** Keep the watch's copy small: about 14 months of prices per holding. */
    private void trim() {
        long cut = LocalDate.now().minusDays(430).toEpochDay();
        List<Store.Asset> all = new ArrayList<>(store.assets);
        all.addAll(store.indices);
        for (Store.Asset a : all) if (a.prices.size() > 2) a.prices.headMap(Math.min(cut, a.prices.lowerKey(a.prices.lastKey())), false).clear();
        store.save();
    }

    // ------------------------------------------------------------------ sync from phone
    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }

    private void sync(final boolean quiet) {
        if (busy) return;
        if (!hasBtPermission()) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, REQ_BT);
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { status = "Turn on Bluetooth to sync"; render(); return; }
        busy = true;
        status = "Connecting to phone…";
        render();
        exec.execute(new Runnable() {
            @Override public void run() {
                String err = null, json = null;
                try { json = WatchLink.fetch(ad); }
                catch (WatchLink.Denied d) { err = "The phone didn't allow this watch."; }
                catch (Throwable t) { err = t.getMessage() == null ? "Sync failed" : t.getMessage(); }
                final String fe = err, fj = json;
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (isFinishing()) return;
                        if (fe != null) { status = quiet ? null : fe; render(); return; }
                        try {
                            JSONObject o = new JSONObject(fj);
                            JSONObject meta = o.optJSONObject("watch");
                            store.fromJson(o);
                            store.accrueAll();
                            store.save();
                            SharedPreferences.Editor ed = prefs().edit().putLong("w_synced", System.currentTimeMillis());
                            if (meta != null) {
                                ed.putString("w_name", meta.optString("name", ""));
                                boolean hide = meta.optBoolean("hide", false);
                                ed.putBoolean("w_hide", hide);
                                Ui.PRIVATE = hide;
                            }
                            if (store.ownerFilter != null && !store.owners().contains(store.ownerFilter)) {
                                store.ownerFilter = null;
                                ed.putString("w_owner", "");
                            }
                            ed.apply();
                            status = null;
                            toast("Synced with phone");
                        } catch (Exception e) {
                            status = "Couldn't read the phone's data";
                        }
                        render();
                        if (System.currentTimeMillis() - store.refreshedAt > 15 * 60_000L) refreshPrices();
                    }
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_BT && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) sync(false);
        else if (code == REQ_BT) { status = "Bluetooth permission is needed to sync"; render(); }
    }
}
