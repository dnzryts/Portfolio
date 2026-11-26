package com.uliptracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);
    private static final String[] RANGES = {"1M", "3M", "6M", "1Y", "3Y", "5Y"};
    private static final int[] RANGE_DAYS = {30, 91, 182, 365, 1095, 1830};
    private static final String[] MODES = {"Value", "Return %", "Compare", "Monthly"};
    private static final String[] ASSET_MODES = {"Area", "Line", "Monthly"};
    private static final String[] TABS = {"Dashboard", "Portfolio", "Analytics", "Markets", "More"};
    private static final int[] TAB_ICONS = {0, 1, 6, 2, 3};
    private static final String[] PLATFORM_SUGGESTIONS = {"Zerodha", "Groww", "Upstox", "Angel One", "SBI Securities",
            "SBI MF (direct)", "SBI YONO", "Kuvera", "Paytm Money", "ICICI Direct", "HDFC Sky", "Coin", "SBI Life"};

    private Store store;
    private float dp;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // navigation state
    private int tab = 0;                  // bottom bar: 0 dashboard, 1 portfolio, 2 indexes, 3 more
    private int screen = 0;               // 0 tab page, 1 asset/index detail, 2 paste
    private int scopeKind = 0;            // portfolio filter: 0 all, 1 type, 2 platform
    private int scopeType = 0;
    private String scopePlatform = "";
    private int groupBy = 0;              // dashboard: 0 type, 1 platform
    private int listMode = 2;             // portfolio list: 0 by type, 1 by platform, 2 merged
    private int chartMode = 0, range = 3, assetMode = 0, assetRange = 3;
    private Store.Asset current;
    private boolean refreshing;
    private String refreshStatus;
    private ScrollView scroller;
    private SwipeFrame content;
    private PullRefresh pull;
    private boolean programmaticSlide;   // slide started by a tap/back, state already updated
    private boolean sliding;
    private final NavIcon[] navIcons = new NavIcon[5];
    private final TextView[] navLabels = new TextView[5];
    private final View[] navPills = new View[5];

    private final ChartView.Formatter rupeeFmt = new ChartView.Formatter() {
        @Override public String format(double v) { return Ui.rupees(v); }
    };
    private final ChartView.Formatter priceFmt = new ChartView.Formatter() {
        @Override public String format(double v) { return String.format(Locale.US, "%.2f", v); }
    };

    @Override
    protected void onCreate(Bundle b) {
        Crash.install(this);
        boolean light = Ui.applyTheme(this);
        CardStack.TILT = Alerts.prefs(this).getBoolean("tilt", true);
        setTheme(light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar);
        super.onCreate(b);
        dp = Ui.dp(this);
        if (b != null) {   // restore place after a theme change / rotation
            tab = b.getInt("tab", 0); scopeKind = b.getInt("scopeKind", 0); scopeType = b.getInt("scopeType", 0);
            scopePlatform = b.getString("scopePlatform", ""); groupBy = b.getInt("groupBy", 0); listMode = b.getInt("listMode", 2);
            // a theme change or rotation restarts the screen: stay unlocked if it was unlocked moments ago
            // (onResume still asks if more than 30 s passed, e.g. the app was closed in the background)
            if (b.getBoolean("unlocked", false)) stoppedAt = b.getLong("visibleAt", 0);
            pendingScrollY = b.getInt("scrollY", 0);
        }
        // full screen: draw behind the status bar and the gesture/navigation bar
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION | android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        if (android.os.Build.VERSION.SDK_INT >= 28) getWindow().setNavigationBarDividerColor(0x00000000);
        // draw under every system bar, including the button bar at the side in landscape
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams wl = getWindow().getAttributes();
            wl.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wl);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        store = new Store(this);
        store.ownerFilter = ownerPref();
        store.accrueAll();
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        content = new SwipeFrame(this);
        content.setOnMove(new Runnable() { @Override public void run() { redrawStrips(); } });
        content.setListener(new SwipeFrame.Listener() {
            @Override public boolean canSwipe(int dir) {
                if (locked || hiddenForRecents) return false;
                if (screen != 0) return dir == -1;                 // swipe right = back
                return tab + dir >= 0 && tab + dir < TABS.length;
            }
            @Override public View buildNeighbour(int dir) {
                return screen != 0 ? buildOffscreen(tab) : buildOffscreen(tab + dir);
            }
            @Override public void onSwitched(int dir, View newPage) {
                if (programmaticSlide) {
                    programmaticSlide = false;           // tab/screen were already set before the slide
                } else if (screen != 0) {
                    screen = 0;                          // swiped back from a detail page
                } else {
                    tab = Math.max(0, Math.min(TABS.length - 1, tab + dir));
                }
                scroller = (ScrollView) newPage;
                updateNav();
            }
        });
        pull = new PullRefresh(this);
        pull.setContent(content);
        pull.setListener(new PullRefresh.Listener() {
            @Override public boolean canPull(float x, float y) {
                if (locked || hiddenForRecents || refreshing || scroller == null) return false;
                if (tab == TABS.length - 1 && screen == 0) return false;      // More: nothing to refresh
                if (scroller.getScrollY() > 0 || scroller.getParent() == null) return false;
                return !onCardStack(content, x, y);                          // the card stack has its own swipe down
            }
            @Override public void onRefresh() { refresh(null, false); }
        });
        root.addView(pull, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // a soft band behind the status bar so text scrolling under the clock stays readable
        // the strips behind the status bar and the floating menu repaint the page's own background (mood glow included),
        // so the background runs edge to edge while content still tucks away behind them
        statusScrim = new Backdrop(this);
        root.addView(statusScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
        navSolid = new Backdrop(this);
        navFade = new View(this);
        root.addView(navSolid, bandParams(true));
        navFade.setVisibility(View.GONE);   // no fade above the menu
        root.addView(navFade, bandParams(false));
        navBar = buildNavBar();
        root.addView(navBar, navParams());
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                int l = in.getSystemWindowInsetLeft(), t = in.getSystemWindowInsetTop(), r = in.getSystemWindowInsetRight(), bt = in.getSystemWindowInsetBottom();
                // the keyboard shows up as a large bottom inset: hide the floating menu while typing
                boolean kb = bt - in.getStableInsetBottom() > 100 * dp;
                keyboardUp = kb;
                if (!locked && !hiddenForRecents) navBar.setVisibility(kb ? View.GONE : View.VISIBLE);
                if (navSolid != null) { navSolid.setVisibility(kb ? View.GONE : View.VISIBLE); }
                if (android.os.Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                    android.view.DisplayCutout dc = in.getDisplayCutout();
                    l = Math.max(l, dc.getSafeInsetLeft()); r = Math.max(r, dc.getSafeInsetRight()); t = Math.max(t, dc.getSafeInsetTop());
                }
                if (l != insL || t != insT || r != insR || bt != insB) {
                    insL = l; insT = t; insR = r; insB = bt;
                    statusScrim.getLayoutParams().height = insT;
                    statusScrim.requestLayout();
                    navBar.setLayoutParams(navParams());
                    navSolid.setLayoutParams(bandParams(true));
                    navFade.setLayoutParams(bandParams(false));
                    pull.setTopInset(insT);
                    if (scroller != null && scroller.getChildCount() > 0) padPage(scroller.getChildAt(0));
                }
                return in;
            }
        });
        FrameLayout shell = new FrameLayout(this);
        shell.addView(root);
        setContentView(shell);
        if (b == null && !Alerts.prefs(this).getBoolean("name_asked", false)) welcome(shell);
        if (b == null) maybeWhatsNew();
        if (b == null && Alerts.prefs(this).getBoolean("intro", true)) {
            IntroView iv = new IntroView(this);
            String fest = Seasons.greeting(LocalDate.now());
            if (fest != null) iv.setGreeting(Seasons.withName(fest, userName()));
            else if (!userName().isEmpty()) iv.setGreeting("Welcome back, " + userName());
            shell.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iv.play(shell);
        }
        Ui.PRIVATE = Alerts.prefs(this).getBoolean("privacyStart", false) || (b != null && b.getBoolean("private", false));
        if (!Lock.enabled(this) || stoppedAt > 0) render(b == null);    // after a theme change: same place, no entrance animation
        if (b == null) handleIncoming(getIntent());
        final String crash = Crash.take(this);
        if (crash != null) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    dialog().setTitle("The app closed unexpectedly last time")
                            .setMessage("Sorry about that. Copy the error details and send them to whoever builds the app so it can be fixed.")
                            .setPositiveButton("Copy details", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(ClipData.newPlainText("Crash report", crash));
                                    toast("Error details copied");
                                }
                            }).setNegativeButton("Dismiss", null).show();
                }
            }, 600);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab); out.putInt("scopeKind", scopeKind); out.putInt("scopeType", scopeType);
        out.putString("scopePlatform", scopePlatform); out.putInt("groupBy", groupBy); out.putInt("listMode", listMode);
        // onStop (which hides data for the recents screen) runs before this on Android 9+, so don't judge by
        // hiddenForRecents: record whether the user had unlocked, and when the app was last on screen
        out.putBoolean("unlocked", !locked);
        out.putLong("visibleAt", hiddenForRecents && stoppedAt > 0 ? stoppedAt : System.currentTimeMillis());
        out.putBoolean("private", Ui.PRIVATE);
        out.putInt("scrollY", scroller != null ? scroller.getScrollY() : 0);
    }

    private boolean locked;          // lock screen showing, fingerprint needed
    private boolean hiddenForRecents; // content cleared while in background
    private boolean authInProgress;
    private long stoppedAt = 0;      // 0 = fresh launch

    @Override
    protected void onResume() {
        super.onResume();
        startWatchSync(false);
        if (authInProgress) return;                    // returning from the system PIN screen
        if (Alerts.schedule(this) != null && Alerts.enabled(this)) Alerts.prefs(this).edit().putBoolean("alerts", false).apply();
        if (Lock.enabled(this) && (locked || stoppedAt == 0 || System.currentTimeMillis() - stoppedAt > 30_000L)) {
            if (!refreshing && stoppedAt > 0) reloadStore();
            showLock();
            return;
        }
        if (!refreshing && store.applyAllSips() > 0) toast("SIP instalments added");
        if (stoppedAt > 0) {
            if (!refreshing) reloadStore();  // pick up background updates
            hiddenForRecents = false;
            navBar.setVisibility(View.VISIBLE);
            render(false);
        }
        if (autoDue()) refresh(null, true);
        startAutoTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        watchServer.stop();
        ui.removeCallbacks(autoTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (authInProgress) return;
        try { runBackup(false); } catch (Throwable ignored) { }
        stoppedAt = System.currentTimeMillis();
        if (Lock.enabled(this) && !locked) {           // hide data in recent apps
            content.removeAllViews();
            hiddenForRecents = true;
        }
    }

    private View navBar;

    private void showLock() {
        locked = true;
        navBar.setVisibility(View.GONE);
        content.removeAllViews();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(Ui.BG);
        TextView icon = Ui.text(this, "🔒", 54, Ui.TEXT, false);
        icon.setGravity(Gravity.CENTER);
        l.addView(icon);
        l.addView(Ui.space(this, 12));
        TextView t = Ui.text(this, "Portfolio is locked", 20, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        l.addView(Ui.space(this, 24));
        TextView b = Ui.button(this, "Unlock", true);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { promptUnlock(); } });
        l.addView(b, new LinearLayout.LayoutParams((int) (180 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.enter(icon, 0);
        promptUnlock();
    }

    private void promptUnlock() {
        authInProgress = true;
        Lock.authenticate(this, "Unlock Portfolio", new Lock.Result() {
            @Override public void ok() {
                authInProgress = false;
                locked = false;
                hiddenForRecents = false;
                if (pendingIncoming != null) { final android.net.Uri u = pendingIncoming; pendingIncoming = null; ui.post(new Runnable() { @Override public void run() { importUri(u); } }); }
                navBar.setVisibility(keyboardUp ? View.GONE : View.VISIBLE);
                render(true);
                if (autoDue()) refresh(null, true);
                startAutoTimer();
            }
            @Override public void fail(String why) { authInProgress = false; if (why != null) toast(why); }
        });
    }

    @Override
    public void onBackPressed() {
        if (screen != 0 && !locked) { slideBack(); }
        else if (screen != 0) { screen = 0; render(true); }
        else if (tab == 1 && scopeKind != 0) { scopeKind = 0; render(true); }
        else if (tab != 0) selectTab(0);
        else super.onBackPressed();
    }

    private void render(final boolean animate) {
        if (content != null && content.isBusy()) {
            ui.postDelayed(new Runnable() { @Override public void run() { render(animate); } }, 120);
            return;
        }
        updateNav();
        RefreshRing.lastAt = store.refreshedAt;
        RefreshRing.intervalMs = Alerts.autoMin(this) * 60_000L;
        // amounts are shown in the currency of the profile on screen (no conversion)
        Ui.setCurrency(screen == 1 && current != null ? store.ccyOf(current.ownerOrDefault()) : store.viewCcy());
        if (screen == 1 && current != null) { if (current.manual()) showManual(current, animate); else showAsset(current, animate); }
        else if (screen == 2) showPaste(null);
        else if (screen == 3) showImport(animate);
        else if (screen == 4) showPolicies(animate);
        else if (screen == 5) showInsights(animate);
        else if (screen == 6) showTax(animate);
        else if (screen == 7) showChangelog(animate);
        else if (tab == 0) showDashboard(animate);
        else if (tab == 1) showPortfolio(animate);
        else if (tab == 2) showAnalytics(animate);
        else if (tab == 3) showIndexes(animate);
        else showMore(animate);
    }

    // ================================================================== bottom bar
    private boolean land, keyboardUp;
    /** A strip that draws the current page's background at its own screen position, so it blends in seamlessly. */
    private class Backdrop extends View {
        private final int[] me = new int[2], page = new int[2];
        Backdrop(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(Ui.BG);
            if (content == null) return;
            getLocationInWindow(me);
            boolean animated = false;
            // paint every page that's on screen (two of them mid-swipe), each clipped to where that page is
            for (int i = 0; i < content.getChildCount(); i++) {
                View p = content.getChildAt(i);
                android.graphics.drawable.Drawable d = p.getBackground();
                if (d == null || p.getWidth() == 0 || p.getVisibility() != View.VISIBLE) continue;
                p.getLocationInWindow(page);
                c.save();
                c.translate(page[0] - me[0], page[1] - me[1]);
                c.clipRect(0, 0, p.getWidth(), p.getHeight());
                d.draw(c);
                c.restore();
                if (d instanceof Fx.Mood) animated = true;
            }
            if (animated) postInvalidateDelayed(50);   // keep in step with the drifting glow
        }
    }

    /** Redraw the background strips (page changed, or pages are sliding). */
    private void redrawStrips() {
        if (statusScrim != null) { statusScrim.invalidate(); navSolid.invalidate(); }
    }

    private int insL, insT, insR, insB;     // system bar / cutout insets
    private View statusScrim, navSolid, navFade;

    /** The solid band (or its fade) behind the floating menu: along the bottom in portrait, the left side in landscape. */
    private FrameLayout.LayoutParams bandParams(boolean solid) {
        int fade = (int) (22 * dp);
        if (land) {
            int w = insL + (int) ((12 + RAIL_W) * dp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(solid ? w : fade, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
            if (!solid) lp.leftMargin = w;
            return lp;
        }
        int h = (keyboardUp ? 0 : insB) + (int) ((10 + NAV_H) * dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, solid ? h : fade, Gravity.BOTTOM);
        if (!solid) lp.bottomMargin = h;
        return lp;
    }

    private static final int NAV_H = 66, RAIL_W = 82;   // dp: the menu card's height (portrait) / width (landscape)

    /** Page padding: below the status bar, clear of the floating menu card and the gesture bar. */
    private void padPage(View col) {
        int side = (int) (16 * dp);
        if (land) col.setPadding(insL + (int) ((12 + RAIL_W + 14) * dp), insT + (int) (14 * dp), insR + side, insB + (int) (28 * dp));
        else col.setPadding(insL + side, insT + (int) (14 * dp), insR + side, insB + (int) ((NAV_H + 12 + 24) * dp));
    }

    private FrameLayout.LayoutParams navParams() {
        int m = (int) (12 * dp);
        if (land) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (RAIL_W * dp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            lp.setMargins(insL + m, insT + m, 0, insB + m);
            return lp;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (NAV_H * dp), Gravity.BOTTOM);
        lp.setMargins(insL + (int) (14 * dp), 0, insR + (int) (14 * dp), (keyboardUp ? 0 : insB) + (int) (10 * dp));
        return lp;
    }

    private View buildNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        GradientDrawable card = new GradientDrawable();
        card.setColor(Ui.NAV);
        card.setCornerRadius(28 * dp);
        if (!Ui.LIGHT) card.setStroke(Math.max(1, (int) dp), 0x14FFFFFF);
        bar.setBackground(card);
        bar.setElevation(12 * dp);
        bar.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        bar.setClipToOutline(true);
        if (land) bar.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        else bar.setPadding((int) (4 * dp), 0, (int) (4 * dp), 0);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            FrameLayout iconBox = new FrameLayout(this);
            View pill = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.NAV_PILL);
            g.setCornerRadius(15 * dp);
            pill.setBackground(g);
            iconBox.addView(pill, new FrameLayout.LayoutParams((int) (54 * dp), (int) (30 * dp), Gravity.CENTER));
            NavIcon ic = new NavIcon(this, TAB_ICONS[i]);
            iconBox.addView(ic, new FrameLayout.LayoutParams((int) (22 * dp), (int) (22 * dp), Gravity.CENTER));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams((int) (58 * dp), (int) (30 * dp));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(iconBox, ilp);
            TextView label = Ui.text(this, TABS[i], land ? 10 : 10.5f, Ui.SUB, true);
            label.setPadding(0, (int) (3 * dp), 0, 0);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            item.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { selectTab(idx); } });
            navIcons[i] = ic; navLabels[i] = label; navPills[i] = pill;
            if (land) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (58 * dp));
                bar.addView(item, lp);
            } else bar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return bar;
    }

    private void selectTab(int i) {
        if (i == tab && screen == 0) {   // re-tap: scroll to top
            if (scroller != null) scroller.smoothScrollTo(0, 0);
            return;
        }
        if (screen == 0 && !locked) { slideTo(i, i > tab ? 1 : -1); return; }
        if (screen != 0 && !locked && !content.isBusy()) {   // from a detail page: slide to that tab
            int from = tab;
            tab = i;
            View page = buildOffscreen(i);
            screen = 0;
            updateNav();
            programmaticSlide = true;
            content.slideTo(i >= from ? 1 : -1, page);
            scroller = (ScrollView) page;
            return;
        }
        tab = i;
        screen = 0;
        render(true);
    }

    /** Slide to a tab with the same motion as a swipe. */
    private void slideTo(int newTab, int dir) {
        if (content.isBusy() || newTab < 0 || newTab >= TABS.length) return;
        View page = buildOffscreen(newTab);
        final int target = newTab;
        tab = target; screen = 0;
        updateNav();
        programmaticSlide = true;
        content.slideTo(dir, page);
        scroller = (ScrollView) page;
    }

    private void slideBack() {
        if (content.isBusy()) return;
        View page = buildOffscreen(tab);
        screen = 0;
        updateNav();
        programmaticSlide = true;
        content.slideTo(-1, page);
        scroller = (ScrollView) page;
    }

    // Off-screen page building (for swipes): page() hands the new ScrollView back instead of attaching it.
    private boolean offscreen;
    private ScrollView built;

    private View buildOffscreen(int forTab) {
        int savedTab = tab, savedScreen = screen;
        ScrollView savedScroller = scroller;
        offscreen = true;
        tab = forTab; screen = 0;
        Ui.setCurrency(store.viewCcy());
        try {
            if (tab == 0) showDashboard(false);
            else if (tab == 1) showPortfolio(false);
            else if (tab == 2) showAnalytics(false);
            else if (tab == 3) showIndexes(false);
            else showMore(false);
        } finally {
            offscreen = false;
            tab = savedTab; screen = savedScreen;
            scroller = savedScroller;
        }
        return built;
    }

    private void updateNav() {
        redrawStrips();
        for (int i = 0; i < TABS.length; i++) {
            boolean on = i == tab;
            navIcons[i].setState(on ? Ui.ACCENT : Ui.SUB, on);
            navLabels[i].setTextColor(on ? Ui.TEXT : Ui.SUB);
            View p = navPills[i];
            if (on && p.getScaleX() < 1f) {
                p.setScaleX(0.3f); p.setAlpha(0f);
                p.animate().scaleX(1f).alpha(1f).setDuration(300).setInterpolator(new OvershootInterpolator(1.6f)).start();
            } else if (!on) { p.animate().cancel(); p.setScaleX(0f); p.setAlpha(0f); }
        }
    }

    // ================================================================== helpers
    private int pendingScrollY;     // scroll position to restore after the screen restarts (theme change)

    private LinearLayout page(boolean keepScroll) {
        int y0 = !offscreen && keepScroll && scroller != null ? scroller.getScrollY() : 0;
        if (!offscreen && pendingScrollY > 0) { y0 = pendingScrollY; pendingScrollY = 0; }
        final int y = y0;
        scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BG);
        redrawStrips();
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);   // no stretch effect at the edges
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        padPage(col);
        scroller.addView(col);
        if (offscreen) { built = scroller; return col; }
        content.removeAllViews();
        content.addView(scroller);
        if (y > 0) {
            final ScrollView sv = scroller;
            sv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    sv.getViewTreeObserver().removeOnPreDrawListener(this);
                    sv.scrollTo(0, y);      // after layout, before the first draw
                    return true;
                }
            });
        }
        return col;
    }

    private void add(LinearLayout col, View v, int idx, boolean animate) {
        col.addView(v);
        if (animate) Ui.enter(v, idx);
    }

    private int dialogTheme() {
        return Ui.LIGHT ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert;
    }

    private AlertDialog.Builder dialog() { return new AppDialog(this); }

    private EditText numberField(String hint, String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint(hint);
        if (value != null) e.setText(value);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private View padded(View v) {
        LinearLayout l = new LinearLayout(this);
        l.setPadding((int) (22 * dp), (int) (8 * dp), (int) (22 * dp), 0);
        l.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private Double num(String s) {
        try { return Double.parseDouble(s.trim().replace(",", "").replace("₹", "").replace(Ui.SYM.trim(), "")); } catch (Exception e) { return null; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private interface Pick { void on(int idx); }

    private LinearLayout chips(String[] labels, int selected, final Pick p, boolean fill) {
        LinearLayout r = Ui.row(this);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, labels[i], 12, i == selected ? Ui.ON_ACCENT : Ui.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding((int) (12 * dp), (int) (7 * dp), (int) (12 * dp), (int) (7 * dp));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(16 * dp);
            g.setColor(i == selected ? Ui.ACCENT : 0x00000000);
            if (i != selected && !fill) g.setStroke((int) dp, Ui.OUTLINE);
            t.setBackground(g);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { p.on(idx); } });
            if (fill) r.addView(t, Ui.weight(1));
            else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, (int) (8 * dp), 0);
                r.addView(t, lp);
            }
        }
        return r;
    }

    private void stat(LinearLayout parent, String label, String value, int color) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, (int) (5 * dp), 0, (int) (5 * dp));
        r.addView(Ui.text(this, label, 14, Ui.SUB, false), Ui.weight(1));
        r.addView(Ui.text(this, value, 14, color, true));
        parent.addView(r);
    }

    private String signed(double v) { return (v >= 0 ? "+" : "") + Ui.rupees(v); }

    private String typeName(int t) {
        return t == Store.STOCK ? "Stocks" : t == Store.MF ? "Mutual funds" : t == Store.INDEX ? "Index"
                : t == Store.OTHER ? "Other assets" : t == Store.LOAN ? "Loans" : "SBI Life ULIP";
    }

    /** True when showing the whole portfolio (dashboard, or portfolio tab with no filter). */
    private boolean all() { return tab == 0 || scopeKind == 0; }

    private List<Store.Asset> scopeAssets() {
        if (tab == 0 || scopeKind == 0) return store.visible(-1);
        if (scopeKind == 1) return store.visible(scopeType);
        return store.visiblePlatform(scopePlatform);
    }

    private String scopeTitle() {
        if (all()) return "Consolidated portfolio";
        if (scopeKind == 1) return typeName(scopeType);
        return scopePlatform;
    }

    private LinkedHashMap<String, List<Store.Asset>> groups(List<Store.Asset> scope, boolean byPlatform) {
        LinkedHashMap<String, List<Store.Asset>> g = new LinkedHashMap<>();
        if (byPlatform) {
            for (String p : store.platforms()) g.put(p, new ArrayList<Store.Asset>());
            for (Store.Asset a : scope) {
                for (Map.Entry<String, List<Store.Asset>> e : g.entrySet())
                    if (e.getKey().equalsIgnoreCase(a.platformOrDefault())) { e.getValue().add(a); break; }
            }
        } else {
            int[] order = {Store.STOCK, Store.MF, Store.ULIP, Store.OTHER};
            for (int t : order) {
                List<Store.Asset> l = new ArrayList<>();
                for (Store.Asset a : scope) if (a.type == t) l.add(a);
                g.put(typeName(t), l);
            }
        }
        List<String> empty = new ArrayList<>();
        for (Map.Entry<String, List<Store.Asset>> e : g.entrySet()) if (e.getValue().isEmpty()) empty.add(e.getKey());
        for (String k : empty) g.remove(k);
        return g;
    }

    /** Same instrument across platforms merged into one line. */
    private LinkedHashMap<String, List<Store.Asset>> merged(List<Store.Asset> scope) {
        LinkedHashMap<String, List<Store.Asset>> m = new LinkedHashMap<>();
        for (Store.Asset a : scope) {
            List<Store.Asset> l = m.get(a.key());
            if (l == null) { l = new ArrayList<>(); m.put(a.key(), l); }
            l.add(a);
        }
        return m;
    }

    private int groupColor(String name, int i) {
        if (name.equals("Stocks")) return Ui.TYPE_COLORS[Store.STOCK];
        if (name.equals("Mutual funds")) return Ui.TYPE_COLORS[Store.MF];
        if (name.equals("SBI Life ULIP")) return Ui.TYPE_COLORS[Store.ULIP];
        if (name.equals("Other assets")) return Ui.TYPE_COLORS[Store.OTHER];
        return Ui.PALETTE[i % Ui.PALETTE.length];
    }

    private void sortByValue(List<Store.Asset> l) {
        Collections.sort(l, new Comparator<Store.Asset>() {
            @Override public int compare(Store.Asset a, Store.Asset b) { return Double.compare(b.value(), a.value()); }
        });
    }

    private int indexOfIgnoreCase(List<String> l, String s) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).equalsIgnoreCase(s)) return i;
        return -1;
    }

    private void circle(View v, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
    }

    /** Page title with optional refresh and + buttons. */
    private View header(String title, boolean withRefresh, boolean withAdd, final Runnable onAdd) {
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.TOP);          // buttons line up with the title, not the title + subtitle block
        final int bs = (int) (36 * dp), gap = (int) (8 * dp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, title, 26, Ui.TEXT, true));
        String sub = refreshing ? (refreshStatus == null ? "Updating prices…" : refreshStatus)
                : store.refreshedAt > 0 ? "Prices updated " + java.time.Instant.ofEpochMilli(store.refreshedAt)
                .atZone(java.time.ZoneId.systemDefault()).format(TF) + autoNote() : "Stocks · mutual funds · SBI Life ULIP";
        titles.addView(Ui.text(this, sub, 12, Ui.SUB, false));
        head.addView(titles, Ui.weight(1));
        FrameLayout eye = new FrameLayout(this);
        circle(eye, Ui.CARD2);
        NavIcon ei = new NavIcon(this, Ui.PRIVATE ? 5 : 4);
        ei.setState(Ui.PRIVATE ? Ui.ACCENT : Ui.TEXT, false);
        eye.addView(ei, new FrameLayout.LayoutParams((int) (18 * dp), (int) (18 * dp), Gravity.CENTER));
        eye.setContentDescription(Ui.PRIVATE ? "Show amounts" : "Hide amounts");
        eye.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.PRIVATE = !Ui.PRIVATE;
                toast(Ui.PRIVATE ? "Privacy mode on: amounts hidden" : "Amounts visible");
                render(false);
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(bs, bs);
        elp.setMargins(gap, 0, 0, 0);
        head.addView(eye, elp);
        if (withRefresh) {
            RefreshRing ref = new RefreshRing(this);
            ref.setContentDescription("Refresh prices");
            ref.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!refreshing) refresh(null, false); } });
            ref.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { autoRefreshDialog(); return true; } });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(bs, bs);
            rlp.setMargins(gap, 0, 0, 0);
            head.addView(ref, rlp);
        }
        if (withAdd) {
            TextView plus = Ui.text(this, "+", 21, Ui.ON_ACCENT, true);
            plus.setIncludeFontPadding(false);
            plus.setGravity(Gravity.CENTER);
            circle(plus, Ui.ACCENT);
            plus.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onAdd.run(); } });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(bs, bs);
            plp.setMargins(gap, 0, 0, 0);
            head.addView(plus, plp);
        }
        return head;
    }

    private final Runnable addAction = new Runnable() { @Override public void run() { addMenu(); } };

    // ================================================================== Dashboard tab
    private boolean moodOn() { return Alerts.prefs(this).getBoolean("mood", true); }

    private void showDashboard(boolean animate) {
        LinearLayout col = page(!animate);
        int idx = 0;
        List<Store.Asset> scope = store.visible(-1);
        boolean mood = moodOn();
        if (mood) {
            double tv = Store.value(scope), td = Store.lastChange(scope);
            scroller.setBackground(new Fx.Mood(tv - td > 0 ? td / (tv - td) * 100 : 0));
            redrawStrips();
        }
        buildDashboard(col, scope, animate, idx);
    }

    private void buildDashboard(LinearLayout col, List<Store.Asset> scope, boolean animate, int idx) {
        View gr = greetingRow();
        if (gr != null) add(col, gr, idx++, animate);
        add(col, header("Dashboard", true, true, addAction), idx++, animate);
        col.addView(Ui.space(this, 14));
        View oc = ownerChips();
        if (oc != null) add(col, oc, idx++, animate);
        add(col, cardStack(animate), idx++, animate);
        add(col, performanceCard(scope, animate), idx++, animate);
        View hm = heatmapCard(scope, animate);
        if (hm != null) add(col, hm, idx++, animate);
        View fcc = forecastCard();
        if (fcc != null) add(col, fcc, idx++, animate);
        View alloc = allocationCard(scope);
        if (alloc != null) add(col, alloc, idx++, animate);
        View tc = targetCard();
        if (tc != null) add(col, tc, idx++, animate);
        View up = upcomingPremiumCard();
        if (up != null) add(col, up, idx++, animate);
        View movers = moversRow(scope, animate);
        if (movers != null) {
            add(col, Ui.text(this, "Top movers", 16, Ui.TEXT, true), idx++, animate);
            col.addView(Ui.space(this, 8));
            add(col, movers, idx++, animate);
            col.addView(Ui.space(this, 12));
        }
        add(col, netWorthCard(), idx++, animate);
        View insc = insightsCard();
        if (insc != null) add(col, insc, idx++, animate);
        if ("INR".equals(Ui.CCY)) add(col, taxCard(), idx++, animate);
        View bench = benchmarkCard(scope);
        if (bench != null) add(col, bench, idx++, animate);
        String ins = insights(scope);
        if (ins != null) {
            LinearLayout ic = Ui.card(this);
            ic.addView(Ui.text(this, "Summary", 16, Ui.TEXT, true));
            ic.addView(Ui.space(this, 6));
            TextView t = Ui.text(this, ins, 14, Ui.SUB, false);
            t.setLineSpacing(5 * dp, 1);
            ic.addView(t);
            add(col, ic, idx++, animate);
        }
        if (Store.value(scope) == 0) add(col, helpCard(), idx++, animate);
    }

    private View helpCard() {
        LinearLayout help = Ui.card(this);
        help.addView(Ui.text(this, "Getting started", 16, Ui.TEXT, true));
        help.addView(Ui.space(this, 6));
        help.addView(Ui.text(this,
                "• Tap + to add NSE/BSE shares, ETFs or mutual funds. Prices update automatically.\n"
                        + "• Enter quantity/units, buy cost and platform (Zerodha, Groww, SBI MF…). The same fund or stock "
                        + "can be added again on another platform.\n"
                        + "• SBI Life ULIP: More › Paste ULIP NAVs, then set units on each fund.\n"
                        + "• Portfolio tab: filter by type or platform, or see everything merged.",
                14, Ui.SUB, false));
        return help;
    }

    /** Portfolio returns vs Nifty 50 over the same periods. */
    private View benchmarkCard(List<Store.Asset> scope) {
        Store.Asset nifty = store.find(Store.INDEX, "^NSEI");
        TreeMap<Long, Double> pv = Store.valueHistory(scope);
        if (nifty == null || nifty.prices.size() < 2 || pv.size() < 2) return null;
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "You vs Nifty 50", 16, Ui.TEXT, true));
        c.addView(Ui.text(this, "Return of your current holdings over each period", 11, Ui.SUB, false));
        int[] days = {7, 30, 91, 365};
        String[] lab = {"1W", "1M", "3M", "1Y"};
        LinearLayout head = Ui.row(this);
        head.setPadding(0, (int) (10 * dp), 0, (int) (4 * dp));
        head.addView(Ui.text(this, "", 12, Ui.SUB, false), Ui.weight(1.3f));
        for (String l : lab) head.addView(Ui.text(this, l, 12, Ui.SUB, true), Ui.weight(1));
        c.addView(head);
        String[] names = {"Portfolio", "Nifty 50"};
        TreeMap<?, ?>[] series = {pv, nifty.prices};
        for (int s = 0; s < 2; s++) {
            @SuppressWarnings("unchecked") TreeMap<Long, Double> m = (TreeMap<Long, Double>) series[s];
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
            r.addView(Ui.text(this, names[s], 13, Ui.TEXT, true), Ui.weight(1.3f));
            for (int d : days) {
                Map.Entry<Long, Double> last = m.lastEntry(), past = m.floorEntry(last.getKey() - d);
                Double v = past == null || past.getKey().equals(last.getKey()) ? null : (last.getValue() / past.getValue() - 1) * 100;
                r.addView(Ui.text(this, Ui.pct(v), 13, Ui.colorFor(v), true), Ui.weight(1));
            }
            c.addView(r);
        }
        return c;
    }

    // ================================================================== Portfolio tab
    private void showPortfolio(boolean animate) {
        LinearLayout col = page(!animate);
        int idx = 0;
        final List<Store.Asset> scope = scopeAssets();
        add(col, header("Portfolio", true, true, addAction), idx++, animate);
        col.addView(Ui.space(this, 14));
        View oc = ownerChips();
        if (oc != null) add(col, oc, idx++, animate);

        // Filter chips: All · types · platforms
        final List<String> labels = new ArrayList<>();
        labels.add("All"); labels.add("Stocks"); labels.add("Mutual funds"); labels.add("ULIP"); labels.add("Other"); labels.add("Loans");
        final List<String> plats = store.platforms();
        for (String p : plats) labels.add("@ " + p);
        int sel = scopeKind == 0 ? 0 : scopeKind == 1 ? (scopeType == Store.STOCK ? 1 : scopeType == Store.MF ? 2
                : scopeType == Store.ULIP ? 3 : scopeType == Store.OTHER ? 4 : 5)
                : 6 + indexOfIgnoreCase(plats, scopePlatform);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips(labels.toArray(new String[0]), sel, new Pick() {
            @Override public void on(int i) {
                if (i == 0) scopeKind = 0;
                else if (i <= 5) { scopeKind = 1; scopeType = i == 1 ? Store.STOCK : i == 2 ? Store.MF : i == 3 ? Store.ULIP : i == 4 ? Store.OTHER : Store.LOAN; }
                else { scopeKind = 2; scopePlatform = plats.get(i - 6); }
                render(false);
            }
        }, false));
        add(col, hs, idx++, animate);
        col.addView(Ui.space(this, 14));
        add(col, hero(scope, animate), idx++, animate);
        if (!all()) {
            add(col, performanceCard(scope, animate), idx++, animate);
            View alloc = allocationCard(scope);
            if (alloc != null) add(col, alloc, idx++, animate);
        }

        if (scopeKind == 1 && scopeType == Store.ULIP) {
            for (Store.Policy p : store.policies) add(col, policyCard(p, true), idx++, animate);
            if (store.policies.isEmpty()) {
                TextView ap = Ui.text(this, "+ Add policy details (premium dates, maturity, reminders)", 13, Ui.ACCENT, true);
                ap.setPadding(0, 0, 0, (int) (12 * dp));
                ap.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { policyDialog(null); } });
                add(col, ap, idx++, animate);
            }
        }
        // List mode
        LinearLayout lm = Ui.row(this);
        lm.addView(Ui.text(this, "Holdings", 18, Ui.TEXT, true), Ui.weight(1));
        add(col, lm, idx++, animate);
        col.addView(Ui.space(this, 8));
        add(col, chips(new String[]{"Merged", "By type", "By platform"}, listMode == 2 ? 0 : listMode + 1, new Pick() {
            @Override public void on(int i) { listMode = i == 0 ? 2 : i - 1; render(false); }
        }, true), idx++, animate);
        col.addView(Ui.space(this, 12));

        double total = Store.value(scope);
        if (listMode == 2) {
            List<List<Store.Asset>> lines = new ArrayList<>(merged(scope).values());
            Collections.sort(lines, new Comparator<List<Store.Asset>>() {
                @Override public int compare(List<Store.Asset> a, List<Store.Asset> b) { return Double.compare(Store.value(b), Store.value(a)); }
            });
            for (List<Store.Asset> l : lines) add(col, l.size() == 1 ? holdingCard(l.get(0), total) : mergedCard(l, total), idx++, animate);
        } else {
            for (Map.Entry<String, List<Store.Asset>> g : groups(scope, listMode == 1).entrySet()) {
                List<Store.Asset> l = g.getValue();
                sortByValue(l);
                final String gname = g.getKey();
                LinearLayout gh = Ui.row(this);
                gh.setPadding(0, (int) (6 * dp), 0, (int) (8 * dp));
                gh.addView(Ui.text(this, gname, 16, Ui.TEXT, true), Ui.weight(1));
                gh.addView(Ui.text(this, Ui.rupees(Store.value(l)), 14, Ui.SUB, true));
                if (listMode == 1) gh.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { renamePlatform(gname); return true; }
                });
                add(col, gh, idx++, animate);
                for (Store.Asset a : l) add(col, holdingCard(a, total), idx++, animate);
            }
        }
        if (scope.isEmpty()) add(col, helpCard(), idx++, animate);
        else if (scopeKind == 1 && scopeType == Store.ULIP) {
            TextView paste = Ui.button(this, "Paste ULIP NAVs", true);
            paste.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showPaste(null); } });
            col.addView(Ui.space(this, 6));
            add(col, paste, idx++, animate);
        }
    }

    private View mergedCard(final List<Store.Asset> l, double total) {
        final Store.Asset first = l.get(0);
        LinearLayout c = Ui.card(this);
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { current = first; screen = 1; render(true); } });
        glow(c, l.toArray(new Store.Asset[0]));
        double units = 0, value = Store.value(l), cost = 0;
        boolean allCost = true;
        StringBuilder plats = new StringBuilder();
        for (Store.Asset a : l) {
            units += a.units;
            if (a.invested > 0) cost += a.invested; else allCost = false;
            if (plats.length() > 0) plats.append(", ");
            plats.append(a.platformOrDefault());
        }
        Map.Entry<Long, Double> last = first.latest();
        LinearLayout top = Ui.row(this);
        View dotV = new View(this);
        circle(dotV, Ui.TYPE_COLORS[first.type]);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (8 * dp), (int) (8 * dp));
        dl.setMargins(0, 0, (int) (10 * dp), 0);
        top.addView(dotV, dl);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = Ui.row(this);
        nameRow.addView(Ui.text(this, shortName(first), 15, Ui.TEXT, true));
        TextView badge = Ui.pill(this, l.size() + " platforms", Ui.ACCENT);
        badge.setTextSize(10);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins((int) (8 * dp), 0, 0, 0);
        nameRow.addView(badge, bl);
        left.addView(nameRow);
        String sub = plats + (last != null ? " · " + trimUnits(units) + (first.type == Store.STOCK ? " sh" : " u") + " @ "
                + priceFmt.format(last.getValue()) : "");
        left.addView(Ui.text(this, sub, 11, Ui.SUB, false));
        top.addView(left, Ui.weight(1));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(Ui.text(this, value > 0 ? Ui.rupees(value) : "—", 14, Ui.TEXT, true));
        Double lc = first.lastChangePct();
        right.addView(Ui.text(this, Ui.pct(lc), 12, Ui.colorFor(lc), false));
        top.addView(right);
        c.addView(top);
        if (value > 0 && total > 0) {
            c.addView(Ui.space(this, 10));
            // stacked bar: one segment per platform
            LinearLayout seg = Ui.row(this);
            for (int i = 0; i < l.size(); i++) {
                View s = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setColor(Ui.PALETTE[i % Ui.PALETTE.length]);
                g.setCornerRadius(3 * dp);
                s.setBackground(g);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, (int) (5 * dp), (float) Math.max(0.001, l.get(i).value()));
                sp.setMargins(0, 0, i < l.size() - 1 ? (int) (2 * dp) : 0, 0);
                seg.addView(s, sp);
            }
            seg.setPivotX(0);
            seg.setScaleX(0f);
            seg.animate().scaleX((float) Math.min(1, value / total * 1.0 / Math.max(0.01, value / total))).setDuration(800).setStartDelay(200).start();
            c.addView(seg);
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (6 * dp), 0, 0);
            r.addView(Ui.text(this, String.format(Locale.US, "%.1f%% of view", value / total * 100), 11, Ui.SUB, false), Ui.weight(1));
            if (allCost && cost > 0) {
                double pl = value - cost;
                r.addView(Ui.text(this, "P&L " + signed(pl) + "  " + Ui.pct(pl / cost * 100), 11, Ui.colorFor(pl), true));
            }
            c.addView(r);
        }
        return c;
    }

    // ================================================================== Indexes tab
    private void showIndexes(boolean animate) {
        LinearLayout col = page(!animate);
        int idx = 0;
        add(col, header("Markets", true, true, new Runnable() { @Override public void run() { marketsAddMenu(); } }), idx++, animate);
        col.addView(Ui.space(this, 14));
        add(col, Ui.text(this, "Indexes", 18, Ui.TEXT, true), idx++, animate);
        col.addView(Ui.space(this, 8));
        if (store.indices.isEmpty()) {
            LinearLayout e = Ui.card(this);
            e.addView(Ui.text(this, "No indexes yet. Tap + to add Nifty, Sensex or a sector index.", 14, Ui.SUB, false));
            add(col, e, idx++, animate);
        }
        idx = tiles(col, store.indices, idx, animate);

        col.addView(Ui.space(this, 6));
        LinearLayout wh = Ui.row(this);
        wh.addView(Ui.text(this, "Watchlist", 18, Ui.TEXT, true), Ui.weight(1));
        TextView addW = Ui.text(this, "+ Add", 14, Ui.ACCENT, true);
        addW.setPadding((int) (8 * dp), (int) (4 * dp), 0, (int) (4 * dp));
        addW.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { watchAddMenu(); } });
        wh.addView(addW);
        add(col, wh, idx++, animate);
        col.addView(Ui.space(this, 8));
        List<Store.Asset> wl = store.watchlist();
        if (wl.isEmpty()) {
            LinearLayout e = Ui.card(this);
            e.addView(Ui.text(this, "Follow stocks or funds you don't own. You'll see their charts here, and can set price alerts.",
                    14, Ui.SUB, false));
            add(col, e, idx++, animate);
        } else idx = tiles(col, wl, idx, animate);
        TextView hint = Ui.text(this, "Tap any tile for its chart, 52-week range and price alerts.", 12, Ui.SUB, false);
        hint.setGravity(Gravity.CENTER);
        add(col, hint, idx++, animate);
    }

    /** Two-column grid of price tiles. Returns the next animation index. */
    private int tiles(LinearLayout col, List<Store.Asset> list, int idx, boolean animate) {
        LinearLayout rowL = null;
        for (int i = 0; i < list.size(); i++) {
            final Store.Asset a = list.get(i);
            if (i % 2 == 0) {
                rowL = Ui.row(this);
                rowL.setGravity(Gravity.TOP);
                add(col, rowL, idx++, animate);
            }
            LinearLayout c = Ui.card(this);
            c.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
            Double lc = a.lastChangePct();
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{(Ui.colorFor(lc) & 0x00FFFFFF) | 0x1E000000, Ui.CARD});
            g.setCornerRadius(18 * dp);
            c.setBackground(g);
            LinearLayout nameRow = Ui.row(this);
            TextView nm = Ui.text(this, shortName(a), 14, Ui.TEXT, true);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nameRow.addView(nm, Ui.weight(1));
            if (a.targetAbove > 0 || a.targetBelow > 0) nameRow.addView(Ui.text(this, "🔔", 11, Ui.SUB, false));
            c.addView(nameRow);
            Map.Entry<Long, Double> l = a.latest();
            String px = l == null ? (a.error != null ? "⚠ " + a.error : "Loading…")
                    : (a.type == Store.INDEX ? String.format(Locale.US, "%,.2f", l.getValue()) : priceSym(a) + String.format(Locale.US, "%,.2f", l.getValue()));
            TextView pv = Ui.text(this, px, 18, a.error != null && l == null ? Ui.RED : Ui.TEXT, true);
            pv.setSingleLine(true);
            c.addView(pv);
            c.addView(Ui.text(this, (lc != null && lc < 0 ? "▼ " : "▲ ") + Ui.pct(lc), 12, Ui.colorFor(lc), true));
            if (a.prices.size() >= 2) {
                c.addView(Ui.space(this, 6));
                ChartView sp = new ChartView(this, true);
                sp.setData(a.prices, l.getKey() - 30, Ui.colorFor(a.changeOver(30)));
                if (animate) sp.play();
                c.addView(sp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * dp)));
                double[] rg = a.range52w();
                if (rg != null && rg[1] > rg[0]) {
                    c.addView(Ui.space(this, 8));
                    c.addView(rangeBar(rg[0], rg[1], l.getValue(), false));
                }
            }
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { current = a; screen = 1; render(true); } });
            glow(c, a);
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(i % 2 == 0 ? 0 : (int) (6 * dp), 0, i % 2 == 0 ? (int) (6 * dp) : 0, (int) (12 * dp));
            rowL.addView(c, lp);
        }
        if (list.size() % 2 == 1 && rowL != null) rowL.addView(new View(this), Ui.weight(1));
        return idx;
    }

    /** 52-week range bar: low ——●—— high, with optional labels. */
    private View rangeBar(final double lo, final double hi, final double now, boolean labels) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final float frac = (float) Math.max(0, Math.min(1, (now - lo) / (hi - lo)));
        View bar = new View(this) {
            final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), cy = h / 2, r = h / 2;
                pt.setShader(new android.graphics.LinearGradient(0, 0, w, 0, Ui.RED, Ui.GREEN, android.graphics.Shader.TileMode.CLAMP));
                pt.setAlpha(110);
                cv.drawRoundRect(0, cy - 2 * dp, w, cy + 2 * dp, 2 * dp, 2 * dp, pt);
                pt.setShader(null);
                pt.setAlpha(255);
                float x = Math.max(r, Math.min(w - r, frac * w));
                pt.setColor(Ui.CARD); cv.drawCircle(x, cy, r, pt);
                pt.setColor(Ui.TEXT); cv.drawCircle(x, cy, r - 2.2f * dp, pt);
            }
        };
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (12 * dp)));
        LinearLayout r = Ui.row(this);
        r.setPadding(0, (int) (3 * dp), 0, 0);
        r.addView(Ui.text(this, (labels ? "52W low " : "L ") + priceFmt.format(lo), 10, Ui.SUB, false), Ui.weight(1));
        r.addView(Ui.text(this, (labels ? "52W high " : "H ") + priceFmt.format(hi), 10, Ui.SUB, false));
        box.addView(r);
        return box;
    }

    private void marketsAddMenu() {
        dialog().setTitle("Add to Markets")
                .setItems(new String[]{"📊 Index\nNifty, Sensex, sectors…", "📈 Watch a stock or ETF\nFollow it without owning it",
                                "🏦 Watch a mutual fund\nTrack its NAV and set alerts"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                if (w == 0) searchDialog(Store.INDEX, "Nifty ");
                                else { watchMode = true; searchDialog(w == 1 ? Store.STOCK : Store.MF, w == 1 ? "" : "SBI "); }
                            }
                        }).show();
    }

    private void watchAddMenu() {
        dialog().setTitle("Add to watchlist")
                .setItems(new String[]{"📈 Stock or ETF\nNSE / BSE", "🏦 Mutual fund\nSBI or any AMC"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        watchMode = true;
                        searchDialog(w == 0 ? Store.STOCK : Store.MF, w == 0 ? "" : "SBI ");
                    }
                }).show();
    }

    private boolean watchMode;   // next search result goes to the watchlist

    /** Set or clear one-shot price alerts for an asset. */
    private void targetDialog(final Store.Asset a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Map.Entry<Long, Double> l = a.latest();
        if (l != null) box.addView(Ui.text(this, "Now " + priceFmt.format(l.getValue()), 13, Ui.SUB, false));
        box.addView(Ui.text(this, "Alert when price goes above", 12, Ui.SUB, false));
        final EditText above = numberField("e.g. " + (l == null ? "100" : priceFmt.format(l.getValue() * 1.1)),
                a.targetAbove > 0 ? priceFmt.format(a.targetAbove) : null);
        box.addView(above);
        box.addView(Ui.text(this, "Alert when price falls below", 12, Ui.SUB, false));
        final EditText below = numberField("e.g. " + (l == null ? "80" : priceFmt.format(l.getValue() * 0.9)),
                a.targetBelow > 0 ? priceFmt.format(a.targetBelow) : null);
        box.addView(below);
        box.addView(Ui.text(this, "Each alert fires once, then clears. Checked with background price updates.", 11, Ui.SUB, false));
        dialog().setTitle("Price alerts · " + shortName(a)).setView(padded(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Double up = num(above.getText().toString()), dn = num(below.getText().toString());
                        a.targetAbove = up == null || up <= 0 ? 0 : up;
                        a.targetBelow = dn == null || dn <= 0 ? 0 : dn;
                        store.save();
                        Alerts.noteTargets(MainActivity.this, store);
                        if (a.targetAbove > 0 || a.targetBelow > 0) {
                            askNotificationPermission();
                            String err = Alerts.schedule(MainActivity.this);
                            toast(err == null ? "Price alert set" : "Alert saved, but background checks unavailable: " + err);
                        } else {
                            Alerts.schedule(MainActivity.this);
                            toast("Price alerts cleared");
                        }
                        render(false);
                    }
                }).setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        a.targetAbove = 0; a.targetBelow = 0;
                        store.save(); Alerts.noteTargets(MainActivity.this, store); Alerts.schedule(MainActivity.this);
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private View alertCard(final Store.Asset a) {
        LinearLayout c = Ui.card(this);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, "🔔  Price alerts", 16, Ui.TEXT, true), Ui.weight(1));
        TextView set = Ui.text(this, a.targetAbove > 0 || a.targetBelow > 0 ? "Edit" : "Set", 14, Ui.ACCENT, true);
        top.addView(set);
        c.addView(top);
        if (a.targetAbove <= 0 && a.targetBelow <= 0) {
            c.addView(Ui.text(this, "Get notified when the price goes above or below a level you choose.", 13, Ui.SUB, false));
        } else {
            if (a.targetAbove > 0) stat(c, "Above", priceFmt.format(a.targetAbove), Ui.GREEN);
            if (a.targetBelow > 0) stat(c, "Below", priceFmt.format(a.targetBelow), Ui.RED);
        }
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { targetDialog(a); } });
        return c;
    }

    // ================================================================== More tab
    private void showMore(boolean animate) {
        LinearLayout col = page(!animate);      // settings changes redraw in place, keeping the scroll position
        int idx = 0;
        add(col, header("More", false, false, null), idx++, animate);
        col.addView(Ui.space(this, 14));
        LinearLayout prof = Ui.card(this);
        LinearLayout pr = Ui.row(this);
        View pl = avatarView("Me", 46);
        pl.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { avatarDialog("Me"); } });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams((int) (46 * dp), (int) (46 * dp));
        plp.setMargins(0, 0, (int) (14 * dp), 0);
        pr.addView(pl, plp);
        LinearLayout pt = new LinearLayout(this);
        pt.setOrientation(LinearLayout.VERTICAL);
        pt.addView(Ui.text(this, userName().isEmpty() ? "Add your name" : userName(), 17, Ui.TEXT, true));
        pt.addView(Ui.text(this, userName().isEmpty() ? "For a personal greeting" : "Tap the picture to change it, or here for your name", 12, Ui.SUB, false));
        pr.addView(pt, Ui.weight(1));
        pr.addView(Ui.text(this, "✎", 18, Ui.ACCENT, true));
        prof.addView(pr);
        prof.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { nameDialog(); } });
        LinearLayout cr = Ui.row(this);
        cr.setPadding(0, (int) (12 * dp), 0, 0);
        cr.addView(Ui.text(this, "Currency", 14, Ui.SUB, false), Ui.weight(1));
        cr.addView(Ui.text(this, Ui.flagOf(store.ccyOf("Me")) + "  " + store.ccyOf("Me") + " · " + Ui.nameOf(store.ccyOf("Me")) + "  ›", 14, Ui.ACCENT, true));
        cr.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { currencyDialog("Me"); } });
        prof.addView(cr);
        add(col, prof, idx++, animate);
        col.addView(Ui.space(this, 4));
        add(col, Ui.text(this, "Privacy", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        LinearLayout pc = Ui.card(this);
        pc.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        pc.addView(switchRow("Hide amounts now", "Same as the eye button. Hides amounts and units, keeps % and charts",
                Ui.PRIVATE, true, new Toggle() {
                    @Override public void set(boolean on) { Ui.PRIVATE = on; renderAfterToggle(); }
                }));
        pc.addView(divider());
        pc.addView(switchRow("Start in privacy mode", "Amounts stay hidden every time the app opens, until you tap the eye",
                Alerts.prefs(this).getBoolean("privacyStart", false), true, new Toggle() {
                    @Override public void set(boolean on) { Alerts.prefs(MainActivity.this).edit().putBoolean("privacyStart", on).apply(); }
                }));
        pc.addView(divider());
        pc.addView(switchRow("Hide amounts on home-screen widget", "Widget shows only the % change",
                Alerts.prefs(this).getBoolean("widgetPrivate", false), true, new Toggle() {
                    @Override public void set(boolean on) { Alerts.prefs(MainActivity.this).edit().putBoolean("widgetPrivate", on).apply(); Widget.requestUpdate(MainActivity.this); }
                }));
        pc.addView(divider());
        pc.addView(switchRow("Hide amounts in notifications", "Alerts show only the % move",
                Alerts.prefs(this).getBoolean("privacyNotify", false), true, new Toggle() {
                    @Override public void set(boolean on) { Alerts.prefs(MainActivity.this).edit().putBoolean("privacyNotify", on).apply(); }
                }));
        add(col, pc, idx++, animate);
        col.addView(Ui.space(this, 14));
        add(col, Ui.text(this, "Appearance", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        LinearLayout ap = Ui.card(this);
        ap.addView(Ui.text(this, "Theme", 15, Ui.TEXT, false));
        ap.addView(Ui.text(this, "System follows your phone's dark mode setting", 12, Ui.SUB, false));
        ap.addView(Ui.space(this, 10));
        ap.addView(chips(new String[]{"System", "Light", "Dark"}, Ui.themePref(this), new Pick() {
            @Override public void on(int i) {
                if (i == Ui.themePref(MainActivity.this)) return;
                Alerts.prefs(MainActivity.this).edit().putInt("theme", i).apply();
                recreate();
            }
        }, true));
        ap.addView(Ui.space(this, 14));
        ap.addView(Ui.text(this, "Dashboard cards", 15, Ui.TEXT, false));
        ap.addView(Ui.text(this, "How solid the card backgrounds look", 12, Ui.SUB, false));
        ap.addView(Ui.space(this, 10));
        final int[] ops = {100, 80, 60, 40};
        int cur = Alerts.prefs(this).getInt("cardOpacity", 60), sel = 2;
        for (int i = 0; i < ops.length; i++) if (ops[i] == cur) sel = i;
        ap.addView(chips(new String[]{"Solid", "80%", "60%", "40%"}, sel, new Pick() {
            @Override public void on(int i) { Alerts.prefs(MainActivity.this).edit().putInt("cardOpacity", ops[i]).apply(); render(false); }
        }, true));
        add(col, ap, idx++, animate);
        add(col, effectsCard(), idx++, animate);
        add(col, Ui.text(this, "About", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        add(col, aboutCard(), idx++, animate);
        add(col, Ui.text(this, "Galaxy Watch", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        add(col, watchCard(), idx++, animate);
        add(col, Ui.text(this, "Prices & refresh", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        add(col, autoRefreshCard(), idx++, animate);
        add(col, Ui.text(this, "Security & alerts", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        add(col, settingsCard(), idx++, animate);
        add(col, Ui.text(this, "Import, family & tools", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        LinearLayout ic = Ui.card(this);
        ic.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        String[][] imp = {
                {"Import mutual fund statement", "CAMS / KFintech CAS PDF: all funds, units, cost and transactions"},
                {"Import broker file", "Zerodha, Groww & others: holdings or tradebook (CSV / Excel)"},
                {"Insurance policies", store.policies.isEmpty() ? "Add SBI Life policy details & premium reminders"
                        : store.policies.size() + " polic" + (store.policies.size() == 1 ? "y" : "ies")},
                {"How to get your CAS", "Free from CAMS or KFintech, by e-mail"},
                {"Automatic backup", Alerts.prefs(this).getString("bkTree", null) == null ? "Off · encrypted daily copy to a folder" : "On · encrypted daily copy"},
                {"Family members", store.owners().size() + " · pictures, currencies, names, or add via the Owner field"},
                {"Insights", "Concentration, overlap, Regular plans, vs Nifty"},
                {"Capital gains & tax", "This year's estimate and tax harvesting"}};
        for (int i = 0; i < imp.length; i++) {
            final int which = i;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding((int) (18 * dp), (int) (13 * dp), (int) (18 * dp), (int) (13 * dp));
            r.addView(Ui.text(this, imp[i][0], 15, Ui.TEXT, false));
            r.addView(Ui.text(this, imp[i][1], 12, Ui.SUB, false));
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (which == 0 || which == 1) pickImportFile();
                    else if (which == 2) { screen = 4; render(true); }
                    else if (which == 4) backupSettings();
                    else if (which == 5) renameOwner();
                    else if (which == 6) { screen = 5; render(true); }
                    else if (which == 7) { screen = 6; render(true); }
                    else dialog().setTitle("Getting your CAS").setMessage(
                            "1. On the CAMS or KFintech website (or MF Central), request a Consolidated Account Statement (CAS).\n"
                                    + "2. Choose the detailed statement (with transactions) for the period since inception, and set a password, often your PAN.\n"
                                    + "3. The PDF arrives by e-mail. Share it to Portfolio from your mail app, or use Import here.\n\n"
                                    + "Covers every mutual fund under your PAN, whichever app you bought through.")
                            .setPositiveButton("OK", null).show();
                }
            });
            ic.addView(r);
            if (i < imp.length - 1) ic.addView(divider());
        }
        add(col, ic, idx++, animate);
        add(col, Ui.text(this, "Data & tools", 13, Ui.SUB, true), idx++, animate);
        col.addView(Ui.space(this, 6));
        String[][] items = {
                {"Paste SBI Life ULIP NAVs", "Update all ULIP fund NAVs from copied text"},
                {"Auto-update ULIP NAVs", ulipLinkedCount() > 0 ? ulipLinkedCount() + " of " + ulipNames().size() + " funds update online every day"
                        : "Find your SBI Life funds online so NAVs update by themselves"},
                {"Refresh all prices", "Stocks, mutual funds, indexes and linked ULIP funds"},
                {"SBI Life policy: amount invested", store.ulipInvested > 0 ? Ui.rupees(store.ulipInvested) : "Not set"},
                {"Show / hide holdings", store.assets.size() + " holdings"},
                {"Rename a platform", store.platforms().size() + " platforms"},
                {"Share backup", "Send all data to yourself (e-mail, Drive, WhatsApp…)"},
                {"Copy backup to clipboard", ""},
                {"Restore backup from clipboard", "Replaces everything in the app"},
                {"Copy last error report", Crash.last(this) != null ? "An error was recorded" : "No errors recorded"},
                {"About prices", "Where the data comes from"}};
        LinearLayout card = Ui.card(this);
        card.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        for (int i = 0; i < items.length; i++) {
            final int which = i == 0 ? 0 : i == 1 ? 100 : i - 1;     // row 1 is the ULIP auto-update link
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding((int) (18 * dp), (int) (13 * dp), (int) (18 * dp), (int) (13 * dp));
            r.addView(Ui.text(this, items[i][0], 15, Ui.TEXT, false));
            if (!items[i][1].isEmpty()) r.addView(Ui.text(this, items[i][1], 12, Ui.SUB, false));
            r.setClickable(true);
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { moreAction(which); } });
            card.addView(r);
            if (i < items.length - 1) {
                View div = new View(this);
                div.setBackgroundColor(Ui.DIVIDER);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dlp.setMargins((int) (18 * dp), 0, 0, 0);
                card.addView(div, dlp);
            }
        }
        add(col, card, idx++, animate);
    }

    private interface Toggle { void set(boolean on); }

    private View switchRow(String title, String sub, boolean on, boolean enabled, final Toggle t) {
        LinearLayout r = Ui.row(this);
        r.setPadding((int) (18 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Ui.text(this, title, 15, enabled ? Ui.TEXT : Ui.SUB, false));
        if (sub != null) tx.addView(Ui.text(this, sub, 12, Ui.SUB, false));
        r.addView(tx, Ui.weight(1));
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(on);
        sw.setEnabled(enabled);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { t.set(c); }
        });
        r.addView(sw);
        return r;
    }

    private View divider() {
        View div = new View(this);
        div.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dlp.setMargins((int) (18 * dp), 0, 0, 0);
        div.setLayoutParams(dlp);
        return div;
    }

    private View settingsCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        final String lockWhy = Lock.unavailableReason(this);
        card.addView(switchRow("Fingerprint lock", lockWhy != null ? lockWhy : "Ask for fingerprint (or phone PIN) when opening the app",
                Lock.enabled(this), lockWhy == null, new Toggle() {
                    @Override public void set(final boolean on) {
                        // confirm with the fingerprint before changing it either way
                        authInProgress = true;
                        Lock.authenticate(MainActivity.this, on ? "Turn on fingerprint lock" : "Turn off fingerprint lock", new Lock.Result() {
                            @Override public void ok() { authInProgress = false; Lock.setEnabled(MainActivity.this, on); toast(on ? "App lock on" : "App lock off"); renderAfterToggle(); }
                            @Override public void fail(String why) { authInProgress = false; renderAfterToggle(); }
                        });
                    }
                }));
        card.addView(divider());
        card.addView(switchRow("Daily closing summary", "One notification after market close (~3:45–4:15 pm) on weekdays",
                Alerts.dailySummary(this), true, new Toggle() {
                    @Override public void set(boolean on) {
                        Alerts.prefs(MainActivity.this).edit().putBoolean("daily", on).apply();
                        if (on) askNotificationPermission();
                        Alerts.schedule(MainActivity.this);
                    }
                }));
        card.addView(divider());
        final boolean al = Alerts.enabled(this);
        card.addView(switchRow("Price move alerts",
                "Notify when a move is " + String.format(Locale.US, "%.0f", Alerts.threshold(this)) + "% or more. Checks about every "
                        + Alerts.intervalMin(this) + " min", al, true, new Toggle() {
                    @Override public void set(boolean on) {
                        Alerts.prefs(MainActivity.this).edit().putBoolean("alerts", on).apply();
                        String err = Alerts.schedule(MainActivity.this);
                        if (err != null) toast("Background checks unavailable: " + err);
                        if (on) askNotificationPermission();
                        renderAfterToggle();
                    }
                }));
        if (al) {
            card.addView(divider());
            card.addView(switchRow("   Each holding", "Stocks, mutual funds and ULIP funds you hold", Alerts.holdings(this), true, new Toggle() {
                @Override public void set(boolean on) { Alerts.prefs(MainActivity.this).edit().putBoolean("a_hold", on).apply(); }
            }));
            card.addView(switchRow("   Each index", "Nifty 50, Sensex and the others on the Indexes tab", Alerts.indexes(this), true, new Toggle() {
                @Override public void set(boolean on) { Alerts.prefs(MainActivity.this).edit().putBoolean("a_idx", on).apply(); }
            }));
            card.addView(switchRow("   Whole portfolio", "Your total value in a day", Alerts.portfolio(this), true, new Toggle() {
                @Override public void set(boolean on) { Alerts.prefs(MainActivity.this).edit().putBoolean("a_port", on).apply(); }
            }));
            card.addView(divider());
            LinearLayout th = new LinearLayout(this);
            th.setOrientation(LinearLayout.VERTICAL);
            th.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
            th.addView(Ui.text(this, "Alert when the move is at least", 14, Ui.TEXT, false));
            th.addView(Ui.space(this, 8));
            final String[] ths = {"2%", "3%", "5%", "7%", "10%"};
            final float[] thv = {2, 3, 5, 7, 10};
            int sel = 2;
            for (int i = 0; i < thv.length; i++) if (Math.abs(thv[i] - Alerts.threshold(this)) < 0.01) sel = i;
            th.addView(chips(ths, sel, new Pick() {
                @Override public void on(int i) { Alerts.prefs(MainActivity.this).edit().putFloat("threshold", thv[i]).apply(); render(false); }
            }, true));
            th.addView(Ui.space(this, 12));
            th.addView(Ui.text(this, "Check every", 14, Ui.TEXT, false));
            th.addView(Ui.space(this, 8));
            final int[] iv = {15, 30, 60, 180};
            int isel = 1;
            for (int i = 0; i < iv.length; i++) if (iv[i] == Alerts.intervalMin(this)) isel = i;
            th.addView(chips(new String[]{"15 min", "30 min", "1 hour", "3 hours"}, isel, new Pick() {
                @Override public void on(int i) {
                    Alerts.prefs(MainActivity.this).edit().putInt("interval", iv[i]).apply();
                    Alerts.schedule(MainActivity.this);
                    renderAfterToggle();
                }
            }, true));
            th.addView(Ui.space(this, 10));
            TextView test = Ui.text(this, "Send a test notification", 13, Ui.ACCENT, false);
            test.setPadding(0, (int) (4 * dp), 0, 0);
            test.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { testNotification(); } });
            th.addView(test);
            card.addView(th);
        }
        return card;
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
    }

    private void testNotification() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askNotificationPermission();
            toast("Allow notifications, then tap test again");
            return;
        }
        Alerts.ensureChannel(this);
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, new android.app.Notification.Builder(this, Alerts.CHANNEL)
                .setSmallIcon(Alerts.statIcon(this))
                .setContentTitle("▲ Test alert")
                .setContentText("Price alerts are working.")
                .setAutoCancel(true).build());
    }

    private void moreAction(int which) {
        switch (which) {
            case 100: linkUlips(); break;
            case 0: showPaste(null); break;
            case 1: refresh(null, false); break;
            case 2: editUlipInvested(); break;
            case 3: showHide(); break;
            case 4: {
                final List<String> p = store.platforms();
                dialog().setTitle("Rename which platform?").setItems(p.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { renamePlatform(p.get(w)); }
                }).show();
                break;
            }
            case 5: {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                s.putExtra(Intent.EXTRA_SUBJECT, "Portfolio backup " + LocalDate.now());
                s.putExtra(Intent.EXTRA_TEXT, store.toJson().toString());
                startActivity(Intent.createChooser(s, "Save backup"));
                break;
            }
            case 6: {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Portfolio backup", store.toJson().toString()));
                toast("Backup copied");
                break;
            }
            case 7: {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0)
                    confirmRestore(cm.getPrimaryClip().getItemAt(0).coerceToText(this).toString());
                else toast("Clipboard is empty");
                break;
            }
            case 8: {
                String c = Crash.last(this);
                if (c == null) { toast("No errors recorded"); break; }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Crash report", c));
                toast("Error report copied");
                break;
            }
            default:
                dialog().setTitle("About prices").setMessage(
                        "• Mutual funds: mfapi.in (official AMFI NAVs, updated daily in the evening).\n\n"
                                + "• Stocks, ETFs and indexes: Yahoo Finance (free, unofficial, may be delayed ~15 min).\n\n"
                                + "• SBI Life ULIP: pasted by you from SBI Life's NAV page or statement.\n\n"
                                + "All data stays on this phone.").setPositiveButton("OK", null).show();
        }
    }

    private View hero(List<Store.Asset> scope, boolean animate) {
        LinearLayout h = Ui.heroCard(this, Ui.HERO1, Ui.HERO2);
        h.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (18 * dp));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, scopeTitle(), 13, Ui.HERO_SUB, false), Ui.weight(1));
        if (!all() && scopeKind == 2) {
            TextView ren = Ui.text(this, "Rename", 12, Ui.ACCENT, false);
            ren.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { renamePlatform(scopePlatform); } });
            top.addView(ren);
        }
        h.addView(top);
        double total = Store.value(scope);
        TextView val = Ui.text(this, Ui.rupees(total), 34, Ui.TEXT, true);
        val.setSingleLine(true);
        h.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (animate && total > 0) Ui.countUp(val, total, true);

        double day = Store.lastChange(scope);
        LinearLayout pills = Ui.row(this);
        pills.setPadding(0, (int) (6 * dp), 0, 0);
        if (day != 0) {
            double base = total - day;
            pills.addView(Ui.pill(this, (day >= 0 ? "▲ " : "▼ ") + signed(day) + "  " + Ui.pct(base > 0 ? day / base * 100 : null)
                    + "  today", Ui.colorFor(day)));
        } else {
            pills.addView(Ui.pill(this, scope.size() + " holdings", Ui.SUB));
        }
        h.addView(pills);

        double[] cv = store.costAndValue(scope);
        if (cv[0] > 0) {
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (16 * dp), 0, 0);
            r.addView(miniStat("Invested", Ui.rupees(cv[0]), Ui.TEXT), Ui.weight(1));
            double gain = cv[1] - cv[0];
            r.addView(miniStat("Gain / loss", signed(gain), Ui.colorFor(gain)), Ui.weight(1.2f));
            r.addView(miniStat("Return", Ui.pct(gain / cv[0] * 100), Ui.colorFor(gain)), Ui.weight(0.8f));
            h.addView(r);
        }
        Object[] xr = Store.xirr(scope);
        double realised = Store.realised(scope), divs = Store.dividends(scope);
        if (xr[0] != null || realised != 0 || divs > 0) {
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (12 * dp), 0, 0);
            if (xr[0] != null) {
                Double x = (Double) xr[0];
                r.addView(miniStat("XIRR · " + xr[1] + "/" + xr[2] + " holdings", Ui.pct(x) + " /yr", Ui.colorFor(x)), Ui.weight(1.3f));
            }
            if (realised != 0) r.addView(miniStat("Realised", signed(realised), Ui.colorFor(realised)), Ui.weight(1));
            if (divs > 0) r.addView(miniStat("Dividends", Ui.rupees(divs), Ui.GREEN), Ui.weight(0.9f));
            h.addView(r);
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.getLayoutParams();
        lp.bottomMargin = (int) (14 * dp);
        return h;
    }

    private View miniStat(String label, String value, int color) {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.addView(Ui.text(this, label, 11, Ui.HERO_SUB, false));
        m.addView(Ui.text(this, value, 14, color, true));
        return m;
    }

    private View performanceCard(final List<Store.Asset> scope, boolean animate) {
        LinearLayout c = Ui.card(this);
        final View[] self = {c};
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "Performance", 16, Ui.TEXT, true), Ui.weight(1));
        c.addView(head);
        c.addView(Ui.space(this, 8));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips(MODES, chartMode, new Pick() { @Override public void on(int i) { chartMode = i; refreshCard(self[0], performanceCard(scope, false)); } }, false));
        c.addView(hs);
        c.addView(Ui.space(this, 8));

        ChartView ch = new ChartView(this, false);
        ch.setTag("chart");
        TreeMap<Long, Double> hist = Store.valueHistory(scope);
        long last = hist.isEmpty() ? LocalDate.now().toEpochDay() : hist.lastKey();
        long from = last - RANGE_DAYS[range];
        List<ChartView.Series> s = new ArrayList<>();
        if (chartMode == 2) {
            if (all()) {
                int i = 0;
                for (Map.Entry<String, List<Store.Asset>> g : groups(scope, groupBy == 1).entrySet()) {
                    TreeMap<Long, Double> gh = Store.valueHistory(g.getValue());
                    if (gh.size() >= 2) s.add(new ChartView.Series(g.getKey(), groupColor(g.getKey(), i), gh, from));
                    if (++i >= 6) break;
                }
            } else {
                List<Store.Asset> l = new ArrayList<>(scope);
                sortByValue(l);
                int i = 0;
                for (Store.Asset a : l) {
                    if (a.prices.size() < 2) continue;
                    s.add(new ChartView.Series(shortName(a), Ui.PALETTE[i % Ui.PALETTE.length], a.prices, from));
                    if (++i >= 5) break;
                }
            }
            ch.setSeries(s, ChartView.LINE, true, animate);
        } else {
            s.add(new ChartView.Series("", Ui.ACCENT, hist, from));
            ch.setFormatter(rupeeFmt);
            ch.setSeries(s, chartMode == 3 ? ChartView.BARS : chartMode == 1 ? ChartView.LINE : ChartView.AREA,
                    chartMode == 1, animate);
        }
        c.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (230 * dp)));
        c.addView(Ui.space(this, 6));
        c.addView(chips(RANGES, range, new Pick() { @Override public void on(int i) { range = i; refreshCard(self[0], performanceCard(scope, false)); } }, true));
        if (hist.isEmpty() && chartMode != 2) {
            c.addView(Ui.text(this, "Chart appears once your holdings have units and prices.", 12, Ui.SUB, false));
        }
        return c;
    }

    private void playCharts() {
        View v = scroller == null ? null : scroller.findViewWithTag("chart");
        if (v instanceof ChartView) ((ChartView) v).play();
    }

    private String shortName(Store.Asset a) {
        String n = a.name.replaceAll("(?i)\\s*-\\s*(direct|regular) plan.*$", "").replaceAll("(?i)^sbi\\s+", "");
        return n.length() > 16 ? n.substring(0, 15) + "…" : n;
    }

    private View allocationCard(final List<Store.Asset> scope) {
        double total = Store.value(scope);
        if (total <= 0) return null;
        LinearLayout c = Ui.card(this);
        final View[] self = {c};
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "Allocation", 16, Ui.TEXT, true), Ui.weight(1));
        if (all()) {
            head.addView(chips(new String[]{"By type", "By platform"}, groupBy, new Pick() {
                @Override public void on(int i) { groupBy = i; View n = allocationCard(scope); if (n != null) refreshCard(self[0], n); }
            }, false));
        }
        c.addView(head);
        c.addView(Ui.space(this, 10));

        final List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        final List<Object> targets = new ArrayList<>();
        if (all()) {
            for (Map.Entry<String, List<Store.Asset>> g : groups(scope, groupBy == 1).entrySet()) {
                names.add(g.getKey()); vals.add(Store.value(g.getValue())); targets.add(g.getKey());
            }
        } else {
            List<Store.Asset> l = new ArrayList<>(scope);
            sortByValue(l);
            double other = 0;
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).value() <= 0) continue;
                if (names.size() < 6) { names.add(shortName(l.get(i))); vals.add(l.get(i).value()); targets.add(l.get(i)); }
                else other += l.get(i).value();
            }
            if (other > 0) { names.add("Others"); vals.add(other); targets.add(null); }
        }
        int[] colors = new int[names.size()];
        for (int i = 0; i < colors.length; i++) colors[i] = groupColor(names.get(i), i);

        LinearLayout body = Ui.row(this);
        DonutView d = new DonutView(this);
        d.setData(names, vals, colors, Ui.rupees(total).replaceAll("\\.\\d+$", ""), names.size() + (all() ? " groups" : " holdings"));
        body.addView(d, new LinearLayout.LayoutParams((int) (150 * dp), (int) (150 * dp)));
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.VERTICAL);
        legend.setPadding((int) (14 * dp), 0, 0, 0);
        for (int i = 0; i < names.size(); i++) {
            final Object target = targets.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, (int) (4 * dp), 0, (int) (6 * dp));
            LinearLayout top = Ui.row(this);
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL); g.setColor(colors[i]);
            sw.setBackground(g);
            LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams((int) (8 * dp), (int) (8 * dp));
            swp.setMargins(0, 0, (int) (6 * dp), 0);
            top.addView(sw, swp);
            top.addView(Ui.text(this, names.get(i), 12, Ui.TEXT, false), Ui.weight(1));
            top.addView(Ui.text(this, String.format(Locale.US, "%.0f%%", vals.get(i) / total * 100), 12, Ui.SUB, true));
            r.addView(top);
            r.addView(Ui.space(this, 3));
            r.addView(Ui.bar(this, (float) (vals.get(i) / total), colors[i]));
            if (target != null) {
                r.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (target instanceof Store.Asset) { current = (Store.Asset) target; screen = 1; render(true); return; }
                        String k = (String) target;
                        if (groupBy == 1) { scopeKind = 2; scopePlatform = k; }
                        else { scopeKind = 1; scopeType = k.equals("Stocks") ? Store.STOCK : k.equals("Mutual funds") ? Store.MF : Store.ULIP; }
                        tab = 1; screen = 0;
                        render(true);
                    }
                });
                if (all() && groupBy == 1) {
                    r.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) { renamePlatform((String) target); return true; }
                    });
                }
            }
            legend.addView(r);
        }
        body.addView(legend, Ui.weight(1));
        c.addView(body);
        if (all()) c.addView(Ui.text(this, "Tap a group to open it" + (groupBy == 1 ? " · long-press to rename" : ""),
                11, Ui.SUB, false));
        return c;
    }

    private View moversRow(List<Store.Asset> scope, boolean animate) {
        List<Store.Asset> l = new ArrayList<>();
        for (Store.Asset a : scope) if (a.lastChangePct() != null) l.add(a);
        if (l.size() < 2) return null;
        Collections.sort(l, new Comparator<Store.Asset>() {
            @Override public int compare(Store.Asset a, Store.Asset b) { return Double.compare(Math.abs(b.lastChangePct()), Math.abs(a.lastChangePct())); }
        });
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = Ui.row(this);
        for (int i = 0; i < Math.min(8, l.size()); i++) {
            final Store.Asset a = l.get(i);
            Double p = a.lastChangePct();
            LinearLayout c = Ui.card(this);
            c.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{(Ui.colorFor(p) & 0x00FFFFFF) | 0x22000000, Ui.CARD});
            g.setCornerRadius(18 * dp);
            c.setBackground(g);
            c.addView(Ui.text(this, shortName(a), 13, Ui.TEXT, true));
            c.addView(Ui.text(this, a.type == Store.STOCK ? a.code.replace(".NS", " · NSE").replace(".BO", " · BSE") : typeName(a.type),
                    11, Ui.SUB, false));
            c.addView(Ui.space(this, 6));
            ChartView sp = new ChartView(this, true);
            sp.setData(a.prices, a.latest().getKey() - 30, Ui.colorFor(p));
            if (animate) sp.play();
            c.addView(sp, new LinearLayout.LayoutParams((int) (120 * dp), (int) (36 * dp)));
            c.addView(Ui.space(this, 6));
            c.addView(Ui.text(this, Ui.pct(p), 16, Ui.colorFor(p), true));
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { current = a; screen = 1; render(true); } });
            glow(c, a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (150 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, (int) (10 * dp), 0);
            r.addView(c, lp);
        }
        hs.addView(r);
        return hs;
    }

    private String insights(List<Store.Asset> scope) {
        List<Store.Asset> withData = new ArrayList<>();
        for (Store.Asset a : scope) if (a.latest() != null) withData.add(a);
        if (withData.isEmpty()) return null;
        double total = Store.value(scope);
        int up = 0, down = 0;
        Store.Asset best1m = null, worst1m = null, bestPl = null, worstPl = null, biggest = null;
        Double b1 = null, w1 = null, bp = null, wp = null;
        for (Store.Asset a : withData) {
            Double lc = a.lastChangePct();
            if (lc != null) { if (lc >= 0) up++; else down++; }
            Double m = a.changeOver(30);
            if (m != null) {
                if (b1 == null || m > b1) { b1 = m; best1m = a; }
                if (w1 == null || m < w1) { w1 = m; worst1m = a; }
            }
            if (a.invested > 0 && a.held()) {
                double pl = (a.value() / a.invested - 1) * 100;
                if (bp == null || pl > bp) { bp = pl; bestPl = a; }
                if (wp == null || pl < wp) { wp = pl; worstPl = a; }
            }
            if (a.held() && (biggest == null || a.value() > biggest.value())) biggest = a;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("• ").append(withData.size()).append(" holdings priced: ").append(up).append(" up, ").append(down)
                .append(" down on the latest day.\n");
        if (best1m != null) sb.append("• Best 1-month: ").append(shortName(best1m)).append(" ").append(Ui.pct(b1)).append("\n");
        if (worst1m != null && worst1m != best1m) sb.append("• Weakest 1-month: ").append(shortName(worst1m)).append(" ").append(Ui.pct(w1)).append("\n");
        if (bestPl != null) sb.append("• Biggest gain since purchase: ").append(shortName(bestPl)).append(" ").append(Ui.pct(bp)).append("\n");
        if (worstPl != null && worstPl != bestPl && wp < 0) sb.append("• Below cost: ").append(shortName(worstPl)).append(" ").append(Ui.pct(wp)).append("\n");
        if (biggest != null && total > 0) {
            double share = biggest.value() / total * 100;
            sb.append("• Largest holding: ").append(shortName(biggest)).append(String.format(Locale.US, " (%.0f%%)", share));
            if (share > 35) sb.append(" — a big share of this view");
            sb.append("\n");
        }
        if (all() && total > 0) {
            for (Map.Entry<String, List<Store.Asset>> g : groups(scope, groupBy == 1).entrySet()) {
                double gv = Store.value(g.getValue());
                double gc = Store.lastChange(g.getValue());
                sb.append("• ").append(g.getKey()).append(String.format(Locale.US, ": %.0f%% of portfolio", gv / total * 100));
                if (gc != 0) sb.append(", ").append(signed(gc)).append(" today");
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private View holdingCard(final Store.Asset a, double total) {
        LinearLayout c = Ui.card(this);
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { current = a; screen = 1; render(true); } });
        Map.Entry<Long, Double> l = a.latest();
        LinearLayout top = Ui.row(this);
        View dotV = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL); g.setColor(Ui.TYPE_COLORS[a.type]);
        dotV.setBackground(g);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (8 * dp), (int) (8 * dp));
        dl.setMargins(0, 0, (int) (10 * dp), 0);
        top.addView(dotV, dl);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Ui.text(this, shortName(a), 15, Ui.TEXT, true));
        String sub = a.platformOrDefault();
        if (a.units > 0 && l != null) sub += " · " + trimUnits(a.units) + (a.type == Store.STOCK ? " sh" : " u") + " @ " + priceFmt.format(l.getValue());
        else if (l != null) sub += " · " + priceFmt.format(l.getValue());
        if (a.error != null) sub += " · ⚠ update failed";
        left.addView(Ui.text(this, sub, 11, a.error != null ? Ui.RED : Ui.SUB, false));
        top.addView(left, Ui.weight(1));
        if (a.prices.size() >= 2) {
            ChartView sp = new ChartView(this, true);
            sp.setData(a.prices, l.getKey() - 30, Ui.colorFor(a.changeOver(30)));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams((int) (60 * dp), (int) (28 * dp));
            slp.setMargins((int) (6 * dp), 0, (int) (10 * dp), 0);
            top.addView(sp, slp);
        }
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(Ui.text(this, a.held() ? Ui.rupees(a.value()) : "—", 14, Ui.TEXT, true));
        Double lc = a.lastChangePct();
        TextView ch = Ui.text(this, Ui.pct(lc), 12, Ui.colorFor(lc), false);
        ch.setGravity(Gravity.END);
        right.addView(ch);
        top.addView(right);
        c.addView(top);
        if (a.held() && total > 0) {
            c.addView(Ui.space(this, 10));
            c.addView(Ui.bar(this, (float) (a.value() / total), Ui.TYPE_COLORS[a.type]));
            if (a.invested > 0) {
                double pl = a.value() - a.invested;
                LinearLayout r = Ui.row(this);
                r.setPadding(0, (int) (6 * dp), 0, 0);
                r.addView(Ui.text(this, String.format(Locale.US, "%.1f%% of view", a.value() / total * 100), 11, Ui.SUB, false), Ui.weight(1));
                r.addView(Ui.text(this, "P&L " + signed(pl) + "  " + Ui.pct(pl / a.invested * 100), 11, Ui.colorFor(pl), true));
                c.addView(r);
            }
        }
        glow(c, a);
        return c;
    }

    private String trimUnits(double u) {
        if (Ui.PRIVATE) return "•••";
        return u == Math.rint(u) ? String.valueOf((long) u) : String.format(Locale.US, "%.3f", u);
    }

    // ================================================================== asset detail
    private void showAsset(final Store.Asset a, boolean animate) {
        screen = 1;
        current = a;
        LinearLayout col = page(!animate);
        int idx = 0;
        TextView back = Ui.text(this, "‹  Back", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { slideBack(); } });
        col.addView(back);
        String profCcy = store.ccyOf(a.ownerOrDefault());
        if (a.type != Store.INDEX && !a.watch && !a.currency.isEmpty() && !a.currency.equals(profCcy)) {
            LinearLayout wc = Ui.card(this);
            wc.addView(Ui.text(this, "💱  Trades in " + a.currency + ", profile is in " + profCcy, 14, Ui.TEXT, true));
            wc.addView(Ui.text(this, "Amounts aren't converted, so this holding's value is added to " + a.ownerOrDefault()
                    + "'s " + profCcy + " totals as it is. Move it to a profile in " + a.currency + " (edit › Owner), or set this profile's currency in More.",
                    12, Ui.SUB, false));
            col.addView(wc);
        }

        Map.Entry<Long, Double> l = a.latest();
        LinearLayout h = Ui.heroCard(this, (Ui.TYPE_COLORS[a.type] & 0x00FFFFFF) | 0x40000000, Ui.CARD);
        h.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (18 * dp));
        String kind = typeName(a.type) + " · " + a.platformOrDefault();
        if (a.type == Store.STOCK) kind = a.code.replace(".NS", " · NSE").replace(".BO", " · BSE") + " · " + a.platformOrDefault();
        if (a.type == Store.INDEX) kind = "Index · " + a.code;
        else if (a.watch) kind = "Watchlist · " + (a.type == Store.STOCK ? a.code.replace(".NS", " · NSE").replace(".BO", " · BSE") : "Mutual fund");
        else if (store.siblings(a).size() > 1) kind = typeName(a.type) + " · " + store.siblings(a).size() + " platforms";
        h.addView(Ui.text(this, kind, 12, Ui.HERO_SUB, false));
        h.addView(Ui.text(this, a.name, 20, Ui.TEXT, true));
        h.addView(Ui.space(this, 8));
        final String cur = a.type == Store.INDEX ? "" : priceSym(a);
        TextView price = Ui.text(this, l == null ? "No price yet" : cur + priceFmt.format(l.getValue()), 30, Ui.TEXT, true);
        price.setSingleLine(true);
        h.addView(price, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (animate && l != null) {
            final TextView pt = price;
            final double pv = l.getValue();
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(900);
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator v) {
                    pt.setText(cur + priceFmt.format(pv * (Float) v.getAnimatedValue()));
                }
            });
            va.start();
        }
        LinearLayout pills = Ui.row(this);
        pills.setPadding(0, (int) (6 * dp), 0, 0);
        Double lc = a.lastChangePct();
        pills.addView(Ui.pill(this, (lc != null && lc < 0 ? "▼ " : "▲ ") + Ui.pct(lc) + " last", Ui.colorFor(lc)));
        if (l != null) {
            TextView d = Ui.text(this, "   " + LocalDate.ofEpochDay(l.getKey()).format(DF), 12, Ui.SUB, false);
            pills.addView(d);
        }
        h.addView(pills);
        if (a.error != null) h.addView(Ui.text(this, "Last update failed: " + a.error, 12, Ui.RED, false));
        add(col, h, idx++, animate);

        // Chart
        LinearLayout cc = Ui.card(this);
        cc.addView(chips(ASSET_MODES, assetMode, new Pick() { @Override public void on(int i) { assetMode = i; render(false); playCharts(); } }, false));
        cc.addView(Ui.space(this, 8));
        ChartView ch = new ChartView(this, false);
        ch.setTag("chart");
        ch.setFormatter(priceFmt);
        long from = l == null ? 0 : l.getKey() - RANGE_DAYS[assetRange];
        Double rc = rangeChange(a, from);
        List<ChartView.Series> s = new ArrayList<>();
        s.add(new ChartView.Series("", rc == null || rc >= 0 ? Ui.GREEN : Ui.RED, a.prices, from));
        ch.setSeries(s, assetMode == 2 ? ChartView.BARS : assetMode == 1 ? ChartView.LINE : ChartView.AREA, false, animate);
        cc.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (240 * dp)));
        cc.addView(Ui.space(this, 6));
        cc.addView(chips(RANGES, assetRange, new Pick() { @Override public void on(int i) { assetRange = i; render(false); playCharts(); } }, true));
        add(col, cc, idx++, animate);

        // 52-week range
        double[] rg52 = a.range52w();
        if (l != null && rg52 != null && rg52[1] > rg52[0]) {
            LinearLayout r52 = Ui.card(this);
            LinearLayout rt = Ui.row(this);
            rt.addView(Ui.text(this, "52-week range", 16, Ui.TEXT, true), Ui.weight(1));
            double fromHi = (l.getValue() / rg52[1] - 1) * 100, fromLo = (l.getValue() / rg52[0] - 1) * 100;
            rt.addView(Ui.text(this, String.format(Locale.US, "%.0f%% of range", (l.getValue() - rg52[0]) / (rg52[1] - rg52[0]) * 100), 12, Ui.SUB, false));
            r52.addView(rt);
            r52.addView(Ui.space(this, 10));
            r52.addView(rangeBar(rg52[0], rg52[1], l.getValue(), true));
            r52.addView(Ui.space(this, 6));
            stat(r52, "From 52W high", Ui.pct(fromHi), Ui.colorFor(fromHi));
            stat(r52, "From 52W low", Ui.pct(fromLo), Ui.colorFor(fromLo));
            if (a.prices.firstKey() > l.getKey() - 360)
                r52.addView(Ui.text(this, "Based on the " + a.prices.size() + " prices recorded so far", 11, Ui.SUB, false));
            add(col, r52, idx++, animate);
        }
        add(col, alertCard(a), idx++, animate);

        // Stats
        if (l != null) {
            LinearLayout sc = Ui.card(this);
            sc.addView(Ui.text(this, "Returns", 16, Ui.TEXT, true));
            LinearLayout grid = Ui.row(this);
            grid.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
            int[] days = {7, 30, 91, 365, 1095};
            String[] lab = {"1W", "1M", "3M", "1Y", "3Y"};
            for (int i = 0; i < days.length; i++) {
                LinearLayout m = new LinearLayout(this);
                m.setOrientation(LinearLayout.VERTICAL);
                m.addView(Ui.text(this, lab[i], 11, Ui.SUB, false));
                Double v = a.changeOver(days[i]);
                m.addView(Ui.text(this, Ui.pct(v), 13, Ui.colorFor(v), true));
                grid.addView(m, Ui.weight(1));
            }
            sc.addView(grid);
            double hi = -1, lo = Double.MAX_VALUE;
            for (double v : a.prices.tailMap(from, true).values()) { hi = Math.max(hi, v); lo = Math.min(lo, v); }
            stat(sc, RANGES[assetRange] + " high", priceFmt.format(hi), Ui.TEXT);
            stat(sc, RANGES[assetRange] + " low", priceFmt.format(lo), Ui.TEXT);
            double fh = (l.getValue() / hi - 1) * 100;
            stat(sc, "From " + RANGES[assetRange] + " high", Ui.pct(fh), Ui.colorFor(fh));
            add(col, sc, idx++, animate);
        }

        if (a.watch) {
            LinearLayout actions = Ui.row(this);
            TextView own = Ui.button(this, "Add to portfolio", true);
            own.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Store.Asset h = copyFor(a);
                    holdingDialog(h, true);
                }
            });
            TextView del = Ui.button(this, "Remove", false);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    store.assets.remove(a); store.save(); Alerts.noteTargets(MainActivity.this, store); slideBack();
                }
            });
            LinearLayout.LayoutParams p1 = Ui.weight(1.3f); p1.setMargins(0, 0, (int) (8 * dp), 0);
            actions.addView(own, p1);
            actions.addView(del, Ui.weight(1));
            add(col, actions, idx++, animate);
            col.addView(Ui.space(this, 14));
        } else if (a.type == Store.INDEX) {
            LinearLayout actions = Ui.row(this);
            TextView rf = Ui.button(this, "Refresh", true);
            rf.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { refresh(a); } });
            TextView del = Ui.button(this, "Remove index", false);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { store.indices.remove(a); store.save(); Alerts.noteTargets(MainActivity.this, store); slideBack(); }
            });
            LinearLayout.LayoutParams p1 = Ui.weight(1); p1.setMargins(0, 0, (int) (8 * dp), 0);
            actions.addView(rf, p1);
            actions.addView(del, Ui.weight(1));
            add(col, actions, idx++, animate);
            col.addView(Ui.space(this, 14));
        } else {
            final List<Store.Asset> lots = store.siblings(a);
            double allV = Store.value(store.visible(-1));
            LinearLayout hc = Ui.card(this);
            hc.addView(Ui.text(this, lots.size() > 1 ? "Your holding · " + lots.size() + " platforms" : "Your holding", 16, Ui.TEXT, true));
            double u = 0, v = 0, cost = 0;
            boolean allCost = true;
            for (Store.Asset x : lots) { u += x.units; v += x.value(); if (x.invested > 0) cost += x.invested; else allCost = false; }
            stat(hc, a.type == Store.STOCK ? "Total quantity" : "Total units", u > 0 ? trimUnits(u) : "Not set", Ui.TEXT);
            if (v > 0) stat(hc, "Current value", Ui.rupees(v), Ui.TEXT);
            if (allCost && cost > 0) {
                stat(hc, "Invested", Ui.rupees(cost), Ui.TEXT);
                if (u > 0) stat(hc, "Average cost", Ui.SYM + priceFmt.format(cost / u), Ui.TEXT);
                if (v > 0) stat(hc, "Profit / loss", signed(v - cost) + "  (" + Ui.pct((v - cost) / cost * 100) + ")", Ui.colorFor(v - cost));
            }
            Object[] hx = Store.xirr(lots);
            if (hx[0] != null) stat(hc, "XIRR (yearly return)", Ui.pct((Double) hx[0]), Ui.colorFor((Double) hx[0]));
            double rl = Store.realised(lots), dv = Store.dividends(lots);
            if (rl != 0) stat(hc, "Realised P&L", signed(rl), Ui.colorFor(rl));
            if (dv > 0) stat(hc, "Dividends received", Ui.rupees(dv), Ui.GREEN);
            if (v > 0 && allV > 0) {
                stat(hc, "Share of portfolio", String.format(Locale.US, "%.1f%%", v / allV * 100), Ui.TEXT);
                hc.addView(Ui.bar(this, (float) (v / allV), Ui.TYPE_COLORS[a.type]));
            }
            // One row per platform; tap to edit that lot
            hc.addView(Ui.space(this, 10));
            hc.addView(Ui.text(this, "By platform  (tap to edit)", 12, Ui.SUB, true));
            for (int i = 0; i < lots.size(); i++) {
                final Store.Asset x = lots.get(i);
                LinearLayout r = Ui.row(this);
                r.setPadding(0, (int) (9 * dp), 0, (int) (9 * dp));
                View dotV = new View(this);
                circle(dotV, Ui.PALETTE[i % Ui.PALETTE.length]);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (8 * dp), (int) (8 * dp));
                dl.setMargins(0, 0, (int) (8 * dp), 0);
                r.addView(dotV, dl);
                LinearLayout t = new LinearLayout(this);
                t.setOrientation(LinearLayout.VERTICAL);
                t.addView(Ui.text(this, x.platformOrDefault(), 14, Ui.TEXT, true));
                String d = (x.units > 0 ? trimUnits(x.units) + (a.type == Store.STOCK ? " sh" : " u") : "Units not set")
                        + (x.invested > 0 && x.units > 0 ? " · avg " + Ui.SYM + priceFmt.format(x.invested / x.units) : "");
                t.addView(Ui.text(this, d, 11, Ui.SUB, false));
                r.addView(t, Ui.weight(1));
                LinearLayout rt = new LinearLayout(this);
                rt.setOrientation(LinearLayout.VERTICAL);
                rt.setGravity(Gravity.END);
                rt.addView(Ui.text(this, x.held() ? Ui.rupees(x.value()) : "—", 13, Ui.TEXT, true));
                if (x.invested > 0 && x.held()) {
                    double pl = x.value() - x.invested;
                    rt.addView(Ui.text(this, signed(pl) + " " + Ui.pct(pl / x.invested * 100), 11, Ui.colorFor(pl), false));
                }
                r.addView(rt);
                r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View vv) { holdingDialog(x, false); } });
                r.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View vv) { removeAsset(x); return true; }
                });
                hc.addView(r);
            }
            TextView another = Ui.text(this, "+ Add on another platform", 13, Ui.ACCENT, true);
            another.setPadding(0, (int) (8 * dp), 0, 0);
            another.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) { holdingDialog(copyFor(a), true); }
            });
            hc.addView(another);
            add(col, hc, idx++, animate);

            add(col, ledgerCard(a), idx++, animate);

            if (a.type == Store.ULIP) add(col, ulipLinkCard(a), idx++, animate);
            LinearLayout actions = Ui.row(this);
            TextView addp = Ui.button(this, a.type == Store.ULIP ? (a.fetchCode().isEmpty() ? "Add NAV" : "Refresh NAV") : "Refresh price", true);
            addp.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) {
                    if (a.type == Store.ULIP && a.fetchCode().isEmpty()) addPriceManual(a, LocalDate.now()); else refresh(a);
                }
            });
            TextView del = Ui.button(this, "Remove", false);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) {
                    if (lots.size() == 1) { removeAsset(a); return; }
                    String[] names = new String[lots.size()];
                    for (int i = 0; i < lots.size(); i++) names[i] = "Remove lot on " + lots.get(i).platformOrDefault();
                    dialog().setTitle("Remove which?").setItems(names, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { removeAsset(lots.get(w)); }
                    }).show();
                }
            });
            LinearLayout.LayoutParams p1 = Ui.weight(1.3f); p1.setMargins(0, 0, (int) (8 * dp), 0);
            actions.addView(addp, p1);
            actions.addView(del, Ui.weight(1));
            add(col, actions, idx++, animate);
            col.addView(Ui.space(this, 14));
        }

        if (!a.prices.isEmpty()) {
            LinearLayout hl = Ui.card(this);
            hl.addView(Ui.text(this, "Price history" + (a.type == Store.ULIP ? "  (long-press to delete)" : ""), 16, Ui.TEXT, true));
            int n = 0;
            for (final Map.Entry<Long, Double> e : a.prices.descendingMap().entrySet()) {
                if (n++ >= 40) break;
                Map.Entry<Long, Double> prev = a.prices.lowerEntry(e.getKey());
                Double c2 = prev == null ? null : (e.getValue() / prev.getValue() - 1) * 100;
                LinearLayout r = Ui.row(this);
                r.setPadding(0, (int) (7 * dp), 0, (int) (7 * dp));
                r.addView(Ui.text(this, LocalDate.ofEpochDay(e.getKey()).format(DF), 13, Ui.SUB, false), Ui.weight(1.2f));
                r.addView(Ui.text(this, priceFmt.format(e.getValue()), 13, Ui.TEXT, false), Ui.weight(1));
                TextView pc = Ui.text(this, Ui.pct(c2), 12, Ui.colorFor(c2), false);
                pc.setGravity(Gravity.END);
                r.addView(pc, Ui.weight(0.8f));
                if (a.type == Store.ULIP) {
                    r.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            dialog().setTitle("Delete NAV for " + LocalDate.ofEpochDay(e.getKey()).format(DF) + "?")
                                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int w) { a.prices.remove(e.getKey()); store.save(); render(false); }
                                    }).setNegativeButton("Cancel", null).show();
                            return true;
                        }
                    });
                }
                hl.addView(r);
            }
            add(col, hl, idx++, animate);
        }
    }

    private Double rangeChange(Store.Asset a, long from) {
        Map.Entry<Long, Double> f = a.prices.ceilingEntry(from), l = a.latest();
        if (f == null || l == null || f.getKey().equals(l.getKey())) return null;
        return (l.getValue() / f.getValue() - 1) * 100;
    }

    private void removeAsset(final Store.Asset a) {
        dialog().setTitle("Remove " + shortName(a) + "?")
                .setMessage(a.type == Store.ULIP ? "Hides this fund. Its NAV history is kept so it can come back from More › Show/hide."
                        : "Deletes this holding and its price history from the app.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        List<Store.Asset> rest = store.siblings(a);
                        rest.remove(a);
                        if (a.type == Store.ULIP && rest.isEmpty()) a.hidden = true; else store.assets.remove(a);
                        store.save();
                        if (!rest.isEmpty()) { current = rest.get(0); screen = 1; } else screen = 0;
                        render(true);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ================================================================== add / edit
    private void addMenu() {
        dialog().setTitle("Add investment")
                .setItems(new String[]{"📈 NSE / BSE stock or ETF\nShares and ETFs · prices update live",
                                "🏦 Mutual fund\nSBI or any AMC · daily NAV",
                                "🛡️ SBI Life ULIP fund\nYour ULIP funds and units",
                                "💰 Other assets\nFD, RD, PPF, EPF, NPS, gold, property, cash…",
                                "🏠 Loan\nHome, car, personal… for net worth"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (which == 0) searchDialog(Store.STOCK, "");
                                else if (which == 1) searchDialog(Store.MF, "SBI ");
                                else if (which == 2) addUlip();
                                else addOtherMenu(which == 3 ? Store.OTHER : Store.LOAN);
                            }
                        }).show();
    }

    private void searchDialog(final int type, String prefill) {
        final EditText e = new EditText(this);
        e.setHint(type == Store.STOCK ? "Company or symbol, e.g. Reliance, SBIN, TCS"
                : type == Store.INDEX ? "e.g. Nifty Midcap, Nifty Pharma, BSE 500" : "Fund name, e.g. SBI Small Cap");
        e.setText(prefill);
        e.setSelection(e.getText().length());
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        dialog().setTitle(watchMode ? (type == Store.STOCK ? "Watch a stock" : "Watch a mutual fund")
                : type == Store.STOCK ? "Find stock" : type == Store.INDEX ? "Add index" : "Find mutual fund")
                .setView(padded(e))
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { runSearch(type, e.getText().toString().trim()); }
                }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { watchMode = false; }
                }).show();
    }

    private void runSearch(final int type, final String q) {
        if (q.isEmpty()) return;
        toast("Searching…");
        exec.execute(new Runnable() {
            @Override public void run() {
                List<String[]> res = new ArrayList<>();
                String err = null;
                try { res = type == Store.STOCK ? Net.stockSearch(q) : type == Store.INDEX ? Net.indexSearch(q) : Net.mfSearch(q); }
                catch (Exception ex) { err = ex.getMessage(); }
                final List<String[]> results = res;
                final String error = err;
                ui.post(new Runnable() { @Override public void run() { showResults(type, q, results, error); } });
            }
        });
    }

    private void showResults(final int type, final String q, List<String[]> res, String error) {
        final List<String[]> items = new ArrayList<>();
        if (type == Store.MF) {
            // Prefer Growth options, Direct first
            List<String[]> growth = new ArrayList<>(), other = new ArrayList<>();
            for (String[] r : res) (r[1].toLowerCase(Locale.ROOT).contains("growth") ? growth : other).add(r);
            items.addAll(growth); items.addAll(other);
            while (items.size() > 60) items.remove(items.size() - 1);
        } else if (type == Store.INDEX) {
            items.addAll(res);
        } else {
            items.addAll(res);
            String sym = q.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9&\\-]", "");
            if (!sym.isEmpty() && sym.length() <= 20) {
                items.add(new String[]{sym + ".NS", "Use symbol " + sym + " on NSE", "NSE"});
                items.add(new String[]{sym + ".BO", "Use symbol " + sym + " on BSE", "BSE"});
            }
        }
        if (items.isEmpty()) {
            dialog().setTitle("Nothing found").setMessage(error != null ? "Search failed: " + error + "\nCheck your internet connection."
                    : "Try a different name.").setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            String[] r = items.get(i);
            labels[i] = type == Store.STOCK ? r[1] + "\n" + r[0].replace(".NS", "").replace(".BO", "") + " · " + r[2]
                    : type == Store.INDEX ? r[1] + "  (" + r[2] + ")" : r[1];
        }
        dialog().setTitle(type == Store.STOCK ? "Pick a stock" : "Pick a fund (Growth options first)")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final String[] r = items.get(which);
                        if (type == Store.INDEX) {
                            if (store.find(Store.INDEX, r[0]) != null) { toast("Already on your Indexes tab"); return; }
                            Store.Asset ix = new Store.Asset(Store.INDEX, r[1]);
                            ix.code = r[0];
                            store.indices.add(ix);
                            store.save();
                            refresh(ix, false);
                            return;
                        }
                        if (watchMode) {
                            watchMode = false;
                            if (store.findWatch(type, r[0]) != null) { toast("Already on your watchlist"); return; }
                            Store.Asset wa = new Store.Asset(type, type == Store.STOCK && r[1].startsWith("Use symbol")
                                    ? r[0].replace(".NS", "").replace(".BO", "") : r[1]);
                            wa.code = r[0];
                            wa.watch = true;
                            store.assets.add(wa);
                            store.save();
                            current = wa; screen = 1;
                            refresh(wa, false);
                            return;
                        }
                        final List<Store.Asset> existing = store.findAll(type, r[0]);
                        if (!existing.isEmpty()) { askExisting(existing, type, r); return; }
                        Store.Asset a = new Store.Asset(type, type == Store.STOCK && r[1].startsWith("Use symbol")
                                ? r[0].replace(".NS", "").replace(".BO", "") : r[1]);
                        a.code = r[0];
                        holdingDialog(a, true);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** The instrument is already held somewhere: edit that, or add it on another platform. */
    private void askExisting(final List<Store.Asset> existing, final int type, final String[] r) {
        final List<String> opts = new ArrayList<>();
        opts.add("Add on another platform");
        for (Store.Asset e : existing) opts.add("Edit holding on " + e.platformOrDefault());
        dialog().setTitle(shortName(existing.get(0)) + " is already in your portfolio")
                .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            holdingDialog(copyFor(existing.get(0)), true);
                        } else {
                            Store.Asset e = existing.get(w - 1);
                            e.hidden = false;
                            holdingDialog(e, false);
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** New holding of the same instrument (shares price history) for another platform. */
    private Store.Asset copyFor(Store.Asset src) {
        Store.Asset a = new Store.Asset(src.type, src.name);
        a.code = src.code;
        a.prices.putAll(src.prices);
        return a;
    }

    private void addUlip() {
        final List<String> missing = new ArrayList<>();
        for (String n : Store.DEFAULT_ULIPS) {
            Store.Asset a = store.find(Store.ULIP, n);
            if (a == null || a.hidden) missing.add(n);
        }
        missing.add("Other fund name…");
        for (Store.Asset a : store.visible(Store.ULIP)) if (a.units > 0) missing.add("Another platform: " + a.name);
        dialog().setTitle("SBI Life ULIP fund")
                .setItems(missing.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String pick = missing.get(which);
                        if (pick.startsWith("Another platform: ")) {
                            Store.Asset src = store.find(Store.ULIP, pick.substring(18));
                            holdingDialog(copyFor(src), true);
                            return;
                        }
                        if (pick.equals("Other fund name…")) { customUlip(); return; }
                        Store.Asset a = store.find(Store.ULIP, missing.get(which));
                        if (a == null) { a = new Store.Asset(Store.ULIP, missing.get(which)); store.assets.add(a); }
                        a.hidden = false;
                        store.save();
                        holdingDialog(a, false);
                    }
                }).show();
    }

    private void customUlip() {
        final EditText e = new EditText(this);
        e.setHint("e.g. Bond Fund");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        dialog().setTitle("ULIP fund name").setMessage("Use the name as SBI Life writes it, so pasted NAVs match.")
                .setView(padded(e))
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = e.getText().toString().trim().replaceAll("(?i)^sbi life\\s*-?\\s*", "");
                        if (n.isEmpty()) return;
                        Store.Asset a = new Store.Asset(Store.ULIP, n);
                        store.assets.add(a);
                        store.save();
                        holdingDialog(a, false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Quantity/units, cost, purchase date and platform. isNew: fetch prices then add. */
    private void holdingDialog(final Store.Asset a, final boolean isNew) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final boolean stock = a.type == Store.STOCK;
        final boolean ledger = a.hasLedger() && !isNew;
        final EditText units, cost;
        final long[] bought = {isNew && a.type != Store.ULIP ? LocalDate.now().toEpochDay() : 0};
        if (ledger) {
            box.addView(Ui.text(this, "Units and cost come from this holding's transactions. "
                    + "Use Buy / Sell on the holding page to change them.", 13, Ui.SUB, false));
            units = null; cost = null;
        } else {
            box.addView(Ui.text(this, stock ? "Quantity (shares)" : "Units held", 12, Ui.SUB, false));
            units = numberField(stock ? "e.g. 25" : "e.g. 1234.567", a.units > 0 ? trimUnitsRaw(a.units) : null);
            box.addView(units);
            if (a.type != Store.ULIP) {
                box.addView(Ui.text(this, stock ? "Average buy price per share (" + Ui.SYM.trim() + ")" : "Amount invested (" + Ui.SYM.trim() + ") · blank = NAV on purchase date",
                        12, Ui.SUB, false));
                String cv = null;
                if (a.invested > 0) cv = stock && a.units > 0 ? String.format(Locale.US, "%.2f", a.invested / a.units)
                        : String.format(Locale.US, "%.0f", a.invested);
                cost = numberField(stock ? "e.g. 2450.50" : "e.g. 150000", cv);
                box.addView(cost);
                final TextView dateRow = Ui.text(this, "", 14, Ui.ACCENT, false);
                dateRow.setPadding(0, (int) (10 * dp), 0, (int) (6 * dp));
                final Runnable label = new Runnable() {
                    @Override public void run() {
                        dateRow.setText(bought[0] > 0 ? "Bought on " + LocalDate.ofEpochDay(bought[0]).format(DF) + "  · change"
                                : "+ Add purchase date (enables XIRR & history)");
                    }
                };
                label.run();
                dateRow.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pickDate(bought[0] > 0 ? LocalDate.ofEpochDay(bought[0]) : LocalDate.now().minusYears(1), new DateListener() {
                            @Override public void onDate(LocalDate d) { bought[0] = d.toEpochDay(); label.run(); }
                        });
                    }
                });
                box.addView(dateRow);
                box.addView(Ui.text(this, "Bought at different times? Save this, then add each purchase or SIP on the holding page.",
                        11, Ui.SUB, false));
            } else cost = null;
        }
        box.addView(Ui.text(this, "Platform / broker", 12, Ui.SUB, false));
        final AutoCompleteTextView plat = new AutoCompleteTextView(this);
        List<String> sugg = new ArrayList<>(store.platforms());
        for (String s : PLATFORM_SUGGESTIONS) if (indexOfIgnoreCase(sugg, s) < 0) sugg.add(s);
        sugg.remove("Unassigned");
        plat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, sugg));
        plat.setThreshold(0);
        plat.setHint("e.g. Zerodha, Groww, SBI MF");
        plat.setText(a.platform == null ? "" : a.platform);
        plat.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) { if (f) plat.showDropDown(); }
        });
        box.addView(plat);
        box.addView(Ui.text(this, "Owner (family member)", 12, Ui.SUB, false));
        final AutoCompleteTextView ownerF = ownerField(a.owner);
        box.addView(ownerF);

        dialog().setTitle(shortName(a))
                .setView(padded(box))
                .setPositiveButton(isNew ? "Add" : "Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (!ledger) {
                            Double u = num(units.getText().toString());
                            double qty = u == null ? 0 : Math.max(0, u);
                            double inv = 0;
                            if (cost != null) {
                                Double c = num(cost.getText().toString());
                                inv = c == null || c <= 0 ? 0 : stock ? c * qty : c;
                            }
                            if (bought[0] > 0 && qty > 0 && a.type != Store.ULIP) {
                                // record as a dated purchase so XIRR and history work
                                Ledger.Txn t = new Ledger.Txn();
                                t.day = bought[0]; t.kind = Ledger.BUY; t.units = qty;
                                t.amount = inv; t.price = inv > 0 ? inv / qty : 0;   // 0 = fill from price history
                                t.note = isNew ? "" : "Opening balance";
                                a.txns.clear();
                                a.txns.add(t);
                                Map.Entry<Long, Double> px = a.prices.floorEntry(t.day);
                                if (t.price <= 0 && px != null) { t.price = px.getValue(); t.amount = qty * t.price; }
                                a.recompute();
                            } else {
                                a.units = qty;
                                a.invested = inv;
                            }
                        }
                        a.platform = plat.getText().toString().trim();
                        a.owner = ownerValue(ownerF.getText().toString());
                        for (Store.Asset o : store.siblings(a)) {
                            if (o != a && o.platformOrDefault().equalsIgnoreCase(a.platformOrDefault())) {
                                toast("Already held on " + a.platformOrDefault() + ". Saved as a separate lot.");
                                break;
                            }
                        }
                        if (isNew) {
                            store.assets.add(a); store.save(); current = a; screen = 1;
                            if (a.type == Store.ULIP) render(true); else refresh(a);
                        }
                        else { store.save(); render(false); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private String trimUnitsRaw(double u) {
        return u == Math.rint(u) ? String.valueOf((long) u) : String.format(Locale.US, "%.3f", u);
    }

    // ================================================================== transactions & SIPs
    /** Pick which platform's lot a transaction belongs to (when held on several). */
    private void pickLot(final Store.Asset a, final Pick then) {
        final List<Store.Asset> lots = store.siblings(a);
        if (lots.size() == 1) { then.on(0); return; }
        String[] names = new String[lots.size()];
        for (int i = 0; i < lots.size(); i++) names[i] = lots.get(i).platformOrDefault();
        dialog().setTitle("Which platform?").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { then.on(w); }
        }).show();
    }

    /** Add (t == null) or edit a transaction on lot `lot`. */
    private void txnDialog(final Store.Asset lot, final int kind, final Ledger.Txn existing) {
        final Ledger.Txn t = existing != null ? existing.copy() : new Ledger.Txn();
        if (existing == null) { t.kind = kind; t.day = LocalDate.now().toEpochDay(); }
        final boolean div = t.kind == Ledger.DIV;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView dateRow = Ui.text(this, "", 14, Ui.ACCENT, false);
        dateRow.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        box.addView(dateRow);
        final EditText units = div ? null : numberField(lot.type == Store.STOCK ? "Quantity" : "Units", t.units > 0 ? trimUnitsRaw(t.units) : null);
        final EditText price = div ? null : numberField("Price per unit (" + Ui.SYM.trim() + ")", t.price > 0 ? String.format(Locale.US, "%.4f", t.price) : null);
        final EditText amount = numberField(div ? "Dividend received (" + Ui.SYM.trim() + ")" : "Total amount (" + Ui.SYM.trim() + ", optional)",
                t.amount > 0 ? String.format(Locale.US, "%.2f", t.amount) : null);
        final Runnable fillPrice = new Runnable() {
            @Override public void run() {
                dateRow.setText(LocalDate.ofEpochDay(t.day).format(DF) + "  · change date");
                if (price != null && price.getText().length() == 0) {
                    Map.Entry<Long, Double> px = lot.prices.floorEntry(t.day);
                    if (px != null && px.getKey() >= t.day - 7) price.setText(String.format(Locale.US, "%.4f", px.getValue()));
                }
            }
        };
        fillPrice.run();
        dateRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickDate(LocalDate.ofEpochDay(t.day), new DateListener() {
                    @Override public void onDate(LocalDate d) {
                        t.day = d.toEpochDay();
                        if (price != null) price.setText("");
                        fillPrice.run();
                    }
                });
            }
        });
        if (!div) {
            box.addView(Ui.text(this, lot.type == Store.STOCK ? "Quantity (shares)" : "Units", 12, Ui.SUB, false));
            box.addView(units);
            box.addView(Ui.text(this, "Price per unit (" + Ui.SYM.trim() + ") · filled from history when available", 12, Ui.SUB, false));
            box.addView(price);
            box.addView(Ui.text(this, t.kind == Ledger.BUY ? "Total paid incl. charges (" + Ui.SYM.trim() + ") · blank = units × price"
                    : "Total received after charges (" + Ui.SYM.trim() + ") · blank = units × price", 12, Ui.SUB, false));
        } else {
            box.addView(Ui.text(this, "Amount", 12, Ui.SUB, false));
        }
        box.addView(amount);
        String title = Ledger.KIND[t.kind] + " · " + shortName(lot) + (store.siblings(lot).size() > 1 ? " · " + lot.platformOrDefault() : "");
        AlertDialog.Builder bld = dialog().setTitle(title).setView(padded(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (div) {
                            Double am = num(amount.getText().toString());
                            if (am == null || am <= 0) { toast("Enter the dividend amount"); return; }
                            t.amount = am; t.units = 0; t.price = 0;
                        } else {
                            Double u = num(units.getText().toString()), p = num(price.getText().toString()), am = num(amount.getText().toString());
                            if (u == null || u <= 0) { toast("Enter units"); return; }
                            t.units = u;
                            if (am != null && am > 0) { t.amount = am; t.price = p != null && p > 0 ? p : am / u; }
                            else if (p != null && p > 0) { t.price = p; t.amount = p * u; }
                            else { t.price = 0; t.amount = 0; }   // filled when prices arrive
                            if (t.kind == Ledger.SELL) {
                                List<Ledger.Txn> test = new ArrayList<>(lot.txns);
                                if (existing != null) test.remove(existing);
                                double held = Ledger.unitsOn(test, t.day) + (lot.hasLedger() ? 0 : lot.units);
                                if (u > held + 1e-6) { toast("Only " + trimUnitsRaw(held) + " units held on that date"); return; }
                            }
                        }
                        if (!lot.hasLedger() && lot.units > 0 && t.kind != Ledger.DIV) convertOpening(lot, t.day);
                        if (existing != null) lot.txns.remove(existing);
                        lot.txns.add(t);
                        Ledger.sort(lot.txns);
                        lot.recompute();
                        lot.applySips(LocalDate.now().toEpochDay());
                        store.save();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null);
        if (existing != null) bld.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                lot.txns.remove(existing);
                lot.recompute();
                if (lot.txns.isEmpty()) { lot.units = 0; lot.invested = 0; }
                store.save();
                render(false);
            }
        });
        bld.show();
    }

    /** A holding entered without dates gets an "opening balance" purchase before its first dated transaction. */
    private void convertOpening(Store.Asset lot, long before) {
        Ledger.Txn o = new Ledger.Txn();
        o.day = Math.min(before - 1, LocalDate.now().toEpochDay() - 365);
        o.kind = Ledger.BUY; o.units = lot.units;
        o.amount = lot.invested > 0 ? lot.invested : 0;
        o.price = o.amount > 0 ? o.amount / o.units : 0;
        o.note = "Opening balance (date estimated)";
        lot.txns.add(o);
        Ledger.sort(lot.txns);
        toast("Your earlier units were added as an opening balance. Edit its date for accurate XIRR.");
    }

    private void sipDialog(final Store.Asset lot, final Ledger.Sip existing) {
        final Ledger.Sip sp = existing != null ? existing : new Ledger.Sip();
        if (existing == null) sp.start = LocalDate.now().withDayOfMonth(1).toEpochDay();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "Monthly amount (" + Ui.SYM.trim() + ")", 12, Ui.SUB, false));
        final EditText amt = numberField("e.g. 10000", sp.amount > 0 ? String.format(Locale.US, "%.0f", sp.amount) : null);
        box.addView(amt);
        box.addView(Ui.text(this, "Day of month", 12, Ui.SUB, false));
        final EditText dom = numberField("1–28", String.valueOf(sp.dom));
        dom.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(dom);
        final TextView startRow = Ui.text(this, "", 14, Ui.ACCENT, false);
        startRow.setPadding(0, (int) (10 * dp), 0, (int) (6 * dp));
        final Runnable lab = new Runnable() {
            @Override public void run() { startRow.setText("Started " + LocalDate.ofEpochDay(sp.start).format(DF) + "  · change"); }
        };
        lab.run();
        startRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickDate(LocalDate.ofEpochDay(sp.start), new DateListener() {
                    @Override public void onDate(LocalDate d) { sp.start = d.toEpochDay(); lab.run(); }
                });
            }
        });
        box.addView(startRow);
        box.addView(Ui.text(this, (existing == null
                ? "Past instalments since the start date are added automatically at that day's "
                : "Changes apply to future instalments. Each instalment uses that day's ")
                + (lot.type == Store.STOCK ? "price (whole shares)." : "NAV."), 11, Ui.SUB, false));
        AlertDialog.Builder bld = dialog().setTitle((existing == null ? "New SIP · " : "SIP · ") + shortName(lot)).setView(padded(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Double a = num(amt.getText().toString()), dm = num(dom.getText().toString());
                        if (a == null || a <= 0) { toast("Enter the SIP amount"); return; }
                        sp.amount = a;
                        sp.dom = dm == null ? 5 : (int) Math.max(1, Math.min(28, dm));
                        if (existing == null) {
                            if (!lot.hasLedger() && lot.units > 0) convertOpening(lot, sp.start);
                            lot.sips.add(sp);
                        }
                        int n = lot.applySips(LocalDate.now().toEpochDay());
                        lot.recompute();
                        store.save();
                        Alerts.schedule(MainActivity.this);
                        if (n > 0) toast(n + " instalment" + (n == 1 ? "" : "s") + " added");
                        render(false);
                    }
                }).setNegativeButton("Cancel", null);
        if (existing != null) bld.setNeutralButton(sp.end > 0 ? "Delete" : "Stop SIP", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (sp.end > 0) lot.sips.remove(sp); else sp.end = LocalDate.now().toEpochDay();
                store.save(); render(false);
            }
        });
        bld.show();
    }

    /** Transactions + SIPs + returns for a holding (all its platforms). */
    private View ledgerCard(final Store.Asset a) {
        final List<Store.Asset> lots = store.siblings(a);
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Transactions", 16, Ui.TEXT, true));
        c.addView(Ui.space(this, 8));
        LinearLayout btns = Ui.row(this);
        String[] labels = a.type == Store.ULIP ? new String[]{"+ Buy", "+ Sell"} : new String[]{"+ Buy", "+ Sell", "+ Dividend"};
        for (int i = 0; i < labels.length; i++) {
            final int kind = i;
            TextView b = Ui.button(this, labels[i], i == 0);
            b.setTextSize(13);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pickLot(a, new Pick() { @Override public void on(int idx) { txnDialog(lots.get(idx), kind, null); } });
                }
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i < labels.length - 1) lp.setMargins(0, 0, (int) (8 * dp), 0);
            btns.addView(b, lp);
        }
        c.addView(btns);

        // SIPs
        c.addView(Ui.space(this, 12));
        LinearLayout sh = Ui.row(this);
        sh.addView(Ui.text(this, "SIPs", 14, Ui.TEXT, true), Ui.weight(1));
        TextView addSip = Ui.text(this, "+ Add SIP", 13, Ui.ACCENT, true);
        addSip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickLot(a, new Pick() { @Override public void on(int idx) { sipDialog(lots.get(idx), null); } });
            }
        });
        sh.addView(addSip);
        c.addView(sh);
        boolean anySip = false;
        for (final Store.Asset lot : lots) for (final Ledger.Sip sp : lot.sips) {
            anySip = true;
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (7 * dp), 0, (int) (7 * dp));
            r.addView(Ui.text(this, Ui.rupees(sp.amount) + " on day " + sp.dom + (lots.size() > 1 ? " · " + lot.platformOrDefault() : ""),
                    13, Ui.TEXT, false), Ui.weight(1));
            r.addView(Ui.pill(this, sp.end > 0 ? "Stopped" : "Active", sp.end > 0 ? Ui.SUB : Ui.GREEN));
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sipDialog(lot, sp); } });
            c.addView(r);
        }
        if (!anySip) c.addView(Ui.text(this, "Add a SIP and each month's instalment is recorded automatically.", 12, Ui.SUB, false));

        // History
        List<Object[]> rows = new ArrayList<>();   // [lot, txn]
        for (Store.Asset lot : lots) for (Ledger.Txn t : lot.txns) rows.add(new Object[]{lot, t});
        Collections.sort(rows, new Comparator<Object[]>() {
            @Override public int compare(Object[] x, Object[] y) { return Long.compare(((Ledger.Txn) y[1]).day, ((Ledger.Txn) x[1]).day); }
        });
        c.addView(Ui.space(this, 12));
        c.addView(Ui.text(this, rows.isEmpty() ? "No transactions yet" : "History (" + rows.size() + ") · tap to edit", 14, Ui.TEXT, true));
        if (!a.hasLedger() && a.units > 0 && rows.isEmpty()) {
            TextView conv = Ui.text(this, "+ Add purchase date to your current units (enables XIRR)", 13, Ui.ACCENT, false);
            conv.setPadding(0, (int) (8 * dp), 0, 0);
            conv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { holdingDialog(a, false); } });
            c.addView(conv);
        }
        int n = 0;
        for (Object[] row : rows) {
            if (n++ >= 60) break;
            final Store.Asset lot = (Store.Asset) row[0];
            final Ledger.Txn t = (Ledger.Txn) row[1];
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
            int col = t.kind == Ledger.BUY ? Ui.ACCENT : t.kind == Ledger.SELL ? Ui.RED : Ui.GREEN;
            View dotV = new View(this);
            circle(dotV, col);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (7 * dp), (int) (7 * dp));
            dl.setMargins(0, 0, (int) (10 * dp), 0);
            r.addView(dotV, dl);
            LinearLayout l1 = new LinearLayout(this);
            l1.setOrientation(LinearLayout.VERTICAL);
            String head = (t.sip ? "SIP" : Ledger.KIND[t.kind]) + " · " + LocalDate.ofEpochDay(t.day).format(DF);
            l1.addView(Ui.text(this, head, 13, Ui.TEXT, false));
            String sub = t.kind == Ledger.DIV ? "Dividend" : trimUnits(t.units) + " @ " + (t.price > 0 ? priceFmt.format(t.price) : "price pending");
            if (lots.size() > 1) sub += " · " + lot.platformOrDefault();
            if (t.note != null && t.note.startsWith("Opening")) sub += " · opening";
            l1.addView(Ui.text(this, sub, 11, Ui.SUB, false));
            r.addView(l1, Ui.weight(1));
            r.addView(Ui.text(this, (t.kind == Ledger.BUY ? "−" : "+") + Ui.rupees(t.amount), 13, col, true));
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { txnDialog(lot, t.kind, t); } });
            c.addView(r);
        }
        return c;
    }

    // ================================================================== target allocation
    private static final String[] TGT_KEYS = {"tgt_stock", "tgt_mf", "tgt_ulip", "tgt_other"};
    private static final int[] TGT_TYPES = {Store.STOCK, Store.MF, Store.ULIP, Store.OTHER};

    private float target(int i) { return Alerts.prefs(this).getFloat(TGT_KEYS[i], 0); }

    private View targetCard() {
        List<Store.Asset> all = store.visible(-1);
        double total = Store.value(all);
        if (total <= 0) return null;
        LinearLayout c = Ui.card(this);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, "Target mix", 16, Ui.TEXT, true), Ui.weight(1));
        TextView set = Ui.text(this, target(0) + target(1) + target(2) + target(3) > 0 ? "Edit" : "Set", 14, Ui.ACCENT, true);
        set.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { targetMixDialog(); } });
        top.addView(set);
        c.addView(top);
        float sum = target(0) + target(1) + target(2) + target(3);
        if (sum <= 0) {
            c.addView(Ui.text(this, "Set how you'd like to split your money between stocks, mutual funds and ULIP, "
                    + "and see how far off you are and what to add or trim.", 13, Ui.SUB, false));
            return c;
        }
        StringBuilder hints = new StringBuilder();
        for (int i = 0; i < TGT_KEYS.length; i++) {
            double v = Store.value(store.visible(TGT_TYPES[i]));
            double actual = v / total * 100, tg = target(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
            LinearLayout h = Ui.row(this);
            h.addView(Ui.text(this, typeName(TGT_TYPES[i]), 13, Ui.TEXT, false), Ui.weight(1));
            double diff = actual - tg;
            h.addView(Ui.text(this, String.format(Locale.US, "%.0f%% now · %.0f%% target", actual, tg), 12,
                    Math.abs(diff) < 3 ? Ui.SUB : diff > 0 ? Ui.RED : Ui.ACCENT, true));
            r.addView(h);
            r.addView(Ui.space(this, 4));
            r.addView(targetBar((float) (actual / 100), (float) (tg / 100), Ui.TYPE_COLORS[TGT_TYPES[i]]));
            c.addView(r);
            double need = tg / 100 * total - v;
            if (Math.abs(diff) >= 3)
                hints.append("• ").append(need > 0 ? "Add " : "Trim ").append(Ui.rupees(Math.abs(need)))
                        .append(need > 0 ? " to " : " from ").append(typeName(TGT_TYPES[i])).append("\n");
        }
        c.addView(Ui.space(this, 6));
        c.addView(Ui.text(this, hints.length() == 0 ? "✓ Within 3% of your target on every type." : hints.toString().trim()
                + "\nTip: directing new money to the short side avoids selling.", 12, Ui.SUB, false));
        return c;
    }

    private View targetBar(final float actual, final float tgt, final int color) {
        View v = new View(this) {
            final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight();
                pt.setColor(Ui.EMPTY);
                cv.drawRoundRect(0, h / 2 - 3 * dp, w, h / 2 + 3 * dp, 3 * dp, 3 * dp, pt);
                pt.setColor(color);
                cv.drawRoundRect(0, h / 2 - 3 * dp, Math.max(6 * dp, w * Math.min(1, actual)), h / 2 + 3 * dp, 3 * dp, 3 * dp, pt);
                pt.setColor(Ui.TEXT);
                float x = w * Math.min(1, tgt);
                cv.drawRect(x - 1.2f * dp, 0, x + 1.2f * dp, h, pt);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (14 * dp)));
        return v;
    }

    private void targetMixDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText[] f = new EditText[TGT_KEYS.length];
        for (int i = 0; i < TGT_KEYS.length; i++) {
            box.addView(Ui.text(this, typeName(TGT_TYPES[i]) + " (%)", 12, Ui.SUB, false));
            f[i] = numberField("e.g. 30", target(i) > 0 ? String.format(Locale.US, "%.0f", target(i)) : null);
            box.addView(f[i]);
        }
        box.addView(Ui.text(this, "Should add up to 100%. Leave all blank to turn this off.", 11, Ui.SUB, false));
        dialog().setTitle("Target mix").setView(padded(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        float sum = 0;
                        float[] v = new float[TGT_KEYS.length];
                        for (int i = 0; i < TGT_KEYS.length; i++) { Double x = num(f[i].getText().toString()); v[i] = x == null ? 0 : (float) Math.max(0, x); sum += v[i]; }
                        if (sum > 0 && Math.abs(sum - 100) > 0.5) {
                            for (int i = 0; i < TGT_KEYS.length; i++) v[i] = v[i] / sum * 100;   // scale to 100
                            toast("Scaled to add up to 100%");
                        }
                        android.content.SharedPreferences.Editor ed = Alerts.prefs(MainActivity.this).edit();
                        for (int i = 0; i < TGT_KEYS.length; i++) ed.putFloat(TGT_KEYS[i], v[i]);
                        ed.apply();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void addPriceManual(final Store.Asset a, final LocalDate date) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView dateBtn = Ui.text(this, "Date: " + date.format(DF) + "  (tap to change)", 14, Ui.ACCENT, false);
        dateBtn.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        final EditText e = numberField("NAV, e.g. 210.0123", null);
        box.addView(dateBtn);
        box.addView(e);
        final AlertDialog d = dialog().setTitle("Add NAV · " + a.name).setView(padded(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface di, int w) {
                        Double v = num(e.getText().toString());
                        if (v == null || v <= 0) { toast("Enter a valid NAV"); return; }
                        for (Store.Asset x : store.siblings(a)) x.prices.put(date.toEpochDay(), v);
                        store.save();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).create();
        dateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickDate(date, new DateListener() { @Override public void onDate(LocalDate nd) { d.dismiss(); addPriceManual(a, nd); } });
            }
        });
        d.show();
    }

    private interface DateListener { void onDate(LocalDate d); }

    private void pickDate(LocalDate initial, final DateListener l) {
        new DatePickerDialog(this, dialogTheme(), new DatePickerDialog.OnDateSetListener() {
            @Override public void onDateSet(DatePicker v, int y, int m, int d) { l.onDate(LocalDate.of(y, m + 1, d)); }
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
    }

    private void renamePlatform(final String from) {
        final EditText e = new EditText(this);
        e.setText(from.equals("Unassigned") ? "" : from);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        dialog().setTitle("Rename platform").setMessage("Applies to every holding on \"" + from + "\".")
                .setView(padded(e))
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String to = e.getText().toString().trim();
                        if (to.isEmpty()) return;
                        store.renamePlatform(from, to);
                        if (scopeKind == 2) scopePlatform = to;
                        store.save();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ================================================================== refresh
    private void refresh(Store.Asset only) { refresh(only, false); }

    /** Refresh one instrument (all platforms holding it), or everything online when only == null. */
    private void refresh(final Store.Asset only, final boolean silent) {
        if (refreshing) return;
        final List<Store.Asset> todo = Updater.online(store, only);
        if (todo.isEmpty()) {
            if (pull != null) pull.stop();
            if (silent) { autoEmptyAt = System.currentTimeMillis(); return; }   // auto refresh: nothing online, wait a full interval
            if (only == null) toast("Nothing to update online. ULIP NAVs are pasted.");
            render(true);
            return;
        }
        refreshing = true;
        RefreshRing.busy = true; RefreshRing.done = 0; RefreshRing.total = todo.size();
        if (!silent && only == null && pull != null) pull.start();
        refreshStatus = "Updating " + todo.size() + " prices…";
        render(false);
        final java.util.Map<String, String[]> symbols = Updater.symbols(todo);
        final Store owner = store;
        exec.execute(new Runnable() {
            @Override public void run() {
                final java.util.Map<String, Updater.Result> results = Updater.fetch(symbols, new Updater.Progress() {
                    @Override public void on(final int done, final int total) {
                        RefreshRing.done = done; RefreshRing.total = total;
                        ui.post(new Runnable() { @Override public void run() { refreshStatus = "Updating prices… " + done + "/" + total; } });
                    }
                });
                ui.post(new Runnable() {
                    @Override public void run() {
                        refreshing = false;
                        refreshStatus = null;
                        RefreshRing.busy = false;
                        RefreshRing.finishedAt = System.currentTimeMillis();
                        if (pull != null) pull.stop();
                        if (owner != store || isFinishing()) return;   // data was reloaded meanwhile
                        Map<Store.Asset, Double> before = new java.util.HashMap<>();
                        for (Store.Asset a : todo) { Map.Entry<Long, Double> l = a.latest(); if (l != null) before.put(a, l.getValue()); }
                        int[] r = Updater.apply(store, todo, results);
                        moved.clear();
                        for (Store.Asset a : todo) {
                            Map.Entry<Long, Double> l = a.latest();
                            Double b = before.get(a);
                            if (l != null && b != null && Math.abs(l.getValue() - b) > 1e-9) moved.put(a, l.getValue() > b ? 1 : -1);
                        }
                        movedAt = System.currentTimeMillis();
                        try { Alerts.check(MainActivity.this, store); } catch (Throwable t) { Crash.save(MainActivity.this, t); }
                        if (r[1] > 0 && !silent) toast(r[0] + " updated, " + r[1] + " failed. Check internet or symbol.");
                        if (!locked && !hiddenForRecents) render(only != null);
                    }
                });
            }
        });
    }

    // ================================================================== imports (CAS PDF, broker CSV/XLSX)
    private static final int REQ_FILE = 41;
    private List<Cas.Item> importItems = new ArrayList<>();
    private final Map<Cas.Item, TreeMap<Long, Double>> importPrices = new java.util.HashMap<>();
    private String importTitle = "", importPlatform = "", importStatus;
    private boolean importMatching;
    private android.net.Uri pendingIncoming;

    private void pickImportFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/csv", "text/comma-separated-values",
                "text/plain", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
                "application/octet-stream"});
        authInProgress = true;   // returning from the picker shouldn't re-lock
        try { startActivityForResult(i, REQ_FILE); }
        catch (Exception e) { authInProgress = false; toast("No file picker available"); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE) {
            authInProgress = false;
            if (res == RESULT_OK && data != null && data.getData() != null) onTreePicked(data.getData());
            return;
        }
        if (req == REQ_AVATAR) {
            authInProgress = false;
            if (res == RESULT_OK && data != null && data.getData() != null && avatarFor != null) {
                try { Avatars.setPhoto(this, avatarFor, data.getData()); render(false); }
                catch (Exception e) { toast("Couldn't use that picture"); }
            }
            return;
        }
        if (req != REQ_FILE) return;
        authInProgress = false;
        if (res == RESULT_OK && data != null && data.getData() != null) importUri(data.getData());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    /** A file shared to the app (e.g. a CAS PDF from Gmail) or opened with it. */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        android.net.Uri u = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(intent.getAction())) u = intent.getData();
        if (u == null) return;
        intent.setAction(Intent.ACTION_MAIN);   // handle once
        if (locked) { pendingIncoming = u; return; }
        importUri(u);
    }

    private void importUri(final android.net.Uri uri) {
        toast("Reading file…");
        exec.execute(new Runnable() {
            @Override public void run() {
                byte[] data = null;
                String err = null;
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > 30_000_000) throw new Exception("File is too large"); }
                    in.close();
                    data = o.toByteArray();
                } catch (Throwable t) { err = t.getMessage(); }
                final byte[] fd = data;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (fd == null) { toast("Couldn't read the file" + (fe != null ? ": " + fe : "")); return; }
                        importBytes(fd, null);
                    }
                });
            }
        });
    }

    private void importBytes(final byte[] data, final String password) {
        if (Backup.isBackup(data)) { askRestorePassword(data); return; }
        boolean pdf = data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
        if (!pdf) {
            // CSV / XLSX, or a text CAS
            exec.execute(new Runnable() {
                @Override public void run() {
                    Tabular.Result r = null;
                    List<Cas.Item> cas = null;
                    String err = null;
                    try {
                        boolean zip = data.length > 1 && data[0] == 'P' && data[1] == 'K';
                        if (!zip) {
                            List<String> lines = java.util.Arrays.asList(new String(data, java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n"));
                            if (Cas.looksLikeCas(lines)) cas = Cas.parse(lines);
                        }
                        if (cas == null) r = Tabular.interpret(Tabular.rows(data));
                    } catch (Throwable t) { err = t.getMessage(); Crash.save(MainActivity.this, t); }
                    final Tabular.Result fr = r;
                    final List<Cas.Item> fc = cas;
                    final String fe = err;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (fc != null) { startImport(fc, "Mutual fund statement", ""); return; }
                            if (fr == null || fr.error != null) {
                                dialog().setTitle("Couldn't import this file")
                                        .setMessage((fr != null ? fr.error : fe) + "\n\nSupported: CAMS/KFintech statement PDF, "
                                                + "broker holdings or tradebook/order history as CSV or Excel.")
                                        .setPositiveButton("OK", null).show();
                                return;
                            }
                            startImport(fr.items, fr.kind, fr.platform);
                        }
                    });
                }
            });
            return;
        }
        toast("Opening PDF…");
        exec.execute(new Runnable() {
            @Override public void run() {
                List<String> lines = null;
                boolean needPw = false, wrongPw = false;
                String err = null;
                try { lines = Pdf.extractLines(data, password == null ? "" : password); }
                catch (Pdf.PasswordException e) { needPw = true; wrongPw = password != null; }
                catch (Throwable t) { err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); Crash.save(MainActivity.this, t); }
                final List<String> fl = lines;
                final boolean np = needPw, wp = wrongPw;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (np) { askPdfPassword(data, wp); return; }
                        if (fl == null) { toast("Couldn't read this PDF: " + fe); return; }
                        if (Cas.looksLikeCas(fl)) { startImport(Cas.parse(fl), "Mutual fund statement", ""); return; }
                        // not a CAS: maybe an SBI Life NAV statement — try the ULIP NAV reader
                        StringBuilder sb = new StringBuilder();
                        for (String s : fl) sb.append(s).append('\n');
                        showPaste(sb.toString());
                    }
                });
            }
        });
    }

    private void askPdfPassword(final byte[] data, boolean wrong) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setHint("e.g. ABCDE1234F");
        dialog().setTitle(wrong ? "Wrong password, try again" : "PDF password")
                .setMessage("CAMS / KFintech statements use the password you chose when requesting them, usually your PAN in capitals.")
                .setView(padded(e))
                .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { importBytes(data, e.getText().toString().trim()); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void startImport(List<Cas.Item> items, String title, String platform) {
        importItems = items;
        importPrices.clear();
        importTitle = title;
        importPlatform = platform;
        importStatus = null;
        if (items.isEmpty()) {
            dialog().setTitle("Nothing to import").setMessage("No holdings were found in this file.").setPositiveButton("OK", null).show();
            return;
        }
        screen = 3;
        render(true);
        matchAll();
    }

    /** Find the mfapi scheme code / exchange symbol for every item (background). */
    private void matchAll() {
        importMatching = true;
        final List<Cas.Item> items = new ArrayList<>(importItems);
        exec.execute(new Runnable() {
            @Override public void run() {
                int i = 0;
                for (final Cas.Item it : items) {
                    i++;
                    final int n = i;
                    ui.post(new Runnable() { @Override public void run() { importStatus = "Matching " + n + " of " + items.size() + "…"; refreshImportStatus(); } });
                    if (!it.code.isEmpty() || !it.include) continue;
                    try {
                        TreeMap<Long, Double> px = new TreeMap<>();
                        if (it.type == Store.MF) matchFund(it, px); else matchStock(it, px);
                        if (!px.isEmpty()) synchronized (importPrices) { importPrices.put(it, px); }
                    } catch (Throwable ignored) { }
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        importMatching = false;
                        importStatus = null;
                        if (screen == 3) render(false);
                    }
                });
            }
        });
    }

    private void refreshImportStatus() {
        View v = scroller == null ? null : scroller.findViewWithTag("importStatus");
        if (v instanceof TextView && importStatus != null) ((TextView) v).setText(importStatus);
    }

    private void matchFund(Cas.Item it, TreeMap<Long, Double> px) throws Exception {
        List<String[]> res = Net.mfSearch(Cas.searchQuery(it.name));
        if (res.isEmpty()) res = Net.mfSearch(it.name.split("\\s+")[0] + " " + (it.name.split("\\s+").length > 1 ? it.name.split("\\s+")[1] : ""));
        final Cas.Item ref = it;
        Collections.sort(res, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return Double.compare(Cas.score(ref.name, b[1]), Cas.score(ref.name, a[1])); }
        });
        // confirm with the ISIN when the statement has one
        for (int k = 0; k < Math.min(4, res.size()); k++) {
            TreeMap<Long, Double> p = new TreeMap<>();
            String[] info = Net.mfInfo(res.get(k)[0], p);
            boolean isinMatch = !it.isin.isEmpty() && (it.isin.equalsIgnoreCase(info[1]) || it.isin.equalsIgnoreCase(info[2]));
            if (isinMatch || (it.isin.isEmpty() && k == 0) || (k == 0 && info[1].isEmpty() && info[2].isEmpty())) {
                it.code = res.get(k)[0];
                it.matchName = info[0] != null ? info[0] : res.get(k)[1];
                px.putAll(p);
                if (isinMatch || it.isin.isEmpty()) return;
            }
        }
        if (it.code.isEmpty() && !res.isEmpty() && Cas.score(it.name, res.get(0)[1]) > 0.75) {
            it.code = res.get(0)[0];
            it.matchName = res.get(0)[1];
        }
    }

    private void matchStock(Cas.Item it, TreeMap<Long, Double> px) throws Exception {
        String sym = it.symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.matches("[A-Z0-9&\\-]{1,20}")) {
            for (String suf : new String[]{".NS", ".BO"}) {
                try {
                    TreeMap<Long, Double> p = new TreeMap<>();
                    String nm = Net.stockHistory(sym + suf, p);
                    it.code = sym + suf;
                    it.matchName = nm != null ? nm : sym;
                    px.putAll(p);
                    return;
                } catch (Exception ignored) { }
            }
        }
        for (String q : new String[]{it.isin, it.name}) {
            if (q == null || q.isEmpty()) continue;
            List<String[]> r = Net.stockSearch(q);
            if (!r.isEmpty()) {
                String[] best = r.get(0);
                for (String[] x : r) if (x[0].endsWith(".NS")) { best = x; break; }
                it.code = best[0];
                it.matchName = best[1];
                try { Net.stockHistory(it.code, px); } catch (Exception ignored) { }
                return;
            }
        }
    }

    private void showImport(boolean animate) {
        LinearLayout col = page(!animate);
        TextView back = Ui.text(this, "‹  Cancel import", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { screen = 0; render(true); } });
        col.addView(back);
        col.addView(Ui.text(this, "Import · " + importTitle, 22, Ui.TEXT, true));
        int matched = 0, inc = 0;
        for (Cas.Item it : importItems) { if (!it.code.isEmpty()) matched++; if (it.include) inc++; }
        TextView st = Ui.text(this, importMatching ? (importStatus == null ? "Matching…" : importStatus)
                : importItems.size() + " found · " + matched + " matched · " + inc + " selected", 13, Ui.SUB, false);
        st.setTag("importStatus");
        col.addView(st);
        col.addView(Ui.space(this, 12));

        LinearLayout pc = Ui.card(this);
        pc.addView(Ui.text(this, "Platform for these holdings", 13, Ui.SUB, true));
        final AutoCompleteTextView plat = new AutoCompleteTextView(this);
        List<String> sugg = new ArrayList<>(store.platforms());
        for (String s : PLATFORM_SUGGESTIONS) if (indexOfIgnoreCase(sugg, s) < 0) sugg.add(s);
        sugg.remove("Unassigned");
        plat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, sugg));
        plat.setThreshold(0);
        plat.setHint(importTitle.startsWith("Mutual fund statement") ? "e.g. SBI MF (direct), Groww, Kuvera" : "e.g. Zerodha, Groww");
        plat.setText(importPlatform);
        plat.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { importPlatform = s.toString().trim(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        pc.addView(plat);
        pc.addView(Ui.text(this, "Holdings already on this platform are updated (replaced), others are added.", 11, Ui.SUB, false));
        col.addView(pc);

        for (final Cas.Item it : importItems) {
            LinearLayout c = Ui.card(this);
            LinearLayout top = Ui.row(this);
            final android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setChecked(it.include);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { it.include = on; }
            });
            top.addView(cb);
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(Ui.text(this, it.name, 14, Ui.TEXT, true));
            String d = trimUnitsRaw(it.units) + (it.type == Store.STOCK ? " shares" : " units")
                    + (it.cost > 0 ? " · cost " + Ui.rupees(it.cost) : "")
                    + (it.historyOk ? " · " + it.txns.size() + " transactions ✓ XIRR" : "");
            if (!it.folio.isEmpty()) d += " · folio " + it.folio;
            tx.addView(Ui.text(this, d, 12, Ui.SUB, false));
            String m = !it.code.isEmpty() ? "→ " + it.matchName + (it.type == Store.STOCK ? " (" + it.code + ")" : "")
                    : importMatching ? "Matching…" : "⚠ Not matched · tap to choose";
            tx.addView(Ui.text(this, m, 12, it.code.isEmpty() ? (importMatching ? Ui.SUB : Ui.RED) : Ui.GREEN, false));
            if (!it.note.isEmpty()) tx.addView(Ui.text(this, it.note, 11, Ui.SUB, false));
            top.addView(tx, Ui.weight(1));
            c.addView(top);
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!importMatching) manualMatch(it); } });
            col.addView(c);
        }
        TextView go = Ui.button(this, importMatching ? "Matching…" : "Import selected", true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (importMatching) { toast("Still matching, one moment"); return; }
                applyImport();
            }
        });
        col.addView(go);
        col.addView(Ui.space(this, 8));
        col.addView(Ui.text(this, "Tip: tap a holding to change its match. Untick anything you don't want to import.", 12, Ui.SUB, false));
    }

    private void manualMatch(final Cas.Item it) {
        final EditText e = new EditText(this);
        e.setText(it.type == Store.MF ? Cas.searchQuery(it.name) : (it.symbol.isEmpty() ? it.name : it.symbol));
        e.setSelection(e.getText().length());
        dialog().setTitle("Find " + (it.type == Store.MF ? "fund" : "stock")).setView(padded(e))
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String q = e.getText().toString().trim();
                        exec.execute(new Runnable() {
                            @Override public void run() {
                                List<String[]> r = new ArrayList<>();
                                try { r = it.type == Store.MF ? Net.mfSearch(q) : Net.stockSearch(q); } catch (Exception ignored) { }
                                final List<String[]> fr = r;
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (fr.isEmpty()) { toast("Nothing found"); return; }
                                        String[] labels = new String[Math.min(50, fr.size())];
                                        for (int i = 0; i < labels.length; i++) labels[i] = fr.get(i)[1] + (it.type == Store.STOCK ? " (" + fr.get(i)[0] + ")" : "");
                                        dialog().setTitle("Pick a match").setItems(labels, new DialogInterface.OnClickListener() {
                                            @Override public void onClick(DialogInterface d2, int k) {
                                                it.code = fr.get(k)[0];
                                                it.matchName = fr.get(k)[1];
                                                it.include = true;
                                                synchronized (importPrices) { importPrices.remove(it); }
                                                render(false);
                                            }
                                        }).show();
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void applyImport() {
        String platform = importPlatform == null ? "" : importPlatform.trim();
        int added = 0, updated = 0, skipped = 0;
        for (Cas.Item it : importItems) {
            if (!it.include) continue;
            if (it.code.isEmpty()) { skipped++; continue; }
            Store.Asset target = null;
            for (Store.Asset a : store.assets)
                if (!a.watch && a.type == it.type && a.code.equalsIgnoreCase(it.code) && a.platform.trim().equalsIgnoreCase(platform)
                        && (selectedPerson() == null || a.ownerOrDefault().equalsIgnoreCase(selectedPerson()))) target = a;
            if (target == null) {
                target = new Store.Asset(it.type, it.matchName.isEmpty() ? it.name : it.matchName);
                target.code = it.code;
                target.platform = platform;
                if (selectedPerson() != null && !selectedPerson().equals("Me")) target.owner = selectedPerson();
                store.assets.add(target);
                added++;
            } else { target.hidden = false; updated++; }
            synchronized (importPrices) { if (importPrices.containsKey(it)) target.prices.putAll(importPrices.get(it)); }
            if (target.prices.isEmpty() && it.nav > 0 && it.navDay > 0) target.prices.put(it.navDay, it.nav);
            target.txns.clear();
            target.sips.clear();
            if (it.historyOk && !it.txns.isEmpty()) {
                for (Ledger.Txn t : it.txns) target.txns.add(t.copy());
                target.recompute();
            } else {
                target.units = it.units;
                target.invested = it.cost;
                target.recompute();
            }
        }
        store.save();
        screen = 0; tab = 1; scopeKind = 0;
        importItems = new ArrayList<>();
        toast("Imported: " + added + " added, " + updated + " updated" + (skipped > 0 ? ", " + skipped + " skipped (not matched)" : ""));
        refresh(null, true);
        render(true);
    }

    // ================================================================== SBI Life policies
    private void showPolicies(boolean animate) {
        LinearLayout col = page(!animate);
        TextView back = Ui.text(this, "‹  Back", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { slideBack(); } });
        col.addView(back);
        LinearLayout h = Ui.row(this);
        h.addView(Ui.text(this, "Insurance policies", 22, Ui.TEXT, true), Ui.weight(1));
        TextView add = Ui.text(this, "+ Add", 15, Ui.ACCENT, true);
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { policyDialog(null); } });
        h.addView(add);
        col.addView(h);
        col.addView(Ui.text(this, "Premium reminders arrive before each due date (needs notifications).", 12, Ui.SUB, false));
        col.addView(Ui.space(this, 12));
        if (store.policies.isEmpty()) {
            LinearLayout e = Ui.card(this);
            e.addView(Ui.text(this, "Add your SBI Life policy: premium, how often it's due, next due date, maturity date and sum assured.",
                    14, Ui.SUB, false));
            TextView b = Ui.button(this, "+ Add policy", true);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { policyDialog(null); } });
            e.addView(Ui.space(this, 10));
            e.addView(b);
            add(col, e, 0, animate);
            return;
        }
        int i = 0;
        for (Store.Policy p : store.policies) add(col, policyCard(p, true), i++, animate);
    }

    private View policyCard(final Store.Policy p, boolean full) {
        LinearLayout c = Ui.card(this);
        long today = LocalDate.now().toEpochDay();
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, "🛡  " + p.name, 16, Ui.TEXT, true), Ui.weight(1));
        if (p.nextDue > 0) {
            long left = p.nextDue - today;
            top.addView(Ui.pill(this, left <= 0 ? "Due today" : left == 1 ? "Due tomorrow" : "Due in " + left + " days",
                    left <= 7 ? Ui.RED : left <= 30 ? Ui.ACCENT : Ui.SUB));
        }
        c.addView(top);
        if (!p.number.isEmpty() && full) c.addView(Ui.text(this, "Policy " + (Ui.PRIVATE ? "••••" + p.number.substring(Math.max(0, p.number.length() - 3)) : p.number), 12, Ui.SUB, false));
        String[] fq = {"", "Monthly", "", "Quarterly", "", "", "Half-yearly", "", "", "", "", "", "Yearly"};
        stat(c, "Premium", Ui.rupees(p.premium) + " " + (p.freq < fq.length ? fq[p.freq].toLowerCase(Locale.ROOT) : ""), Ui.TEXT);
        if (p.nextDue > 0) stat(c, "Next due", LocalDate.ofEpochDay(p.nextDue).format(DF), Ui.TEXT);
        if (full) {
            if (p.payUntil > 0) stat(c, "Premiums until", LocalDate.ofEpochDay(p.payUntil).format(DF), Ui.TEXT);
            if (p.maturity > 0) {
                long y = (p.maturity - today) / 365;
                stat(c, "Maturity", LocalDate.ofEpochDay(p.maturity).format(DF) + (p.maturity > today ? "  (" + y + " yrs)" : ""), Ui.TEXT);
            }
            if (p.sumAssured > 0) stat(c, "Sum assured", Ui.rupees(p.sumAssured), Ui.TEXT);
            stat(c, "Yearly premium", Ui.rupees(p.yearlyPremium()), Ui.SUB);
            double fv = Store.value(store.visible(Store.ULIP));
            if (fv > 0) stat(c, "ULIP fund value (all funds)", Ui.rupees(fv), Ui.TEXT);
            if (!p.notes.isEmpty()) c.addView(Ui.text(this, p.notes, 12, Ui.SUB, false));
        }
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { policyDialog(p); } });
        return c;
    }

    private void policyDialog(final Store.Policy existing) {
        final Store.Policy p = existing != null ? existing : new Store.Policy();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText name = new EditText(this);
        name.setHint("Name, e.g. SBI Life Smart Wealth Builder");
        name.setText(p.name);
        box.addView(name);
        final EditText number = new EditText(this);
        number.setHint("Policy number (optional)");
        number.setText(p.number);
        box.addView(number);
        box.addView(Ui.text(this, "Premium (" + Ui.SYM.trim() + ")", 12, Ui.SUB, false));
        final EditText prem = numberField("e.g. 100000", p.premium > 0 ? String.format(Locale.US, "%.0f", p.premium) : null);
        box.addView(prem);
        final android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        final int[] fv = {1, 3, 6, 12};
        String[] fl = {"Monthly", "Qtrly", "Half-yr", "Yearly"};
        for (int i = 0; i < 4; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(fl[i]);
            rb.setTextSize(12);
            rb.setId(1000 + i);
            rg.addView(rb);
            if (fv[i] == p.freq) rb.setChecked(true);
        }
        box.addView(rg);
        final long[] dates = {p.nextDue, p.payUntil, p.maturity};
        final String[] dl = {"Next premium due", "Premiums paid until (optional)", "Maturity date (optional)"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            final TextView row = Ui.text(this, "", 14, Ui.ACCENT, false);
            row.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
            final Runnable lab = new Runnable() {
                @Override public void run() { row.setText(dl[k] + ": " + (dates[k] > 0 ? LocalDate.ofEpochDay(dates[k]).format(DF) : "tap to set")); }
            };
            lab.run();
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pickDate(dates[k] > 0 ? LocalDate.ofEpochDay(dates[k]) : LocalDate.now().plusMonths(k == 0 ? 1 : 120), new DateListener() {
                        @Override public void onDate(LocalDate d) { dates[k] = d.toEpochDay(); lab.run(); }
                    });
                }
            });
            box.addView(row);
        }
        box.addView(Ui.text(this, "Sum assured (" + Ui.SYM.trim() + ", optional)", 12, Ui.SUB, false));
        final EditText sa = numberField("e.g. 1000000", p.sumAssured > 0 ? String.format(Locale.US, "%.0f", p.sumAssured) : null);
        box.addView(sa);
        box.addView(Ui.text(this, "Remind me this many days before", 12, Ui.SUB, false));
        final EditText rd = numberField("7", String.valueOf(p.remindDays));
        rd.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(rd);
        final EditText notes = new EditText(this);
        notes.setHint("Notes (optional)");
        notes.setText(p.notes);
        box.addView(notes);
        ScrollView sv = new ScrollView(this);
        sv.addView(padded(box));
        AlertDialog.Builder b = dialog().setTitle(existing == null ? "Add policy" : "Edit policy").setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Double pr = num(prem.getText().toString()), s = num(sa.getText().toString()), r = num(rd.getText().toString());
                        p.name = name.getText().toString().trim().isEmpty() ? "SBI Life ULIP" : name.getText().toString().trim();
                        p.number = number.getText().toString().trim();
                        p.premium = pr == null ? 0 : pr;
                        p.sumAssured = s == null ? 0 : s;
                        p.remindDays = r == null ? 7 : (int) Math.max(1, Math.min(60, r));
                        int sel = rg.getCheckedRadioButtonId() - 1000;
                        p.freq = sel >= 0 && sel < 4 ? fv[sel] : 12;
                        p.nextDue = dates[0]; p.payUntil = dates[1]; p.maturity = dates[2];
                        p.notes = notes.getText().toString().trim();
                        p.roll(LocalDate.now().toEpochDay());
                        if (existing == null) store.policies.add(p);
                        store.save();
                        Alerts.notePolicies(MainActivity.this, store);
                        if (p.nextDue > 0) { askNotificationPermission(); Alerts.schedule(MainActivity.this); }
                        render(false);
                    }
                }).setNegativeButton("Cancel", null);
        if (existing != null) b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                store.policies.remove(existing); store.save(); Alerts.notePolicies(MainActivity.this, store); Alerts.schedule(MainActivity.this); render(false);
            }
        });
        b.show();
    }

    /** Dashboard: premiums due in the next 45 days. */
    private View upcomingPremiumCard() {
        long today = LocalDate.now().toEpochDay();
        for (Store.Policy p : store.policies) p.roll(today);
        Store.Policy soon = null;
        for (Store.Policy p : store.policies)
            if (p.nextDue > 0 && p.nextDue - today <= 45 && (soon == null || p.nextDue < soon.nextDue)) soon = p;
        if (soon == null) return null;
        View v = policyCard(soon, false);
        return v;
    }

    // ================================================================== Phase 4: family, other assets, net worth
    private String ownerPref() { return Alerts.prefs(this).getString("owner", null); }

    private void reloadStore() {
        store = new Store(this);
        store.ownerFilter = ownerPref();
        store.accrueAll();
    }

    /** "Everyone · Me · …" chips; null when there's only one person. */
    private View ownerChips() {
        final List<String> owners = store.owners();
        if (owners.size() <= 1) return null;
        // each chip: label, filter (null = everyone in the default currency, "@SGD" = everyone in SGD, or a name), person
        final List<String> labels = new ArrayList<>(), filters = new ArrayList<>(), people = new ArrayList<>();
        boolean mixed = store.mixed();
        List<String> ccys = store.currencies();
        if (!mixed) { labels.add("👪 Everyone"); filters.add(null); people.add(null); }
        else for (int k = 0; k < ccys.size(); k++) {
            labels.add(Ui.flagOf(ccys.get(k)) + " Everyone · " + Ui.symbolOf(ccys.get(k)).trim());
            filters.add(k == 0 ? null : "@" + ccys.get(k));
            people.add(null);
        }
        for (String o : owners) {
            labels.add(mixed ? o + " · " + Ui.symbolOf(store.ccyOf(o)).trim() : o);
            filters.add(o);
            people.add(o);
        }
        int sel = 0;
        for (int i = 0; i < filters.size(); i++) {
            String f = filters.get(i);
            if (f == null ? store.ownerFilter == null : f.equalsIgnoreCase(store.ownerFilter)) { sel = i; break; }
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        View chipRow = chips(labels.toArray(new String[0]), sel, new Pick() {
            @Override public void on(int i) {
                store.ownerFilter = filters.get(i);
                Alerts.prefs(MainActivity.this).edit().putString("owner", store.ownerFilter).apply();
                render(false);
            }
        }, false);
        // a small round picture before each person's name
        List<TextView> tvs = new ArrayList<>();
        collectTexts(chipRow, tvs);
        for (int i = 0; i < tvs.size() && i < people.size(); i++) {
            final String who = people.get(i);
            if (who == null) continue;
            android.graphics.drawable.Drawable d = Avatars.drawable(this, who, (int) (20 * dp));
            tvs.get(i).setCompoundDrawablesRelative(d, null, null, null);
            tvs.get(i).setCompoundDrawablePadding((int) (6 * dp));
            tvs.get(i).setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { avatarDialog(who); return true; }
            });
        }
        hs.addView(chipRow);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * dp);
        hs.setLayoutParams(lp);
        return hs;
    }

    /** The person currently selected, or null for an "Everyone" view. */
    private String selectedPerson() {
        return store.ownerFilter == null || store.ownerFilter.startsWith("@") ? null : store.ownerFilter;
    }

    private AutoCompleteTextView ownerField(String current) {
        final AutoCompleteTextView f = new AutoCompleteTextView(this);
        f.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, store.owners()));
        f.setThreshold(0);
        f.setHint("Me, or a family member's name");
        f.setText(current == null || current.isEmpty() ? (selectedPerson() != null && !selectedPerson().equals("Me") ? selectedPerson() : "") : current);
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { if (has) f.showDropDown(); }
        });
        return f;
    }

    private static String ownerValue(String s) {
        String t = s == null ? "" : s.trim();
        return t.equalsIgnoreCase("me") ? "" : t;
    }

    private void addOtherMenu(final int type) {
        final String[] kinds = type == Store.LOAN ? Store.LOAN_KINDS : Store.OTHER_KINDS;
        String[] shown = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) shown[i] = kindIcon(kinds[i]) + " " + kinds[i];
        dialog().setTitle(type == Store.LOAN ? "Add a loan" : "Add other asset")
                .setItems(shown, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { otherDialog(null, type, kinds[w]); }
                }).show();
    }

    /** Create or edit a fixed deposit, PPF, gold, property, cash… or a loan. */
    private void otherDialog(final Store.Asset existing, final int type, final String kind) {
        final Store.Asset a = existing != null ? existing : new Store.Asset(type, kind);
        if (existing == null) { a.kind = kind; a.units = 1; }
        final boolean rd = "Recurring deposit".equals(a.kind);
        final boolean fd = "Fixed deposit".equals(a.kind) || rd, loan = type == Store.LOAN;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText name = new EditText(this);
        name.setHint(rd ? "e.g. SBI RD 2028" : fd ? "e.g. SBI FD 2027" : loan ? "e.g. SBI Home Loan" : "e.g. " + a.kind);
        name.setText(existing == null ? "" : a.name);
        box.addView(name);
        box.addView(Ui.text(this, loan ? "Lender" : "Bank / institution (optional)", 12, Ui.SUB, false));
        final EditText inst = new EditText(this);
        inst.setText(a.platform);
        box.addView(inst);
        final EditText amount, value, rate, invested;
        final long[] dates = {a.start > 0 ? a.start : LocalDate.now().toEpochDay(), a.maturity};
        if (fd) {
            box.addView(Ui.text(this, rd ? "Monthly instalment (" + Ui.SYM.trim() + ")" : "Deposit amount (" + Ui.SYM.trim() + ")", 12, Ui.SUB, false));
            amount = numberField(rd ? "e.g. 10000" : "e.g. 500000", a.principal > 0 ? String.format(Locale.US, "%.0f", a.principal) : null);
            box.addView(amount);
            value = null; invested = null;
        } else {
            box.addView(Ui.text(this, loan ? "Outstanding amount today (" + Ui.SYM.trim() + ")" : "Current value / balance today (" + Ui.SYM.trim() + ")", 12, Ui.SUB, false));
            value = numberField("e.g. 250000", a.baseValue > 0 ? String.format(Locale.US, "%.0f", a.accruedOn(LocalDate.now().toEpochDay())) : null);
            box.addView(value);
            amount = null;
            if (!loan) {
                box.addView(Ui.text(this, "Amount you've put in (" + Ui.SYM.trim() + ", optional, for gain)", 12, Ui.SUB, false));
                invested = numberField("e.g. 200000", a.invested > 0 ? String.format(Locale.US, "%.0f", a.invested) : null);
                box.addView(invested);
            } else invested = null;
        }
        box.addView(Ui.text(this, fd ? "Interest rate (% a year)" : loan ? "Interest rate (% a year, optional)"
                : "Expected growth (% a year, optional; e.g. PPF 7.1)", 12, Ui.SUB, false));
        rate = numberField("e.g. 7.1", a.rate > 0 ? String.valueOf(a.rate) : null);
        box.addView(rate);
        if (fd || loan) {
            final String[] lab = {fd ? "Start date" : "Loan start", fd ? "Maturity date" : "Loan ends"};
            for (int i = (fd ? 0 : 1); i < 2; i++) {
                final int k = i;
                final TextView row = Ui.text(this, "", 14, Ui.ACCENT, false);
                row.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
                final Runnable l = new Runnable() {
                    @Override public void run() { row.setText(lab[k] + ": " + (dates[k] > 0 ? LocalDate.ofEpochDay(dates[k]).format(DF) : "tap to set")); }
                };
                l.run();
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pickDate(dates[k] > 0 ? LocalDate.ofEpochDay(dates[k]) : LocalDate.now().plusYears(1), new DateListener() {
                            @Override public void onDate(LocalDate dd) { dates[k] = dd.toEpochDay(); l.run(); }
                        });
                    }
                });
                box.addView(row);
            }
        }
        box.addView(Ui.text(this, "Owner", 12, Ui.SUB, false));
        final AutoCompleteTextView own = ownerField(a.owner);
        box.addView(own);
        ScrollView sv = new ScrollView(this);
        sv.addView(padded(box));
        AlertDialog.Builder b = dialog().setTitle((existing == null ? "Add " : "Edit ") + a.kind).setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        long today = LocalDate.now().toEpochDay();
                        String nm = name.getText().toString().trim();
                        a.name = nm.isEmpty() ? a.kind : nm;
                        a.platform = inst.getText().toString().trim();
                        a.owner = ownerValue(own.getText().toString());
                        Double r = num(rate.getText().toString());
                        a.rate = r == null ? 0 : r;
                        if (fd) {
                            Double p = num(amount.getText().toString());
                            if (p == null || p <= 0) { toast(rd ? "Enter the monthly instalment" : "Enter the deposit amount"); return; }
                            if (rd && dates[1] <= dates[0]) { toast("Set the maturity date"); return; }
                            a.principal = p; a.invested = p; a.start = dates[0]; a.maturity = dates[1];
                            a.prices.clear();
                        } else {
                            Double v = num(value.getText().toString());
                            if (v == null || v < 0) { toast("Enter the amount"); return; }
                            a.baseValue = v; a.baseDay = today;
                            if (invested != null) { Double iv = num(invested.getText().toString()); a.invested = iv == null ? 0 : iv; }
                            if (loan) a.maturity = dates[1];
                            for (Long k : new ArrayList<>(a.prices.tailMap(today, false).keySet())) a.prices.remove(k);
                        }
                        a.units = 1;
                        a.accrue(today);
                        if (existing == null) store.assets.add(a);
                        store.save();
                        current = a; screen = 1;
                        render(true);
                    }
                }).setNegativeButton("Cancel", null);
        if (existing != null) b.setNeutralButton("Remove", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { store.assets.remove(existing); store.save(); screen = 0; render(true); }
        });
        b.show();
    }

    /** Detail page for other assets and loans. */
    private void showManual(final Store.Asset a, boolean animate) {
        LinearLayout col = page(!animate);
        int idx = 0;
        TextView back = Ui.text(this, "‹  Back", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { slideBack(); } });
        col.addView(back);
        boolean loan = a.type == Store.LOAN, fd = "Fixed deposit".equals(a.kind), rd = a.isRd();
        long today = LocalDate.now().toEpochDay();
        double v = a.value();
        LinearLayout h = Ui.heroCard(this, (Ui.TYPE_COLORS[a.type] & 0x00FFFFFF) | 0x40000000, Ui.CARD);
        h.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (18 * dp));
        h.addView(Ui.text(this, a.kind + (a.platform.isEmpty() ? "" : " · " + a.platform) + (a.owner.isEmpty() ? "" : " · " + a.owner), 12, Ui.HERO_SUB, false));
        h.addView(Ui.text(this, a.name, 20, Ui.TEXT, true));
        h.addView(Ui.space(this, 8));
        TextView val = Ui.text(this, (loan ? "−" : "") + Ui.rupees(v), 30, loan ? Ui.RED : Ui.TEXT, true);
        val.setSingleLine(true);
        h.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.addView(Ui.text(this, loan ? "Outstanding" : fd || rd ? "Value today (interest accrued)" : "Current value", 12, Ui.SUB, false));
        add(col, h, idx++, animate);

        if (a.prices.size() >= 2) {
            LinearLayout cc = Ui.card(this);
            ChartView ch = new ChartView(this, false);
            ch.setTag("chart");
            ch.setFormatter(rupeeFmt);
            List<ChartView.Series> s = new ArrayList<>();
            s.add(new ChartView.Series("", loan ? Ui.RED : Ui.TYPE_COLORS[Store.OTHER], a.prices, 0));
            ch.setSeries(s, ChartView.AREA, false, animate);
            cc.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * dp)));
            add(col, cc, idx++, animate);
        }
        LinearLayout dc = Ui.card(this);
        dc.addView(Ui.text(this, "Details", 16, Ui.TEXT, true));
        if (rd) {
            int total = a.maturity > a.start ? a.rdMonths(a.maturity - 1) : 0, paid = a.rdMonths(Math.min(today, a.maturity > 0 ? a.maturity - 1 : today));
            stat(dc, "Monthly instalment", Ui.rupees(a.principal), Ui.TEXT);
            stat(dc, "Instalments", paid + " of " + total + " paid", Ui.TEXT);
            dc.addView(Ui.bar(this, total > 0 ? (float) paid / total : 0, Ui.TYPE_COLORS[Store.OTHER]));
            stat(dc, "Deposited so far", Ui.rupees(a.principal * paid), Ui.TEXT);
            stat(dc, "Interest rate", String.format(Locale.US, "%.2f%% a year", a.rate), Ui.TEXT);
            if (a.start > 0) stat(dc, "Started", LocalDate.ofEpochDay(a.start).format(DF), Ui.TEXT);
            if (a.maturity > 0) {
                stat(dc, "Matures", LocalDate.ofEpochDay(a.maturity).format(DF) + (a.maturity > today ? "  (" + (a.maturity - today) + " days)" : "  ✓"), Ui.TEXT);
                stat(dc, "Total you'll deposit", Ui.rupees(a.principal * total), Ui.TEXT);
                stat(dc, "Maturity value", Ui.rupees(a.accruedOn(a.maturity)), Ui.GREEN);
            }
            stat(dc, "Interest earned so far", signed(v - a.principal * paid), Ui.GREEN);
            dc.addView(Ui.text(this, "Each instalment earns interest compounded quarterly from the month it's paid, before tax. "
                    + "Assumes every instalment is paid on time.", 11, Ui.SUB, false));
        } else if (fd) {
            stat(dc, "Deposited", Ui.rupees(a.principal), Ui.TEXT);
            stat(dc, "Interest rate", String.format(Locale.US, "%.2f%% a year", a.rate), Ui.TEXT);
            if (a.start > 0) stat(dc, "Started", LocalDate.ofEpochDay(a.start).format(DF), Ui.TEXT);
            if (a.maturity > 0) {
                stat(dc, "Matures", LocalDate.ofEpochDay(a.maturity).format(DF) + (a.maturity > today ? "  (" + (a.maturity - today) + " days)" : "  ✓"), Ui.TEXT);
                stat(dc, "Maturity value", Ui.rupees(a.accruedOn(a.maturity)), Ui.GREEN);
            }
            stat(dc, "Interest earned so far", signed(v - a.principal), Ui.GREEN);
            dc.addView(Ui.text(this, "Estimated with quarterly compounding, before tax.", 11, Ui.SUB, false));
        } else {
            if (a.invested > 0) {
                stat(dc, "Put in", Ui.rupees(a.invested), Ui.TEXT);
                stat(dc, "Gain", signed(v - a.invested) + "  (" + Ui.pct((v / a.invested - 1) * 100) + ")", Ui.colorFor(v - a.invested));
            }
            if (a.rate > 0) stat(dc, loan ? "Interest rate" : "Growth rate used", String.format(Locale.US, "%.2f%% a year", a.rate), Ui.TEXT);
            if (a.baseDay > 0) stat(dc, "Last updated", LocalDate.ofEpochDay(a.baseDay).format(DF), Ui.TEXT);
            if (loan && a.maturity > 0) stat(dc, "Ends", LocalDate.ofEpochDay(a.maturity).format(DF), Ui.TEXT);
            if (!loan && a.rate > 0) dc.addView(Ui.text(this, "Value grows at the rate above until you update it.", 11, Ui.SUB, false));
        }
        add(col, dc, idx++, animate);
        LinearLayout actions = Ui.row(this);
        TextView upd = Ui.button(this, fd || rd ? "Edit deposit" : loan ? "Update outstanding" : "Update value", true);
        upd.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View vv) { otherDialog(a, a.type, a.kind); } });
        actions.addView(upd, Ui.weight(1));
        add(col, actions, idx++, animate);
    }

    /** Assets − loans, with a breakdown. */
    private View netWorthCard() {
        List<Store.Asset> all = store.visible(-1);
        List<Store.Asset> loans = store.loans();
        boolean anyOther = false;
        for (Store.Asset a : all) if (a.type == Store.OTHER) anyOther = true;
        if (!anyOther && loans.isEmpty()) {
            LinearLayout c = Ui.card(this);
            LinearLayout t = Ui.row(this);
            t.addView(Ui.text(this, "Net worth", 16, Ui.TEXT, true), Ui.weight(1));
            TextView add = Ui.text(this, "+ Add", 14, Ui.ACCENT, true);
            add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { addOtherMenu(Store.OTHER); } });
            t.addView(add);
            c.addView(t);
            c.addView(Ui.text(this, "Add fixed deposits, PPF, EPF, NPS, gold, property, cash and loans to see your full net worth.", 13, Ui.SUB, false));
            return c;
        }
        double assets = Store.value(all), debt = Store.value(loans);
        LinearLayout c = Ui.card(this);
        LinearLayout t = Ui.row(this);
        t.addView(Ui.text(this, "Net worth", 16, Ui.TEXT, true), Ui.weight(1));
        TextView add = Ui.text(this, "+ Add", 14, Ui.ACCENT, true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog().setTitle("Add to net worth").setItems(new String[]{"💰 Other asset\nFD, RD, PPF, gold, property…", "🏠 Loan\nWhat you owe"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { addOtherMenu(w == 0 ? Store.OTHER : Store.LOAN); }
                }).show();
            }
        });
        t.addView(add);
        c.addView(t);
        TextView nw = Ui.text(this, Ui.rupees(assets - debt), 26, Ui.TEXT, true);
        c.addView(nw);
        stat(c, "Investments & assets", Ui.rupees(assets), Ui.TEXT);
        if (debt > 0) stat(c, "Loans", "−" + Ui.rupees(debt), Ui.RED);
        // breakdown by kind of asset
        LinkedHashMap<String, Double> parts = new LinkedHashMap<>();
        for (Store.Asset a : all) {
            String k = a.type == Store.OTHER ? a.kind : typeName(a.type);
            parts.put(k, (parts.containsKey(k) ? parts.get(k) : 0) + a.value());
        }
        int i = 0;
        for (Map.Entry<String, Double> e : parts.entrySet()) {
            if (e.getValue() <= 0 || assets <= 0) continue;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, (int) (6 * dp), 0, 0);
            LinearLayout hr = Ui.row(this);
            hr.addView(Ui.text(this, e.getKey(), 12, Ui.TEXT, false), Ui.weight(1));
            hr.addView(Ui.text(this, String.format(Locale.US, "%.0f%%", e.getValue() / assets * 100), 12, Ui.SUB, true));
            r.addView(hr);
            r.addView(Ui.space(this, 3));
            r.addView(Ui.bar(this, (float) (e.getValue() / assets), Ui.PALETTE[i++ % Ui.PALETTE.length]));
            c.addView(r);
        }
        for (final Store.Asset l : loans) {
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (8 * dp), 0, 0);
            r.addView(Ui.text(this, "🏦 " + l.name, 12, Ui.TEXT, false), Ui.weight(1));
            r.addView(Ui.text(this, "−" + Ui.rupees(l.value()), 12, Ui.RED, true));
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { current = l; screen = 1; render(true); } });
            c.addView(r);
        }
        return c;
    }

    // ================================================================== dashboard card stack
    private int stackIndex = 0;
    private static final int[][] CARD_GRADIENTS = {
            {0xFF2F6FE0, 0xFF7C5CE0}, {0xFFE07A2F, 0xFFD9467A}, {0xFF1E9E8A, 0xFF2F6FE0}, {0xFF7C5CE0, 0xFFB33FC0},
            {0xFF15965A, 0xFF0E7490}, {0xFF6B7280, 0xFF374151}, {0xFFD98E04, 0xFFE0517A}, {0xFF0E9AA7, 0xFF15965A},
            {0xFF3B4FD8, 0xFF0E9AA7}, {0xFFB33F62, 0xFF7C5CE0}};

    private interface Scope { void open(); }

    /** Soft shadow behind white text so it stays readable on a see-through card. */
    private void textShadow(View v) {
        if (v instanceof TextView) ((TextView) v).setShadowLayer(4 * dp, 0, dp, 0x59000000);
        else if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) textShadow(((ViewGroup) v).getChildAt(i));
    }

    private View stackCard(String icon, String title, List<Store.Asset> list, int[] grad, final Scope onTap, boolean animate) {
        FrameLayout f = new FrameLayout(this);
        final int op = Alerts.prefs(this).getInt("cardOpacity", 60);
        int[] gc = new int[grad.length];
        for (int i = 0; i < grad.length; i++) gc[i] = (grad[i] & 0x00FFFFFF) | ((op * 255 / 100) << 24);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, gc);
        g.setCornerRadius(24 * dp);
        if (op < 100) g.setStroke(Math.max(1, (int) dp), 0x4DFFFFFF);     // thin glass edge
        f.setBackground(g);
        if (op >= 100) f.setElevation(6 * dp);     // a shadow would show through a see-through card
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding((int) (20 * dp), (int) (16 * dp), (int) (30 * dp), (int) (14 * dp));
        f.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, icon + "  " + title, 14, 0xE6FFFFFF, true), Ui.weight(1));
        int n = 0;
        for (Store.Asset a : list) if (a.held()) n++;
        TextView cnt = Ui.text(this, n + (n == 1 ? " holding" : " holdings"), 11, 0xFFFFFFFF, true);
        cnt.setPadding((int) (8 * dp), (int) (3 * dp), (int) (8 * dp), (int) (3 * dp));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0x33FFFFFF);
        pg.setCornerRadius(10 * dp);
        cnt.setBackground(pg);
        top.addView(cnt);
        c.addView(top);
        double total = Store.value(list);
        String rk = "card:" + title + "@" + store.ownerFilter;
        RollText val = new RollText(this);
        Ui.style(val, 30, 0xFFFFFFFF, true);
        boolean seen = RollText.seen(rk);
        val.show(rk, Ui.rupees(total), total);
        c.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (animate && total > 0 && !seen) Ui.countUp(val, total, true);
        double day = Store.lastChange(list);
        double base = total - day;
        String ch = day == 0 ? "No change today" : (day >= 0 ? "▲ " : "▼ ") + signed(day) + "  " + Ui.pct(base > 0 ? day / base * 100 : null) + " today";
        RollText chv = new RollText(this);
        Ui.style(chv, 13, day >= 0 ? 0xFFCFFFE2 : 0xFFFFD6D6, true);
        chv.show(rk + ":day", ch, day);
        c.addView(chv);
        View spacer = new View(this);
        c.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout bottom = Ui.row(this);
        double[] cv = store.costAndValue(list);
        if (cv[0] > 0) {
            double gain = cv[1] - cv[0];
            LinearLayout inv = new LinearLayout(this);
            inv.setOrientation(LinearLayout.VERTICAL);
            inv.addView(Ui.text(this, "Invested", 11, 0xB3FFFFFF, false));
            TextView iv = Ui.text(this, Ui.rupees(cv[0]).replaceAll("\\.\\d+$", ""), 13, 0xFFFFFFFF, true);
            iv.setSingleLine(true);
            inv.addView(iv);
            bottom.addView(inv, Ui.weight(1));
            LinearLayout m = new LinearLayout(this);
            m.setOrientation(LinearLayout.VERTICAL);
            m.addView(Ui.text(this, "Gain / loss", 11, 0xB3FFFFFF, false));
            TextView gv = Ui.text(this, signed(gain).replaceAll("\\.\\d+$", "") + " " + Ui.pct(gain / cv[0] * 100), 13, 0xFFFFFFFF, true);
            gv.setSingleLine(true);
            m.addView(gv);
            bottom.addView(m, Ui.weight(1.25f));
        } else {
            LinearLayout inv = new LinearLayout(this);
            inv.setOrientation(LinearLayout.VERTICAL);
            inv.addView(Ui.text(this, "Invested", 11, 0xB3FFFFFF, false));
            inv.addView(Ui.text(this, "Not set · tap to add", 12, 0xE6FFFFFF, false));
            bottom.addView(inv, Ui.weight(1));
        }
        TreeMap<Long, Double> hist = Store.valueHistory(list);
        if (hist.size() >= 2) {
            ChartView sp = new ChartView(this, true);
            sp.setData(hist, hist.lastKey() - 30, 0xFFFFFFFF);
            bottom.addView(sp, new LinearLayout.LayoutParams((int) (80 * dp), (int) (34 * dp)));
        }
        c.addView(bottom);
        if (op < 100) textShadow(c);
        f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onTap.open(); } });
        return f;
    }

    private View cardStack(boolean animate) {
        List<View> views = new ArrayList<>();
        int gi = 0;
        List<Store.Asset> all = store.visible(-1);
        views.add(stackCard("🌐", selectedPerson() == null ? (store.mixed() ? "All · " + Ui.CCY : "All investments") : selectedPerson() + " · all", all, CARD_GRADIENTS[gi++], new Scope() {
            @Override public void open() { scopeKind = 0; tab = 1; screen = 0; render(true); }
        }, animate));
        int[] types = {Store.STOCK, Store.MF, Store.ULIP};
        String[] icons = {"📈", "🧺", "🛡"};
        for (int i = 0; i < types.length; i++) {
            final int t = types[i];
            List<Store.Asset> l = store.visible(t);
            if (Store.value(l) <= 0) continue;
            views.add(stackCard(icons[i], typeName(t), l, CARD_GRADIENTS[gi++ % CARD_GRADIENTS.length], new Scope() {
                @Override public void open() { scopeKind = 1; scopeType = t; tab = 1; screen = 0; render(true); }
            }, false));
        }
        List<Store.Asset> cash = new ArrayList<>(), other = new ArrayList<>();
        for (Store.Asset a : store.visible(Store.OTHER)) {
            if ("Savings / cash".equals(a.kind) || a.isDeposit()) cash.add(a); else other.add(a);
        }
        Scope otherScope = new Scope() { @Override public void open() { scopeKind = 1; scopeType = Store.OTHER; tab = 1; screen = 0; render(true); } };
        if (Store.value(cash) > 0) views.add(stackCard("💵", "Cash, FDs & RDs", cash, CARD_GRADIENTS[gi++ % CARD_GRADIENTS.length], otherScope, false));
        if (Store.value(other) > 0) views.add(stackCard("🏠", "Other assets", other, CARD_GRADIENTS[gi++ % CARD_GRADIENTS.length], otherScope, false));
        for (final String p : store.platforms()) {
            boolean kindName = false;
            for (String k : Store.OTHER_KINDS) if (k.equalsIgnoreCase(p)) kindName = true;
            if (kindName || p.equals("Other")) continue;
            List<Store.Asset> l = store.visiblePlatform(p);
            if (Store.value(l) <= 0) continue;
            views.add(stackCard("🏦", p, l, CARD_GRADIENTS[gi++ % CARD_GRADIENTS.length], new Scope() {
                @Override public void open() { scopeKind = 2; scopePlatform = p; tab = 1; screen = 0; render(true); }
            }, false));
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        final CardStack cs = new CardStack(this, 190);
        cs.setCards(views, stackIndex % Math.max(1, views.size()));
        final TextView hint = Ui.text(this, "", 11, Ui.SUB, false);
        hint.setGravity(Gravity.CENTER);
        final int count = views.size();
        cs.setListener(new CardStack.Listener() {
            @Override public void onChanged(int i) { stackIndex = i; hint.setText((i + 1) + " of " + count + " · swipe up / down"); }
        });
        hint.setText(count > 1 ? (cs.index() + 1) + " of " + count + " · swipe up / down" : "");
        wrap.addView(cs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (count > 1) { hint.setPadding(0, (int) (6 * dp), 0, 0); wrap.addView(hint); }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (16 * dp);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    // ================================================================== Insights
    private List<String[]> insightList() {   // [icon, title, detail]
        List<String[]> out = new ArrayList<>();
        List<Store.Asset> all = store.visible(-1);
        double total = Store.value(all);
        if (total <= 0) return out;
        // concentration
        List<Store.Asset> held = new ArrayList<>();
        for (Store.Asset a : all) if (a.held()) held.add(a);
        sortByValue(held);
        if (!held.isEmpty()) {
            double top = held.get(0).value() / total * 100, top5 = 0;
            for (int i = 0; i < Math.min(5, held.size()); i++) top5 += held.get(i).value();
            top5 = top5 / total * 100;
            if (top >= 25) out.add(new String[]{"⚠️", "One holding is " + String.format(Locale.US, "%.0f%%", top) + " of everything",
                    shortName(held.get(0)) + " dominates. A big fall there would hit your whole portfolio."});
            out.add(new String[]{"📊", String.format(Locale.US, "Top 5 holdings = %.0f%%", top5),
                    held.size() + " holdings in total. Above ~70% in the top 5 means you're fairly concentrated."});
            for (Store.Asset a : held) {
                if (a.type == Store.STOCK && a.value() / total > 0.10)
                    out.add(new String[]{"🎯", shortName(a) + " is " + String.format(Locale.US, "%.0f%%", a.value() / total * 100) + " in a single stock",
                            "Single companies carry more risk than funds. Consider whether that's intentional."});
            }
        }
        // platform concentration
        Map<String, Double> byPlat = new HashMap<>();
        for (Store.Asset a : all) byPlat.put(a.platformOrDefault(), (byPlat.containsKey(a.platformOrDefault()) ? byPlat.get(a.platformOrDefault()) : 0) + a.value());
        for (Map.Entry<String, Double> e : byPlat.entrySet())
            if (e.getValue() / total > 0.6 && byPlat.size() > 1)
                out.add(new String[]{"🏦", String.format(Locale.US, "%.0f%% sits on %s", e.getValue() / total * 100, e.getKey()),
                        "Most of your money is with one platform. That's fine for convenience; keep nominee details up to date there."});
        // MF category overlap
        Map<String, List<Store.Asset>> byCat = new LinkedHashMap<>();
        for (Store.Asset a : all) {
            if (a.type != Store.MF || a.category.isEmpty() || !a.held()) continue;
            String c = a.category.replaceAll("(?i)^.*?-\\s*", "").trim();
            if (!byCat.containsKey(c)) byCat.put(c, new ArrayList<Store.Asset>());
            boolean dup = false;
            for (Store.Asset x : byCat.get(c)) if (x.code.equals(a.code)) dup = true;
            if (!dup) byCat.get(c).add(a);
        }
        for (Map.Entry<String, List<Store.Asset>> e : byCat.entrySet()) {
            if (e.getValue().size() < 2 || e.getKey().toLowerCase(Locale.ROOT).contains("liquid")) continue;
            StringBuilder n = new StringBuilder();
            for (Store.Asset a : e.getValue()) { if (n.length() > 0) n.append(", "); n.append(shortName(a)); }
            out.add(new String[]{"🔁", e.getValue().size() + " funds in " + e.getKey(),
                    n + ". Funds in the same category usually hold many of the same companies, so they add less diversification than it looks."});
        }
        // regular vs direct plans
        int regular = 0;
        double regVal = 0;
        for (Store.Asset a : all) if (a.type == Store.MF && a.name.toLowerCase(Locale.ROOT).contains("regular")) { regular++; regVal += a.value(); }
        if (regular > 0)
            out.add(new String[]{"💸", regular + " fund" + (regular == 1 ? "" : "s") + " on Regular plans (" + Ui.rupees(regVal) + ")",
                    "Regular plans include a distributor commission, so their expense ratio is higher than the Direct plan of the same fund. "
                            + "Switching counts as a sale for tax, so check gains first."});
        // vs Nifty 50
        Store.Asset nifty = store.find(Store.INDEX, "^NSEI");
        if (nifty != null && nifty.prices.size() > 300) {
            Double n1 = nifty.changeOver(365);
            int beat = 0, n = 0;
            List<String> lag = new ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            for (Store.Asset a : all) {
                if ((a.type != Store.MF && a.type != Store.STOCK) || !seen.add(a.code)) continue;
                Double r = a.changeOver(365);
                if (r == null || n1 == null) continue;
                n++;
                if (r >= n1) beat++; else if (r < n1 - 10 && lag.size() < 3) lag.add(shortName(a));
            }
            if (n > 0) out.add(new String[]{"📈", beat + " of " + n + " beat the Nifty 50 over 1 year",
                    "Nifty 50: " + Ui.pct(n1) + (lag.isEmpty() ? "." : ". Trailing by 10%+: " + String.join(", ", lag) + ". One year is short, so compare over 3–5 years before acting.")});
        }
        // well below cost
        for (Store.Asset a : held) {
            if (a.invested <= 0) continue;
            double r = (a.value() / a.invested - 1) * 100;
            if (r <= -25) out.add(new String[]{"🔻", shortName(a) + " is " + Ui.pct(r) + " below cost",
                    "Worth reviewing whether the reason you bought it still holds. A loss can also offset other capital gains this year."});
        }
        // emergency cash
        double cash = 0;
        for (Store.Asset a : all) if (a.type == Store.OTHER && ("Savings / cash".equals(a.kind) || a.isDeposit())) cash += a.value();
        if (cash == 0) out.add(new String[]{"🛟", "No cash or FDs recorded", "Add your savings/FDs under Net worth to see your emergency buffer alongside investments."});
        return out;
    }

    private View insightsCard() {
        List<String[]> l = insightList();
        if (l.isEmpty()) return null;
        LinearLayout c = Ui.card(this);
        LinearLayout t = Ui.row(this);
        t.addView(Ui.text(this, "💡  Insights", 16, Ui.TEXT, true), Ui.weight(1));
        t.addView(Ui.pill(this, l.size() + " tips", Ui.ACCENT));
        c.addView(t);
        for (int i = 0; i < Math.min(2, l.size()); i++) {
            c.addView(Ui.space(this, 6));
            c.addView(Ui.text(this, l.get(i)[0] + "  " + l.get(i)[1], 13, Ui.SUB, false));
        }
        TextView more = Ui.text(this, "See all insights ›", 13, Ui.ACCENT, true);
        more.setPadding(0, (int) (8 * dp), 0, 0);
        c.addView(more);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { screen = 5; render(true); } });
        return c;
    }

    private void showInsights(boolean animate) {
        LinearLayout col = page(!animate);
        TextView back = Ui.text(this, "‹  Back", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { slideBack(); } });
        col.addView(back);
        col.addView(Ui.text(this, "Insights", 24, Ui.TEXT, true));
        col.addView(Ui.text(this, "Observations from your holdings. Not investment advice.", 12, Ui.SUB, false));
        col.addView(Ui.space(this, 12));
        int i = 0;
        for (String[] s : insightList()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, s[0] + "  " + s[1], 15, Ui.TEXT, true));
            c.addView(Ui.space(this, 4));
            c.addView(Ui.text(this, s[2], 13, Ui.SUB, false));
            add(col, c, i++, animate);
        }
        boolean noCat = false;
        for (Store.Asset a : store.visible(Store.MF)) if (a.category.isEmpty()) noCat = true;
        if (noCat) col.addView(Ui.text(this, "Tap ↻ to load fund categories for the overlap check.", 12, Ui.SUB, false));
    }

    // ================================================================== Tax estimate
    private static final double LTCG_EXEMPT = 125000, STCG_RATE = 0.20, LTCG_RATE = 0.125;

    private boolean equityLike(Store.Asset a) {
        if (a.type == Store.STOCK) return true;
        if (a.type != Store.MF) return false;
        String c = (a.category + " " + a.name).toLowerCase(Locale.ROOT);
        if (c.contains("debt") || c.contains("liquid") || c.contains("money market") || c.contains("gilt") || c.contains("overnight")
                || c.contains("bond") || c.contains("credit risk") || c.contains("duration")) return false;
        return c.contains("equity") || c.contains("index") || c.contains("etf") || c.contains("elss") || c.contains("aggressive")
                || c.contains("arbitrage") || c.contains("cap") || c.contains("flexi") || c.contains("multi") || c.contains("focused")
                || c.contains("value") || c.contains("sectoral") || c.contains("thematic") || c.contains("balanced advantage")
                || a.category.isEmpty();
    }

    private void showTax(boolean animate) {
        LinearLayout col = page(!animate);
        TextView back = Ui.text(this, "‹  Back", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { slideBack(); } });
        col.addView(back);
        if (!"INR".equals(Ui.CCY)) {
            LinearLayout nc = Ui.card(this);
            nc.addView(Ui.text(this, "Indian tax only", 16, Ui.TEXT, true));
            nc.addView(Ui.text(this, "This page follows Indian capital-gains rules, so it covers profiles in ₹. Pick a ₹ profile at the top of "
                    + "Dashboard or Portfolio to see it. For " + Ui.nameOf(Ui.CCY) + " holdings, check the rules where you're resident.", 13, Ui.SUB, false));
            col.addView(nc);
            return;
        }
        LocalDate now = LocalDate.now();
        int fy = now.getMonthValue() >= 4 ? now.getYear() : now.getYear() - 1;
        long fs = LocalDate.of(fy, 4, 1).toEpochDay(), fe = LocalDate.of(fy + 1, 3, 31).toEpochDay(), today = now.toEpochDay();
        col.addView(Ui.text(this, "Tax estimate · FY " + fy + "-" + String.valueOf(fy + 1).substring(2), 22, Ui.TEXT, true));
        col.addView(Ui.text(this, "Equity shares & equity funds: short-term (≤12 months) 20%, long-term 12.5% above ₹1.25 lakh a year. "
                + "Estimate only, excludes cess/surcharge and grandfathering. Not tax advice.", 12, Ui.SUB, false));
        col.addView(Ui.space(this, 12));

        double stR = 0, ltR = 0, stU = 0, ltU = 0, soon = 0, debtR = 0, debtU = 0;
        long soonDay = 0;
        int noDates = 0;
        List<String[]> rows = new ArrayList<>();
        for (Store.Asset a : store.visible(-1)) {
            if (a.type != Store.STOCK && a.type != Store.MF) continue;
            if (!a.hasLedger()) { if (a.held()) noDates++; continue; }
            Map.Entry<Long, Double> l = a.latest();
            double px = l == null ? 0 : l.getValue();
            Ledger.Gains g = Ledger.gains(a.txns, fs, fe, px, today);
            if (equityLike(a)) {
                stR += g.stRealised; ltR += g.ltRealised; stU += g.stUnreal; ltU += g.ltUnreal;
                if (g.soonLtGain > 0) { soon += g.soonLtGain; if (soonDay == 0 || g.soonDay < soonDay) soonDay = g.soonDay; }
                if (Math.abs(g.ltUnreal) > 1 || Math.abs(g.stUnreal) > 1)
                    rows.add(new String[]{shortName(a), signed(g.ltUnreal), signed(g.stUnreal), String.valueOf(g.ltUnreal)});
            } else {
                debtR += g.stRealised + g.ltRealised; debtU += g.stUnreal + g.ltUnreal;
            }
        }
        LinearLayout rc = Ui.card(this);
        rc.addView(Ui.text(this, "Realised this year (equity)", 16, Ui.TEXT, true));
        stat(rc, "Short-term gains", signed(stR), Ui.colorFor(stR));
        stat(rc, "Long-term gains", signed(ltR), Ui.colorFor(ltR));
        double ltTaxable = Math.max(0, ltR - LTCG_EXEMPT);
        double tax = Math.max(0, stR) * STCG_RATE + ltTaxable * LTCG_RATE;
        stat(rc, "Estimated tax so far", Ui.rupees(tax), tax > 0 ? Ui.RED : Ui.TEXT);
        double left = Math.max(0, LTCG_EXEMPT - Math.max(0, ltR));
        stat(rc, "Tax-free LTCG left this year", Ui.rupees(left), Ui.GREEN);
        if (debtR != 0) stat(rc, "Debt fund gains (taxed at your slab)", signed(debtR), Ui.colorFor(debtR));
        add(col, rc, 0, animate);

        LinearLayout uc = Ui.card(this);
        uc.addView(Ui.text(this, "If you sold everything today", 16, Ui.TEXT, true));
        stat(uc, "Long-term (held 12+ months)", signed(ltU), Ui.colorFor(ltU));
        stat(uc, "Short-term", signed(stU), Ui.colorFor(stU));
        if (debtU != 0) stat(uc, "Debt funds (slab rate)", signed(debtU), Ui.colorFor(debtU));
        add(col, uc, 1, animate);

        LinearLayout hc = Ui.card(this);
        hc.addView(Ui.text(this, "🌾  Tax harvesting", 16, Ui.TEXT, true));
        String harvest;
        if (left > 0 && ltU > 0) harvest = "You could book about " + Ui.rupees(Math.min(left, ltU)) + " of long-term gains this year tax-free "
                + "(sell and buy back) to raise your cost price. Check exit loads and the time out of the market first.";
        else if (left <= 0) harvest = "You've used this year's ₹1.25 lakh long-term exemption.";
        else harvest = "No long-term gains to harvest right now.";
        hc.addView(Ui.text(this, harvest, 13, Ui.SUB, false));
        if (soon > 0) hc.addView(Ui.text(this, "\n⏳ " + Ui.rupees(soon) + " of short-term gains become long-term by "
                + LocalDate.ofEpochDay(soonDay).format(DF) + ". Selling after that is taxed at 12.5% instead of 20%.", 13, Ui.SUB, false));
        if (stU < 0 || ltU < 0) hc.addView(Ui.text(this, "\n🔻 Unrealised losses can offset gains if booked in the same year.", 13, Ui.SUB, false));
        add(col, hc, 2, animate);

        if (!rows.isEmpty()) {
            Collections.sort(rows, new Comparator<String[]>() {
                @Override public int compare(String[] x, String[] y) { return Double.compare(Double.parseDouble(y[3]), Double.parseDouble(x[3])); }
            });
            LinearLayout lc = Ui.card(this);
            lc.addView(Ui.text(this, "By holding (unrealised)", 16, Ui.TEXT, true));
            LinearLayout hdr = Ui.row(this);
            hdr.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
            hdr.addView(Ui.text(this, "", 11, Ui.SUB, false), Ui.weight(1.4f));
            hdr.addView(Ui.text(this, "Long-term", 11, Ui.SUB, true), Ui.weight(1));
            hdr.addView(Ui.text(this, "Short-term", 11, Ui.SUB, true), Ui.weight(1));
            lc.addView(hdr);
            for (String[] r : rows) {
                LinearLayout rr = Ui.row(this);
                rr.setPadding(0, (int) (5 * dp), 0, (int) (5 * dp));
                rr.addView(Ui.text(this, r[0], 12, Ui.TEXT, false), Ui.weight(1.4f));
                rr.addView(Ui.text(this, r[1], 12, Ui.SUB, false), Ui.weight(1));
                rr.addView(Ui.text(this, r[2], 12, Ui.SUB, false), Ui.weight(1));
                lc.addView(rr);
            }
            add(col, lc, 3, animate);
        }
        // ULIP status
        double prem = 0;
        for (Store.Policy p : store.policies) prem += p.yearlyPremium();
        LinearLayout ul = Ui.card(this);
        ul.addView(Ui.text(this, "🛡  SBI Life ULIP", 16, Ui.TEXT, true));
        String ut = store.policies.isEmpty() ? "Add your policy (More › Insurance policies) to check the ₹2.5 lakh premium rule."
                : prem <= 250000 ? "Total yearly ULIP premium " + Ui.rupees(prem) + " is within ₹2.5 lakh, so maturity proceeds can be tax-free under "
                + "Section 10(10D) (for policies issued from Feb 2021, if the premium is also ≤10% of the sum assured)."
                : "Total yearly ULIP premium " + Ui.rupees(prem) + " is above ₹2.5 lakh. Policies issued from Feb 2021 then don't get the 10(10D) "
                + "exemption and gains are taxed like equity funds.";
        ul.addView(Ui.text(this, ut, 13, Ui.SUB, false));
        add(col, ul, 4, animate);
        if (noDates > 0) col.addView(Ui.text(this, noDates + " holding" + (noDates == 1 ? " has" : "s have")
                + " no purchase dates and isn't included. Add dates or import your CAS/tradebook.", 12, Ui.SUB, false));
    }

    private View taxCard() {
        boolean any = false;
        for (Store.Asset a : store.visible(-1)) if ((a.type == Store.STOCK || a.type == Store.MF) && a.hasLedger()) any = true;
        LinearLayout c = Ui.card(this);
        LinearLayout t = Ui.row(this);
        t.addView(Ui.text(this, "🧾  Capital gains & tax", 16, Ui.TEXT, true), Ui.weight(1));
        t.addView(Ui.text(this, "›", 18, Ui.SUB, true));
        c.addView(t);
        c.addView(Ui.text(this, any ? "This year's realised gains, tax-free limit left and harvesting ideas."
                : "Add purchase dates (or import your CAS/tradebook) to estimate capital gains tax.", 13, Ui.SUB, false));
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { screen = 6; render(true); } });
        return c;
    }

    // ================================================================== automatic encrypted backup
    private static final int REQ_TREE = 42;

    private void backupSettings() {
        final android.content.SharedPreferences p = Alerts.prefs(this);
        String folder = p.getString("bkTree", null);
        String last = p.getLong("bkLast", 0) > 0 ? java.time.Instant.ofEpochMilli(p.getLong("bkLast", 0))
                .atZone(java.time.ZoneId.systemDefault()).format(TF) : "never";
        String[] items = folder == null ? new String[]{"Choose backup folder…"}
                : new String[]{"Back up now", "Change folder", "Change password", "Turn off automatic backup"};
        dialog().setTitle("Automatic backup").setMessage(folder == null
                        ? "Saves an encrypted copy of all your data once a day to a folder you choose (e.g. Documents, or a synced cloud folder)."
                        : "Encrypted daily backup is on. Last backup: " + last + ".")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String f = p.getString("bkTree", null);
                        if (f == null || w == 1) {
                            authInProgress = true;
                            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE); }
                            catch (Exception e) { authInProgress = false; toast("No folder picker available"); }
                        } else if (w == 0) runBackup(true);
                        else if (w == 2) askBackupPassword(false);
                        else { p.edit().remove("bkTree").remove("bkPass").apply(); toast("Automatic backup off"); }
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void onTreePicked(android.net.Uri tree) {
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) { }
        Alerts.prefs(this).edit().putString("bkTree", tree.toString()).apply();
        if (Alerts.prefs(this).getString("bkPass", null) == null) askBackupPassword(true); else runBackup(true);
    }

    private void askBackupPassword(final boolean thenBackup) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setHint("At least 6 characters");
        dialog().setTitle("Backup password").setMessage("Backups are encrypted with this password. You'll need it to restore, so keep it somewhere safe.")
                .setView(padded(e))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String pw = e.getText().toString();
                        if (pw.length() < 6) { toast("Use at least 6 characters"); return; }
                        Alerts.prefs(MainActivity.this).edit().putString("bkPass", pw).apply();
                        if (thenBackup) runBackup(true);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Writes portfolio-backup.ppbk to the chosen folder (background). */
    private void runBackup(final boolean manual) {
        final android.content.SharedPreferences p = Alerts.prefs(this);
        final String tree = p.getString("bkTree", null), pw = p.getString("bkPass", null);
        if (tree == null || pw == null) return;
        if (!manual && System.currentTimeMillis() - p.getLong("bkLast", 0) < 20 * 3600_000L) return;
        final String json = store.toJson().toString();
        exec.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    byte[] enc = Backup.encrypt(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), pw);
                    Backup.write(MainActivity.this, android.net.Uri.parse(tree), "portfolio-backup.ppbk", enc);
                    p.edit().putLong("bkLast", System.currentTimeMillis()).apply();
                } catch (Throwable t) { err = t.getMessage(); Crash.save(MainActivity.this, t); }
                final String fe = err;
                if (manual) ui.post(new Runnable() { @Override public void run() { toast(fe == null ? "Backup saved" : "Backup failed: " + fe); } });
            }
        });
    }

    private void askRestorePassword(final byte[] data) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        dialog().setTitle("Restore backup").setMessage("Enter the backup password.").setView(padded(e))
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try { confirmRestore(new String(Backup.decrypt(data, e.getText().toString()), java.nio.charset.StandardCharsets.UTF_8)); }
                        catch (Exception ex) { toast("Wrong password or damaged backup"); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void renameOwner() {
        final List<String> owners = store.owners();
        owners.remove("Me");
        if (owners.isEmpty()) {
            dialog().setTitle("Family members").setMessage("Add someone by typing their name in the Owner field when you add or edit a holding. "
                    + "Then switch between Everyone and each person at the top of Dashboard and Portfolio.").setPositiveButton("OK", null).show();
            return;
        }
        dialog().setTitle("Family members").setItems(owners.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                final String who = owners.get(w);
                dialog().setTitle(who).setItems(new String[]{"🖼️ Change picture\nPhoto or emoji",
                        Ui.flagOf(store.ccyOf(who)) + " Currency\n" + store.ccyOf(who) + " · " + Ui.nameOf(store.ccyOf(who)), "✏️ Rename"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d1, int w1) { if (w1 == 0) avatarDialog(who); else if (w1 == 1) currencyDialog(who); else renameOne(who); }
                }).show();
            }
        }).show();
    }

    private void renameOne(final String from) {
        {
            {
                final EditText e = new EditText(MainActivity.this);
                e.setText(from);
                dialog().setTitle("Rename " + from).setView(padded(e)).setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d2, int w2) {
                        String to = ownerValue(e.getText().toString());
                        for (Store.Asset a : store.assets) if (a.ownerOrDefault().equalsIgnoreCase(from)) a.owner = to;
                        Avatars.rename(MainActivity.this, from, to.isEmpty() ? "Me" : to);
                        String fc = store.ccyOf(from);
                        store.setCcy(from, null);
                        store.setCcy(to.isEmpty() ? "Me" : to, fc);
                        if (from.equalsIgnoreCase(store.ownerFilter)) { store.ownerFilter = to.isEmpty() ? "Me" : to; Alerts.prefs(MainActivity.this).edit().putString("owner", store.ownerFilter).apply(); }
                        store.save(); render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
            }
        }
    }

    // ================================================================== more menu
    private void moreMenu() {
        dialog().setTitle("More")
                .setItems(new String[]{"Paste SBI Life ULIP NAVs", "SBI Life policy: amount invested", "Show / hide holdings",
                        "Share backup", "Copy backup to clipboard", "Restore backup from clipboard"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (which == 0) showPaste(null);
                                else if (which == 1) editUlipInvested();
                                else if (which == 2) showHide();
                                else if (which == 3) {
                                    Intent s = new Intent(Intent.ACTION_SEND);
                                    s.setType("text/plain");
                                    s.putExtra(Intent.EXTRA_SUBJECT, "Portfolio backup " + LocalDate.now());
                                    s.putExtra(Intent.EXTRA_TEXT, store.toJson().toString());
                                    startActivity(Intent.createChooser(s, "Save backup"));
                                } else if (which == 4) {
                                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(ClipData.newPlainText("Portfolio backup", store.toJson().toString()));
                                    toast("Backup copied");
                                } else {
                                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0)
                                        confirmRestore(cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this).toString());
                                    else toast("Clipboard is empty");
                                }
                            }
                        }).show();
    }

    private void editUlipInvested() {
        final EditText e = numberField("Total premiums allocated to funds", store.ulipInvested > 0
                ? String.format(Locale.US, "%.0f", store.ulipInvested) : null);
        dialog().setTitle("SBI Life: amount invested")
                .setMessage("Total you've put into the ULIP policy. Used for the ULIP gain/loss.")
                .setView(padded(e))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Double v = num(e.getText().toString());
                        store.ulipInvested = v == null ? 0 : Math.max(0, v);
                        store.save(); render(true);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void showHide() {
        final String[] names = new String[store.assets.size()];
        final boolean[] shown = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            Store.Asset a = store.assets.get(i);
            names[i] = shortName(a) + "  ·  " + typeName(a.type);
            shown[i] = !a.hidden;
        }
        dialog().setTitle("Show / hide holdings")
                .setMultiChoiceItems(names, shown, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean c) { shown[which] = c; }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < names.length; i++) store.assets.get(i).hidden = !shown[i];
                        store.save(); render(true);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmRestore(final String json) {
        dialog().setTitle("Restore backup?")
                .setMessage("This replaces all holdings and price history in the app.")
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            store.fromJson(new JSONObject(json.trim()));
                            store.save(); toast("Backup restored"); screen = 0; scopeKind = 0; render(true);
                        } catch (Exception ex) { toast("That doesn't look like a backup from this app"); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ================================================================== ULIP paste
    private LocalDate pasteDate;

    private void showPaste(String prefill) {
        screen = 2;
        LinearLayout col = page(false);
        TextView back = Ui.text(this, "‹  Cancel", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { slideBack(); } });
        col.addView(back);
        col.addView(Ui.text(this, "Paste ULIP NAVs", 24, Ui.TEXT, true));
        col.addView(Ui.text(this, "Copy the NAV table from SBI Life's website, app, statement or SMS and paste it below. "
                + "Fund names and NAVs are matched automatically.", 13, Ui.SUB, false));
        col.addView(Ui.space(this, 12));
        final EditText input = new EditText(this);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.HINT);
        input.setTextSize(13);
        input.setHint("e.g.\nEquity Fund  ULIF001100105EQUITY-FND111  210.0123\nMidcap Fund ...  45.6789\nNAV as on 20-11-2026");
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.CARD);
        g.setCornerRadius(14 * dp);
        input.setBackground(g);
        input.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        if (prefill != null) input.setText(prefill);
        col.addView(input);
        col.addView(Ui.space(this, 10));
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = Ui.row(this);
        TextView clip = Ui.button(this, "Paste clipboard", false);
        clip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    input.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this));
                    pasteDate = null;
                    readPaste(input.getText().toString(), results);
                } else toast("Clipboard is empty");
            }
        });
        TextView read = Ui.button(this, "Read", true);
        read.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteDate = null; readPaste(input.getText().toString(), results); }
        });
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.setMargins(0, 0, (int) (8 * dp), 0);
        actions.addView(clip, lp);
        actions.addView(read, Ui.weight(1));
        col.addView(actions);
        col.addView(Ui.space(this, 14));
        col.addView(results);
    }

    private void readPaste(final String text, final LinearLayout results) {
        results.removeAllViews();
        String t = text.trim();
        if (t.startsWith("{") && (t.contains("\"assets\"") || t.contains("\"funds\""))) { confirmRestore(t); return; }
        List<String> casLines = java.util.Arrays.asList(text.split("\\r?\\n"));
        if (Cas.looksLikeCas(casLines)) { startImport(Cas.parse(casLines), "Mutual fund statement", ""); return; }
        List<String> names = new ArrayList<>();
        for (Store.Asset a : store.assets) if (a.type == Store.ULIP && !names.contains(a.name)) names.add(a.name);
        final Parser.Result r = Parser.parse(text, names);
        if (pasteDate == null) pasteDate = r.date != null ? r.date : LocalDate.now();
        LinearLayout c = Ui.card(this);
        if (r.navs.isEmpty()) {
            c.addView(Ui.text(this, "No fund NAVs found", 15, Ui.TEXT, true));
            c.addView(Ui.text(this, "Make sure the text has fund names like \"Equity Fund\" with the NAV next to them. "
                    + "For a fund with another name, add it via + › SBI Life ULIP fund first.", 13, Ui.SUB, false));
            results.addView(c);
            return;
        }
        c.addView(Ui.text(this, "Found " + r.navs.size() + " fund" + (r.navs.size() == 1 ? "" : "s"), 15, Ui.TEXT, true));
        TextView dateBtn = Ui.text(this, "NAV date: " + pasteDate.format(DF) + (r.date == null ? "  (not in text, using today)" : "")
                + "  · change", 13, Ui.ACCENT, false);
        dateBtn.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        dateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickDate(pasteDate, new DateListener() { @Override public void onDate(LocalDate d) { pasteDate = d; readPaste(text, results); } });
            }
        });
        c.addView(dateBtn);
        for (Map.Entry<String, Double> e : r.navs.entrySet()) {
            Store.Asset a = store.find(Store.ULIP, e.getKey());
            Map.Entry<Long, Double> prev = a.prices.lowerEntry(pasteDate.toEpochDay());
            Double ch = prev == null ? null : (e.getValue() / prev.getValue() - 1) * 100;
            LinearLayout row = Ui.row(this);
            row.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
            row.addView(Ui.text(this, e.getKey(), 14, Ui.TEXT, false), Ui.weight(1.6f));
            row.addView(Ui.text(this, Ui.nav(e.getValue()), 14, Ui.TEXT, true), Ui.weight(1));
            TextView pc = Ui.text(this, Ui.pct(ch), 13, Ui.colorFor(ch), false);
            pc.setGravity(Gravity.END);
            row.addView(pc, Ui.weight(0.8f));
            c.addView(row);
            if (ch != null && Math.abs(ch) > 15) c.addView(Ui.text(this, "   Big jump vs previous NAV. Check this value.", 12, Ui.RED, false));
        }
        c.addView(Ui.space(this, 10));
        TextView save = Ui.button(this, "Save NAVs", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long d = pasteDate.toEpochDay();
                for (Map.Entry<String, Double> e : r.navs.entrySet()) {
                    for (Store.Asset a : store.findAll(Store.ULIP, e.getKey())) {
                        a.prices.put(d, e.getValue());
                    }
                }
                store.applyAllSips();
                Alerts.check(MainActivity.this, store);
                store.save();
                toast("Saved " + r.navs.size() + " NAVs for " + pasteDate.format(DF));
                screen = 0; tab = 1; scopeKind = 1; scopeType = Store.ULIP;
                render(true);
            }
        });
        c.addView(save);
        results.addView(c);
        Ui.enter(c, 0);
    }

    // ================================================================== Analytics tab
    private int anSeg = 0, anRange = 0;
    private String fcKey;
    private Analytics.Forecast fcCache;
    private Analytics.Mix mixCache;
    private static final String[] PROFILES = {"Conservative", "Moderate", "Aggressive"};

    private android.content.SharedPreferences ap() { return Alerts.prefs(this); }

    private double[] classRet() {
        double[] r = Analytics.DEF_RETURN.clone();
        for (int i = 0; i < r.length; i++) r[i] = ap().getFloat("an_ret_" + i, (float) r[i]);
        return r;
    }

    private int horizon() { return ap().getInt("an_horizon", 10); }
    private double inflation() { return ap().getFloat("an_infl", 6f); }
    private boolean realMoney() { return ap().getBoolean("an_real", false); }
    private double extraMonthly() { return ap().getFloat("an_extra", 0f); }
    private double stepUp() { return ap().getFloat("an_step", 0f); }
    private int profile() { return ap().getInt("an_profile", 1); }
    private double expenses() { return ap().getFloat("an_expenses", 0f); }
    private int nowYear() { return LocalDate.now().getYear(); }

    /** Monthly SIP amount across visible holdings, and how many SIPs. */
    private double[] sipMonthly() {
        long today = LocalDate.now().toEpochDay();
        double t = 0; int n = 0;
        for (Store.Asset a : store.visible(-1))
            for (Ledger.Sip s : a.sips)
                if ((s.end == 0 || s.end >= today) && s.start <= today + 31) { t += s.amount; n++; }
        return new double[]{t, n};
    }

    private Analytics.Mix mixNow() {
        mixCache = Analytics.mix(store.visible(-1), classRet());
        return mixCache;
    }

    private Analytics.Forecast forecast() {
        Analytics.Mix m = mixNow();
        double monthly = sipMonthly()[0] + extraMonthly();
        String key = String.format(Locale.US, "%.0f|%.0f|%.2f|%.3f|%.3f", m.value, monthly, stepUp(), m.mu, m.sigma);
        if (!key.equals(fcKey) || fcCache == null) {
            fcCache = Analytics.simulate(m.value, monthly, stepUp(), m.mu, m.sigma, 1000, 7);
            fcKey = key;
        }
        return fcCache;
    }

    /** Divide by this to show an amount `years` ahead in today's money. */
    private double deflator(int years) { return realMoney() ? Math.pow(1 + inflation() / 100, years) : 1; }

    private boolean hasGoal() { return ap().getFloat("goal_amt", 0f) > 0 && ap().getInt("goal_year", 0) > nowYear(); }
    private double goalToday() { return ap().getFloat("goal_amt", 0f); }
    private int goalYears() { return ap().getInt("goal_year", 0) - nowYear(); }
    private String goalName() { String s = ap().getString("goal_name", ""); return s.isEmpty() ? "Your goal" : s; }
    /** The goal in rupees of the goal year (it was entered in today's money). */
    private double goalFuture() { return goalToday() * Math.pow(1 + inflation() / 100, goalYears()); }

    private void showAnalytics(boolean animate) {
        LinearLayout col = page(!animate);
        int idx = 0;
        add(col, header("Analytics", true, false, null), idx++, animate);
        col.addView(Ui.space(this, 12));
        View oc = ownerChips();
        if (oc != null) add(col, oc, idx++, animate);
        add(col, chips(new String[]{"Forecast", "Performance", "Suggestions"}, anSeg, new Pick() {
            @Override public void on(int i) { if (i != anSeg) { anSeg = i; render(false); } }
        }, true), idx++, animate);
        col.addView(Ui.space(this, 12));
        if (Store.value(store.visible(-1)) <= 0) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "Nothing to analyse yet", 16, Ui.TEXT, true));
            c.addView(Ui.text(this, "Add holdings, FDs or other assets and this page will show how they've done, where they could be "
                    + "in the years ahead and what's worth a look.", 13, Ui.SUB, false));
            add(col, c, idx, animate);
            return;
        }
        if (anSeg == 0) forecastSection(col, idx, animate);
        else if (anSeg == 1) performanceSection(col, idx, animate);
        else suggestionsSection(col, idx, animate);
        col.addView(Ui.space(this, 10));
        col.addView(Ui.text(this, "Projections are estimates from long-run assumptions, not guarantees, and past returns don't predict future ones. "
                + "Suggestions are general, rule-based and educational, not personalised investment advice. For advice, speak to a "
                + "SEBI-registered investment adviser.", 11, Ui.HINT, false));
    }

    // ------------------------------------------------------------------ forecast
    private void forecastSection(LinearLayout col, int idx, boolean animate) {
        final Analytics.Forecast f = forecast();
        final Analytics.Mix m = mixCache;
        final int H = horizon();
        final int n = H + 1;
        final double[] p10 = new double[n], p25 = new double[n], p50 = new double[n], p75 = new double[n], p90 = new double[n], put = new double[n];
        for (int y = 0; y <= H; y++) {
            double d = deflator(y);
            p10[y] = f.pct(y, 0.10) / d; p25[y] = f.pct(y, 0.25) / d; p50[y] = f.pct(y, 0.50) / d;
            p75[y] = f.pct(y, 0.75) / d; p90[y] = f.pct(y, 0.90) / d; put[y] = f.contributed(y) / d;
        }
        // hero
        LinearLayout h = Ui.heroCard(this, Ui.HERO1, Ui.HERO2);
        h.addView(Ui.text(this, "In " + H + " years (" + (nowYear() + H) + ") you could have", 13, Ui.HERO_SUB, false));
        TextView big = Ui.text(this, Ui.rupees(p50[H]).replaceAll("\\.\\d+$", ""), 30, Ui.TEXT, true);
        big.setSingleLine(true);
        h.addView(big);
        if (animate) Ui.countUp(big, Math.round(p50[H]), true);
        h.addView(Ui.text(this, "Middle outcome" + (realMoney() ? ", in today's money" : ""), 12, Ui.HERO_SUB, false));
        h.addView(Ui.space(this, 8));
        h.addView(Ui.text(this, "Likely range  " + Analytics.shortRupees(p10[H]) + " – " + Analytics.shortRupees(p90[H])
                + "   ·   8 in 10 outcomes", 13, Ui.TEXT, false));
        h.addView(Ui.text(this, "You'll have put in " + Analytics.shortRupees(put[H]) + " (today's value + future investing)", 12, Ui.HERO_SUB, false));
        h.addView(Ui.space(this, 10));
        final int[] hs = {5, 10, 15, 20, 25, 30};
        int hsel = 1;
        for (int i = 0; i < hs.length; i++) if (hs[i] == H) hsel = i;
        h.addView(chips(new String[]{"5y", "10y", "15y", "20y", "25y", "30y"}, hsel, new Pick() {
            @Override public void on(int i) { ap().edit().putInt("an_horizon", hs[i]).apply(); render(false); }
        }, true));
        add(col, h, idx++, animate);
        // chart
        LinearLayout cc = Ui.card(this);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, "Range of outcomes", 16, Ui.TEXT, true), Ui.weight(1));
        cc.addView(top);
        cc.addView(Ui.space(this, 6));
        cc.addView(chips(new String[]{"Future " + Ui.SYM.trim(), "Today's money"}, realMoney() ? 1 : 0, new Pick() {
            @Override public void on(int i) { ap().edit().putBoolean("an_real", i == 1).apply(); render(false); }
        }, false));
        final TextView read = Ui.text(this, "", 12, Ui.SUB, false);
        read.setPadding(0, (int) (8 * dp), 0, (int) (2 * dp));
        cc.addView(read);
        ForecastView fv = new ForecastView(this);
        fv.setData(p10, p25, p50, p75, p90, put, nowYear(), Ui.ACCENT);
        if (hasGoal() && goalYears() <= H) fv.setGoal(goalFuture() / deflator(goalYears()), goalYears());
        final ForecastView.Listener rl = new ForecastView.Listener() {
            @Override public void onYear(int y) {
                read.setText((nowYear() + y) + "  ·  middle " + Analytics.shortRupees(p50[y]) + "  ·  range "
                        + Analytics.shortRupees(p10[y]) + "–" + Analytics.shortRupees(p90[y]) + "  ·  put in " + Analytics.shortRupees(put[y]));
            }
        };
        fv.setListener(rl);
        rl.onYear(H);
        cc.addView(fv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (220 * dp)));
        if (animate) fv.play();
        LinearLayout lg = Ui.row(this);
        lg.setPadding(0, (int) (8 * dp), 0, 0);
        lg.addView(legendDot((Ui.ACCENT & 0x00FFFFFF) | 0x55000000, "Likely range"), Ui.weight(1));
        lg.addView(legendDot(Ui.ACCENT, "Middle outcome"), Ui.weight(1));
        lg.addView(legendDot(Ui.SUB, "Money put in"), Ui.weight(1));
        cc.addView(lg);
        cc.addView(Ui.text(this, "Drag across the chart to see any year. The darker band is the middle half of outcomes.", 11, Ui.HINT, false));
        add(col, cc, idx++, animate);
        // plan
        double[] sip = sipMonthly();
        LinearLayout pc = Ui.card(this);
        pc.addView(Ui.text(this, "Your plan", 16, Ui.TEXT, true));
        stat(pc, "Starting value", Ui.rupees(m.value), Ui.TEXT);
        stat(pc, "SIPs found", sip[1] > 0 ? Ui.rupees(sip[0]) + " a month (" + (int) sip[1] + ")" : "None", Ui.TEXT);
        LinearLayout er = Ui.row(this);
        er.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        er.addView(Ui.text(this, "Extra each month", 14, Ui.SUB, false), Ui.weight(1));
        er.addView(Ui.text(this, (extraMonthly() > 0 ? Ui.rupees(extraMonthly()) : Ui.SYM + "0") + "  ✎", 14, Ui.ACCENT, true));
        er.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { extraDialog(); } });
        pc.addView(er);
        pc.addView(Ui.text(this, "Increase investing each year by", 13, Ui.SUB, false));
        pc.addView(Ui.space(this, 6));
        final float[] steps = {0, 5, 10, 15};
        int ss = 0;
        for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - stepUp()) < 0.01) ss = i;
        pc.addView(chips(new String[]{"0%", "5%", "10%", "15%"}, ss, new Pick() {
            @Override public void on(int i) { ap().edit().putFloat("an_step", steps[i]).apply(); render(false); }
        }, true));
        pc.addView(Ui.space(this, 6));
        pc.addView(Ui.text(this, "SIPs come from the SIP schedules on your holdings. Each month's investing follows today's mix.", 11, Ui.HINT, false));
        add(col, pc, idx++, animate);
        add(col, goalCard(f), idx++, animate);
        add(col, assumptionsCard(m), idx++, animate);
    }

    private View legendDot(int color, String label) {
        LinearLayout r = Ui.row(this);
        View d = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(4 * dp);
        d.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (12 * dp), (int) (8 * dp));
        lp.setMargins(0, 0, (int) (6 * dp), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        r.addView(d, lp);
        r.addView(Ui.text(this, label, 11, Ui.SUB, false));
        return r;
    }

    private View goalCard(Analytics.Forecast f) {
        LinearLayout c = Ui.card(this);
        LinearLayout t = Ui.row(this);
        t.addView(Ui.text(this, "🎯  " + (hasGoal() ? goalName() : "Goal"), 16, Ui.TEXT, true), Ui.weight(1));
        TextView edit = Ui.text(this, hasGoal() ? "Edit" : "Set", 14, Ui.ACCENT, true);
        edit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { goalDialog(); } });
        t.addView(edit);
        c.addView(t);
        if (!hasGoal()) {
            c.addView(Ui.text(this, "Set an amount and a year (a house, education, retirement) to see your chance of getting there "
                    + "and how much to invest each month.", 13, Ui.SUB, false));
            return c;
        }
        int Y = goalYears();
        double T = goalFuture(), monthly = f.monthly;
        double ch = Y <= Analytics.MAX_YEARS ? f.chance(Y, T) : 0;
        c.addView(Ui.text(this, Ui.rupees(goalToday()).replaceAll("\\.\\d+$", "") + " in today's money by " + (nowYear() + Y)
                + "  ·  " + Analytics.shortRupees(T) + " then, at " + fmt1(inflation()) + "% inflation", 12, Ui.SUB, false));
        c.addView(Ui.space(this, 8));
        LinearLayout r = Ui.row(this);
        int col = ch >= 0.8 ? Ui.GREEN : ch >= 0.5 ? Ui.ACCENT : Ui.RED;
        r.addView(Ui.text(this, String.format(Locale.US, "%.0f%%", ch * 100), 28, col, true));
        TextView lbl = Ui.text(this, "  chance of reaching it at your current pace", 13, Ui.SUB, false);
        r.addView(lbl, Ui.weight(1));
        c.addView(r);
        c.addView(Ui.bar(this, (float) ch, col));
        c.addView(Ui.space(this, 8));
        if (Y > Analytics.MAX_YEARS) {
            c.addView(Ui.text(this, "Forecasts go up to " + Analytics.MAX_YEARS + " years.", 13, Ui.SUB, false));
            return c;
        }
        double need80 = f.monthlyNeeded(Y, T, 0.8);
        if (ch >= 0.8) {
            c.addView(Ui.text(this, "✓ On track. " + (need80 < monthly ? "Even " + Ui.rupees(Math.ceil(need80 / 100) * 100)
                    + " a month would give an 80% chance." : ""), 13, Ui.GREEN, false));
        } else {
            double more = need80 - monthly;
            c.addView(Ui.text(this, "For an 80% chance, invest about " + Ui.rupees(Math.ceil(need80 / 100) * 100) + " a month"
                    + (monthly > 0 ? " (" + Ui.rupees(Math.ceil(more / 100) * 100) + " more than now)" : "")
                    + (stepUp() > 0 ? ", rising " + fmt1(stepUp()) + "% a year." : "."), 13, Ui.TEXT, false));
            int yrs = -1;
            for (int y = Y + 1; y <= Analytics.MAX_YEARS; y++) {
                if (f.chance(y, goalToday() * Math.pow(1 + inflation() / 100, y), monthly) >= 0.8) { yrs = y; break; }
            }
            c.addView(Ui.space(this, 4));
            c.addView(Ui.text(this, yrs > 0 ? "Or keep going as you are and reach it by about " + (nowYear() + yrs) + "."
                    : "At the current pace it isn't likely within " + Analytics.MAX_YEARS + " years.", 13, Ui.SUB, false));
        }
        return c;
    }

    private String fmt1(double v) { return v == Math.rint(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v); }

    private View assumptionsCard(Analytics.Mix m) {
        LinearLayout c = Ui.card(this);
        LinearLayout t = Ui.row(this);
        t.addView(Ui.text(this, "How this is worked out", 16, Ui.TEXT, true), Ui.weight(1));
        TextView e = Ui.text(this, "Edit", 14, Ui.ACCENT, true);
        e.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { assumptionsDialog(); } });
        t.addView(e);
        c.addView(t);
        stat(c, "Expected return", fmt1(Math.round(m.mu * 10) / 10.0) + "% a year", Ui.TEXT);
        stat(c, "Ups and downs (volatility)", fmt1(Math.round(m.sigma * 10) / 10.0) + "% a year", Ui.TEXT);
        stat(c, "Inflation", fmt1(inflation()) + "% a year", Ui.TEXT);
        double[] cr = classRet();
        for (int i = 0; i < Analytics.CLASS_NAMES.length; i++) {
            if (m.byClass[i] <= 0) continue;
            double share = m.byClass[i] / m.value;
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (8 * dp), 0, (int) (3 * dp));
            r.addView(Ui.text(this, Analytics.CLASS_NAMES[i], 13, Ui.TEXT, false), Ui.weight(1));
            r.addView(Ui.text(this, String.format(Locale.US, "%.0f%%", share * 100) + (i == Analytics.FIXED ? "  · own rates" : "  · " + fmt1(cr[i]) + "%"),
                    12, Ui.SUB, false));
            c.addView(r);
            c.addView(Ui.bar(this, (float) share, Ui.PALETTE[i % Ui.PALETTE.length]));
        }
        c.addView(Ui.space(this, 8));
        c.addView(Ui.text(this, "Each holding gets a long-run return for its type; FDs, RDs, PPF (" + fmt1(Analytics.PPF_RATE) + "%) and EPF ("
                + fmt1(Analytics.EPF_RATE) + "%) use their own rates. Ups and downs come from each holding's last 3 years of prices where "
                + "available (" + m.withHistory + " of " + m.holdings + " holdings), otherwise typical values for its type. "
                + "1,000 simulated paths, monthly. Loans and taxes aren't included.", 11, Ui.HINT, false));
        return c;
    }

    private void extraDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "On top of your SIPs, how much more will you invest each month?", 13, Ui.SUB, false));
        final EditText amt = numberField("e.g. 10000", extraMonthly() > 0 ? String.format(Locale.US, "%.0f", extraMonthly()) : null);
        box.addView(amt);
        dialog().setTitle("Extra each month").setView(padded(box))
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Double v = num(amt.getText().toString());
                        ap().edit().putFloat("an_extra", v == null ? 0f : (float) Math.max(0, v)).apply();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void goalDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "Goal name", 12, Ui.SUB, false));
        final EditText name = new EditText(this);
        name.setHint("e.g. House, Retirement, Education");
        name.setSingleLine(true);
        if (hasGoal()) name.setText(ap().getString("goal_name", ""));
        box.addView(name);
        box.addView(Ui.text(this, "Amount in today's money (" + Ui.SYM.trim() + ")", 12, Ui.SUB, false));
        final EditText amt = numberField("e.g. 10000000", hasGoal() ? String.format(Locale.US, "%.0f", goalToday()) : null);
        box.addView(amt);
        box.addView(Ui.text(this, "Target year", 12, Ui.SUB, false));
        final EditText yr = numberField("e.g. " + (nowYear() + 10), hasGoal() ? String.valueOf(ap().getInt("goal_year", 0)) : null);
        yr.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(yr);
        box.addView(Ui.text(this, "The app grows the amount with inflation to the target year.", 11, Ui.HINT, false));
        AlertDialog.Builder b = dialog().setTitle("Goal").setView(padded(box))
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Double a = num(amt.getText().toString()), y = num(yr.getText().toString());
                        if (a == null || a <= 0) { toast("Enter the amount"); return; }
                        if (y == null || y <= nowYear() || y > nowYear() + 50) { toast("Enter a year after " + nowYear()); return; }
                        ap().edit().putString("goal_name", name.getText().toString().trim()).putFloat("goal_amt", a.floatValue())
                                .putInt("goal_year", y.intValue()).apply();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null);
        if (hasGoal()) b.setNeutralButton("Remove", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                ap().edit().remove("goal_name").remove("goal_amt").remove("goal_year").apply();
                render(false);
            }
        });
        b.show();
    }

    private void assumptionsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "Expected long-run return, % a year", 12, Ui.SUB, false));
        final double[] cr = classRet();
        final int[] which = {Analytics.EQUITY, Analytics.HYBRID, Analytics.DEBT, Analytics.GOLD, Analytics.REAL, Analytics.CASH};
        final EditText[] f = new EditText[which.length];
        for (int i = 0; i < which.length; i++) {
            LinearLayout r = Ui.row(this);
            r.addView(Ui.text(this, Analytics.CLASS_NAMES[which[i]], 14, Ui.TEXT, false), Ui.weight(1));
            f[i] = numberField(fmt1(Analytics.DEF_RETURN[which[i]]), fmt1(cr[which[i]]));
            r.addView(f[i], new LinearLayout.LayoutParams((int) (90 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(r);
        }
        LinearLayout ir = Ui.row(this);
        ir.addView(Ui.text(this, "Inflation", 14, Ui.TEXT, false), Ui.weight(1));
        final EditText inf = numberField("6", fmt1(inflation()));
        ir.addView(inf, new LinearLayout.LayoutParams((int) (90 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(ir);
        dialog().setTitle("Assumptions").setView(padded(box))
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        android.content.SharedPreferences.Editor ed = ap().edit();
                        for (int i = 0; i < which.length; i++) {
                            Double v = num(f[i].getText().toString());
                            if (v != null && v > -20 && v < 40) ed.putFloat("an_ret_" + which[i], v.floatValue());
                        }
                        Double v = num(inf.getText().toString());
                        if (v != null && v >= 0 && v < 20) ed.putFloat("an_infl", v.floatValue());
                        ed.apply();
                        render(false);
                    }
                })
                .setNeutralButton("Defaults", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        android.content.SharedPreferences.Editor ed = ap().edit();
                        for (int i = 0; i < Analytics.CLASS_NAMES.length; i++) ed.remove("an_ret_" + i);
                        ed.remove("an_infl").apply();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Small forecast teaser for the dashboard. */
    private View forecastCard() {
        if (mixNow().value <= 0) return null;
        Analytics.Forecast f = forecast();
        int H = horizon();
        double d = deflator(H);
        LinearLayout c = Ui.card(this);
        LinearLayout t = Ui.row(this);
        t.addView(Ui.text(this, "🔮  Forecast", 16, Ui.TEXT, true), Ui.weight(1));
        t.addView(Ui.text(this, "›", 18, Ui.SUB, true));
        c.addView(t);
        c.addView(Ui.text(this, "In " + H + " years: about " + Analytics.shortRupees(f.pct(H, 0.5) / d)
                + (realMoney() ? " in today's money" : ""), 14, Ui.TEXT, false));
        c.addView(Ui.text(this, "Likely range " + Analytics.shortRupees(f.pct(H, 0.1) / d) + " – " + Analytics.shortRupees(f.pct(H, 0.9) / d), 12, Ui.SUB, false));
        if (hasGoal() && goalYears() <= Analytics.MAX_YEARS)
            c.addView(Ui.text(this, "🎯 " + goalName() + ": " + String.format(Locale.US, "%.0f%%", f.chance(goalYears(), goalFuture()) * 100)
                    + " chance by " + (nowYear() + goalYears()), 12, Ui.SUB, false));
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { anSeg = 0; selectTab(2); } });
        return c;
    }

    // ------------------------------------------------------------------ performance
    private void performanceSection(LinearLayout col, int idx, boolean animate) {
        List<Store.Asset> scope = store.visible(-1);
        Store.Asset nifty = store.find(Store.INDEX, "^NSEI");
        Analytics.Perf pf = Analytics.performance(scope, nifty);
        if (pf.index.size() < 2) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "Not enough price history yet", 16, Ui.TEXT, true));
            c.addView(Ui.text(this, "Refresh prices to load history for your stocks, funds and ULIP funds.", 13, Ui.SUB, false));
            add(col, c, idx, animate);
            return;
        }
        // returns tiles
        LinearLayout rc = Ui.card(this);
        rc.addView(Ui.text(this, "Returns of today's holdings", 16, Ui.TEXT, true));
        rc.addView(Ui.text(this, "As if you'd held today's units all along, so it shows how your current picks have done.", 12, Ui.SUB, false));
        rc.addView(Ui.space(this, 10));
        LinearLayout tiles = Ui.row(this);
        String[] lb = {"1M", "3M", "6M", "1Y", "3Y p.a."};
        Double[] vs = {pf.r1m, pf.r3m, pf.r6m, pf.r1y, pf.cagr3y};
        for (int i = 0; i < lb.length; i++) {
            LinearLayout tl = new LinearLayout(this);
            tl.setOrientation(LinearLayout.VERTICAL);
            tl.setGravity(Gravity.CENTER_HORIZONTAL);
            tl.setPadding((int) (2 * dp), (int) (8 * dp), (int) (2 * dp), (int) (8 * dp));
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.CARD2); g.setCornerRadius(12 * dp);
            tl.setBackground(g);
            tl.addView(Ui.text(this, lb[i], 11, Ui.SUB, true));
            TextView v = Ui.text(this, vs[i] == null ? "—" : String.format(Locale.US, "%+.1f%%", vs[i]), 13, Ui.colorFor(vs[i]), true);
            v.setSingleLine(true);
            tl.addView(v);
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(i == 0 ? 0 : (int) (3 * dp), 0, i == lb.length - 1 ? 0 : (int) (3 * dp), 0);
            tiles.addView(tl, lp);
        }
        rc.addView(tiles);
        Object[] xr = Store.xirr(scope);
        if (xr[0] != null) {
            rc.addView(Ui.space(this, 6));
            stat(rc, "Your XIRR (actual buys & sells)", Ui.pct(((Double) xr[0]) * 100), Ui.colorFor((Double) xr[0]));
        }
        if (pf.coverage < 0.98)
            rc.addView(Ui.text(this, String.format(Locale.US, "Covers %.0f%% of your market holdings by value (the rest has no price history yet).",
                    pf.coverage * 100), 11, Ui.HINT, false));
        add(col, rc, idx++, animate);
        // chart vs Nifty
        LinearLayout cc = Ui.card(this);
        cc.addView(Ui.text(this, "Your holdings vs Nifty 50", 16, Ui.TEXT, true));
        cc.addView(Ui.space(this, 6));
        cc.addView(chips(new String[]{"1 year", "3 years"}, anRange, new Pick() {
            @Override public void on(int i) { if (i != anRange) { anRange = i; render(false); } }
        }, false));
        cc.addView(Ui.space(this, 6));
        long last = pf.index.lastKey(), from = last - (anRange == 0 ? 365 : 3 * 365);
        List<ChartView.Series> s = new ArrayList<>();
        s.add(new ChartView.Series("Your holdings", Ui.ACCENT, pf.index, from));
        if (pf.bench.size() > 2) s.add(new ChartView.Series("Nifty 50", Ui.TYPE_COLORS[Store.STOCK], pf.bench, from));
        ChartView ch = new ChartView(this, false);
        ch.setSeries(s, ChartView.LINE, true, animate);
        cc.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * dp)));
        if (pf.bench.size() <= 2)
            cc.addView(Ui.text(this, "Add the Nifty 50 (^NSEI) under Markets to compare.", 11, Ui.HINT, false));
        add(col, cc, idx++, animate);
        // risk
        LinearLayout rk = Ui.card(this);
        rk.addView(Ui.text(this, "Risk", 16, Ui.TEXT, true));
        if (pf.vol != null) {
            stat(rk, "Ups and downs (1 year)", fmt1(Math.round(pf.vol * 10) / 10.0) + "% a year", Ui.TEXT);
            rk.addView(Ui.text(this, pf.vol < 8 ? "Low: closer to a debt fund." : pf.vol < 14 ? "Moderate: like a balanced fund."
                    : pf.vol < 20 ? "Typical of an equity fund." : "High: more than a typical equity fund.", 11, Ui.HINT, false));
        }
        if (pf.maxDD < 0) {
            stat(rk, "Worst fall from a high", String.format(Locale.US, "%.1f%%", pf.maxDD), Ui.RED);
            rk.addView(Ui.text(this, LocalDate.ofEpochDay(pf.ddPeak).format(DF) + " → " + LocalDate.ofEpochDay(pf.ddLow).format(DF)
                    + (pf.ddRecovered > 0 ? " · back to the high by " + LocalDate.ofEpochDay(pf.ddRecovered).format(DF) : " · not yet back to the high"),
                    11, Ui.HINT, false));
        }
        if (pf.beta != null) {
            stat(rk, "Sensitivity to Nifty (beta)", String.format(Locale.US, "%.2f", pf.beta), Ui.TEXT);
            rk.addView(Ui.text(this, String.format(Locale.US, "When the Nifty moves 1%%, your holdings have tended to move about %.1f%%.", pf.beta),
                    11, Ui.HINT, false));
        }
        if (!pf.months.isEmpty()) {
            Map.Entry<Long, Double> best = null, worst = null;
            for (Map.Entry<Long, Double> e : pf.months.entrySet()) {
                if (best == null || e.getValue() > best.getValue()) best = e;
                if (worst == null || e.getValue() < worst.getValue()) worst = e;
            }
            java.time.format.DateTimeFormatter mf = java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.US);
            stat(rk, "Best month", LocalDate.ofEpochDay(best.getKey()).format(mf) + "  " + Ui.pct(best.getValue()), Ui.GREEN);
            stat(rk, "Worst month", LocalDate.ofEpochDay(worst.getKey()).format(mf) + "  " + Ui.pct(worst.getValue()), Ui.colorFor(worst.getValue()));
        }
        add(col, rk, idx++, animate);
        // month grid
        if (pf.months.size() >= 3) {
            LinearLayout mc = Ui.card(this);
            mc.addView(Ui.text(this, "Month by month", 16, Ui.TEXT, true));
            mc.addView(Ui.space(this, 8));
            java.time.format.DateTimeFormatter mf = java.time.format.DateTimeFormatter.ofPattern("MMM", Locale.US);
            double mx = 1;
            for (double v : pf.months.values()) mx = Math.max(mx, Math.abs(v));
            List<Map.Entry<Long, Double>> ml = new ArrayList<>(pf.months.entrySet());
            for (int r0 = 0; r0 < ml.size(); r0 += 4) {
                LinearLayout row = Ui.row(this);
                for (int k = r0; k < r0 + 4; k++) {
                    LinearLayout cell = new LinearLayout(this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
                    if (k < ml.size()) {
                        double v = ml.get(k).getValue();
                        int base = v >= 0 ? Ui.GREEN : Ui.RED;
                        int alpha = (int) (0x22 + 0x66 * Math.min(1, Math.abs(v) / mx));
                        GradientDrawable g = new GradientDrawable();
                        g.setColor((base & 0x00FFFFFF) | (alpha << 24)); g.setCornerRadius(10 * dp);
                        cell.setBackground(g);
                        cell.addView(Ui.text(this, LocalDate.ofEpochDay(ml.get(k).getKey()).format(mf), 11, Ui.SUB, false));
                        cell.addView(Ui.text(this, String.format(Locale.US, "%+.1f%%", v), 13, Ui.TEXT, true));
                    }
                    LinearLayout.LayoutParams lp = Ui.weight(1);
                    lp.setMargins((int) (3 * dp), (int) (3 * dp), (int) (3 * dp), (int) (3 * dp));
                    row.addView(cell, lp);
                }
                mc.addView(row);
            }
            add(col, mc, idx++, animate);
        }
        // contributors
        List<Store.Asset> withCost = new ArrayList<>();
        double totalGain = 0;
        for (Store.Asset a : scope)
            if (a.held() && a.invested > 0 && (a.type == Store.MF || a.type == Store.STOCK || a.type == Store.ULIP)) {
                withCost.add(a); totalGain += a.value() - a.invested;
            }
        if (withCost.size() >= 2) {
            java.util.Collections.sort(withCost, new java.util.Comparator<Store.Asset>() {
                @Override public int compare(Store.Asset x, Store.Asset y) { return Double.compare(y.value() - y.invested, x.value() - x.invested); }
            });
            double mx = 1;
            for (Store.Asset a : withCost) mx = Math.max(mx, Math.abs(a.value() - a.invested));
            LinearLayout gc = Ui.card(this);
            gc.addView(Ui.text(this, "What's driving your gains", 16, Ui.TEXT, true));
            gc.addView(Ui.text(this, "Unrealised gain or loss on what you hold now · total " + signed(totalGain).replaceAll("\\.\\d+$", ""), 12, Ui.SUB, false));
            List<Store.Asset> show = new ArrayList<>();
            for (int i = 0; i < Math.min(5, withCost.size()); i++) if (withCost.get(i).value() > withCost.get(i).invested) show.add(withCost.get(i));
            for (int i = withCost.size() - 1, k = 0; i >= 0 && k < 3; i--, k++) {
                Store.Asset a = withCost.get(i);
                if (a.value() < a.invested && !show.contains(a)) show.add(a);
            }
            for (Store.Asset a : show) {
                double g = a.value() - a.invested;
                LinearLayout r = Ui.row(this);
                r.setPadding(0, (int) (10 * dp), 0, (int) (3 * dp));
                r.addView(Ui.text(this, shortName(a), 13, Ui.TEXT, false), Ui.weight(1));
                r.addView(Ui.text(this, signed(g).replaceAll("\\.\\d+$", "") + "  " + Ui.pct(g / a.invested * 100), 12, Ui.colorFor(g), true));
                gc.addView(r);
                gc.addView(Ui.bar(this, (float) (Math.abs(g) / mx), g >= 0 ? Ui.GREEN : Ui.RED));
            }
            add(col, gc, idx++, animate);
        }
        // class mix donut
        Analytics.Mix m = mixNow();
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<Integer> cl = new ArrayList<>();
        for (int i = 0; i < Analytics.CLASS_NAMES.length; i++)
            if (m.byClass[i] > 0) { names.add(Analytics.CLASS_NAMES[i]); vals.add(m.byClass[i]); cl.add(Ui.PALETTE[i % Ui.PALETTE.length]); }
        if (!names.isEmpty()) {
            LinearLayout dc = Ui.card(this);
            dc.addView(Ui.text(this, "Mix by asset class", 16, Ui.TEXT, true));
            dc.addView(Ui.space(this, 8));
            LinearLayout body = Ui.row(this);
            DonutView d = new DonutView(this);
            int[] colors = new int[cl.size()];
            for (int i = 0; i < colors.length; i++) colors[i] = cl.get(i);
            d.setData(names, vals, colors, String.format(Locale.US, "%.0f%%", m.equityShare), "in shares");
            body.addView(d, new LinearLayout.LayoutParams((int) (130 * dp), (int) (130 * dp)));
            LinearLayout lgd = new LinearLayout(this);
            lgd.setOrientation(LinearLayout.VERTICAL);
            lgd.setPadding((int) (14 * dp), 0, 0, 0);
            for (int i = 0; i < names.size(); i++)
                lgd.addView(legendDot(colors[i], names.get(i) + "  " + String.format(Locale.US, "%.0f%%", vals.get(i) / m.value * 100)));
            body.addView(lgd, Ui.weight(1));
            dc.addView(body);
            dc.addView(Ui.text(this, "\"In shares\" counts equity fully and hybrid funds/NPS as about 60% equity.", 11, Ui.HINT, false));
            add(col, dc, idx++, animate);
        }
    }

    // ------------------------------------------------------------------ suggestions
    private static class Sug {
        final int pri; final String icon, title, detail, action; final Runnable run;
        Sug(int pri, String icon, String title, String detail, String action, Runnable run) {
            this.pri = pri; this.icon = icon; this.title = title; this.detail = detail; this.action = action; this.run = run;
        }
    }

    private void suggestionsSection(LinearLayout col, int idx, boolean animate) {
        LinearLayout pc = Ui.card(this);
        pc.addView(Ui.text(this, "Your risk profile", 16, Ui.TEXT, true));
        int[] b = Analytics.band(profile());
        pc.addView(Ui.text(this, "Used to judge your equity share (" + b[0] + "–" + b[1] + "% for " + PROFILES[profile()].toLowerCase(Locale.ROOT) + ").",
                12, Ui.SUB, false));
        pc.addView(Ui.space(this, 8));
        pc.addView(chips(PROFILES, profile(), new Pick() {
            @Override public void on(int i) { ap().edit().putInt("an_profile", i).apply(); render(false); }
        }, true));
        LinearLayout er = Ui.row(this);
        er.setPadding(0, (int) (12 * dp), 0, (int) (2 * dp));
        er.addView(Ui.text(this, "Monthly expenses", 14, Ui.SUB, false), Ui.weight(1));
        er.addView(Ui.text(this, (expenses() > 0 ? Ui.rupees(expenses()) : "Not set") + "  ✎", 14, Ui.ACCENT, true));
        er.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { expensesDialog(); } });
        pc.addView(er);
        pc.addView(Ui.text(this, "For the emergency-fund check.", 11, Ui.HINT, false));
        add(col, pc, idx++, animate);
        List<Sug> list = suggestions();
        if (list.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "✓  Nothing stands out", 16, Ui.TEXT, true));
            c.addView(Ui.text(this, "Your mix, cash buffer and costs look in line with the checks here.", 13, Ui.SUB, false));
            add(col, c, idx++, animate);
        }
        String[] tag = {"Important", "Worth a look", "Idea"};
        int[] tc = {Ui.RED, Ui.TYPE_COLORS[Store.STOCK], Ui.ACCENT};
        for (final Sug s : list) {
            LinearLayout c = Ui.card(this);
            LinearLayout t = Ui.row(this);
            t.addView(Ui.text(this, s.icon + "  " + s.title, 15, Ui.TEXT, true), Ui.weight(1));
            c.addView(t);
            c.addView(Ui.space(this, 4));
            LinearLayout pr = Ui.row(this);
            pr.addView(Ui.pill(this, tag[s.pri], tc[s.pri]));
            c.addView(pr);
            c.addView(Ui.space(this, 6));
            TextView d = Ui.text(this, s.detail, 13, Ui.SUB, false);
            d.setLineSpacing(3 * dp, 1);
            c.addView(d);
            if (s.run != null) {
                TextView a = Ui.text(this, s.action + "  ›", 13, Ui.ACCENT, true);
                a.setPadding(0, (int) (8 * dp), 0, 0);
                c.addView(a);
                c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { s.run.run(); } });
            }
            add(col, c, idx++, animate);
        }
        LinearLayout links = Ui.card(this);
        TextView ins = Ui.text(this, "💡  More observations in Insights  ›", 14, Ui.ACCENT, true);
        ins.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        ins.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { screen = 5; render(true); } });
        links.addView(ins);
        TextView tx = Ui.text(this, "🧾  Capital gains & tax  ›", 14, Ui.ACCENT, true);
        tx.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        tx.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { screen = 6; render(true); } });
        links.addView(tx);
        add(col, links, idx, animate);
    }

    private void expensesDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "Roughly what your household spends in a month, including EMIs and rent.", 13, Ui.SUB, false));
        final EditText amt = numberField("e.g. 60000", expenses() > 0 ? String.format(Locale.US, "%.0f", expenses()) : null);
        box.addView(amt);
        dialog().setTitle("Monthly expenses").setView(padded(box))
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Double v = num(amt.getText().toString());
                        ap().edit().putFloat("an_expenses", v == null ? 0f : (float) Math.max(0, v)).apply();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private List<Sug> suggestions() {
        List<Sug> out = new ArrayList<>();
        List<Store.Asset> all = store.visible(-1);
        Analytics.Mix m = mixNow();
        if (m.value <= 0) return out;
        double total = m.value;
        double[] cr = classRet();
        long today = LocalDate.now().toEpochDay();
        Analytics.Forecast f = forecast();
        int H = horizon();
        // 1. equity share vs profile
        int[] band = Analytics.band(profile());
        String prof = PROFILES[profile()].toLowerCase(Locale.ROOT);
        if (m.equityShare > band[1] + 5) {
            double shift = (m.equityShare - band[1]) / 100 * total;
            out.add(new Sug(m.equityShare > band[1] + 15 ? 0 : 1, "⚖️",
                    String.format(Locale.US, "Equity is %.0f%%, above the %d–%d%% range for a %s investor", m.equityShare, band[0], band[1], prof),
                    "Moving about " + Ui.rupees(Math.round(shift / 1000) * 1000.0) + " into debt funds, FDs or PPF would bring it to " + band[1]
                            + "%. You can also get there gradually by sending new investing to debt instead of selling. A big market fall would hit "
                            + "this mix harder than your profile suggests.", null, null));
        } else if (m.equityShare < band[0] - 5) {
            double shift = (band[0] - m.equityShare) / 100 * total;
            out.add(new Sug(1, "⚖️",
                    String.format(Locale.US, "Equity is %.0f%%, below the %d–%d%% range for a %s investor", m.equityShare, band[0], band[1], prof),
                    "About " + Ui.rupees(Math.round(shift / 1000) * 1000.0) + " more in equity would bring it to " + band[0] + "%. Doing it slowly, "
                            + "for example through monthly SIPs over 6–12 months, spreads out the timing risk.", null, null));
        }
        // 2. emergency fund & idle cash
        double liquid = 0, savings = 0, bestDep = 0;
        for (Store.Asset a : all) {
            if (a.type != Store.OTHER) continue;
            boolean sv = a.kind != null && a.kind.startsWith("Savings");
            if (sv || a.isDeposit()) liquid += a.value();
            if (sv) savings += a.value();
            if (sv || a.isDeposit()) bestDep = Math.max(bestDep, a.rate > 0 ? a.rate : sv ? cr[Analytics.CASH] : 0);
        }
        for (Store.Asset a : all) if (Analytics.classOf(a) == Analytics.CASH && a.type != Store.OTHER) liquid += a.value();   // liquid funds
        double E = expenses();
        if (E > 0) {
            double months = liquid / E;
            if (months < 6) {
                double gap = 6 * E - liquid;
                out.add(new Sug(months < 3 ? 0 : 1, "🛟", String.format(Locale.US, "Emergency fund covers %.1f months", months),
                        "A common rule is about 6 months of expenses (" + Ui.rupees(6 * E).replaceAll("\\.\\d+$", "") + ") in savings, a sweep-in FD "
                                + "or a liquid fund, ready to use. Topping up " + Ui.rupees(gap).replaceAll("\\.\\d+$", "")
                                + " before investing more keeps you from selling investments in a bad month.", null, null));
            } else if (savings > 0 && liquid > 24 * E) {
                double idle = Math.min(savings, liquid - 12 * E);
                double gain = idle * (Math.pow(1 + cr[Analytics.DEBT] / 100, 5) - Math.pow(1 + cr[Analytics.CASH] / 100, 5));
                out.add(new Sug(1, "💤", String.format(Locale.US, "%.0f months of expenses in cash & deposits", months),
                        "Beyond 6–12 months, money you won't need for 3+ years usually earns more elsewhere. At the assumptions here, moving "
                                + Ui.rupees(idle).replaceAll("\\.\\d+$", "") + " from a " + fmt1(cr[Analytics.CASH]) + "% savings account to a "
                                + fmt1(cr[Analytics.DEBT]) + "% debt option adds roughly " + Ui.rupees(gain).replaceAll("\\.\\d+$", "") + " over 5 years.",
                        null, null));
            }
        } else {
            out.add(new Sug(2, "🛟", "Add your monthly expenses", "With it, the app checks your emergency fund and spots cash sitting idle.",
                    "Set expenses", new Runnable() { @Override public void run() { expensesDialog(); } }));
        }
        // 3. costly loans
        for (Store.Asset l : store.visible(Store.LOAN)) {
            if (l.rate >= 9 && l.value() > 0 && (bestDep <= 0 || l.rate > bestDep + 1.5)) {
                out.add(new Sug(1, "🏦", shortName(l) + " costs " + fmt1(l.rate) + "% a year",
                        "Each " + Ui.rupees(100000).replaceAll("\\.\\d+$", "") + " prepaid saves about " + Ui.rupees(l.rate * 1000).replaceAll("\\.\\d+$", "") + " a year in interest"
                                + (bestDep > 0 ? ", versus about " + Ui.rupees(bestDep * 1000).replaceAll("\\.\\d+$", "") + " (taxable) from your deposits" : "")
                                + ". Keep your emergency fund intact and check prepayment charges; home-loan tax benefits can change the maths.", null, null));
            }
        }
        // 4. goal
        if (hasGoal() && goalYears() <= Analytics.MAX_YEARS) {
            double ch = f.chance(goalYears(), goalFuture());
            double need = f.monthlyNeeded(goalYears(), goalFuture(), 0.8);
            Runnable open = new Runnable() { @Override public void run() { anSeg = 0; render(true); } };
            if (ch < 0.8)
                out.add(new Sug(ch < 0.5 ? 0 : 1, "🎯", goalName() + ": " + String.format(Locale.US, "%.0f%%", ch * 100) + " chance by " + (nowYear() + goalYears()),
                        "Investing about " + Ui.rupees(Math.ceil(need / 100) * 100) + " a month in total ("
                                + Ui.rupees(Math.ceil(Math.max(0, need - f.monthly) / 100) * 100) + " more) lifts it to 80%. A later date or a "
                                + "yearly step-up also helps.", "See forecast", open));
            else out.add(new Sug(2, "🎯", goalName() + " is on track", String.format(Locale.US, "%.0f%% chance at your current pace.", ch * 100), "See forecast", open));
        } else if (!hasGoal()) {
            out.add(new Sug(2, "🎯", "Set a goal", "See the chance of reaching an amount by a year, and what to invest each month to get there.",
                    "Set a goal", new Runnable() { @Override public void run() { goalDialog(); } }));
        }
        // 5. regular investing / step-up
        if (f.monthly <= 0) {
            double v = f.pct(H, 0.5, 10000) - f.pct(H, 0.5, 0);
            out.add(new Sug(1, "🔁", "No regular investing found",
                    Ui.rupees(10000).replaceAll("\\.\\d+$", "") + " a month at your mix's assumptions could add about " + Analytics.shortRupees(v) + " in " + H + " years (middle outcome). "
                            + "Add a SIP schedule to a holding, or an extra monthly amount on the Forecast page.", "Add extra monthly",
                    new Runnable() { @Override public void run() { extraDialog(); } }));
        } else if (stepUp() <= 0) {
            Analytics.Forecast s10 = Analytics.simulate(f.start, f.monthly, 10, f.mu, f.sigma, 1000, 7);
            double diff = s10.pct(H, 0.5) - f.pct(H, 0.5);
            out.add(new Sug(2, "📈", "Raise your investing 10% each year",
                    "Growing your " + Ui.rupees(f.monthly).replaceAll("\\.\\d+$", "") + " a month by 10% yearly, roughly in line with pay rises, could add about "
                            + Analytics.shortRupees(diff) + " in " + H + " years (middle outcome).", "Use 10% step-up",
                    new Runnable() { @Override public void run() { ap().edit().putFloat("an_step", 10f).apply(); anSeg = 0; render(true); } }));
        }
        // 6. regular plans
        double regVal = 0;
        for (Store.Asset a : all) if (a.type == Store.MF && a.name.toLowerCase(Locale.ROOT).contains("regular")) regVal += a.value();
        if (regVal > 0) {
            double mu = m.mu / 100;
            double drag = regVal * (Math.pow(1 + mu, 10) - Math.pow(1 + mu - 0.007, 10));
            out.add(new Sug(1, "💸", "Regular plans may cost about " + Ui.rupees(regVal * 0.007).replaceAll("\\.\\d+$", "") + " a year",
                    "Regular plans usually charge around 0.5–1% a year more than the Direct plan of the same fund. On "
                            + Ui.rupees(regVal).replaceAll("\\.\\d+$", "") + ", a 0.7% gap is roughly " + Analytics.shortRupees(drag)
                            + " over 10 years. Switching counts as a sale for tax and may have an exit load, so check the Tax page first.",
                    "Open Tax", new Runnable() { @Override public void run() { screen = 6; render(true); } }));
        }
        // 7. very volatile holdings
        int vc = 0;
        for (Store.Asset a : all) {
            if (vc >= 2 || !a.held()) continue;
            Double hv = Analytics.histVol(a);
            double w = a.value() / total;
            if (hv != null && hv > 35 && w > 0.05) {
                vc++;
                out.add(new Sug(1, "🎢", shortName(a) + " swings a lot",
                        String.format(Locale.US, "It's %.0f%% of your money and its price has moved about %.0f%% a year, about twice a typical equity fund. "
                                + "Make sure a 40–50%% fall in it wouldn't upset your plans.", w * 100, hv), null, null));
            }
        }
        // 8. equity funds trailing the Nifty over 3 years
        Store.Asset nifty = store.find(Store.INDEX, "^NSEI");
        Double n3 = nifty == null ? null : nifty.changeOver(1095);
        if (n3 != null) {
            double nc = (Math.pow(1 + n3 / 100, 1 / 3.0) - 1) * 100;
            int lc = 0;
            Set<String> seen = new java.util.HashSet<>();
            for (Store.Asset a : all) {
                if (lc >= 3 || (a.type != Store.MF && a.type != Store.ULIP) || !a.held() || Analytics.classOf(a) != Analytics.EQUITY || !seen.add(a.code + a.name)) continue;
                Double r3 = a.changeOver(1095);
                if (r3 == null) continue;
                double c3 = (Math.pow(1 + r3 / 100, 1 / 3.0) - 1) * 100;
                if (c3 < nc - 3) {
                    lc++;
                    out.add(new Sug(1, "🐢", shortName(a) + " trails the Nifty 50 over 3 years",
                            String.format(Locale.US, "%.1f%% a year vs %.1f%% for the Nifty 50. Compare it with other funds in its category and an index fund "
                                    + "before deciding; a fund's style can lag for a while and then catch up.", c3, nc), null, null));
                }
            }
        }
        // 9. tax-free long-term gains
        boolean ltcg = false;
        for (Store.Asset a : all) {
            if (!equityLike(a) || !a.held() || a.value() <= a.invested || a.txns.isEmpty()) continue;
            long first = Long.MAX_VALUE;
            for (Ledger.Txn t : a.txns) first = Math.min(first, t.day);
            if (today - first > 365) { ltcg = true; break; }
        }
        if (ltcg && "INR".equals(Ui.CCY)) out.add(new Sug(2, "🌾", "Use this year's ₹1.25 lakh tax-free long-term gains",
                "Long-term equity gains up to ₹1.25 lakh a year are tax-free. Selling and buying back resets your cost so you pay less tax later. "
                        + "The Tax page shows how much is left this year.", "Open Tax", new Runnable() { @Override public void run() { screen = 6; render(true); } }));
        // 10. deposits maturing soon
        for (Store.Asset a : all) {
            if (!a.isDeposit() || a.maturity < today || a.maturity > today + 45) continue;
            out.add(new Sug(1, "⏰", shortName(a) + " matures on " + LocalDate.ofEpochDay(a.maturity).format(DF),
                    "About " + Ui.rupees(a.accruedOn(a.maturity)).replaceAll("\\.\\d+$", "") + " will be free. Decide where it goes: your emergency fund, "
                            + "a goal, or a new deposit at current rates.", null, null));
        }
        // 11. gold share
        double gold = m.byClass[Analytics.GOLD] / total * 100;
        if (gold > 20) out.add(new Sug(1, "🪙", String.format(Locale.US, "Gold is %.0f%% of your money", gold),
                "Gold steadies a portfolio but earns nothing on its own; many plans keep it to about 5–15%.", null, null));
        else if (gold == 0 && total > 1_000_000) out.add(new Sug(2, "🪙", "No gold in your mix",
                "Many plans hold 5–10% in gold (gold funds or ETFs), as it has often held up when shares fell.", null, null));
        java.util.Collections.sort(out, new java.util.Comparator<Sug>() {
            @Override public int compare(Sug x, Sug y) { return Integer.compare(x.pri, y.pri); }
        });
        return out;
    }


    // ================================================================== name & welcome
    private String userName() { return Alerts.prefs(this).getString("user_name", "").trim(); }

    private String partOfDay() {
        int h = java.time.LocalTime.now().getHour();
        return h < 5 ? "Good evening" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    /** Your picture (or the logo) with "Good morning, Asha 👋", or a festival greeting, above the dashboard title. */
    private View greetingRow() {
        String fest = Seasons.greeting(LocalDate.now());
        if (userName().isEmpty() && fest == null) return null;
        LinearLayout r = Ui.row(this);
        r.setPadding(0, 0, 0, (int) (6 * dp));
        View pic = Avatars.isSet(this, "Me") ? avatarView("Me", 30) : IntroView.logo(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (30 * dp), (int) (30 * dp));
        lp.setMargins(0, 0, (int) (10 * dp), 0);
        r.addView(pic, lp);
        String line = fest != null ? Seasons.withName(fest, userName()) : partOfDay() + ", " + userName() + " 👋";
        TextView t = Ui.text(this, line, 15, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, Ui.weight(1));
        pic.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { avatarDialog("Me"); } });
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { nameDialog(); } });
        return r;
    }

    /** First-run screen: logo, welcome message and an optional name. */
    private void welcome(final FrameLayout shell) {
        final ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClickable(true);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        c.setPadding((int) (28 * dp), (int) (40 * dp), (int) (28 * dp), (int) (40 * dp));
        sv.addView(c, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        IntroView logo = IntroView.logo(this);
        c.addView(logo, new LinearLayout.LayoutParams((int) (112 * dp), (int) (112 * dp)));
        c.addView(Ui.space(this, 22));
        TextView h = Ui.text(this, "Welcome to Portfolio", 26, Ui.TEXT, true);
        h.setGravity(Gravity.CENTER);
        c.addView(h);
        c.addView(Ui.space(this, 8));
        TextView sub = Ui.text(this, "Stocks, mutual funds, SBI Life ULIPs, deposits and everything else you own, in one place. "
                + "Your data stays on this phone.", 14, Ui.SUB, false);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(3 * dp, 1);
        c.addView(sub);
        c.addView(Ui.space(this, 28));
        TextView q = Ui.text(this, "What should we call you?", 14, Ui.TEXT, true);
        c.addView(q, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText name = new EditText(this);
        name.setHint("Your name");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(30)});
        name.setText(userName());
        c.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(Ui.space(this, 20));
        TextView go = Ui.button(this, "Get started", true);
        c.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(Ui.space(this, 6));
        TextView skip = Ui.text(this, "Skip for now", 14, Ui.SUB, false);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        c.addView(skip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Runnable done = new Runnable() {
            @Override public void run() {
                String n = name.getText().toString().trim().replaceAll("\\s+", " ");
                Alerts.prefs(MainActivity.this).edit().putBoolean("name_asked", true).putString("user_name", n).apply();
                android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(name.getWindowToken(), 0);
                if (!n.isEmpty()) toast("Welcome, " + n + "!");
                sv.animate().alpha(0f).translationY(-30 * dp).setDuration(280).withEndAction(new Runnable() {
                    @Override public void run() { shell.removeView(sv); }
                }).start();
                if (tab == 0 && screen == 0) render(false);
            }
        };
        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { done.run(); } });
        skip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { name.setText(""); done.run(); } });
        name.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, android.view.KeyEvent e) { done.run(); return true; }
        });
        shell.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logo.setScaleX(0.6f); logo.setScaleY(0.6f); logo.setAlpha(0f);
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(1300).setDuration(500)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
    }

    private void nameDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText name = new EditText(this);
        name.setHint("Your name");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(30)});
        name.setText(userName());
        name.setSelectAllOnFocus(true);
        box.addView(name);
        box.addView(Ui.text(this, "Shown in the greeting on the Dashboard and when the app opens.", 11, Ui.HINT, false));
        dialog().setTitle("Your name").setView(padded(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Alerts.prefs(MainActivity.this).edit().putBoolean("name_asked", true)
                                .putString("user_name", name.getText().toString().trim().replaceAll("\\s+", " ")).apply();
                        render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }


    /** True if (x, y) in `g`'s coordinates lands on the dashboard card stack. */
    private static boolean onCardStack(ViewGroup g, float x, float y) {
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
            float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
            if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight()) continue;
            if (c instanceof CardStack) return true;
            if (c instanceof ViewGroup) return onCardStack((ViewGroup) c, cx, cy);
            return false;
        }
        return false;
    }


    // ================================================================== Galaxy Watch sync
    private static final int REQ_BT = 78;
    private final WatchLink.Server watchServer = new WatchLink.Server();

    private boolean btAllowed() {
        return android.os.Build.VERSION.SDK_INT < 31
                || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_BT) return;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startWatchSync(true);
        else toast("Bluetooth permission is needed to sync with your watch");
        render(false);
    }

    /** Listen for the watch while the app is open (only when turned on). */
    private void startWatchSync(boolean ask) {
        if (!Alerts.prefs(this).getBoolean("watch_sync", false)) { watchServer.stop(); return; }
        if (!btAllowed()) {
            if (ask) requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, REQ_BT);
            return;
        }
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { if (ask) toast("Turn on Bluetooth to sync with your watch"); return; }
        try {
            watchServer.start(ad, new WatchLink.Phone() {
                @Override public boolean allow(final android.bluetooth.BluetoothDevice d) {
                    if (locked || hiddenForRecents) throw new IllegalStateException("locked");
                    final String addr = d.getAddress();
                    if (Alerts.prefs(MainActivity.this).getStringSet("watch_allowed", new java.util.HashSet<String>()).contains(addr)) return true;
                    final boolean[] ok = {false};
                    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            String n = null;
                            try { n = d.getName(); } catch (Throwable ignored) { }
                            dialog().setTitle("Allow your watch?")
                                    .setMessage((n == null ? "A paired device" : n) + " wants to get your portfolio over Bluetooth. Allow it now and in future?")
                                    .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface di, int w) {
                                            java.util.Set<String> set = new java.util.HashSet<>(Alerts.prefs(MainActivity.this)
                                                    .getStringSet("watch_allowed", new java.util.HashSet<String>()));
                                            set.add(addr);
                                            Alerts.prefs(MainActivity.this).edit().putStringSet("watch_allowed", set).apply();
                                            ok[0] = true;
                                            done.countDown();
                                        }
                                    })
                                    .setNegativeButton("Don't allow", new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface di, int w) { done.countDown(); }
                                    })
                                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                        @Override public void onDismiss(DialogInterface di) { done.countDown(); }
                                    }).show();
                        }
                    });
                    try { done.await(60, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    return ok[0];
                }

                @Override public byte[] payload() throws Exception {
                    final Object[] r = new Object[1];
                    final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            try {
                                JSONObject o = WatchLink.trimmed(store.toJson(), 10);
                                JSONObject meta = new JSONObject();
                                meta.put("name", userName());
                                meta.put("hide", Alerts.prefs(MainActivity.this).getBoolean("watch_hide", false));
                                meta.put("sentAt", System.currentTimeMillis());
                                o.put("watch", meta);
                                r[0] = WatchLink.gzip(o.toString());
                            } catch (Exception e) { r[0] = e; }
                            done.countDown();
                        }
                    });
                    done.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    if (r[0] instanceof Exception) throw (Exception) r[0];
                    if (r[0] == null) throw new java.io.IOException("timeout");
                    return (byte[]) r[0];
                }

                @Override public void sent(android.bluetooth.BluetoothDevice d, int bytes) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Alerts.prefs(MainActivity.this).edit().putLong("watch_last", System.currentTimeMillis()).apply();
                            toast("Portfolio sent to your watch");
                            if (tab == TABS.length - 1 && screen == 0) render(false);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            if (ask) toast("Couldn't start watch sync: " + t.getMessage());
        }
    }

    private View watchCard() {
        LinearLayout c = Ui.card(this);
        c.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        c.addView(switchRow("Sync with watch", "While this app is open, the Portfolio app on your Galaxy Watch can fetch your holdings and profile over Bluetooth",
                Alerts.prefs(this).getBoolean("watch_sync", false), true, new Toggle() {
                    @Override public void set(boolean on) {
                        Alerts.prefs(MainActivity.this).edit().putBoolean("watch_sync", on).apply();
                        if (on) startWatchSync(true); else watchServer.stop();
                        renderAfterToggle();
                    }
                }));
        c.addView(divider());
        c.addView(switchRow("Hide amounts on watch", "The watch opens with amounts hidden; tap the total to show them",
                Alerts.prefs(this).getBoolean("watch_hide", false), true, new Toggle() {
                    @Override public void set(boolean on) { Alerts.prefs(MainActivity.this).edit().putBoolean("watch_hide", on).apply(); }
                }));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (10 * dp));
        long last = Alerts.prefs(this).getLong("watch_last", 0);
        boolean on = Alerts.prefs(this).getBoolean("watch_sync", false);
        info.addView(Ui.text(this, !on ? "Off" : watchServer.running() ? "Ready: open Portfolio on your watch and tap Sync."
                : "Waiting for Bluetooth permission or Bluetooth to be on.", 12, on && watchServer.running() ? Ui.GREEN : Ui.SUB, false));
        if (last > 0) info.addView(Ui.text(this, "Last sent " + java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)), 12, Ui.SUB, false));
        final java.util.Set<String> allowed = Alerts.prefs(this).getStringSet("watch_allowed", new java.util.HashSet<String>());
        if (!allowed.isEmpty()) {
            TextView f = Ui.text(this, "Forget allowed watches (" + allowed.size() + ")", 13, Ui.ACCENT, true);
            f.setPadding(0, (int) (8 * dp), 0, 0);
            f.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Alerts.prefs(MainActivity.this).edit().remove("watch_allowed").apply();
                    toast("The watch will need to be allowed again");
                    render(false);
                }
            });
            info.addView(f);
        }
        info.addView(Ui.space(this, 4));
        info.addView(Ui.text(this, "The watch gets your holdings, family profiles and name, then refreshes prices on its own. "
                + "Sync again after you add or change holdings. Nothing goes through the internet.", 11, Ui.HINT, false));
        c.addView(info);
        return c;
    }


    // ================================================================== looks: glow, heat-map, avatars, effects
    private final Map<Store.Asset, Integer> moved = new java.util.HashMap<>();
    private long movedAt;
    private int heatRange = 0;
    private static final int REQ_AVATAR = 79;
    private String avatarFor;

    /** A brief green or red glow on a card whose price just changed in a refresh. */
    private void glow(final View card, Store.Asset... assets) {
        if (System.currentTimeMillis() - movedAt > 8000) return;
        int dir = 0;
        for (Store.Asset a : assets) { Integer m = moved.get(a); if (m != null) dir += m; }
        if (dir == 0) return;
        final GradientDrawable g = new GradientDrawable();
        g.setColor(((dir > 0 ? Ui.GREEN : Ui.RED) & 0x00FFFFFF) | 0x55000000);
        g.setCornerRadius(18 * dp);
        g.setAlpha(0);
        card.setForeground(g);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setStartDelay(200);
        va.setDuration(1700);
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator v) {
                float t = (Float) v.getAnimatedValue();
                // two soft pulses, the second fainter
                double a = t < 0.5 ? Math.sin(t / 0.5 * Math.PI) : 0.6 * Math.sin((t - 0.5) / 0.5 * Math.PI);
                g.setAlpha((int) (255 * Math.max(0, a)));
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { card.setForeground(null); }
        });
        va.start();
    }

    private static void collectTexts(View v, List<TextView> out) {
        if (v instanceof TextView) { out.add((TextView) v); return; }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collectTexts(((ViewGroup) v).getChildAt(i), out);
    }

    private View heatmapCard(List<Store.Asset> scope, boolean animate) {
        Map<String, List<Store.Asset>> m = merged(scope);
        List<TreemapView.Item> items = new ArrayList<>();
        for (List<Store.Asset> l : m.values()) {
            Store.Asset a = l.get(0);
            if (a.type != Store.MF && a.type != Store.STOCK && a.type != Store.ULIP) continue;
            double v = Store.value(l);
            if (v <= 0) continue;
            Double ch;
            if (heatRange == 0) { double d = Store.lastChange(l); ch = v - d > 0 && a.previous() != null ? d / (v - d) * 100 : null; }
            else ch = a.changeOver(heatRange == 1 ? 30 : 365);
            items.add(new TreemapView.Item(shortName(a), v, ch, a));
        }
        if (items.size() < 2) return null;
        LinearLayout c = Ui.card(this);
        final View[] self = {c};
        final List<Store.Asset> fscope = scope;
        LinearLayout t = Ui.row(this);
        t.addView(Ui.text(this, "Heat-map", 16, Ui.TEXT, true), Ui.weight(1));
        t.addView(Ui.text(this, "size = value", 11, Ui.SUB, false));
        c.addView(t);
        c.addView(Ui.space(this, 8));
        c.addView(chips(new String[]{"Today", "1 month", "1 year"}, heatRange, new Pick() {
            @Override public void on(int i) {
                if (i == heatRange) return;
                heatRange = i;
                View n = heatmapCard(fscope, false);
                if (n != null) { refreshCard(self[0], n); TreemapView t2 = (TreemapView) n.findViewWithTag("treemap"); if (t2 != null) t2.play(); }
            }
        }, false));
        c.addView(Ui.space(this, 10));
        TreemapView tv = new TreemapView(this);
        tv.setTag("treemap");
        tv.setItems(items, heatRange == 0 ? 3 : heatRange == 1 ? 10 : 30);
        tv.setListener(new TreemapView.Listener() {
            @Override public void onTap(TreemapView.Item it) { current = (Store.Asset) it.tag; screen = 1; render(true); }
        });
        if (animate) tv.play();
        c.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (230 * dp)));
        c.addView(Ui.space(this, 6));
        c.addView(Ui.text(this, "Green up, red down; stronger colour, bigger move. Tap a tile to open it.", 11, Ui.HINT, false));
        return c;
    }

    /** Round picture view for a person ("Me" is you). */
    private View avatarView(final String who, final int sizeDp) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(Avatars.bitmap(this, who, (int) (sizeDp * dp * 2)));
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private void avatarDialog(final String who) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] dlg = new AlertDialog[1];
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(6);
        for (final String e : Avatars.EMOJIS) {
            TextView b = Ui.text(this, e, 26, Ui.TEXT, false);
            b.setGravity(Gravity.CENTER);
            b.setPadding((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), (int) (6 * dp));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Avatars.setEmoji(MainActivity.this, who, e);
                    if (dlg[0] != null) dlg[0].dismiss();
                    render(false);
                }
            });
            android.widget.GridLayout.LayoutParams gl = new android.widget.GridLayout.LayoutParams();
            gl.width = (int) (44 * dp);
            grid.addView(b, gl);
        }
        box.addView(grid);
        AlertDialog.Builder b = dialog().setTitle("Me".equals(who) ? "Your picture" : who + "'s picture")
                .setView(padded(box))
                .setPositiveButton("Choose photo", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        avatarFor = who;
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        authInProgress = true;
                        try { startActivityForResult(Intent.createChooser(i, "Choose a photo"), REQ_AVATAR); }
                        catch (Exception e) { authInProgress = false; toast("No photo picker found"); }
                    }
                })
                .setNegativeButton("Cancel", null);
        if (Avatars.isSet(this, who)) b.setNeutralButton("Remove", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { Avatars.clear(MainActivity.this, who); render(false); }
        });
        dlg[0] = b.show();
    }

    private View effectsCard() {
        LinearLayout c = Ui.card(this);
        c.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        c.addView(switchRow("Market-mood background", "Dashboard glows softly green on up days and red on down days",
                moodOn(), true, new Toggle() {
                    @Override public void set(boolean on) {
                        Alerts.prefs(MainActivity.this).edit().putBoolean("mood", on).apply();
                        renderAfterToggle();
                    }
                }));
        c.addView(divider());
        c.addView(switchRow("Tilt cards with the phone", "The dashboard card stack leans slightly as you tilt, with a moving shine",
                CardStack.TILT, true, new Toggle() {
                    @Override public void set(boolean on) {
                        CardStack.TILT = on;
                        Alerts.prefs(MainActivity.this).edit().putBoolean("tilt", on).apply();
                        renderAfterToggle();
                    }
                }));
        c.addView(divider());
        c.addView(switchRow("Pure black", "True black backgrounds in dark mode, for AMOLED screens",
                Alerts.prefs(this).getBoolean("amoled", false), true, new Toggle() {
                    @Override public void set(boolean on) {
                        Alerts.prefs(MainActivity.this).edit().putBoolean("amoled", on).apply();
                        ui.postDelayed(new Runnable() { @Override public void run() { recreate(); } }, 280);
                    }
                }));
        return c;
    }


    /** Swaps one card for a freshly built one in place, so the rest of the page doesn't rebuild or jump. */
    private void refreshCard(View old, View fresh) {
        if (old == null || fresh == null || !(old.getParent() instanceof ViewGroup)) { render(false); return; }
        ViewGroup p = (ViewGroup) old.getParent();
        int i = p.indexOfChild(old);
        ViewGroup.LayoutParams lp = old.getLayoutParams();
        p.removeViewAt(i);
        p.addView(fresh, i, lp);
        View ch = fresh.findViewWithTag("chart");
        if (ch instanceof ChartView) ((ChartView) ch).play();
    }


    /** Icon for an other-asset or loan kind, used in menus. */
    private static String kindIcon(String k) {
        String l = k.toLowerCase(Locale.ROOT);
        if (l.startsWith("fixed")) return "🏦";
        if (l.startsWith("recurring")) return "🔁";
        if (l.equals("ppf")) return "📗";
        if (l.equals("epf")) return "🧾";
        if (l.equals("nps")) return "🌅";
        if (l.startsWith("gold")) return "🪙";
        if (l.startsWith("real") || l.startsWith("home")) return "🏠";
        if (l.startsWith("savings")) return "💵";
        if (l.startsWith("bond")) return "📜";
        if (l.startsWith("car")) return "🚗";
        if (l.startsWith("personal")) return "👤";
        if (l.startsWith("education")) return "🎓";
        if (l.startsWith("credit")) return "💳";
        return "📦";
    }


    /** Symbol for a holding's prices: its exchange currency if known, else its owner's. */
    private String priceSym(Store.Asset a) {
        return Ui.symbolOf(a.currency != null && !a.currency.isEmpty() ? a.currency : store.ccyOf(a.ownerOrDefault()));
    }

    /** Choose a profile's currency: common ones first, then every ISO currency. */
    private void currencyDialog(final String who) {
        final List<String> codes = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (String[] c : Ui.CURRENCIES) { codes.add(c[0]); rows.add(c[2] + " " + c[0] + " · " + c[1].trim() + "\n" + c[3]); }
        codes.add("*");
        rows.add("🌍 More currencies\nEvery other currency");
        String title = "Me".equals(who) ? "Your currency" : who + "'s currency";
        dialog().setTitle(title).setItems(rows.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if ("*".equals(codes.get(w))) { allCurrencies(who); return; }
                applyCurrency(who, codes.get(w));
            }
        }).show();
    }

    private void allCurrencies(final String who) {
        final List<String> codes = new ArrayList<>();
        for (java.util.Currency c : java.util.Currency.getAvailableCurrencies()) codes.add(c.getCurrencyCode());
        java.util.Collections.sort(codes);
        String[] rows = new String[codes.size()];
        for (int i = 0; i < rows.length; i++) rows[i] = codes.get(i) + " · " + Ui.nameOf(codes.get(i));
        dialog().setTitle("All currencies").setItems(rows, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { applyCurrency(who, codes.get(w)); }
        }).setNegativeButton("Cancel", null).show();
    }

    private void applyCurrency(String who, String code) {
        store.setCcy(who, code);
        store.save();
        // a currency group that no longer exists falls back to the default view
        if (store.ownerFilter != null && store.ownerFilter.startsWith("@") && !store.currencies().contains(store.ownerFilter.substring(1))) {
            store.ownerFilter = null;
            Alerts.prefs(this).edit().putString("owner", null).apply();
        }
        toast(("Me".equals(who) ? "Your" : who + "'s") + " amounts now show in " + code + ". Nothing is converted.");
        render(false);
    }


    // ================================================================== version & what's new
    /** {versionName, versionCode} of the installed app. */
    private String[] appVersion() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return new String[]{pi.versionName, String.valueOf(code)};
        } catch (Exception e) {
            return new String[]{"?", "0"};
        }
    }

    private View aboutCard() {
        String[] v = appVersion();
        LinearLayout c = Ui.card(this);
        LinearLayout r = Ui.row(this);
        View logo = IntroView.logo(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (46 * dp), (int) (46 * dp));
        lp.setMargins(0, 0, (int) (14 * dp), 0);
        r.addView(logo, lp);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(this, "Portfolio", 17, Ui.TEXT, true));
        t.addView(Ui.text(this, "Version " + v[0] + "  ·  build " + v[1], 13, Ui.SUB, false));
        r.addView(t, Ui.weight(1));
        c.addView(r);
        Changelog.Entry latest = Changelog.ENTRIES[0];
        c.addView(Ui.space(this, 12));
        c.addView(Ui.text(this, "New in " + latest.version, 13, Ui.TEXT, true));
        for (int i = 0; i < Math.min(3, latest.items.length); i++) {
            TextView li = Ui.text(this, "•  " + latest.items[i], 12, Ui.SUB, false);
            li.setPadding(0, (int) (3 * dp), 0, 0);
            c.addView(li);
        }
        TextView all = Ui.text(this, "What's new & full history  ›", 14, Ui.ACCENT, true);
        all.setPadding(0, (int) (12 * dp), 0, 0);
        c.addView(all);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { screen = 7; render(true); } });
        return c;
    }

    private void showChangelog(boolean animate) {
        LinearLayout col = page(!animate);
        int idx = 0;
        TextView back = Ui.text(this, "‹  Back", 15, Ui.ACCENT, false);
        back.setPadding(0, 0, 0, (int) (10 * dp));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { slideBack(); } });
        col.addView(back);
        String[] v = appVersion();
        add(col, header("What's new", false, false, null), idx++, animate);
        col.addView(Ui.text(this, "You're on version " + v[0] + " (build " + v[1] + ")", 13, Ui.SUB, false));
        col.addView(Ui.space(this, 14));
        for (int k = 0; k < Changelog.ENTRIES.length; k++) {
            Changelog.Entry e = Changelog.ENTRIES[k];
            LinearLayout c = Ui.card(this);
            LinearLayout r = Ui.row(this);
            r.addView(Ui.text(this, e.version, k == 0 ? 18 : 16, Ui.TEXT, true), Ui.weight(1));
            if (k == 0) r.addView(Ui.pill(this, "Latest", Ui.ACCENT));
            else if (e.version.startsWith(v[0] + " ") || e.version.equals(v[0])) r.addView(Ui.pill(this, "Installed", Ui.GREEN));
            c.addView(r);
            if (!e.date.isEmpty()) c.addView(Ui.text(this, e.date, 12, Ui.SUB, false));
            c.addView(Ui.space(this, 6));
            for (String it : e.items) {
                TextView li = Ui.text(this, "•  " + it, 13, k == 0 ? Ui.TEXT : Ui.SUB, false);
                li.setPadding(0, (int) (4 * dp), 0, 0);
                li.setLineSpacing(2 * dp, 1);
                c.addView(li);
            }
            add(col, c, idx++, animate && k < 6);
        }
    }

    /** After an update (not on a fresh install), show what changed once. */
    private void maybeWhatsNew() {
        String[] v = appVersion();
        android.content.SharedPreferences p = Alerts.prefs(this);
        String seen = p.getString("seen_version", null);
        p.edit().putString("seen_version", v[1]).apply();
        boolean upgrade = seen != null ? !seen.equals(v[1]) : p.getBoolean("name_asked", false);
        if (!upgrade) return;
        final Changelog.Entry e = Changelog.ENTRIES[0];
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || locked) return;
                StringBuilder b = new StringBuilder();
                for (String it : e.items) b.append("•  ").append(it).append("\n\n");
                dialog().setTitle("What's new in " + e.version).setMessage(b.toString().trim())
                        .setPositiveButton("Got it", null)
                        .setNeutralButton("All changes", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { screen = 7; render(true); }
                        }).show();
            }
        }, 2200);
    }


    // ================================================================== ULIP NAVs online
    private List<String> ulipNames() {
        List<String> n = new ArrayList<>();
        for (Store.Asset a : store.assets) if (a.type == Store.ULIP && !a.hidden && !n.contains(a.name)) n.add(a.name);
        return n;
    }

    private int ulipLinkedCount() {
        List<String> done = new ArrayList<>();
        for (Store.Asset a : store.assets) if (a.type == Store.ULIP && !a.hidden && !a.fetchCode().isEmpty() && !done.contains(a.name)) done.add(a.name);
        return done.size();
    }

    private View ulipLinkCard(final Store.Asset a) {
        LinearLayout c = Ui.card(this);
        if (a.navSymbol.isEmpty() && a.navUrl.isEmpty()) {
            c.addView(Ui.text(this, "📝  NAV from paste", 15, Ui.TEXT, true));
            c.addView(Ui.text(this, "Link this fund and its NAV will update every business day, like your mutual funds.", 12, Ui.SUB, false));
            TextView y = Ui.text(this, "Find it online  ›", 14, Ui.ACCENT, true);
            y.setPadding(0, (int) (10 * dp), 0, (int) (4 * dp));
            y.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { linkUlips(); } });
            c.addView(y);
            TextView w = Ui.text(this, "Use a web page link (e.g. Moneycontrol)  ›", 14, Ui.ACCENT, true);
            w.setPadding(0, (int) (6 * dp), 0, 0);
            w.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { webLinkDialog(a); } });
            c.addView(w);
        } else {
            boolean web = a.navSymbol.isEmpty();
            String host = a.navUrl;
            try { host = new java.net.URL(a.navUrl).getHost().replaceFirst("^www\\.", ""); } catch (Exception ignored) { }
            c.addView(Ui.text(this, web ? "🔗  NAV from " + host : "🔗  NAV updates online", 15, Ui.TEXT, true));
            c.addView(Ui.text(this, (web ? "The app reads the NAV from that page"
                    : "From Yahoo Finance (" + a.navSymbol + ")") + " on each refresh, once each business day after SBI Life publishes it. "
                    + "Pasting still works and fills any gaps." + (a.error != null ? "\nLast try: " + a.error : ""), 12, a.error != null ? Ui.RED : Ui.SUB, false));
            LinearLayout r = Ui.row(this);
            r.setPadding(0, (int) (10 * dp), 0, 0);
            if (web) {
                TextView ch = Ui.text(this, "Change link", 14, Ui.ACCENT, true);
                ch.setPadding(0, 0, (int) (24 * dp), 0);
                ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { webLinkDialog(a); } });
                r.addView(ch);
            }
            TextView u = Ui.text(this, "Unlink", 14, Ui.ACCENT, true);
            u.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (Store.Asset x : store.assets) if (x.type == Store.ULIP && x.name.equals(a.name)) { x.navSymbol = ""; x.navUrl = ""; x.error = null; }
                    store.save();
                    toast(a.name + " goes back to pasted NAVs");
                    render(false);
                }
            });
            r.addView(u);
            c.addView(r);
        }
        return c;
    }

    /** Link a ULIP fund to a web page that shows its NAV; checks the page and asks before linking. */
    private void webLinkDialog(final Store.Asset a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "Open the fund's page in your browser (for example on Moneycontrol), copy the link and paste it here.", 12, Ui.SUB, false));
        final EditText url = new EditText(this);
        url.setHint("https://www.moneycontrol.com/insurance/ulip/…");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String pre = a.navUrl;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (pre.isEmpty() && cm != null && cm.hasPrimaryClip()) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null && t.toString().trim().startsWith("http")) pre = t.toString().trim();
            }
        } catch (Exception ignored) { }
        url.setText(pre);
        box.addView(url);
        dialog().setTitle(a.name + ": NAV page").setView(padded(box))
                .setPositiveButton("Check page", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String u = url.getText().toString().trim();
                        // links pasted with trailing punctuation (",,") still work
                        u = u.replaceAll("[,;\\s]+$", "");
                        if (!u.startsWith("http")) { toast("Paste a link that starts with http"); return; }
                        checkWebLink(a, u);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void checkWebLink(final Store.Asset a, final String u) {
        Map.Entry<Long, Double> l = a.latest();
        final Double mine = l == null ? null : l.getValue();
        final long mineDay = l == null ? -1 : l.getKey();
        toast("Reading the page…");
        exec.execute(new Runnable() {
            @Override public void run() {
                PageNav.Found f = null;
                String err = null;
                try { f = Net.pageNav(u, mine); } catch (Throwable t) { err = t.getMessage(); }
                final PageNav.Found ff = f;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (ff == null) {
                            dialog().setTitle("No NAV found").setMessage(fe != null ? "Couldn't open the page: " + fe
                                    : mine != null ? "The page didn't show a NAV close to your last one (" + String.format(Locale.US, "%.4f", mine)
                                    + "). Check it's the page for " + a.name + "." : "Couldn't find a NAV on that page.")
                                    .setPositiveButton("OK", null).show();
                            return;
                        }
                        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US);
                        String msg = "NAV on the page: " + String.format(Locale.US, "%.4f", ff.nav)
                                + (ff.day >= 0 ? " · " + LocalDate.ofEpochDay(ff.day).format(df) : " · no date shown (today's date will be used)")
                                + (mine != null ? "\nYour last NAV: " + String.format(Locale.US, "%.4f", mine) + " · " + LocalDate.ofEpochDay(mineDay).format(df) : "")
                                + (ff.context.isEmpty() ? "" : "\n\nFrom the page: \u201c" + ff.context + "\u201d")
                                + "\n\nLink " + a.name + " to this page?";
                        dialog().setTitle("Found the NAV").setMessage(msg)
                                .setPositiveButton("Link", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        for (Store.Asset x : store.assets)
                                            if (x.type == Store.ULIP && x.name.equals(a.name)) {
                                                x.navUrl = u; x.navSymbol = ""; x.error = null;
                                                x.prices.put(ff.day >= 0 ? ff.day : LocalDate.now().toEpochDay(), ff.nav);
                                            }
                                        store.save();
                                        toast(a.name + " will now update from this page");
                                        render(false);
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    }
                });
            }
        });
    }

    /** One row of the link review: a fund, what was found online, and whether to link it. */
    private static class UlipMatch {
        String fund, symbol, listing; Double online, mine; long onlineDay, mineDay; boolean pick;
        List<String[]> all = new ArrayList<>();
    }

    /** Looks up each ULIP fund online, then lets the person confirm the matches. */
    private void linkUlips() {
        final List<String> names = ulipNames();
        if (names.isEmpty()) { toast("No ULIP funds to link"); return; }
        final List<UlipMatch> rows = new ArrayList<>();
        for (String n : names) {
            UlipMatch m = new UlipMatch();
            m.fund = n;
            for (Store.Asset a : store.assets)
                if (a.type == Store.ULIP && a.name.equals(n)) {
                    Map.Entry<Long, Double> l = a.latest();
                    if (l != null && (m.mine == null || l.getKey() > m.mineDay)) { m.mine = l.getValue(); m.mineDay = l.getKey(); }
                    if (!a.navSymbol.isEmpty()) m.symbol = a.navSymbol;
                }
            rows.add(m);
        }
        toast("Looking up your SBI Life funds…");
        exec.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                for (UlipMatch m : rows) {
                    try {
                        m.all = Net.ulipSearch(m.fund);
                        String[] hit = null;
                        if (m.symbol != null) for (String[] f : m.all) if (f[0].equals(m.symbol)) hit = f;
                        if (hit == null) hit = UlipLink.exact(m.fund, m.all);
                        if (hit != null) lookUp(m, hit);
                    } catch (Throwable t) { err = t.getMessage(); }
                }
                final String fe = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        boolean any = false;
                        for (UlipMatch m : rows) if (!m.all.isEmpty()) any = true;
                        if (!any) {
                            dialog().setTitle("Not found online").setMessage(fe != null
                                    ? "Couldn't reach Yahoo Finance (" + fe + "). Check your connection and try again."
                                    : "None of your ULIP funds turned up on Yahoo Finance, so keep pasting NAVs for now.")
                                    .setPositiveButton("OK", null).show();
                            return;
                        }
                        reviewUlipLinks(rows);
                    }
                });
            }
        });
    }

    /** Fetches a listing's latest NAV so the person can compare it with theirs (runs on the background thread). */
    private static void lookUp(UlipMatch m, String[] hit) {
        m.symbol = hit[0];
        m.listing = hit[1];
        try {
            TreeMap<Long, Double> h = new TreeMap<>();
            Net.stockHistory(hit[0], h);
            if (!h.isEmpty()) { m.online = h.lastEntry().getValue(); m.onlineDay = h.lastKey(); }
        } catch (Throwable ignored) { }
        m.pick = m.online != null && (m.mine == null || UlipLink.close(m.mine, m.online));
    }

    private void reviewUlipLinks(final List<UlipMatch> rows) {
        final java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.US);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.text(this, "Tick the funds to update online. Compare the online NAV with your last pasted one to be sure it's the same fund.",
                12, Ui.SUB, false));
        final Runnable[] redraw = new Runnable[1];
        redraw[0] = new Runnable() {
            @Override public void run() {
                while (box.getChildCount() > 1) box.removeViewAt(1);
                for (final UlipMatch m : rows) {
                    LinearLayout r = Ui.row(MainActivity.this);
                    r.setPadding(0, (int) (12 * dp), 0, (int) (4 * dp));
                    final android.widget.CheckBox cb = new android.widget.CheckBox(MainActivity.this);
                    cb.setChecked(m.pick);
                    cb.setEnabled(m.symbol != null && m.online != null);
                    cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
                    cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { m.pick = on; }
                    });
                    r.addView(cb);
                    LinearLayout t = new LinearLayout(MainActivity.this);
                    t.setOrientation(LinearLayout.VERTICAL);
                    t.addView(Ui.text(MainActivity.this, m.fund, 15, Ui.TEXT, true));
                    if (m.online != null) {
                        t.addView(Ui.text(MainActivity.this, m.listing, 12, Ui.SUB, false));
                        String line = "Online " + String.format(Locale.US, "%.4f", m.online) + " · " + LocalDate.ofEpochDay(m.onlineDay).format(f);
                        if (m.mine != null) line += "   Yours " + String.format(Locale.US, "%.4f", m.mine) + " · " + LocalDate.ofEpochDay(m.mineDay).format(f);
                        boolean ok = m.mine == null || UlipLink.close(m.mine, m.online);
                        t.addView(Ui.text(MainActivity.this, line, 12, ok ? Ui.GREEN : Ui.RED, false));
                        if (!ok) t.addView(Ui.text(MainActivity.this, "Very different from yours: check it's the same fund", 11, Ui.RED, false));
                    } else {
                        t.addView(Ui.text(MainActivity.this, m.all.isEmpty() ? "Not found on Yahoo · use a web page link on the fund's page" : "No exact match · tap to choose from " + m.all.size(),
                                12, m.all.isEmpty() ? Ui.SUB : Ui.ACCENT, false));
                    }
                    if (!m.all.isEmpty()) t.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { chooseListing(m, redraw[0]); }
                    });
                    r.addView(t, Ui.weight(1));
                    box.addView(r);
                }
            }
        };
        redraw[0].run();
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(padded(box));
        dialog().setTitle("Auto-update ULIP NAVs").setView(sv)
                .setPositiveButton("Link selected", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (UlipMatch m : rows) {
                            for (Store.Asset a : store.assets)
                                if (a.type == Store.ULIP && a.name.equals(m.fund)) {
                                    boolean link = m.pick && m.symbol != null && m.online != null;
                                    if (link) { a.navSymbol = m.symbol; a.navUrl = ""; }
                                    else if (!a.navSymbol.isEmpty()) a.navSymbol = "";     // unticked: stop using Yahoo (a web link stays)
                                }
                            if (m.pick && m.symbol != null && m.online != null) n++;
                        }
                        store.save();
                        toast(n == 0 ? "No funds linked" : n + (n == 1 ? " fund" : " funds") + " will now update online");
                        if (n > 0) refresh(null, false); else render(false);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Pick a listing by hand when the name didn't match exactly. */
    private void chooseListing(final UlipMatch m, final Runnable done) {
        String[] labels = new String[m.all.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = m.all.get(i)[1] + "\n" + m.all.get(i)[0];
        dialog().setTitle(m.fund).setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, final int w) {
                exec.execute(new Runnable() {
                    @Override public void run() {
                        lookUp(m, m.all.get(w));
                        m.pick = m.online != null;
                        ui.post(done);
                    }
                });
            }
        }).show();
    }


    // ================================================================== auto refresh
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (autoDue()) refresh(null, true);
            ui.postDelayed(this, 20_000);
        }
    };

    private long autoEmptyAt;

    private void startAutoTimer() {
        ui.removeCallbacks(autoTick);
        if (Alerts.autoMin(this) > 0) ui.postDelayed(autoTick, 20_000);
    }

    /** Time for an automatic refresh: on, unlocked, not already refreshing, interval passed, and in market hours if asked. */
    private boolean autoDue() {
        int m = Alerts.autoMin(this);
        if (m <= 0 || refreshing || locked || hiddenForRecents || authInProgress) return false;
        long now = System.currentTimeMillis();
        if (now - store.refreshedAt < m * 60_000L || now - autoEmptyAt < m * 60_000L) return false;
        return !Alerts.autoMarketOnly(this) || Alerts.marketWindow(store.refreshedAt);
    }

    private String autoNote() {
        int m = Alerts.autoMin(this);
        if (m <= 0) return " · auto refresh off";
        if (Alerts.autoMarketOnly(this) && !Alerts.marketWindow(store.refreshedAt)) return " · auto refresh resumes in market hours";
        long next = store.refreshedAt + m * 60_000L - System.currentTimeMillis();
        return next <= 30_000 ? " · refreshing soon" : " · next in " + (next < 3_600_000 ? Math.max(1, next / 60_000) + " min" : (next / 3_600_000) + " h");
    }

    private static final int[] AUTO_MIN = {0, 1, 5, 15, 30, 60};
    private static final String[] AUTO_LABEL = {"Off", "1 min", "5 min", "15 min", "30 min", "1 hour"};

    private View autoRefreshCard() {
        LinearLayout c = Ui.card(this);
        c.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
        top.addView(Ui.text(this, "Auto refresh while the app is open", 15, Ui.TEXT, false));
        top.addView(Ui.text(this, "The ring round the ↻ button fills while prices update, and counts down to the next refresh. "
                + "Long-press it to change this.", 12, Ui.SUB, false));
        top.addView(Ui.space(this, 10));
        int sel = 3;
        for (int i = 0; i < AUTO_MIN.length; i++) if (AUTO_MIN[i] == Alerts.autoMin(this)) sel = i;
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips(AUTO_LABEL, sel, new Pick() {
            @Override public void on(int i) { setAutoMin(AUTO_MIN[i]); }
        }, false));
        top.addView(hs);
        c.addView(top);
        if (Alerts.autoMin(this) > 0) {
            c.addView(divider());
            c.addView(switchRow("Only while the market is open", "NSE hours on weekdays (9:15 am–3:30 pm IST), plus one refresh after 8 pm for mutual fund and ULIP NAVs",
                    Alerts.autoMarketOnly(this), true, new Toggle() {
                        @Override public void set(boolean on) {
                            Alerts.prefs(MainActivity.this).edit().putBoolean("autoMarket", on).apply();
                            renderAfterToggle();
                        }
                    }));
            c.addView(divider());
            c.addView(switchRow("Also when the app is closed", "Keeps prices, alerts and the home-screen widget fresh. Android runs this at most every 15 min "
                    + "and may delay it to save battery", Alerts.prefs(this).getBoolean("autoBg", false), true, new Toggle() {
                        @Override public void set(boolean on) {
                            Alerts.prefs(MainActivity.this).edit().putBoolean("autoBg", on).apply();
                            String err = Alerts.schedule(MainActivity.this);
                            if (err != null) toast("Background refresh unavailable: " + err);
                        }
                    }));
        }
        return c;
    }

    private void setAutoMin(int m) {
        Alerts.prefs(this).edit().putInt("auto", m).apply();
        Alerts.schedule(this);
        startAutoTimer();
        toast(m == 0 ? "Auto refresh off" : "Auto refresh every " + (m == 60 ? "hour" : m + " min"));
        render(false);
    }

    private void autoRefreshDialog() {
        int cur = Alerts.autoMin(this);
        String[] rows = new String[AUTO_MIN.length];
        for (int i = 0; i < rows.length; i++)
            rows[i] = (AUTO_MIN[i] == cur ? "✓ " : "⏱ ") + (AUTO_MIN[i] == 0 ? "Off\nRefresh only when you ask" : "Every " + AUTO_LABEL[i]);
        dialog().setTitle("Auto refresh").setItems(rows, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { setAutoMin(AUTO_MIN[w]); }
        }).show();
    }


    /** Redraw after a switch has finished sliding, so it visibly turns on/off in place (the scroll position is kept). */
    private void renderAfterToggle() {
        ui.removeCallbacks(toggleRender);
        ui.postDelayed(toggleRender, 280);
    }

    private final Runnable toggleRender = new Runnable() { @Override public void run() { render(false); } };
}
