package com.uliptracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a fund's NAV and its date from an ordinary web page (e.g. a Moneycontrol fund page).
 * Pages differ, so it looks for numbers near the word "NAV" (and NAV fields in embedded data). When there are
 * several, it takes the one closest to the last known NAV, which also guards against picking the wrong figure.
 */
public final class PageNav {

    private PageNav() { }

    public static final class Found {
        public double nav;
        public long day = -1;        // epoch day of the NAV, -1 if the page shows no date
        public String context = "";  // a few words around it, to show the person
    }

    private static final Pattern NUM = Pattern.compile("(?<![\\d.,])(\\d{1,3}(?:,\\d{2,3})+|\\d{1,6})\\.(\\d{2,6})(?![\\d])");
    private static final Pattern NAV_WORD = Pattern.compile("\\bNAV\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_NAV = Pattern.compile("\"(?:nav|NAV|latestNav|latest_nav|navValue|nav_value|currentNav|current_nav)\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)");

    /** @param anchor the last known NAV (or null); used to pick among several numbers and to reject wild ones */
    public static Found extract(String html, Double anchor) {
        String text = text(html);
        List<double[]> cands = new ArrayList<>();      // {value, position in text, distance from "NAV"}
        Matcher w = NAV_WORD.matcher(text);
        while (w.find()) {
            int from = Math.max(0, w.start() - 40), to = Math.min(text.length(), w.end() + 160);
            Matcher n = NUM.matcher(text.substring(from, to));
            while (n.find()) {
                double v = parse(n.group());
                if (v < 0.5 || v > 100000) continue;
                int pos = from + n.start();
                cands.add(new double[]{v, pos, Math.abs(pos - w.end())});
            }
        }
        Matcher j = JSON_NAV.matcher(html);
        while (j.find()) {
            double v = parse(j.group(1));
            if (v >= 0.5 && v <= 100000) cands.add(new double[]{v, -1, 500});
        }
        if (cands.isEmpty()) return null;
        double[] best = null;
        if (anchor != null && anchor > 0) {
            for (double[] c : cands) {
                double off = Math.abs(c[0] / anchor - 1);
                if (off <= 0.15 && (best == null || off < Math.abs(best[0] / anchor - 1))) best = c;
            }
            if (best == null) return null;             // nothing near the last NAV: likely the wrong page
        } else {
            for (double[] c : cands) if (c[1] >= 0 && (best == null || c[2] < best[2])) best = c;
            if (best == null) best = cands.get(0);
        }
        Found f = new Found();
        f.nav = best[0];
        int at = (int) best[1];
        if (at >= 0) {
            f.day = dateNear(text, Math.max(0, at - 200), Math.min(text.length(), at + 200));
            f.context = text.substring(Math.max(0, at - 60), Math.min(text.length(), at + 40)).trim();
        }
        if (f.day < 0) {
            Matcher jd = Pattern.compile("\"(?:navDate|nav_date|navdate|NavDate|asOnDate|as_on_date|date)\"\\s*:\\s*\"([^\"]{6,30})\"").matcher(html);
            if (jd.find()) f.day = dateNear(jd.group(1), 0, jd.group(1).length());
        }
        if (f.day < 0) f.day = dateAfter(text, "as on");
        if (f.day < 0) f.day = dateAfter(text, "as of");
        return f;
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return -1; }
    }

    /** Visible text: tags removed, common entities decoded, spaces collapsed. */
    static String text(String html) {
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&#8377;", "₹").replace("&#x20b9;", "₹").replace("&amp;", "&")
                .replace("&#39;", "'").replace("&quot;", "\"");
        return t.replaceAll("\\s+", " ");
    }

    private static final String MON = "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?";
    private static final Pattern[] DATES = {
            Pattern.compile("\\b(\\d{1,2})[\\s-]+" + MON + ",?[\\s-]+(\\d{4}|\\d{2})\\b", Pattern.CASE_INSENSITIVE),  // 25 Nov, 2026
            Pattern.compile("\\b" + MON + "\\s+(\\d{1,2}),?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE),               // Nov 25, 2026
            Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b"),                                                     // 2026-11-25
            Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})\\b"),                                            // 25-11-2026, 25/11/2026
    };

    static long dateNear(String text, int from, int to) {
        String s = text.substring(from, to);
        long best = -1;
        int bestDist = Integer.MAX_VALUE, mid = (to - from) / 2;
        for (int k = 0; k < DATES.length; k++) {
            Matcher m = DATES[k].matcher(s);
            while (m.find()) {
                long d = toDay(k, m);
                if (d < 0) continue;
                int dist = Math.abs(m.start() - mid);
                if (dist < bestDist) { bestDist = dist; best = d; }
            }
        }
        return best;
    }

    private static long dateAfter(String text, String word) {
        int i = text.toLowerCase(Locale.ROOT).indexOf(word);
        return i < 0 ? -1 : dateNear(text, i, Math.min(text.length(), i + 60));
    }

    private static long toDay(int kind, Matcher m) {
        try {
            LocalDate d;
            if (kind == 0) {
                int y = Integer.parseInt(m.group(3));
                if (y < 100) y += 2000;
                d = LocalDate.of(y, month(m.group(2)), Integer.parseInt(m.group(1)));
            } else if (kind == 1) {
                d = LocalDate.of(Integer.parseInt(m.group(3)), month(m.group(1)), Integer.parseInt(m.group(2)));
            } else if (kind == 2) {
                d = LocalDate.parse(m.group(), DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                d = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
            long day = d.toEpochDay(), today = LocalDate.now().toEpochDay();
            if (day > today + 1 || day < today - 90) return -1;      // not a recent NAV date
            return day;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int month(String s) {
        String[] m = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        String k = s.toLowerCase(Locale.ROOT).substring(0, 3);
        for (int i = 0; i < 12; i++) if (m[i].equals(k)) return i + 1;
        throw new IllegalArgumentException(s);
    }
}
