package com.uliptracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Settings, price-move alerts and the background check schedule. */
public final class Alerts {
    private Alerts() { }

    public static final String CHANNEL = "price_alerts";
    private static final int JOB_ID = 4242;

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    public static boolean enabled(Context c) { return prefs(c).getBoolean("alerts", false); }
    public static float threshold(Context c) { return prefs(c).getFloat("threshold", 5f); }
    public static boolean holdings(Context c) { return prefs(c).getBoolean("a_hold", true); }
    public static boolean indexes(Context c) { return prefs(c).getBoolean("a_idx", true); }
    public static boolean portfolio(Context c) { return prefs(c).getBoolean("a_port", true); }
    public static int intervalMin(Context c) { return prefs(c).getInt("interval", 30); }
    public static boolean dailySummary(Context c) { return prefs(c).getBoolean("daily", false); }

    /** Background checks are needed for move alerts, the daily summary, or any price target. */
    static boolean needJob(Context c) {
        if (enabled(c) || dailySummary(c)) return true;
        return prefs(c).getBoolean("hasTargets", false) || prefs(c).getBoolean("hasPolicies", false);
    }

    static void notePolicies(Context c, Store store) {
        boolean any = false;
        for (Store.Policy p : store.policies) if (p.nextDue > 0) any = true;
        prefs(c).edit().putBoolean("hasPolicies", any).apply();
    }

    static void noteTargets(Context c, Store store) {
        boolean any = false;
        for (Store.Asset a : store.everything()) if (a.targetAbove > 0 || a.targetBelow > 0) any = true;
        prefs(c).edit().putBoolean("hasTargets", any).apply();
    }

    public static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Price alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Big moves in your holdings, indexes or total portfolio");
            nm.createNotificationChannel(ch);
        }
    }

    /** Start or stop the periodic background check. Returns null, or why it couldn't be scheduled. */
    public static String schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (!needJob(c)) { js.cancel(JOB_ID); return null; }
            long period = Math.max(15, intervalMin(c)) * 60_000L;
            JobInfo existing = js.getPendingJob(JOB_ID);
            if (existing != null && existing.getIntervalMillis() == period) return null;   // already set
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, AlertJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(period);
            try {
                js.schedule(b.setPersisted(true).build());
            } catch (Throwable t) {          // some phones refuse persisted jobs: fall back
                js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, AlertJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(period).build());
            }
            return null;
        } catch (Throwable t) {
            Crash.save(c, t);
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }

    /** Check moves and post notifications. Each item alerts at most once per day per direction. */
    public static int check(Context c, Store store) {
        Ui.setCurrency(store.viewCcy());
        if (Ui.TYPE_COLORS == null) Ui.applyTheme(c);
        boolean moves = enabled(c);
        float th = threshold(c);
        SharedPreferences p = prefs(c);
        Set<String> sent = new HashSet<>(p.getStringSet("sent", new HashSet<String>()));
        long today = LocalDate.now().toEpochDay();
        // forget old entries
        Set<String> keep = new HashSet<>();
        for (String s : sent) {
            try { if (Long.parseLong(s.substring(s.lastIndexOf('|') + 1)) >= today - 3) keep.add(s); } catch (Exception ignored) { }
        }
        sent = keep;
        List<String[]> out = new ArrayList<>(); // [id, title, text]

        // ---- one-shot price targets (holdings, watchlist, indexes) ----
        boolean targetsChanged = false;
        for (Store.Asset a : store.everything()) {
            if (a.hidden || a.latest() == null || (a.targetAbove <= 0 && a.targetBelow <= 0)) continue;
            double px = a.latest().getValue();
            String cur = a.type == Store.INDEX ? String.format(Locale.US, "%,.2f", px)
                    : Ui.symbolOf(!a.currency.isEmpty() ? a.currency : store.ccyOf(a.ownerOrDefault())) + String.format(Locale.US, "%,.2f", px);
            if (a.targetAbove > 0 && px >= a.targetAbove) {
                out.add(new String[]{"t:" + a.key() + ":u:" + a.targetAbove + "|" + today,
                        "▲ " + a.name + " crossed " + fmtTarget(a, a.targetAbove), "Now " + cur + " · price alert reached"});
                a.targetAbove = 0; targetsChanged = true;
            }
            if (a.targetBelow > 0 && px <= a.targetBelow) {
                out.add(new String[]{"t:" + a.key() + ":d:" + a.targetBelow + "|" + today,
                        "▼ " + a.name + " fell below " + fmtTarget(a, a.targetBelow), "Now " + cur + " · price alert reached"});
                a.targetBelow = 0; targetsChanged = true;
            }
        }
        if (targetsChanged) { store.save(); noteTargets(c, store); }

        // ---- premium reminders ----
        boolean polChanged = false;
        for (Store.Policy pol : store.policies) {
            if (pol.nextDue <= 0) continue;
            long left = pol.nextDue - today;
            if (left < 0) { polChanged |= pol.roll(today); continue; }
            String when = left == 0 ? "today" : left == 1 ? "tomorrow" : "in " + left + " days";
            if (left == pol.remindDays || left == 1 || left == 0) {
                String id = "pr:" + pol.name + ":" + pol.nextDue + ":" + left + "|" + today;
                if (!sent.contains(id)) out.add(new String[]{id, "▲ Premium due " + when + " · " + pol.name,
                        (hideAmounts(c) ? "" : Ui.rupeesRaw(pol.premium).replaceAll("\\.\\d+$", "") + " · ")
                                + "due " + java.time.LocalDate.ofEpochDay(pol.nextDue).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))});
            }
        }
        if (polChanged) store.save();

        // ---- daily closing summary (weekdays after 15:45 IST) ----
        if (dailySummary(c)) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
            int dow = now.getDayOfWeek().getValue();
            boolean afterClose = now.getHour() > 15 || (now.getHour() == 15 && now.getMinute() >= 45);
            String id = "s|" + today;
            if (dow <= 5 && afterClose && !sent.contains(id)) {
                List<Store.Asset> all = store.visible(-1);
                double total = Store.value(all), change = Store.lastChange(all), base = total - change;
                Store.Asset best = null, worst = null;
                Double bp = null, wp = null;
                for (Store.Asset a : all) {
                    Double ch = a.lastChangePct();
                    if (!a.held() || ch == null || a.latest().getKey() < today - 1) continue;
                    if (bp == null || ch > bp) { bp = ch; best = a; }
                    if (wp == null || ch < wp) { wp = ch; worst = a; }
                }
                if (total > 0) {
                    String head = (change >= 0 ? "▲" : "▼") + " Today " + Ui.pct(base > 0 ? change / base * 100 : null)
                            + (hideAmounts(c) ? "" : "  (" + (change >= 0 ? "+" : "") + Ui.rupeesRaw(change).replaceAll("\\.\\d+$", "") + ")");
                    StringBuilder body = new StringBuilder();
                    if (best != null) body.append("Top: ").append(shortName(best)).append(" ").append(Ui.pct(bp));
                    if (worst != null && worst != best) body.append(" · Worst: ").append(shortName(worst)).append(" ").append(Ui.pct(wp));
                    if (!hideAmounts(c)) body.append(body.length() > 0 ? " · " : "").append("Value ")
                            .append(Ui.rupeesRaw(total).replaceAll("\\.\\d+$", ""));
                    out.add(new String[]{id, head, body.toString()});
                }
            }
        }

        if (moves && holdings(c)) {
            Set<String> done = new HashSet<>();
            List<Store.Asset> pool = new ArrayList<>(store.visible(-1));
            pool.addAll(store.watchlist());
            for (Store.Asset a : pool) {
                if ((!a.held() && !a.watch) || a.latest() == null || !done.add(a.key() + a.watch)) continue;
                Double ch = a.lastChangePct();
                if (ch == null || Math.abs(ch) < th) continue;
                long day = a.latest().getKey();
                if (day < today - 3) continue;
                String id = "h:" + a.key() + ":" + (ch > 0 ? "u" : "d") + "|" + day;
                if (sent.contains(id)) continue;
                double val = 0;
                for (Store.Asset s : store.siblings(a)) val += s.value();
                out.add(new String[]{id, arrow(ch) + " " + a.name + " " + Ui.pct(ch),
                        "Now " + String.format(Locale.US, "%.2f", a.latest().getValue())
                                + (a.watch ? " · watchlist" : hideAmounts(c) ? "" : " · your holding " + Ui.rupeesRaw(val))});
            }
        }
        if (moves && indexes(c)) {
            for (Store.Asset a : store.indices) {
                Double ch = a.lastChangePct();
                if (a.hidden || ch == null || Math.abs(ch) < th) continue;
                long day = a.latest().getKey();
                if (day < today - 3) continue;
                String id = "i:" + a.code + ":" + (ch > 0 ? "u" : "d") + "|" + day;
                if (sent.contains(id)) continue;
                out.add(new String[]{id, arrow(ch) + " " + a.name + " " + Ui.pct(ch),
                        "Index at " + String.format(Locale.US, "%,.2f", a.latest().getValue())});
            }
        }
        if (moves && portfolio(c)) {
            List<Store.Asset> all = store.visible(-1);
            double total = Store.value(all), change = Store.lastChange(all);
            double base = total - change;
            if (base > 0) {
                double pct = change / base * 100;
                if (Math.abs(pct) >= th) {
                    String id = "p:" + (pct > 0 ? "u" : "d") + "|" + today;
                    if (!sent.contains(id)) out.add(new String[]{id, arrow(pct) + " Portfolio " + Ui.pct(pct),
                            hideAmounts(c) ? "Your total portfolio moved " + Ui.pct(pct) + " today"
                                    : (change >= 0 ? "+" : "") + Ui.rupeesRaw(change) + " · now " + Ui.rupeesRaw(total)});
                }
            }
        }
        if (out.isEmpty()) { p.edit().putStringSet("sent", sent).apply(); return 0; }

        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        for (String[] n : out) {
            boolean up = n[1].startsWith("▲");
            Notification note = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(statIcon(c))
                    .setContentTitle(n[1])
                    .setContentText(n[2])
                    .setColor(up ? Ui.GREEN : Ui.RED)
                    .setStyle(new Notification.BigTextStyle().bigText(n[2]))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setGroup("moves")
                    .build();
            try { nm.notify(n[0].hashCode(), note); } catch (Throwable t) { Crash.save(c, t); }
            sent.add(n[0]);
        }
        p.edit().putStringSet("sent", sent).apply();
        return out.size();
    }

    /** Monochrome status-bar icon (res/mipmap/ic_stat.png), looked up by name so any build system works. */
    public static int statIcon(Context c) {
        int id = c.getResources().getIdentifier("ic_stat", "mipmap", c.getPackageName());
        return id != 0 ? id : c.getApplicationInfo().icon;
    }

    private static String fmtTarget(Store.Asset a, double v) {
        return (a.type == Store.INDEX ? "" : Ui.symbolOf(a.currency.isEmpty() ? "INR" : a.currency)) + String.format(Locale.US, v >= 1000 ? "%,.0f" : "%,.2f", v);
    }

    private static String shortName(Store.Asset a) {
        String n = a.name.replaceAll("(?i)\\s*-\\s*(direct|regular) plan.*$", "").replaceAll("(?i)^sbi\\s+", "");
        return n.length() > 18 ? n.substring(0, 17) + "…" : n;
    }

    static boolean hideAmounts(Context c) { return prefs(c).getBoolean("privacyNotify", false); }

    private static String arrow(double v) { return v >= 0 ? "▲" : "▼"; }
}
