package com.uliptracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text of a CAMS / KFintech Consolidated Account Statement (detailed or summary) into
 * schemes with closing units, cost, ISIN and dated transactions. Pure Java.
 */
public final class Cas {
    private Cas() { }

    /** One holding found in an import (mutual fund scheme or stock). */
    public static class Item {
        public int type = Store.MF;
        public String name = "", isin = "", folio = "", symbol = "", code = "", matchName = "";
        public double units, cost, nav, marketValue;
        public long navDay;
        public final List<Ledger.Txn> txns = new ArrayList<>();
        public boolean include = true, historyOk;
        public String note = "";
    }

    private static final Pattern ISIN = Pattern.compile("ISIN\\s*:?\\s*(IN[EF][0-9A-Z]{9})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLIO = Pattern.compile("Folio\\s*(?:No\\.?|Number)?\\s*:?\\s*([0-9A-Za-z/ ]{3,30}?)(?=\\s{2,}|\\s+PAN|\\s+KYC|\\s*$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING = Pattern.compile("Closing\\s+Unit\\s+Balance\\s*:?\\s*([\\d,]+\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING = Pattern.compile("Opening\\s+Unit\\s+Balance\\s*:?\\s*([\\d,]+\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST = Pattern.compile("(?:Total\\s+)?Cost\\s+Value\\s*:?\\s*(?:INR|Rs\\.?|₹)?\\s*([\\d,]+\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV = Pattern.compile("NAV\\s+on\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\s*:?\\s*(?:INR|Rs\\.?|₹)?\\s*([\\d,]+\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MV = Pattern.compile("(?:Market|Valuation)\\s+Value\\s+on\\s+\\d{1,2}-[A-Za-z]{3}-\\d{4}\\s*:?\\s*(?:INR|Rs\\.?|₹)?\\s*([\\d,]+\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TXN = Pattern.compile("^(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\s+(.*?)\\s*((?:\\(?-?[\\d,]*\\.?\\d+\\)?\\s*){1,4})$");
    private static final Pattern NUM = Pattern.compile("\\(?-?[\\d,]*\\.?\\d+\\)?");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

    public static boolean looksLikeCas(List<String> lines) {
        int isin = 0, cas = 0;
        for (String l : lines) {
            if (ISIN.matcher(l).find()) isin++;
            String u = l.toUpperCase(Locale.ROOT);
            if (u.contains("CONSOLIDATED ACCOUNT STATEMENT") || u.contains("CLOSING UNIT BALANCE") || u.contains("CAMS") || u.contains("KFINTECH")) cas++;
        }
        return isin > 0 && (cas > 0 || isin > 1);
    }

    public static List<Item> parse(List<String> lines) {
        List<Item> out = new ArrayList<>();
        Item cur = null;
        String folio = "";
        double opening = 0;
        String prev = "";
        for (String raw : lines) {
            String l = raw.replace(' ', ' ').trim();
            if (l.isEmpty()) continue;
            Matcher fm = FOLIO.matcher(l);
            if (fm.find()) folio = fm.group(1).trim();
            Matcher im = ISIN.matcher(l);
            if (im.find()) {
                if (cur != null) finish(cur, opening);
                cur = new Item();
                cur.isin = im.group(1).toUpperCase(Locale.ROOT);
                cur.folio = folio;
                String nm = l.substring(0, im.start());
                if (nm.replaceAll("[^A-Za-z]", "").length() < 6) nm = prev + " " + nm;
                cur.name = cleanName(nm);
                out.add(cur);
                opening = 0;
                // summary statements put the numbers on the same line
                scanSummary(cur, l.substring(im.end()));
                prev = l;
                continue;
            }
            if (cur != null) {
                Matcher m;
                if ((m = OPENING.matcher(l)).find()) opening = num(m.group(1));
                if ((m = CLOSING.matcher(l)).find()) cur.units = num(m.group(1));
                if ((m = COST.matcher(l)).find()) cur.cost = num(m.group(1));
                if ((m = NAV.matcher(l)).find()) {
                    cur.nav = num(m.group(2));
                    try { cur.navDay = LocalDate.parse(cap(m.group(1)), D).toEpochDay(); } catch (Exception ignored) { }
                }
                if ((m = MV.matcher(l)).find()) cur.marketValue = num(m.group(1));
                if ((m = TXN.matcher(l)).find()) txn(cur, m);
            }
            prev = l;
        }
        if (cur != null) finish(cur, opening);
        // drop duplicates of the same scheme+folio (statements sometimes repeat headers across pages)
        List<Item> dedup = new ArrayList<>();
        for (Item it : out) {
            Item same = null;
            for (Item d : dedup) if (d.isin.equals(it.isin) && d.folio.equals(it.folio)) same = d;
            if (same == null) dedup.add(it);
            else if (same.units == 0 && it.units > 0) dedup.set(dedup.indexOf(same), it);
        }
        return dedup;
    }

    private static String cap(String s) {   // "05-JAN-2024" -> "05-Jan-2024"
        String[] p = s.split("-");
        if (p.length != 3) return s;
        return p[0] + "-" + p[1].substring(0, 1).toUpperCase(Locale.ROOT) + p[1].substring(1).toLowerCase(Locale.ROOT) + "-" + p[2];
    }

    private static void txn(Item cur, Matcher m) {
        long day;
        try { day = LocalDate.parse(cap(m.group(1)), D).toEpochDay(); } catch (Exception e) { return; }
        String desc = m.group(2).toLowerCase(Locale.ROOT);
        List<Double> nums = new ArrayList<>();
        Matcher n = NUM.matcher(m.group(3));
        while (n.find()) {
            String t = n.group();
            boolean neg = t.startsWith("(") || t.startsWith("-");
            double v = num(t);
            nums.add(neg ? -v : v);
        }
        if (nums.isEmpty()) return;
        if (nums.size() == 1) {
            double amt = Math.abs(nums.get(0));
            if (desc.contains("stamp duty")) {
                // stamp duty is part of the cost of that day's purchase
                for (int i = cur.txns.size() - 1; i >= 0; i--) {
                    Ledger.Txn t = cur.txns.get(i);
                    if (t.day == day && t.kind == Ledger.BUY) { t.amount += amt; break; }
                }
            } else if ((desc.contains("dividend") || desc.contains("idcw")) && !desc.contains("reinvest")) {
                Ledger.Txn t = new Ledger.Txn();
                t.day = day; t.kind = Ledger.DIV; t.amount = amt; t.note = "Dividend";
                cur.txns.add(t);
            }
            return;   // STT, TDS and similar charges are ignored
        }
        if (nums.size() < 3) return;
        double amount = nums.get(0), units = nums.get(1), price = nums.get(2);
        if (units == 0) return;
        Ledger.Txn t = new Ledger.Txn();
        t.day = day;
        t.kind = units < 0 ? Ledger.SELL : Ledger.BUY;
        t.units = Math.abs(units);
        t.price = Math.abs(price);
        t.amount = Math.abs(amount) > 0 ? Math.abs(amount) : t.units * t.price;
        t.sip = desc.contains("sip") || desc.contains("systematic");
        t.note = t.sip ? "SIP" : desc.contains("switch") ? "Switch" : "";
        cur.txns.add(t);
    }

    private static void scanSummary(Item it, String rest) {
        Matcher n = Pattern.compile("[\\d,]+\\.\\d+").matcher(rest);
        List<String> v = new ArrayList<>();
        while (n.find()) v.add(n.group());
        if (v.size() >= 3) {
            // typical: units, NAV, cost, market value
            for (String s : v) if (it.units == 0 && s.matches(".*\\.\\d{3}$")) it.units = num(s);
            if (v.size() >= 4) { it.cost = num(v.get(v.size() - 2)); it.marketValue = num(v.get(v.size() - 1)); }
        }
    }

    private static void finish(Item it, double opening) {
        Ledger.sort(it.txns);
        double sum = 0;
        for (Ledger.Txn t : it.txns) if (t.kind == Ledger.BUY) sum += t.units; else if (t.kind == Ledger.SELL) sum -= t.units;
        it.historyOk = !it.txns.isEmpty() && opening < 0.001 && Math.abs(sum - it.units) < 0.01 + it.units * 0.0005;
        if (!it.txns.isEmpty() && !it.historyOk) {
            it.note = opening > 0.001 ? "Statement starts after your first purchase. Imported as current units and cost (no XIRR)."
                    : "Transactions don't add up to the closing balance. Imported as current units and cost.";
        }
        if (it.units == 0 && it.historyOk) it.units = sum;
        if (it.cost == 0 && it.historyOk) it.cost = Ledger.replay(it.txns, Long.MAX_VALUE).cost;
        if (it.units < 0.0005) { it.include = false; it.note = "Fully redeemed"; }
    }

    static String cleanName(String s) {
        String n = s.replaceAll("\\(Advisor[^)]*\\)", " ")
                .replaceAll("(?i)\\b(Registrar|Folio|PAN)\\b.*$", " ")
                .replaceAll("(?i)\\((non-?demat|demat|formerly[^)]*)\\)", " ")
                .replaceAll("(?i)\\s*-\\s*ISIN\\s*:?\\s*$", " ")
                .replaceAll("^\\s*[A-Z0-9]{1,8}\\s*-\\s*(?=[A-Za-z])", "")
                .replaceAll("[\\s-]+$", "")
                .replaceAll("\\s+", " ").trim();
        return n;
    }

    static double num(String s) {
        try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); } catch (Exception e) { return 0; }
    }

    /** Words that identify a scheme (for name matching). */
    static List<String> tokens(String s) {
        List<String> l = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (w.length() < 2 || w.equals("fund") || w.equals("plan") || w.equals("option") || w.equals("the")
                    || w.equals("scheme") || w.equals("of")) continue;
            l.add(w);
        }
        return l;
    }

    /** Similarity score between a statement name and a scheme name (higher is better). */
    public static double score(String casName, String candidate) {
        List<String> a = tokens(casName), b = tokens(candidate);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String w : a) if (b.contains(w)) common++;
        double s = common * 2.0 / (a.size() + b.size());
        String la = casName.toLowerCase(Locale.ROOT), lb = candidate.toLowerCase(Locale.ROOT);
        boolean directA = la.contains("direct"), directB = lb.contains("direct");
        if (directA != directB) s -= 0.35;
        boolean growthA = la.contains("growth"), growthB = lb.contains("growth");
        boolean idcwA = la.contains("idcw") || la.contains("dividend"), idcwB = lb.contains("idcw") || lb.contains("dividend");
        if (growthA && !growthB) s -= 0.25;
        if (idcwA != idcwB) s -= 0.2;
        if (lb.contains("bonus") && !la.contains("bonus")) s -= 0.2;
        return s;
    }

    /** Short search phrase for mfapi (AMC + first distinctive words). */
    public static String searchQuery(String name) {
        String n = name.replaceAll("(?i)\\b(direct|regular)\\s+plan\\b.*$", "").replaceAll("(?i)\\s+-\\s+.*$", "");
        List<String> t = tokens(n);
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < t.size() && i < 4; i++) { if (q.length() > 0) q.append(' '); q.append(t.get(i)); }
        return q.toString();
    }
}
