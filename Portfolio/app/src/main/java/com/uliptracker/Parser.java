package com.uliptracker;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads pasted text (a NAV table copied from a website, app, PDF or SMS) and finds
 * a NAV for each known fund name, plus the NAV date if one is present.
 */
public class Parser {

    public static class Result {
        public LocalDate date;
        public final LinkedHashMap<String, Double> navs = new LinkedHashMap<>();
    }

    // A decimal like 210.0123 or 1,234.56 that is not a percentage
    private static final Pattern NUM = Pattern.compile(
            "(?<![\\d.,])(\\d{1,3}(?:,\\d{2,3})+\\.\\d{2,4}|\\d{1,5}\\.\\d{2,4})(?![\\d.])(?!\\s*%)");

    private static final String MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec";
    private static final Pattern D_NUM = Pattern.compile("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})\\b");
    private static final Pattern D_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern D_DMY = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?[-\\s]+(" + MONTHS + ")[a-z]*[-,\\s]+(\\d{2,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern D_MDY = Pattern.compile(
            "\\b(" + MONTHS + ")[a-z]*\\s+(\\d{1,2}),?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    static String norm(String s) {
        String n = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return " " + n + " ";
    }

    /** Longest fund name that appears as whole words in the line, or null. */
    static String matchFund(String line, List<String> names) {
        String nl = norm(line);
        String best = null;
        for (String name : names) {
            String nn = norm(name);
            if (nl.contains(nn) && (best == null || nn.length() > norm(best).length())) best = name;
        }
        return best;
    }

    static Double firstNav(String line) {
        // Ignore anything that looks like an SFIN code (ULIF...) so its digits aren't read
        String clean = line.replaceAll("(?i)ULIF[0-9A-Z\\-/]+", " ");
        Matcher m = NUM.matcher(clean);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            if (v > 0.5) return v;
        }
        return null;
    }

    public static LocalDate findDate(String text) {
        LocalDate best = null;
        int bestPos = Integer.MAX_VALUE;
        Matcher m;
        try {
            m = D_ISO.matcher(text);
            if (m.find() && m.start() < bestPos) {
                best = LocalDate.of(i(m.group(1)), i(m.group(2)), i(m.group(3))); bestPos = m.start();
            }
        } catch (Exception ignored) { }
        try {
            m = D_NUM.matcher(text);
            if (m.find() && m.start() < bestPos) {
                best = LocalDate.of(i(m.group(3)), i(m.group(2)), i(m.group(1))); bestPos = m.start();
            }
        } catch (Exception ignored) { }
        try {
            m = D_DMY.matcher(text);
            if (m.find() && m.start() < bestPos) {
                best = LocalDate.of(year(m.group(3)), month(m.group(2)), i(m.group(1))); bestPos = m.start();
            }
        } catch (Exception ignored) { }
        try {
            m = D_MDY.matcher(text);
            if (m.find() && m.start() < bestPos) {
                best = LocalDate.of(i(m.group(3)), month(m.group(1)), i(m.group(2))); bestPos = m.start();
            }
        } catch (Exception ignored) { }
        if (best != null && best.isAfter(LocalDate.now().plusDays(1))) return null;
        return best;
    }

    public static Result parse(String text, List<String> names) {
        Result r = new Result();
        r.date = findDate(text);
        String[] lines = text.split("\\r?\\n|\\|\\s*(?=SBI)");
        for (int k = 0; k < lines.length; k++) {
            String fund = matchFund(lines[k], names);
            if (fund == null || r.navs.containsKey(fund)) continue;
            // NAV is normally after the fund name on the same line
            String line = lines[k];
            int at = norm(line).indexOf(norm(fund));
            Double nav = firstNav(at > 0 ? tailAfterName(line, fund) : line);
            // ...or on one of the next few lines (tables copied as one cell per line)
            for (int j = k + 1; nav == null && j < lines.length && j <= k + 4; j++) {
                if (matchFund(lines[j], names) != null) break;
                if (lines[j].contains("%")) continue;
                nav = firstNav(lines[j]);
            }
            if (nav != null) r.navs.put(fund, nav);
        }
        return r;
    }

    private static String tailAfterName(String line, String fund) {
        String[] words = fund.split("\\s+");
        String last = words[words.length - 1].toLowerCase(Locale.ROOT);
        int idx = line.toLowerCase(Locale.ROOT).indexOf(last);
        return idx >= 0 ? line.substring(idx + last.length()) : line;
    }

    private static int i(String s) { return Integer.parseInt(s); }

    private static int year(String s) { int y = i(s); return y < 100 ? 2000 + y : y; }

    private static int month(String s) {
        return MONTHS.indexOf(s.substring(0, 3).toLowerCase(Locale.ROOT)) / 4 + 1;
    }
}
