package com.uliptracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Transactions, SIPs, FIFO cost basis and XIRR. Pure Java (testable off-device). */
public final class Ledger {
    private Ledger() { }

    public static final int BUY = 0, SELL = 1, DIV = 2;
    public static final String[] KIND = {"Buy", "Sell", "Dividend"};

    public static class Txn {
        public long day;
        public int kind;
        public double units;    // BUY/SELL quantity
        public double price;    // per unit
        public double amount;   // cash: BUY paid, SELL received, DIV received
        public boolean sip;     // created by a SIP
        public String note = "";

        public Txn copy() {
            Txn t = new Txn();
            t.day = day; t.kind = kind; t.units = units; t.price = price; t.amount = amount; t.sip = sip; t.note = note;
            return t;
        }
    }

    public static class Sip {
        public double amount;
        public int dom = 5;          // day of month
        public long start;           // epoch day of the first instalment month
        public long end;             // 0 = open-ended
        public long lastApplied;     // epoch day of the last instalment added
    }

    /** Result of replaying transactions. */
    public static class Book {
        public double units, cost, realised, dividends, bought, sold;
    }

    public static void sort(List<Txn> l) {
        Collections.sort(l, new Comparator<Txn>() {
            @Override public int compare(Txn a, Txn b) {
                if (a.day != b.day) return Long.compare(a.day, b.day);
                return Integer.compare(a.kind, b.kind);   // buys before sells on the same day
            }
        });
    }

    /** FIFO replay up to and including `upTo` (Long.MAX_VALUE for all). */
    public static Book replay(List<Txn> txns, long upTo) {
        List<Txn> l = new ArrayList<>(txns);
        sort(l);
        Book b = new Book();
        List<double[]> lots = new ArrayList<>();   // [units, costPerUnit]
        for (Txn t : l) {
            if (t.day > upTo) break;
            if (t.kind == BUY) {
                double cpu = t.units > 0 ? t.amount / t.units : 0;
                lots.add(new double[]{t.units, cpu});
                b.bought += t.amount;
            } else if (t.kind == SELL) {
                double left = t.units, costOut = 0;
                while (left > 1e-9 && !lots.isEmpty()) {
                    double[] lot = lots.get(0);
                    double take = Math.min(left, lot[0]);
                    costOut += take * lot[1];
                    lot[0] -= take;
                    left -= take;
                    if (lot[0] <= 1e-9) lots.remove(0);
                }
                b.realised += t.amount - costOut;
                b.sold += t.amount;
            } else if (t.kind == DIV) {
                b.dividends += t.amount;
            }
        }
        for (double[] lot : lots) { b.units += lot[0]; b.cost += lot[0] * lot[1]; }
        return b;
    }

    /** Units held at the end of `day`. */
    public static double unitsOn(List<Txn> txns, long day) {
        double u = 0;
        for (Txn t : txns) {
            if (t.day > day) continue;
            if (t.kind == BUY) u += t.units; else if (t.kind == SELL) u -= t.units;
        }
        return Math.max(0, u);
    }

    /**
     * Adds any due SIP instalments. Each uses the first price on or after the instalment date
     * (instalments whose price isn't available yet are left for later). Returns how many were added.
     */
    public static int applySips(List<Sip> sips, List<Txn> txns, TreeMap<Long, Double> prices, boolean wholeUnits, long today) {
        int added = 0;
        for (Sip s : sips) {
            if (s.amount <= 0 || s.start <= 0) continue;
            LocalDate d = LocalDate.ofEpochDay(s.start);
            LocalDate next = d.withDayOfMonth(Math.min(s.dom, d.lengthOfMonth()));
            if (next.toEpochDay() < s.start) next = next.plusMonths(1).withDayOfMonth(Math.min(s.dom, next.plusMonths(1).lengthOfMonth()));
            if (s.lastApplied > 0) {
                LocalDate la = LocalDate.ofEpochDay(s.lastApplied).plusMonths(1);
                next = la.withDayOfMonth(Math.min(s.dom, la.lengthOfMonth()));
            }
            int guard = 0;
            while (next.toEpochDay() <= today && (s.end <= 0 || next.toEpochDay() <= s.end) && guard++ < 600) {
                Map.Entry<Long, Double> px = prices.ceilingEntry(next.toEpochDay());
                if (px == null || px.getKey() > next.toEpochDay() + 7) break;   // price not available yet
                Txn t = new Txn();
                t.day = px.getKey();
                t.kind = BUY;
                t.price = px.getValue();
                t.units = wholeUnits ? Math.floor(s.amount / t.price) : Math.round(s.amount / t.price * 1000) / 1000.0;
                t.amount = t.units * t.price;
                t.sip = true;
                t.note = "SIP";
                if (t.units > 0) { txns.add(t); added++; }
                s.lastApplied = next.toEpochDay();
                LocalDate nm = next.plusMonths(1);
                next = nm.withDayOfMonth(Math.min(s.dom, nm.lengthOfMonth()));
            }
        }
        if (added > 0) sort(txns);
        return added;
    }

    // ------------------------------------------------------------------ capital gains (FIFO, holding period)
    public static class Gains {
        public double stRealised, ltRealised;      // in the chosen financial year
        public double stUnreal, ltUnreal;          // on units still held
        public double soonLtGain;                  // unrealised gain turning long-term within 60 days
        public long soonDay;
    }

    /** Equity-style split: long-term when held more than 365 days. */
    public static Gains gains(List<Txn> txns, long fyStart, long fyEnd, double price, long today) {
        List<Txn> l = new ArrayList<>(txns);
        sort(l);
        Gains g = new Gains();
        List<double[]> lots = new ArrayList<>();   // [units, costPerUnit, buyDay]
        for (Txn t : l) {
            if (t.kind == BUY) lots.add(new double[]{t.units, t.units > 0 ? t.amount / t.units : 0, t.day});
            else if (t.kind == SELL) {
                double left = t.units, pricePer = t.units > 0 ? t.amount / t.units : 0;
                while (left > 1e-9 && !lots.isEmpty()) {
                    double[] lot = lots.get(0);
                    double take = Math.min(left, lot[0]);
                    double gain = take * (pricePer - lot[1]);
                    if (t.day >= fyStart && t.day <= fyEnd) {
                        if (t.day - (long) lot[2] > 365) g.ltRealised += gain; else g.stRealised += gain;
                    }
                    lot[0] -= take; left -= take;
                    if (lot[0] <= 1e-9) lots.remove(0);
                }
            }
        }
        for (double[] lot : lots) {
            double gain = lot[0] * (price - lot[1]);
            long held = today - (long) lot[2];
            if (held > 365) g.ltUnreal += gain;
            else {
                g.stUnreal += gain;
                if (held > 305 && gain > 0) { g.soonLtGain += gain; long d = (long) lot[2] + 366; if (g.soonDay == 0 || d < g.soonDay) g.soonDay = d; }
            }
        }
        return g;
    }

    // ------------------------------------------------------------------ XIRR
    /** Cash flows: [epochDay, amount] (outflows negative). Returns annual rate in %, or null. */
    public static Double xirr(List<double[]> flows) {
        if (flows.size() < 2) return null;
        boolean pos = false, neg = false;
        double t0 = Double.MAX_VALUE;
        for (double[] f : flows) { if (f[1] > 0) pos = true; if (f[1] < 0) neg = true; t0 = Math.min(t0, f[0]); }
        if (!pos || !neg) return null;
        double span = 0;
        for (double[] f : flows) span = Math.max(span, f[0] - t0);
        if (span < 1) return null;
        // Newton–Raphson from 10%, falling back to bisection
        double r = 0.1;
        for (int i = 0; i < 60; i++) {
            double f = npv(flows, t0, r), df = dnpv(flows, t0, r);
            if (Math.abs(df) < 1e-12) break;
            double nr = r - f / df;
            if (nr <= -0.9999 || Double.isNaN(nr) || Double.isInfinite(nr)) break;
            if (Math.abs(nr - r) < 1e-9) return nr * 100;
            r = nr;
        }
        double lo = -0.9999, hi = 10;
        double flo = npv(flows, t0, lo), fhi = npv(flows, t0, hi);
        if (Double.isNaN(flo) || flo * fhi > 0) return null;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2, fm = npv(flows, t0, mid);
            if (Math.abs(fm) < 1e-7) return mid * 100;
            if (flo * fm < 0) { hi = mid; } else { lo = mid; flo = fm; }
        }
        return (lo + hi) / 2 * 100;
    }

    private static double npv(List<double[]> fl, double t0, double r) {
        double s = 0;
        for (double[] f : fl) s += f[1] / Math.pow(1 + r, (f[0] - t0) / 365.0);
        return s;
    }

    private static double dnpv(List<double[]> fl, double t0, double r) {
        double s = 0;
        for (double[] f : fl) {
            double t = (f[0] - t0) / 365.0;
            s += -t * f[1] / Math.pow(1 + r, t + 1);
        }
        return s;
    }

    /** Adds a holding's flows (buys -, sells +, dividends +) to `out`. */
    public static void flows(List<Txn> txns, List<double[]> out) {
        for (Txn t : txns) {
            if (t.kind == BUY) out.add(new double[]{t.day, -t.amount});
            else out.add(new double[]{t.day, t.amount});
        }
    }
}
