package com.uliptracker;

import java.util.List;
import java.util.Locale;

/** Matches the app's ULIP fund names ("Equity Optimiser Fund") to SBI Life listings online ("SBI Life - Equity Optimiser Fund"). */
public final class UlipLink {

    private UlipLink() { }

    /** "SBI Life - Equity Optimiser Fund" → "equityoptimiser". */
    public static String norm(String s) {
        String n = s.toLowerCase(Locale.ROOT).replace("sbi life", " ");
        n = n.replaceAll("\\bfund\\b", " ").replaceAll("[^a-z0-9]+", "");
        return n;
    }

    /** The listing whose name matches exactly once "SBI Life", "fund" and punctuation are ignored; null if none. */
    public static String[] exact(String fund, List<String[]> found) {
        String want = norm(fund);
        for (String[] f : found) if (norm(f[1]).equals(want)) return f;
        return null;
    }

    /** True when the online NAV is close to the last pasted one (within 3 %), a sign the match is right. */
    public static boolean close(Double mine, Double online) {
        if (mine == null || online == null || mine <= 0) return false;
        return Math.abs(online / mine - 1) <= 0.03;
    }
}
