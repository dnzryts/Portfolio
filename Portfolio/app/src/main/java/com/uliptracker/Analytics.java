package com.uliptracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Analytics maths, kept free of Android so it can be tested on a desktop JVM:
 * asset classes, how today's holdings have performed, a Monte Carlo forecast and goal maths.
 */
public final class Analytics {

    private Analytics() { }

    // ------------------------------------------------------------------ asset classes
    public static final int EQUITY = 0, HYBRID = 1, DEBT = 2, GOLD = 3, FIXED = 4, REAL = 5, CASH = 6;
    public static final String[] CLASS_NAMES = {"Equity", "Hybrid", "Debt", "Gold", "Deposits & PF", "Real estate", "Cash"};
    /** Long-run assumptions, % a year (editable in the app). */
    public static final double[] DEF_RETURN = {12, 10, 7, 9, 7, 7, 3.5};
    public static final double[] DEF_VOL = {17, 10, 3, 14, 0, 6, 0.5};
    /** How much of each class behaves like shares (for the equity share). */
    public static final double[] EQ_SHARE = {1, 0.6, 0, 0, 0, 0, 0};
    public static final double PPF_RATE = 7.1, EPF_RATE = 8.25;   // PPF Oct–Dec 2026; EPF FY 2025-26

    private static final double[][] CORR = {
            //  EQ    HY    DB    GD   FX    RE   CA
            {1.00, 0.90, 0.10, 0.00, 0, 0.20, 0},
            {0.90, 1.00, 0.35, 0.05, 0, 0.20, 0},
            {0.10, 0.35, 1.00, 0.20, 0, 0.10, 0},
            {0.00, 0.05, 0.20, 1.00, 0, 0.10, 0},
            {0, 0, 0, 0, 1, 0, 0},
            {0.20, 0.20, 0.10, 0.10, 0, 1.00, 0},
            {0, 0, 0, 0, 0, 0, 1}};

    public static int classOf(Store.Asset a) {
        String n = (a.name == null ? "" : a.name).toLowerCase(Locale.ROOT);
        String t = ((a.category == null ? "" : a.category) + " " + n).toLowerCase(Locale.ROOT);
        switch (a.type) {
            case Store.STOCK:
                if (n.contains("gold") || n.contains("silver")) return GOLD;
                if (n.contains("liquid")) return CASH;
                return EQUITY;
            case Store.MF:
                if (t.contains("gold") || t.contains("silver")) return GOLD;
                if (t.contains("liquid") || t.contains("overnight") || t.contains("money market")) return CASH;
                if (t.contains("arbitrage")) return DEBT;
                if (t.contains("hybrid") || t.contains("balanced") || t.contains("asset allocation") || t.contains("multi asset")
                        || t.contains("equity savings") || t.contains("retirement") || t.contains("children")) return HYBRID;
                if (t.contains("debt") || t.contains("gilt") || t.contains("bond") || t.contains("duration") || t.contains("credit risk")
                        || t.contains("banking and psu") || t.contains("floater") || t.contains("income")) return DEBT;
                return EQUITY;
            case Store.ULIP:
                if (n.contains("bond") || n.contains("money market") || n.contains("debt") || n.contains("gilt")
                        || n.contains("secure") || n.contains("liquid")) return DEBT;
                if (n.contains("balanced") || n.contains("growth")) return HYBRID;
                return EQUITY;
            case Store.OTHER: {
                String k = a.kind == null ? "" : a.kind;
                if (k.equals("Fixed deposit") || k.equals("Recurring deposit") || k.equals("PPF") || k.equals("EPF")) return FIXED;
                if (k.equals("NPS")) return HYBRID;
                if (k.startsWith("Gold")) return GOLD;
                if (k.equals("Real estate")) return REAL;
                if (k.startsWith("Savings")) return CASH;
                return DEBT;    // bonds, other
            }
            default:
                return EQUITY;
        }
    }

    /** Expected yearly return (%) for one holding: fixed-rate items use their own rate. */
    public static double expReturn(Store.Asset a, double[] classRet) {
        int c = classOf(a);
        if (a.type == Store.OTHER) {
            if (a.rate > 0 && (c == FIXED || c == CASH || c == DEBT)) return a.rate;
            if ("PPF".equals(a.kind)) return PPF_RATE;
            if ("EPF".equals(a.kind)) return EPF_RATE;
        }
        return classRet[c];
    }

    /** Annualised volatility (%) from up to 3 years of weekly prices, or null if there's too little history. */
    public static Double histVol(Store.Asset a) {
        if (a.type == Store.OTHER || a.type == Store.LOAN || a.prices.size() < 40) return null;
        long last = a.prices.lastKey(), first = Math.max(a.prices.firstKey(), last - 3 * 365);
        if (last - first < 300) return null;
        List<Double> r = new ArrayList<>();
        Double prev = null;
        for (long d = first; d <= last; d += 7) {
            Map.Entry<Long, Double> e = a.prices.floorEntry(d);
            if (e == null || !(e.getValue() > 0) || Double.isInfinite(e.getValue())) continue;   // skip bad (zero/blank) prices
            if (prev != null && prev > 0) r.add(Math.log(e.getValue() / prev));
            prev = e.getValue();
        }
        if (r.size() < 30) return null;
        double m = 0;
        for (double x : r) m += x;
        m /= r.size();
        double v = 0;
        for (double x : r) v += (x - m) * (x - m);
        double vol = Math.sqrt(v / (r.size() - 1)) * Math.sqrt(52) * 100;
        if (Double.isNaN(vol) || Double.isInfinite(vol)) return null;
        return Math.max(2, Math.min(70, vol));
    }

    // ------------------------------------------------------------------ portfolio assumptions
    public static class Mix {
        public double value;                          // total included
        public double mu, sigma;                      // expected return and volatility, % a year
        public final double[] byClass = new double[CLASS_NAMES.length];
        public double equityShare;                    // % behaving like shares
        public int holdings, withHistory;
    }

    public static Mix mix(List<Store.Asset> list, double[] classRet) {
        Mix m = new Mix();
        List<Store.Asset> in = new ArrayList<>();
        for (Store.Asset a : list) if (a.type != Store.LOAN && a.type != Store.INDEX && !a.watch && a.value() > 0) in.add(a);
        int n = in.size();
        double[] w = new double[n], mu = new double[n], sd = new double[n];
        int[] cls = new int[n];
        boolean[] stock = new boolean[n];
        for (int i = 0; i < n; i++) {
            Store.Asset a = in.get(i);
            double v = a.value();
            m.value += v;
            cls[i] = classOf(a);
            mu[i] = expReturn(a, classRet);
            Double hv = histVol(a);
            if (hv != null) m.withHistory++;
            sd[i] = hv != null && cls[i] != FIXED ? hv : DEF_VOL[cls[i]];
            if (Double.isNaN(mu[i]) || Double.isInfinite(mu[i])) mu[i] = classRet[cls[i]];
            stock[i] = a.type == Store.STOCK;
            w[i] = v;
            m.byClass[cls[i]] += v;
        }
        m.holdings = n;
        if (m.value <= 0) return m;
        double var = 0;
        for (int i = 0; i < n; i++) {
            w[i] /= m.value;
            m.mu += w[i] * mu[i];
            for (int j = 0; j < n; j++) {
                double rho = i == j ? 1 : CORR[cls[i]][cls[j]];
                // single shares move together less than funds do
                if (i != j && cls[i] == EQUITY && cls[j] == EQUITY && (stock[i] || stock[j])) rho = 0.55;
                var += w[i] * w[j] * rho * sd[i] * sd[j];
            }
        }
        m.sigma = Math.sqrt(Math.max(0, var));
        if (Double.isNaN(m.sigma) || Double.isInfinite(m.sigma)) m.sigma = DEF_VOL[EQUITY];
        if (Double.isNaN(m.mu) || Double.isInfinite(m.mu)) m.mu = classRet[EQUITY];
        for (int c = 0; c < CLASS_NAMES.length; c++) m.equityShare += m.byClass[c] / m.value * EQ_SHARE[c] * 100;
        return m;
    }

    // ------------------------------------------------------------------ forecast
    public static final int MAX_YEARS = 30;

    /**
     * Monte Carlo paths of the whole portfolio with monthly investing. Values are linear in the monthly amount,
     * so each path keeps the growth of today's money (a) and of one rupee a month (b): value = start·a + monthly·b.
     */
    public static class Forecast {
        public final double start, monthly, stepUp, mu, sigma;
        public final int paths;
        final double[][] a, b;          // [year][path]
        public final double[] unitContrib = new double[MAX_YEARS + 1];   // rupees put in per ₹1 a month, by year

        Forecast(double start, double monthly, double stepUp, double mu, double sigma, int paths) {
            this.start = start; this.monthly = monthly; this.stepUp = stepUp; this.mu = mu; this.sigma = sigma; this.paths = paths;
            a = new double[MAX_YEARS + 1][paths];
            b = new double[MAX_YEARS + 1][paths];
        }

        public double value(int year, int path, double monthlyAmt) { return start * a[year][path] + monthlyAmt * b[year][path]; }

        /** Percentile q (0..1) of the value after `year` years. */
        public double pct(int year, double q) { return pct(year, q, monthly); }

        public double pct(int year, double q, double monthlyAmt) {
            double[] v = new double[paths];
            for (int p = 0; p < paths; p++) v[p] = value(year, p, monthlyAmt);
            Arrays.sort(v);
            int i = (int) Math.round(q * (paths - 1));
            return v[Math.max(0, Math.min(paths - 1, i))];
        }

        /** What you'll have put in by `year` (today's value plus contributions). */
        public double contributed(int year) { return start + monthly * unitContrib[year]; }

        public double chance(int year, double target) { return chance(year, target, monthly); }

        public double chance(int year, double target, double monthlyAmt) {
            int ok = 0;
            for (int p = 0; p < paths; p++) if (value(year, p, monthlyAmt) >= target) ok++;
            return (double) ok / paths;
        }

        /** Monthly amount (first-year level, before step-ups) needed for probability q of reaching target by year. */
        public double monthlyNeeded(int year, double target, double q) {
            if (year <= 0) return Double.NaN;
            double[] need = new double[paths];
            for (int p = 0; p < paths; p++) {
                double bb = b[year][p];
                need[p] = bb <= 0 ? Double.POSITIVE_INFINITY : (target - start * a[year][p]) / bb;
            }
            Arrays.sort(need);
            int i = (int) Math.ceil(q * paths) - 1;
            return Math.max(0, need[Math.max(0, Math.min(paths - 1, i))]);
        }

        /** First year (1..MAX_YEARS) with at least probability q of reaching target, or -1. */
        public int yearsNeeded(double target, double q, double monthlyAmt) {
            for (int y = 1; y <= MAX_YEARS; y++) if (chance(y, target, monthlyAmt) >= q) return y;
            return -1;
        }
    }

    /**
     * @param mu    expected return, % a year (arithmetic)
     * @param sigma volatility, % a year
     * @param stepUp yearly increase of the monthly amount, %
     */
    public static Forecast simulate(double start, double monthly, double stepUp, double mu, double sigma, int paths, long seed) {
        Forecast f = new Forecast(start, monthly, stepUp, mu, sigma, paths);
        // keep the inputs realistic, so one bad price or rate can never collapse the forecast to zero
        if (Double.isNaN(sigma) || Double.isInfinite(sigma)) sigma = 15;
        if (Double.isNaN(mu) || Double.isInfinite(mu)) mu = 0;
        sigma = Math.max(0, Math.min(60, sigma));
        mu = Math.max(-20, Math.min(40, mu));
        double s = sigma / 100, m = Math.log(1 + mu / 100);
        double drift = (m - s * s / 2) / 12, vol = s / Math.sqrt(12);
        Random rnd = new Random(seed);
        double put = 0;
        double[] inst = new double[MAX_YEARS * 12];
        for (int t = 0; t < inst.length; t++) {
            inst[t] = Math.pow(1 + stepUp / 100, t / 12);
            put += inst[t];
            if ((t + 1) % 12 == 0) f.unitContrib[(t + 1) / 12] = put;
        }
        for (int p = 0; p < paths; p++) {
            double a = 1, b = 0;
            f.a[0][p] = 1; f.b[0][p] = 0;
            for (int t = 0; t < MAX_YEARS * 12; t++) {
                b += inst[t];        // this month's instalment, invested at the start of the month
                double g = Math.exp(drift + vol * rnd.nextGaussian());
                a *= g; b *= g;
                if ((t + 1) % 12 == 0) { int y = (t + 1) / 12; f.a[y][p] = a; f.b[y][p] = b; }
            }
        }
        return f;
    }

    // ------------------------------------------------------------------ past performance of today's holdings
    public static class Perf {
        public final TreeMap<Long, Double> index = new TreeMap<>();   // today's holdings, as if held throughout (starts at 100)
        public final TreeMap<Long, Double> bench = new TreeMap<>();   // Nifty 50 on the same days
        public Double r1m, r3m, r6m, r1y, cagr3y;
        public Double vol, beta;
        public double maxDD;            // %, negative
        public long ddPeak, ddLow, ddRecovered;    // epoch days (recovered = 0 if not yet)
        public final TreeMap<Long, Double> months = new TreeMap<>();  // month start -> return %
        public double coverage;         // share of market-priced value that has history
    }

    public static Perf performance(List<Store.Asset> list, Store.Asset nifty) {
        Perf pf = new Perf();
        List<Store.Asset> in = new ArrayList<>();
        double totalMkt = 0, covered = 0;
        for (Store.Asset a : list) {
            if (a.type != Store.MF && a.type != Store.STOCK && a.type != Store.ULIP) continue;
            if (a.watch || !a.held()) continue;
            totalMkt += a.value();
            if (a.prices.size() >= 2) { in.add(a); covered += a.value(); }
        }
        if (in.isEmpty()) return pf;
        pf.coverage = totalMkt > 0 ? covered / totalMkt : 0;
        long last = 0;
        for (Store.Asset a : in) last = Math.max(last, a.prices.lastKey());
        long from = last - 3 * 365 - 10;
        java.util.TreeSet<Long> days = new java.util.TreeSet<>();
        for (Store.Asset a : in) days.addAll(a.prices.subMap(from, true, last, true).keySet());
        Long prevDay = null;
        double level = 100, nb = 100;
        List<double[]> pairs = new ArrayList<>();   // [portfolio r, nifty r] over the last year
        for (long d : days) {
            if (prevDay == null) { prevDay = d; pf.index.put(d, level); if (nifty != null && nifty.priceOnOrBefore(d) != null) pf.bench.put(d, nb); continue; }
            double num = 0, den = 0;
            for (Store.Asset a : in) {
                Map.Entry<Long, Double> p0 = a.prices.floorEntry(prevDay), p1 = a.prices.floorEntry(d);
                if (p0 == null || p1 == null || p0.getValue() <= 0) continue;
                double w = a.value();
                num += w * (p1.getValue() / p0.getValue() - 1);
                den += w;
            }
            double r = den > 0 ? num / den : 0;
            level *= 1 + r;
            pf.index.put(d, level);
            if (nifty != null) {
                Double n0 = nifty.priceOnOrBefore(prevDay), n1 = nifty.priceOnOrBefore(d);
                if (n0 != null && n1 != null && n0 > 0) {
                    double rn = n1 / n0 - 1;
                    if (pf.bench.isEmpty()) pf.bench.put(prevDay, nb);
                    nb *= 1 + rn;
                    pf.bench.put(d, nb);
                    if (d > last - 365) pairs.add(new double[]{r, rn});
                }
            }
            prevDay = d;
        }
        long end = pf.index.lastKey();
        double now = pf.index.lastEntry().getValue();
        pf.r1m = change(pf.index, end, 30);
        pf.r3m = change(pf.index, end, 91);
        pf.r6m = change(pf.index, end, 182);
        pf.r1y = change(pf.index, end, 365);
        if (end - pf.index.firstKey() >= 3 * 365 - 5) {
            Map.Entry<Long, Double> e = pf.index.floorEntry(end - 3 * 365);
            if (e != null) pf.cagr3y = (Math.pow(now / e.getValue(), 365.0 / (end - e.getKey())) - 1) * 100;
        }
        // volatility from the last year's daily moves
        List<Double> rs = new ArrayList<>();
        Double pv = null;
        for (Map.Entry<Long, Double> e : pf.index.tailMap(end - 365, true).entrySet()) {
            if (pv != null) rs.add(e.getValue() / pv - 1);
            pv = e.getValue();
        }
        if (rs.size() >= 40) {
            double m = 0;
            for (double x : rs) m += x;
            m /= rs.size();
            double v = 0;
            for (double x : rs) v += (x - m) * (x - m);
            double perYear = rs.size() * 365.0 / Math.max(1, Math.min(365, end - pf.index.firstKey()));
            pf.vol = Math.sqrt(v / (rs.size() - 1) * perYear) * 100;
        }
        if (pairs.size() >= 40) {
            double mx = 0, my = 0;
            for (double[] q : pairs) { mx += q[0]; my += q[1]; }
            mx /= pairs.size(); my /= pairs.size();
            double cov = 0, vy = 0;
            for (double[] q : pairs) { cov += (q[0] - mx) * (q[1] - my); vy += (q[1] - my) * (q[1] - my); }
            if (vy > 0) pf.beta = cov / vy;
        }
        // worst fall from a high
        double peak = -1; long peakDay = 0;
        for (Map.Entry<Long, Double> e : pf.index.entrySet()) {
            if (e.getValue() > peak) { peak = e.getValue(); peakDay = e.getKey(); }
            double dd = (e.getValue() / peak - 1) * 100;
            if (dd < pf.maxDD) { pf.maxDD = dd; pf.ddPeak = peakDay; pf.ddLow = e.getKey(); }
        }
        if (pf.maxDD < 0) {
            double pk = pf.index.get(pf.ddPeak);
            for (Map.Entry<Long, Double> e : pf.index.tailMap(pf.ddLow, false).entrySet())
                if (e.getValue() >= pk) { pf.ddRecovered = e.getKey(); break; }
        }
        // calendar months, last 12
        LocalDate lm = LocalDate.ofEpochDay(end).withDayOfMonth(1);
        for (int i = 11; i >= 0; i--) {
            LocalDate ms = lm.minusMonths(i);
            Map.Entry<Long, Double> s0 = pf.index.floorEntry(ms.toEpochDay() - 1);
            Map.Entry<Long, Double> s1 = pf.index.floorEntry(Math.min(end, ms.plusMonths(1).toEpochDay() - 1));
            if (s0 == null || s1 == null || s1.getKey() <= s0.getKey()) continue;
            pf.months.put(ms.toEpochDay(), (s1.getValue() / s0.getValue() - 1) * 100);
        }
        return pf;
    }

    private static Double change(TreeMap<Long, Double> s, long end, int days) {
        Map.Entry<Long, Double> e = s.floorEntry(end - days);
        if (e == null || end - e.getKey() > days + 10 || e.getKey() < s.firstKey()) return null;
        return (s.get(end) / e.getValue() - 1) * 100;
    }

    // ------------------------------------------------------------------ helpers
    /** Compact rupees for chart labels: ₹45.2L, ₹1.35Cr. */
    public static String shortRupees(double v) {
        if (Ui.PRIVATE) return Ui.SYM.trim() + "•••";
        double a = Math.abs(v);
        if (!"INR".equals(Ui.CCY)) {      // K / M / B outside India
            String t = a >= 1e9 ? String.format(Locale.US, "%.2fB", a / 1e9) : a >= 1e6 ? String.format(Locale.US, "%.2fM", a / 1e6)
                    : a >= 1e3 ? String.format(Locale.US, "%.1fK", a / 1e3) : String.format(Locale.US, "%.0f", a);
            t = t.replace(".00B", "B").replace(".00M", "M").replace(".0K", "K");
            return (v < 0 ? "-" : "") + Ui.SYM + t;
        }
        String s = a >= 1e7 ? String.format(Locale.US, "%.2fCr", a / 1e7)
                : a >= 1e5 ? String.format(Locale.US, "%.1fL", a / 1e5)
                : a >= 1e3 ? String.format(Locale.US, "%.0fk", a / 1e3) : String.format(Locale.US, "%.0f", a);
        s = s.replace(".00Cr", "Cr").replace(".0L", "L");
        return (v < 0 ? "-₹" : "₹") + s;
    }

    /** Equity share band (min, max %) for a risk profile: 0 conservative, 1 moderate, 2 aggressive. */
    public static int[] band(int profile) {
        return profile == 0 ? new int[]{20, 40} : profile == 2 ? new int[]{65, 85} : new int[]{45, 65};
    }
}
