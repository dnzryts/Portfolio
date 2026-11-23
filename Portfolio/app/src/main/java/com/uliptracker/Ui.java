package com.uliptracker;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.Locale;

/** Colours, formatting and small view builders. */
public final class Ui {
    // Theme colours (set by applyTheme before any view is built)
    public static int BG, CARD, CARD2, TEXT, SUB, ACCENT, ON_ACCENT, GREEN, RED;
    public static int NAV, NAV_PILL, OUTLINE, DIVIDER, GRID, ZERO, TIP, EMPTY, HINT, HERO1, HERO2, HERO_SUB;
    public static int[] TYPE_COLORS, PALETTE;
    public static boolean LIGHT;
    /** Privacy mode: hide money amounts and quantities. */
    public static boolean PRIVATE;
    public static final String MASK = "₹ ••••••";

    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    public static int themePref(Context c) { return Alerts.prefs(c).getInt("theme", THEME_SYSTEM); }

    /** Picks light or dark from the setting (or the phone's dark mode) and loads the palette. */
    public static boolean applyTheme(Context c) {
        int pref = themePref(c);
        boolean sysDark = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        LIGHT = pref == THEME_LIGHT || (pref == THEME_SYSTEM && !sysDark);
        if (LIGHT) {
            BG = 0xFFF4F6FA; CARD = 0xFFFFFFFF; CARD2 = 0xFFE9EDF4; TEXT = 0xFF14181F; SUB = 0xFF5F6775;
            ACCENT = 0xFF2F6FE0; ON_ACCENT = 0xFFFFFFFF; GREEN = 0xFF15965A; RED = 0xFFD63B3B;
            NAV = 0xFFFFFFFF; NAV_PILL = 0x262F6FE0; OUTLINE = 0x1F14181F; DIVIDER = 0x1414181F;
            GRID = 0x1414181F; ZERO = 0x4014181F; TIP = 0xF214181F; EMPTY = 0x1A14181F; HINT = 0xFF9AA1AC;
            HERO1 = 0xFFDCE7FF; HERO2 = 0xFFFFFFFF; HERO_SUB = 0xFF4A5568;
            TYPE_COLORS = new int[]{0xFF7C5CE0, 0xFF2F6FE0, 0xFFD98E04, 0xFF0E9AA7, 0xFF15965A, 0xFFD63B3B};
            PALETTE = new int[]{0xFF2F6FE0, 0xFF7C5CE0, 0xFFD98E04, 0xFF15965A, 0xFFE0517A, 0xFF0E9AA7, 0xFFE07A2F, 0xFF6B7280};
        } else {
            BG = 0xFF0E1116; CARD = 0xFF181C23; CARD2 = 0xFF20252E; TEXT = 0xFFE8EAED; SUB = 0xFF9AA0A6;
            ACCENT = 0xFF7FB2FF; ON_ACCENT = 0xFF0E1116; GREEN = 0xFF4CD787; RED = 0xFFFF6B6B;
            NAV = 0xFF151920; NAV_PILL = 0x337FB2FF; OUTLINE = 0x22FFFFFF; DIVIDER = 0x14FFFFFF;
            GRID = 0x1AFFFFFF; ZERO = 0x44FFFFFF; TIP = 0xEE2A303B; EMPTY = 0x22FFFFFF; HINT = 0xFF5F6670;
            HERO1 = 0xFF23304A; HERO2 = 0xFF1A1F2B; HERO_SUB = 0xFFB8C4DA;
            TYPE_COLORS = new int[]{0xFFB39DFF, 0xFF7FB2FF, 0xFFFFC56B, 0xFF5ED4E0, 0xFF4CD787, 0xFFFF6B6B};
            PALETTE = new int[]{0xFF7FB2FF, 0xFFB39DFF, 0xFFFFC56B, 0xFF4CD787, 0xFFFF8FA3, 0xFF5ED4E0, 0xFFF4A261, 0xFF9AA0A6};
        }
        return LIGHT;
    }

    private static final Locale IN = new Locale("en", "IN");

    private Ui() { }

    public static String rupees(double v) {
        if (PRIVATE) return MASK;
        return rupeesRaw(v);
    }

    public static String rupeesRaw(double v) {
        NumberFormat f = NumberFormat.getCurrencyInstance(IN);
        f.setMaximumFractionDigits(v >= 100000 || v <= -100000 ? 0 : 2);
        return f.format(v).replace("Rs.", "₹").replace("INR", "₹");
    }

    public static String nav(double v) { return String.format(Locale.US, "%.4f", v); }

    public static String units(double v) {
        if (PRIVATE) return "•••"; return String.format(Locale.US, "%.3f", v); }

    public static String pct(Double v) {
        if (v == null) return "—";
        return String.format(Locale.US, "%s%.2f%%", v >= 0 ? "+" : "", v);
    }

    public static int colorFor(Double v) {
        if (v == null) return SUB;
        return v >= 0 ? GREEN : RED;
    }

    public static float dp(Context c) { return c.getResources().getDisplayMetrics().density; }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static LinearLayout card(Context c) {
        float d = dp(c);
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(18 * d);
        l.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, (int) (12 * d));
        l.setLayoutParams(lp);
        return l;
    }

    public static TextView button(Context c, String label, boolean primary) {
        float d = dp(c);
        TextView b = text(c, label, 14, primary ? ON_ACCENT : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) (16 * d), (int) (11 * d), (int) (16 * d), (int) (11 * d));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : CARD2);
        g.setCornerRadius(22 * d);
        b.setBackground(g);
        b.setClickable(true);
        return b;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    /** Card with a diagonal gradient, for hero sections. */
    public static LinearLayout heroCard(Context c, int from, int to) {
        LinearLayout l = card(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(24 * dp(c));
        l.setBackground(g);
        return l;
    }

    public static TextView pill(Context c, String s, int color) {
        float d = dp(c);
        TextView t = text(c, s, 12, color, true);
        t.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
        GradientDrawable g = new GradientDrawable();
        g.setColor((color & 0x00FFFFFF) | 0x26000000);
        g.setCornerRadius(12 * d);
        t.setBackground(g);
        return t;
    }

    /** Fade + slide up, staggered by index. */
    public static void enter(View v, int index) {
        if (index > 7) return;   // only what's on screen animates; the rest is ready for scrolling
        v.setAlpha(0f);
        v.setTranslationY(18 * dp(v.getContext()));
        v.animate().alpha(1f).translationY(0).setStartDelay(45L * index).setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).withLayer().start();
    }

    /** Animate a number counting up into a TextView. */
    public static void countUp(final TextView t, final double to, final boolean rupees) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1100);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator v) {
                if (PRIVATE) { t.setText(MASK); return; }
                double x = to * (Float) v.getAnimatedValue();
                t.setText(rupees ? rupees(x) : String.format(Locale.US, "%.2f", x));
            }
        });
        a.start();
    }

    /** Thin bar that grows to a fraction of its width. */
    public static View bar(Context c, final float fraction, int color) {
        final float d = dp(c);
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(EMPTY); bg.setCornerRadius(3 * d);
        f.setBackground(bg);
        final View fill = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(3 * d);
        fill.setBackground(g);
        fill.setPivotX(0);
        fill.setScaleX(0f);
        f.addView(fill, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        fill.animate().scaleX(Math.max(0.01f, Math.min(1f, fraction))).setDuration(700).setStartDelay(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).withLayer().start();
        f.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (5 * d)));
        return f;
    }

    public static View space(Context c, int dpH) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, (int) (dpH * dp(c))));
        return v;
    }
}
