package com.uliptracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Portfolio data: stocks, mutual funds and ULIP funds, each with price history. Saved to a JSON file. */
public class Store {

    public static final int ULIP = 0, MF = 1, STOCK = 2, INDEX = 3, OTHER = 4, LOAN = 5;
    public static final String[] OTHER_KINDS = {"Fixed deposit", "Recurring deposit", "PPF", "EPF", "NPS", "Gold / SGB", "Real estate",
            "Savings / cash", "Bonds", "Other"};
    public static final String[] LOAN_KINDS = {"Home loan", "Car loan", "Personal loan", "Education loan", "Credit card", "Other loan"};
    public static final String[] TYPE_NAMES = {"SBI Life ULIP", "Mutual funds", "Stocks", "Index"};
    public static final String[][] DEFAULT_INDICES = {
            {"^NSEI", "Nifty 50"}, {"^BSESN", "Sensex"}, {"^NSEBANK", "Nifty Bank"},
            {"^CNXIT", "Nifty IT"}, {"^NSMIDCP", "Nifty Next 50"}
    };

    public static final String[] DEFAULT_ULIPS = {
            "Equity Fund", "Bluechip Fund", "Index Fund",
            "Midcap Fund", "Pure Fund", "Top 300 Fund",
            "Balanced Fund", "Growth Fund", "Equity Optimiser Fund"
    };

    public static class Asset {
        public int type;
        public String name;
        public String code = "";      // MF scheme code or stock symbol (RELIANCE.NS)
        public String platform = "";  // where it's held: Zerodha, Groww, SBI Life ...
        public String owner = "";     // family member ("" = Me)
        public String category = "";  // mutual fund category from AMFI (e.g. "Equity Scheme - Large Cap Fund")
        // other assets & loans
        public String kind = "";      // Fixed deposit, PPF, Home loan ...
        public double principal, rate, baseValue;
        public long start, maturity, baseDay;
        public double units;          // units or shares
        public double invested;       // total cost (MF/stocks)
        public boolean hidden;
        public boolean watch;           // on the watchlist, not part of the portfolio
        public double targetAbove;      // one-shot price alerts (0 = none)
        public double targetBelow;
        public long fetchedAt;
        public String error;          // last refresh problem (not saved)
        public final TreeMap<Long, Double> prices = new TreeMap<>();
        public final List<Ledger.Txn> txns = new ArrayList<>();
        public final List<Ledger.Sip> sips = new ArrayList<>();
        public double realised, dividends;   // derived from txns

        public Asset(int type, String name) { this.type = type; this.name = name; }

        public boolean hasLedger() { return !txns.isEmpty(); }

        /** Re-derive units, cost of remaining units, realised P&L and dividends from transactions. */
        public void recompute() {
            if (txns.isEmpty()) { realised = 0; dividends = 0; return; }
            Ledger.Book b = Ledger.replay(txns, Long.MAX_VALUE);
            units = Math.round(b.units * 1e6) / 1e6;
            invested = b.cost;
            realised = b.realised;
            dividends = b.dividends;
        }

        public double unitsOn(long day) { return txns.isEmpty() ? units : Ledger.unitsOn(txns, day); }

        /** Prices purchases entered without a cost, then adds due SIP instalments; returns changes made. */
        public int applySips(long today) {
            if (prices.isEmpty()) return 0;
            int n = 0;
            for (Ledger.Txn t : txns) {
                if ((t.kind == Ledger.BUY || t.kind == Ledger.SELL) && t.price <= 0) {
                    Map.Entry<Long, Double> px = prices.floorEntry(t.day);
                    if (px == null || px.getKey() < t.day - 7) px = prices.ceilingEntry(t.day);
                    if (px != null) { t.price = px.getValue(); t.amount = t.units * t.price; n++; }
                }
            }
            if (!sips.isEmpty()) n += Ledger.applySips(sips, txns, prices, type == STOCK, today);
            if (n > 0) recompute();
            return n;
        }

        public Map.Entry<Long, Double> latest() { return prices.isEmpty() ? null : prices.lastEntry(); }

        public Map.Entry<Long, Double> previous() {
            Map.Entry<Long, Double> l = latest();
            return l == null ? null : prices.lowerEntry(l.getKey());
        }

        public Double priceOnOrBefore(long day) {
            Map.Entry<Long, Double> e = prices.floorEntry(day);
            return e == null ? null : e.getValue();
        }

        public Double changeOver(int days) {
            Map.Entry<Long, Double> l = latest();
            if (l == null) return null;
            Map.Entry<Long, Double> past = prices.floorEntry(l.getKey() - days);
            if (past == null || past.getKey().equals(l.getKey())) return null;
            return (l.getValue() / past.getValue() - 1) * 100;
        }

        public Double lastChangePct() {
            Map.Entry<Long, Double> l = latest(), p = previous();
            return l == null || p == null ? null : (l.getValue() / p.getValue() - 1) * 100;
        }

        /** [low, high] over the last 365 days of data, or null. */
        public double[] range52w() {
            Map.Entry<Long, Double> l = latest();
            if (l == null) return null;
            double lo = Double.MAX_VALUE, hi = -1;
            for (double v : prices.tailMap(l.getKey() - 365, true).values()) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            return hi < 0 ? null : new double[]{lo, hi};
        }

        public double value() {
            Map.Entry<Long, Double> l = latest();
            return l == null ? 0 : l.getValue() * units;
        }

        public double lastChangeValue() {
            Map.Entry<Long, Double> l = latest(), p = previous();
            return l == null || p == null ? 0 : (l.getValue() - p.getValue()) * units;
        }

        public boolean held() { return units > 0 && latest() != null; }

        /** Trading currency of a quoted stock/ETF/index from the exchange ("" for funds and manual assets). */
        public String currency = "";

        public String ownerOrDefault() { return owner == null || owner.trim().isEmpty() ? "Me" : owner.trim(); }

        public boolean manual() { return type == OTHER || type == LOAN; }

        public boolean isRd() { return type == OTHER && "Recurring deposit".equals(kind); }

        public boolean isDeposit() { return type == OTHER && ("Fixed deposit".equals(kind) || "Recurring deposit".equals(kind)); }

        /** Total monthly RD instalments due by `day` (first on the start date, last the month before maturity). */
        public int rdMonths(long day) {
            if (start <= 0) return 0;
            java.time.LocalDate s0 = java.time.LocalDate.ofEpochDay(start);
            int total = maturity > start ? (int) java.time.temporal.ChronoUnit.MONTHS.between(s0, java.time.LocalDate.ofEpochDay(maturity)) : Integer.MAX_VALUE;
            if (day < start) return 0;
            int k = (int) java.time.temporal.ChronoUnit.MONTHS.between(s0, java.time.LocalDate.ofEpochDay(day)) + 1;
            return Math.max(0, Math.min(k, total));
        }

        /** Value of a fixed deposit (quarterly compounding) or a balance growing at `rate` from the last update. */
        public double accruedOn(long day) {
            if (isRd() && principal > 0 && start > 0) {
                // each monthly instalment compounds quarterly from its own date
                long end = maturity > 0 ? Math.min(day, maturity) : day;
                int k = rdMonths(end == maturity ? maturity - 1 : end);
                double v = 0;
                java.time.LocalDate s0 = java.time.LocalDate.ofEpochDay(start);
                java.time.LocalDate e0 = java.time.LocalDate.ofEpochDay(end);
                for (int i = 0; i < k; i++) {
                    java.time.LocalDate paid = s0.plusMonths(i);
                    long months = java.time.temporal.ChronoUnit.MONTHS.between(paid, e0);
                    double extra = Math.max(0, e0.toEpochDay() - paid.plusMonths(months).toEpochDay()) / 30.4375;
                    // banks compound quarterly by whole months (standard RD formula)
                    v += principal * Math.pow(1 + rate / 400.0, (Math.max(0, months) + extra) / 3.0);
                }
                return v;
            }
            if (type == OTHER && "Fixed deposit".equals(kind) && principal > 0 && start > 0) {
                long end = maturity > 0 ? Math.min(day, maturity) : day;
                double yrs = Math.max(0, end - start) / 365.0;
                return principal * Math.pow(1 + rate / 400.0, 4 * yrs);
            }
            if (baseDay > 0 && rate > 0 && type == OTHER) return baseValue * Math.pow(1 + rate / 100.0, Math.max(0, day - baseDay) / 365.0);
            return baseValue;
        }

        /** Refresh the value series of an FD / PPF / EPF style asset up to today (monthly points). */
        public void accrue(long today) {
            if (!manual()) return;
            long from = isDeposit() && start > 0 ? start : baseDay;
            if (from <= 0) return;
            if (!isDeposit() && rate <= 0) { if (baseDay > 0) prices.put(baseDay, baseValue); return; }
            if (isRd()) invested = principal * rdMonths(maturity > 0 ? Math.min(today, maturity - 1) : today);
            java.time.LocalDate d = java.time.LocalDate.ofEpochDay(from);
            int guard = 0;
            while (d.toEpochDay() < today && guard++ < 1200) {
                if (prices.floorKey(d.toEpochDay()) == null || prices.floorKey(d.toEpochDay()) < d.toEpochDay() || !prices.containsKey(d.toEpochDay()))
                    prices.put(d.toEpochDay(), accruedOn(d.toEpochDay()));
                d = d.plusMonths(1);
            }
            prices.put(today, accruedOn(today));
            if (units <= 0) units = 1;
        }

        public String platformOrDefault() {
            if (platform != null && !platform.trim().isEmpty()) return platform.trim();
            return type == ULIP ? "SBI Life" : manual() ? (kind.isEmpty() ? "Other" : kind) : "Unassigned";
        }

        public String key() {
            if (manual()) return type + ":" + System.identityHashCode(this);
            return type + ":" + (code.isEmpty() ? name.toLowerCase() : code) + "@" + ownerOrDefault().toLowerCase();
        }
    }

    public final List<Asset> assets = new ArrayList<>();
    public final List<Asset> indices = new ArrayList<>();
    public final List<Policy> policies = new ArrayList<>();
    /** Family member being viewed (null = everyone). */
    public String ownerFilter;

    // ---- currency per profile (no conversion: each profile's money stays in its own currency) ----
    /** Profile (owner, lower case) → ISO currency code. Missing = INR. */
    public final Map<String, String> ownerCcy = new java.util.HashMap<>();
    private Boolean mixedCache;

    public String ccyOf(String owner) {
        String c = ownerCcy.get((owner == null || owner.trim().isEmpty() ? "Me" : owner.trim()).toLowerCase(java.util.Locale.ROOT));
        return c == null || c.isEmpty() ? "INR" : c;
    }

    public void setCcy(String owner, String code) {
        String k = (owner == null || owner.trim().isEmpty() ? "Me" : owner.trim()).toLowerCase(java.util.Locale.ROOT);
        if (code == null || "INR".equals(code)) ownerCcy.remove(k); else ownerCcy.put(k, code);
        mixedCache = null;
    }

    /** Currencies in use, the default ("Me") first. */
    public List<String> currencies() {
        List<String> out = new ArrayList<>();
        out.add(ccyOf("Me"));
        for (String o : owners()) { String c = ccyOf(o); if (!out.contains(c)) out.add(c); }
        return out;
    }

    /** True when profiles use more than one currency, so "Everyone" splits by currency. */
    public boolean mixed() {
        if (mixedCache == null) mixedCache = currencies().size() > 1;
        return mixedCache;
    }

    /** The currency of what's on screen: a person's, a currency group's ("@SGD"), or everyone's (Me's when mixed). */
    public String viewCcy() {
        if (ownerFilter == null) return ccyOf("Me");
        if (ownerFilter.startsWith("@")) return ownerFilter.substring(1);
        return ccyOf(ownerFilter);
    }

    private boolean ownerOk(Asset a) {
        if (ownerFilter == null) return !mixed() || ccyOf(a.ownerOrDefault()).equals(ccyOf("Me"));
        if (ownerFilter.startsWith("@")) return ccyOf(a.ownerOrDefault()).equals(ownerFilter.substring(1));
        return a.ownerOrDefault().equalsIgnoreCase(ownerFilter);
    }

    public List<String> owners() {
        List<String> out = new ArrayList<>();
        out.add("Me");
        for (Asset a : assets) {
            String o = a.ownerOrDefault();
            boolean dup = false;
            for (String x : out) if (x.equalsIgnoreCase(o)) dup = true;
            if (!dup) out.add(o);
        }
        return out;
    }

    public List<Asset> loans() {
        List<Asset> l = new ArrayList<>();
        for (Asset a : assets) if (a.type == LOAN && !a.hidden && ownerOk(a)) l.add(a);
        return l;
    }

    public void accrueAll() {
        long today = LocalDate.now().toEpochDay();
        for (Asset a : assets) if (a.manual()) a.accrue(today);
    }

    /** SBI Life (or any) insurance policy details for premium reminders. */
    public static class Policy {
        public String name = "SBI Life ULIP", number = "", notes = "";
        public double premium, sumAssured;
        public int freq = 12;             // months between premiums: 1, 3, 6, 12
        public long nextDue, payUntil, maturity;
        public int remindDays = 7;

        /** Moves the due date forward past today (premiums assumed paid) within the paying term. */
        public boolean roll(long today) {
            boolean changed = false;
            int guard = 0;
            while (nextDue > 0 && nextDue < today && guard++ < 600) {
                LocalDate d = LocalDate.ofEpochDay(nextDue).plusMonths(Math.max(1, freq));
                if (payUntil > 0 && d.toEpochDay() > payUntil) { nextDue = 0; changed = true; break; }
                nextDue = d.toEpochDay();
                changed = true;
            }
            return changed;
        }

        public double yearlyPremium() { return premium * 12.0 / Math.max(1, freq); }
    }
    public double ulipInvested;   // whole-policy amount invested (ULIP fund-level cost is rarely known)
    public long refreshedAt;
    private final File file;
    private final Context ctx;

    public Store(Context c) {
        ctx = c.getApplicationContext();
        file = new File(c.getFilesDir(), "portfolio.json");
        try {
            if (file.exists()) {
                fromJson(new JSONObject(read(file)));
                if (indices.isEmpty()) defaultIndices();
                return;
            }
        } catch (Exception ignored) { }
        // First run, or upgrade from ULIP Tracker 1.0 (data kept in SharedPreferences)
        SharedPreferences old = c.getSharedPreferences("data", Context.MODE_PRIVATE);
        String json = old.getString("json", null);
        boolean migrated = false;
        if (json != null) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray arr = o.getJSONArray("funds");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject fo = arr.getJSONObject(i);
                    Asset a = new Asset(ULIP, fo.getString("name"));
                    a.units = fo.optDouble("units", 0);
                    a.hidden = fo.optBoolean("hidden", false);
                    readPrices(a, fo.optJSONArray("navs"));
                    assets.add(a);
                }
                ulipInvested = o.optDouble("invested", 0);
                migrated = true;
            } catch (Exception ignored) { assets.clear(); }
        }
        if (!migrated) for (String n : DEFAULT_ULIPS) assets.add(new Asset(ULIP, n));
        defaultIndices();
        save();
    }

    private void defaultIndices() {
        for (String[] d : DEFAULT_INDICES) {
            Asset a = new Asset(INDEX, d[1]);
            a.code = d[0];
            indices.add(a);
        }
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } finally { in.close(); }
    }

    public synchronized void save() {
        mixedCache = null;      // owners may have changed
        VERSION++;
        try {
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            tmp.renameTo(file);
        } catch (Exception ignored) { }
        try { Widget.requestUpdate(ctx); } catch (Throwable ignored) { }
    }

    public synchronized JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 2);
            if (!ownerCcy.isEmpty()) {
                JSONObject oc = new JSONObject();
                for (Map.Entry<String, String> e : ownerCcy.entrySet()) oc.put(e.getKey(), e.getValue());
                o.put("ownerCcy", oc);
            }
            o.put("ulipInvested", ulipInvested);
            o.put("refreshedAt", refreshedAt);
            o.put("assets", toArr(assets));
            JSONArray pa = new JSONArray();
            for (Policy p : policies) {
                JSONObject po = new JSONObject();
                po.put("name", p.name); po.put("number", p.number); po.put("notes", p.notes);
                po.put("premium", p.premium); po.put("sumAssured", p.sumAssured); po.put("freq", p.freq);
                po.put("nextDue", p.nextDue); po.put("payUntil", p.payUntil); po.put("maturity", p.maturity);
                po.put("remindDays", p.remindDays);
                pa.put(po);
            }
            o.put("policies", pa);
            o.put("indices", toArr(indices));
            return o;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static JSONArray toArr(List<Asset> list) throws Exception {
            JSONArray arr = new JSONArray();
            for (Asset a : list) {
                JSONObject ao = new JSONObject();
                ao.put("type", a.type);
                ao.put("name", a.name);
                ao.put("code", a.code);
                ao.put("platform", a.platform);
                if (!a.owner.isEmpty()) ao.put("owner", a.owner);
                if (!a.currency.isEmpty()) ao.put("ccy", a.currency);
                if (!a.category.isEmpty()) ao.put("cat", a.category);
                if (a.manual()) {
                    ao.put("kind", a.kind); ao.put("principal", a.principal); ao.put("rate", a.rate);
                    ao.put("start", a.start); ao.put("maturity", a.maturity); ao.put("baseValue", a.baseValue); ao.put("baseDay", a.baseDay);
                }
                ao.put("units", a.units);
                ao.put("invested", a.invested);
                ao.put("hidden", a.hidden);
                ao.put("watch", a.watch);
                ao.put("tAbove", a.targetAbove);
                ao.put("tBelow", a.targetBelow);
                ao.put("fetchedAt", a.fetchedAt);
                JSONArray h = new JSONArray();
                for (Map.Entry<Long, Double> e : a.prices.entrySet()) {
                    JSONArray p = new JSONArray();
                    p.put(LocalDate.ofEpochDay(e.getKey()).toString());
                    p.put(e.getValue());
                    h.put(p);
                }
                ao.put("prices", h);
                if (!a.txns.isEmpty()) {
                    JSONArray tx = new JSONArray();
                    for (Ledger.Txn t : a.txns) {
                        JSONArray q = new JSONArray();
                        q.put(LocalDate.ofEpochDay(t.day).toString()); q.put(t.kind); q.put(t.units); q.put(t.price);
                        q.put(t.amount); q.put(t.sip); q.put(t.note == null ? "" : t.note);
                        tx.put(q);
                    }
                    ao.put("txns", tx);
                }
                if (!a.sips.isEmpty()) {
                    JSONArray sp = new JSONArray();
                    for (Ledger.Sip x : a.sips) {
                        JSONArray q = new JSONArray();
                        q.put(x.amount); q.put(x.dom); q.put(x.start); q.put(x.end); q.put(x.lastApplied);
                        sp.put(q);
                    }
                    ao.put("sips", sp);
                }
                arr.put(ao);
            }
            return arr;
    }

    private static void readLedger(Asset a, JSONObject ao) throws Exception {
        JSONArray tx = ao.optJSONArray("txns");
        if (tx != null) for (int i = 0; i < tx.length(); i++) {
            JSONArray q = tx.getJSONArray(i);
            Ledger.Txn t = new Ledger.Txn();
            t.day = LocalDate.parse(q.getString(0)).toEpochDay();
            t.kind = (int) q.getLong(1); t.units = q.getDouble(2); t.price = q.getDouble(3); t.amount = q.getDouble(4);
            t.sip = q.length() > 5 && "true".equals(q.getString(5));
            t.note = q.length() > 6 ? q.getString(6) : "";
            a.txns.add(t);
        }
        JSONArray sp = ao.optJSONArray("sips");
        if (sp != null) for (int i = 0; i < sp.length(); i++) {
            JSONArray q = sp.getJSONArray(i);
            Ledger.Sip x = new Ledger.Sip();
            x.amount = q.getDouble(0); x.dom = (int) q.getLong(1); x.start = q.getLong(2); x.end = q.getLong(3); x.lastApplied = q.getLong(4);
            a.sips.add(x);
        }
        Ledger.sort(a.txns);
        a.recompute();
    }

    private static void readPrices(Asset a, JSONArray h) throws Exception {
        if (h == null) return;
        for (int j = 0; j < h.length(); j++) {
            JSONArray p = h.getJSONArray(j);
            a.prices.put(LocalDate.parse(p.getString(0)).toEpochDay(), p.getDouble(1));
        }
    }

    /** Accepts v2 backups and v1 (ULIP Tracker) backups. */
    public synchronized void fromJson(JSONObject o) throws Exception {
        JSONObject oc = o.optJSONObject("ownerCcy");
        ownerCcy.clear();
        if (oc != null) {
            java.util.Iterator<String> it = oc.keys();
            while (it.hasNext()) { String k = it.next(); ownerCcy.put(k, oc.optString(k, "INR")); }
        }
        mixedCache = null;
        List<Asset> list = new ArrayList<>();
        List<Asset> idx = new ArrayList<>();
        JSONArray pa = o.optJSONArray("policies");
        if (pa != null) {
            policies.clear();
            for (int i = 0; i < pa.length(); i++) {
                JSONObject po = pa.getJSONObject(i);
                Policy p = new Policy();
                p.name = po.optString("name", p.name); p.number = po.optString("number", ""); p.notes = po.optString("notes", "");
                p.premium = po.optDouble("premium", 0); p.sumAssured = po.optDouble("sumAssured", 0);
                p.freq = (int) po.optLong("freq", 12); p.nextDue = po.optLong("nextDue", 0);
                p.payUntil = po.optLong("payUntil", 0); p.maturity = po.optLong("maturity", 0);
                p.remindDays = (int) po.optLong("remindDays", 7);
                policies.add(p);
            }
        }
        if (o.has("assets")) {
            readArr(o.optJSONArray("indices"), idx);
            JSONArray arr = o.getJSONArray("assets");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ao = arr.getJSONObject(i);
                Asset a = new Asset(ao.getInt("type"), ao.getString("name"));
                a.code = ao.optString("code", "");
                a.platform = ao.optString("platform", "");
                a.owner = ao.optString("owner", "");
                a.currency = ao.optString("ccy", "");
                a.category = ao.optString("cat", "");
                a.kind = ao.optString("kind", "");
                a.principal = ao.optDouble("principal", 0); a.rate = ao.optDouble("rate", 0);
                a.start = ao.optLong("start", 0); a.maturity = ao.optLong("maturity", 0);
                a.baseValue = ao.optDouble("baseValue", 0); a.baseDay = ao.optLong("baseDay", 0);
                a.units = ao.optDouble("units", 0);
                a.invested = ao.optDouble("invested", 0);
                a.hidden = ao.optBoolean("hidden", false);
                a.watch = ao.optBoolean("watch", false);
                a.targetAbove = ao.optDouble("tAbove", 0);
                a.targetBelow = ao.optDouble("tBelow", 0);
                a.fetchedAt = ao.optLong("fetchedAt", 0);
                readPrices(a, ao.optJSONArray("prices"));
                readLedger(a, ao);
                list.add(a);
            }
            ulipInvested = o.optDouble("ulipInvested", 0);
            refreshedAt = o.optLong("refreshedAt", 0);
        } else {
            JSONArray arr = o.getJSONArray("funds");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fo = arr.getJSONObject(i);
                Asset a = new Asset(ULIP, fo.getString("name"));
                a.units = fo.optDouble("units", 0);
                a.hidden = fo.optBoolean("hidden", false);
                readPrices(a, fo.optJSONArray("navs"));
                list.add(a);
            }
            ulipInvested = o.optDouble("invested", 0);
        }
        assets.clear();
        assets.addAll(list);
        if (!idx.isEmpty()) { indices.clear(); indices.addAll(idx); }
    }

    private static void readArr(JSONArray arr, List<Asset> into) throws Exception {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject ao = arr.getJSONObject(i);
            Asset a = new Asset(ao.getInt("type"), ao.getString("name"));
            a.code = ao.optString("code", "");
            a.hidden = ao.optBoolean("hidden", false);
            a.targetAbove = ao.optDouble("tAbove", 0);
            a.targetBelow = ao.optDouble("tBelow", 0);
            a.fetchedAt = ao.optLong("fetchedAt", 0);
            readPrices(a, ao.optJSONArray("prices"));
            into.add(a);
        }
    }

    /** Same instrument held on other platforms (including a itself). */
    public List<Asset> siblings(Asset a) {
        List<Asset> l = new ArrayList<>();
        if (a.type == INDEX) { l.add(a); return l; }
        if (a.watch) { l.add(a); return l; }
        if (a.manual()) { l.add(a); return l; }
        for (Asset x : assets) if (!x.hidden && !x.watch && x.type == a.type && x.code.equalsIgnoreCase(a.code)
                && (x.code.length() > 0 || x.name.equalsIgnoreCase(a.name)) && x.ownerOrDefault().equalsIgnoreCase(a.ownerOrDefault())) l.add(x);
        if (!l.contains(a)) l.add(0, a);
        return l;
    }

    public List<Asset> findAll(int type, String nameOrCode) {
        List<Asset> l = new ArrayList<>();
        for (Asset a : type == INDEX ? indices : assets) {
            if (a.type != type || a.watch) continue;
            if (a.name.equalsIgnoreCase(nameOrCode) || (!a.code.isEmpty() && a.code.equalsIgnoreCase(nameOrCode))) l.add(a);
        }
        return l;
    }

    public List<Asset> watchlist() {
        List<Asset> l = new ArrayList<>();
        for (Asset a : assets) if (a.watch && !a.hidden) l.add(a);
        return l;
    }

    public Asset findWatch(int type, String code) {
        for (Asset a : assets) if (a.watch && a.type == type && a.code.equalsIgnoreCase(code)) return a;
        return null;
    }

    /** Everything that can have price targets: holdings, watchlist and indexes. */
    public List<Asset> everything() {
        List<Asset> l = new ArrayList<>(assets);
        l.addAll(indices);
        return l;
    }

    public Asset find(int type, String nameOrCode) {
        for (Asset a : type == INDEX ? indices : assets) {
            if (a.type != type) continue;
            if (a.name.equalsIgnoreCase(nameOrCode) || (!a.code.isEmpty() && a.code.equalsIgnoreCase(nameOrCode)))
                return a;
        }
        return null;
    }

    public List<Asset> visible(int type) {
        List<Asset> l = new ArrayList<>();
        for (Asset a : assets)
            if (!a.hidden && !a.watch && ownerOk(a) && (type < 0 ? a.type != LOAN : a.type == type)) l.add(a);
        return l;
    }

    public double value(int type) {
        double t = 0;
        for (Asset a : visible(type)) t += a.value();
        return t;
    }

    public double lastChange(int type) {
        double t = 0;
        for (Asset a : visible(type)) if (a.units > 0) t += a.lastChangeValue();
        return t;
    }

    /** Cost basis and the current value of the holdings that have a cost recorded. */
    public double[] costAndValue(int type) {
        double cost = 0, val = 0;
        for (Asset a : visible(type)) {
            if (a.type == ULIP || a.invested <= 0 || !a.held()) continue;
            cost += a.invested;
            val += a.value();
        }
        if ((type < 0 || type == ULIP) && ulipInvested > 0) {
            cost += ulipInvested;
            val += value(ULIP);
        }
        return new double[]{cost, val};
    }

    /** Value of current holdings on each past day (last known price carried forward). */
    public TreeMap<Long, Double> valueHistory(int type) {
        TreeMap<Long, Double> out = new TreeMap<>();
        List<Asset> held = new ArrayList<>();
        for (Asset a : visible(type)) if (a.held()) held.add(a);
        if (held.isEmpty()) return out;
        long start = Long.MIN_VALUE;
        TreeSet<Long> days = new TreeSet<>();
        for (Asset a : held) {
            start = Math.max(start, a.prices.firstKey());
            days.addAll(a.prices.keySet());
        }
        for (Long d : days) {
            if (d < start) continue;
            double v = 0;
            for (Asset a : held) v += a.priceOnOrBefore(d) * a.units;
            out.put(d, v);
        }
        return out;
    }

    // ---- helpers over any subset (type, platform or everything) ----
    public static double value(List<Asset> l) { double t = 0; for (Asset a : l) t += a.value(); return t; }

    public static double lastChange(List<Asset> l) {
        double t = 0; for (Asset a : l) if (a.units > 0) t += a.lastChangeValue(); return t;
    }

    /** [cost, value of holdings that have a cost]. ULIP falls back to the whole-policy amount. */
    public double[] costAndValue(List<Asset> l) {
        double cost = 0, val = 0, ulipVal = 0;
        boolean ulipHasCost = false, anyUlip = false;
        for (Asset a : assets) if (a.type == ULIP && a.invested > 0) ulipHasCost = true;
        for (Asset a : l) {
            if (!a.held()) continue;
            if (a.type == ULIP && !ulipHasCost) { anyUlip = true; ulipVal += a.value(); continue; }
            if (a.invested <= 0) continue;
            cost += a.invested; val += a.value();
        }
        if (anyUlip && ulipInvested > 0) {
            // only when the subset holds every ULIP fund, so the policy total matches
            double allUlip = 0;
            for (Asset a : visible(ULIP)) allUlip += a.value();
            if (Math.abs(allUlip - ulipVal) < 0.5) { cost += ulipInvested; val += ulipVal; }
        }
        return new double[]{cost, val};
    }

    /**
     * Value of current holdings on each past day. A holding with no price yet on a day (e.g. a ULIP
     * fund with only recent NAVs) counts at its earliest known price, so the chart still spans the
     * history of the others instead of shrinking to the newest start date.
     */
    /** Bumped whenever data changes; invalidates cached histories. */
    public static int VERSION = 0;
    private static final java.util.Map<String, TreeMap<Long, Double>> HIST = new java.util.HashMap<>();

    public static TreeMap<Long, Double> valueHistory(List<Asset> l) {
        StringBuilder k = new StringBuilder().append(VERSION);
        for (Asset a : l) k.append('|').append(System.identityHashCode(a)).append(':').append(a.units).append(':').append(a.txns.size())
                .append(':').append(a.prices.size()).append(':').append(a.prices.isEmpty() ? 0 : a.prices.lastEntry().getValue());
        String key = k.toString();
        TreeMap<Long, Double> hit = HIST.get(key);
        if (hit != null) return hit;
        if (HIST.size() > 40) HIST.clear();
        TreeMap<Long, Double> out = computeHistory(l);
        HIST.put(key, out);
        return out;
    }

    private static TreeMap<Long, Double> computeHistory(List<Asset> l) {
        TreeMap<Long, Double> out = new TreeMap<>();
        List<Asset> held = new ArrayList<>();
        for (Asset a : l) if (!a.prices.isEmpty() && (a.held() || a.hasLedger())) held.add(a);
        if (held.isEmpty()) return out;
        TreeSet<Long> days = new TreeSet<>();
        for (Asset a : held) days.addAll(a.prices.keySet());
        // with transactions, history starts at the first purchase and uses units actually held each day
        long first = Long.MAX_VALUE;
        boolean allLedger = true;
        for (Asset a : held) {
            if (a.hasLedger()) first = Math.min(first, a.txns.get(0).day); else allLedger = false;
        }
        for (Long d : days) {
            if (allLedger && d < first) continue;
            double v = 0;
            for (Asset a : held) {
                Double p = a.priceOnOrBefore(d);
                v += (p != null ? p : a.prices.firstEntry().getValue()) * a.unitsOn(d);
            }
            out.put(d, v);
        }
        return out;
    }

    /** XIRR (%) of the holdings in l that have dated transactions; [rate or null, covered, total]. */
    public static Object[] xirr(List<Asset> l) {
        List<double[]> flows = new ArrayList<>();
        int covered = 0, total = 0;
        double terminal = 0;
        long today = LocalDate.now().toEpochDay();
        for (Asset a : l) {
            if (!a.held() && !a.hasLedger()) continue;
            total++;
            if (!a.hasLedger()) continue;
            covered++;
            Ledger.flows(a.txns, flows);
            terminal += a.value();
        }
        if (covered == 0) return new Object[]{null, 0, total};
        flows.add(new double[]{today, terminal});
        return new Object[]{Ledger.xirr(flows), covered, total};
    }

    public static double realised(List<Asset> l) { double t = 0; for (Asset a : l) t += a.realised; return t; }
    public static double dividends(List<Asset> l) { double t = 0; for (Asset a : l) t += a.dividends; return t; }

    /** Add due SIP instalments everywhere. Returns how many were added. */
    public int applyAllSips() {
        long today = LocalDate.now().toEpochDay();
        int n = 0;
        for (Asset a : assets) n += a.applySips(today);
        if (n > 0) save();
        return n;
    }

    public List<Asset> visiblePlatform(String platform) {
        List<Asset> l = new ArrayList<>();
        for (Asset a : assets) if (!a.hidden && !a.watch && a.type != LOAN && ownerOk(a) && a.platformOrDefault().equalsIgnoreCase(platform)) l.add(a);
        return l;
    }

    public List<String> platforms() {
        List<String> out = new ArrayList<>();
        for (Asset a : visible(-1)) {
            String p = a.platformOrDefault();
            boolean dup = false;
            for (String x : out) if (x.equalsIgnoreCase(p)) dup = true;
            if (!dup) out.add(p);
        }
        return out;
    }

    public void renamePlatform(String from, String to) {
        for (Asset a : assets) if (a.platformOrDefault().equalsIgnoreCase(from)) a.platform = to;
    }

    public Long latestDay(int type) {
        Long d = null;
        for (Asset a : visible(type)) {
            Map.Entry<Long, Double> l = a.latest();
            if (l != null && (d == null || l.getKey() > d)) d = l.getKey();
        }
        return d;
    }
}
