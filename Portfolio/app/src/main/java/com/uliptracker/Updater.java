package com.uliptracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fetches prices for stocks, mutual funds and indexes.
 * fetch() only downloads (safe on a background thread, touches no shared data);
 * apply() writes the results into the Store and must run on the thread that owns the Store
 * (the UI thread in the app), so the screen never reads a price map while it is being changed.
 */
public final class Updater {
    private Updater() { }

    public interface Progress { void on(int done, int total); }

    /** Download result for one symbol. */
    public static final class Result {
        public TreeMap<Long, Double> prices;
        public String name, error, category, currency;
    }

    /** Everything that can be updated online (or only `only`, on every platform holding it). */
    public static List<Store.Asset> online(Store store, Store.Asset only) {
        List<Store.Asset> todo = new ArrayList<>();
        List<Store.Asset> all = new ArrayList<>(store.assets);
        all.addAll(store.indices);
        for (Store.Asset a : all)
            if ((only == null || a == only || (only.type != Store.INDEX && a.key().equals(only.key())))
                    && !a.fetchCode().isEmpty() && !a.hidden) todo.add(a);   // ULIP funds only once linked to an online NAV
        return todo;
    }

    /** Symbols to fetch: "type:code" -> [type, code]. Build on the owner thread, then pass to fetch(). */
    public static Map<String, String[]> symbols(List<Store.Asset> todo) {
        Map<String, String[]> m = new HashMap<>();
        for (Store.Asset a : todo) {
            java.util.Map.Entry<Long, Double> l = a.latest();      // a web page is read relative to the last NAV
            m.put(a.type + ":" + a.fetchCode(), new String[]{String.valueOf(a.type), a.fetchCode(), l == null ? "" : String.valueOf(l.getValue())});
        }
        return m;
    }

    /** Network only. Each symbol fetched once even if held on several platforms. */
    public static Map<String, Result> fetch(Map<String, String[]> symbols, Progress p) {
        Map<String, Result> out = new HashMap<>();
        int i = 0;
        for (Map.Entry<String, String[]> e : symbols.entrySet()) {
            Result r = new Result();
            TreeMap<Long, Double> fresh = new TreeMap<>();
            try {
                int type = Integer.parseInt(e.getValue()[0]);
                String code = e.getValue()[1];
                if (code.startsWith("http")) {             // a ULIP fund linked to a web page
                    String an = e.getValue().length > 2 ? e.getValue()[2] : "";
                    PageNav.Found f = Net.pageNav(code, an.isEmpty() ? null : Double.valueOf(an));
                    if (f == null) throw new java.io.IOException("No NAV found on the page");
                    fresh.put(f.day >= 0 ? f.day : java.time.LocalDate.now().toEpochDay(), f.nav);
                }
                else if (type == Store.MF) { String[] m = Net.mfHistoryMeta(code, fresh); r.name = m[0]; r.category = m[1]; }
                else { Net.LAST_CCY.set(null); r.name = Net.stockHistory(code, fresh); r.currency = Net.LAST_CCY.get(); }
                r.prices = fresh;
            } catch (Throwable ex) {
                r.error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            out.put(e.getKey(), r);
            if (p != null) p.on(++i, symbols.size());
        }
        return out;
    }

    /** Writes results into the assets. Returns {ok, failed}. Call on the Store's owner thread. */
    public static int[] apply(Store store, List<Store.Asset> todo, Map<String, Result> results) {
        int ok = 0, fail = 0;
        for (Store.Asset a : todo) {
            Result r = results.get(a.type + ":" + a.fetchCode());
            if (r == null) continue;
            if (r.prices != null) {
                a.prices.putAll(r.prices);
                a.fetchedAt = System.currentTimeMillis();
                a.error = null;
                if (r.category != null && !r.category.isEmpty()) a.category = r.category;
                if (r.currency != null && !r.currency.isEmpty() && a.type != Store.ULIP) a.currency = r.currency;
                if (r.name != null && a.type == Store.STOCK && a.name.equals(a.code.replace(".NS", "").replace(".BO", "")))
                    a.name = r.name;
                ok++;
            } else { a.error = r.error; fail++; }
        }
        store.refreshedAt = System.currentTimeMillis();
        long today = java.time.LocalDate.now().toEpochDay();
        for (Store.Asset a : todo) a.applySips(today);    // new prices may complete SIP instalments
        store.accrueAll();
        store.save();
        return new int[]{ok, fail};
    }

    /** Fetch + apply on the same thread (used by the background job, which has its own Store). */
    public static int[] run(Store store, List<Store.Asset> todo, Progress p) {
        return apply(store, todo, fetch(symbols(todo), p));
    }
}
