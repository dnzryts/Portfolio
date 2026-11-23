package com.uliptracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Saves the last crash so it can be shown and copied on the next launch. */
public final class Crash {
    private Crash() { }

    private static boolean installed;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("crash", Context.MODE_PRIVATE); }

    public static void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try { write(app, e, true); } catch (Throwable ignored) { }
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    /** Record a handled error (does not show a dialog). */
    public static void save(Context c, Throwable e) {
        try { write(c, e, false); } catch (Throwable ignored) { }
    }

    private static void write(Context c, Throwable e, boolean fatal) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String text = "Portfolio " + version(c) + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n" + (fatal ? "CRASH" : "Handled error") + " at "
                + new java.util.Date() + "\n\n" + sw;
        if (text.length() > 12000) text = text.substring(0, 12000);
        SharedPreferences.Editor ed = p(c).edit().putString("last", text);
        if (fatal) ed.putBoolean("pending", true);
        ed.commit();
    }

    private static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** Text of a crash that hasn't been shown yet (then marks it shown). */
    public static String take(Context c) {
        if (!p(c).getBoolean("pending", false)) return null;
        p(c).edit().putBoolean("pending", false).apply();
        return p(c).getString("last", null);
    }

    public static String last(Context c) { return p(c).getString("last", null); }
}
