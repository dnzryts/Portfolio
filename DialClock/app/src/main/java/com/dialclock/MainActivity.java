package com.dialclock;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.content.ComponentName;
import android.content.Intent;
import android.app.WallpaperManager;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;

/** Full-screen always-on clock. Tap: 12/24h. Long-press: change accent colour. */
public class MainActivity extends Activity {

    static final int[] ACCENTS = {
            0xFFFFFFFF, 0xFFB9A7FF, 0xFF7FD6FF, 0xFFFFC56B, 0xFF8CF5B0, 0xFFFF8FA3
    };
    private ClockView clock;
    private int accentIdx = 0;
    private final Handler handler = new Handler();
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            float d = getResources().getDisplayMetrics().density * 6;
            clock.setBurnInOffset((float) (Math.random() * 2 - 1) * d, (float) (Math.random() * 2 - 1) * d);
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);

        // Dim screen to save battery / feel like AOD
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.screenBrightness = 0.15f;
        win.setAttributes(lp);

        clock = new ClockView(this);
        final android.content.SharedPreferences p =getSharedPreferences("clock", MODE_PRIVATE);
        clock.set24h(p.getBoolean("24h", true));
        accentIdx = p.getInt("accent", 0);
        clock.setAccent(ACCENTS[accentIdx]);

        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                clock.set24h(!clock.is24h());
                p.edit().putBoolean("24h", clock.is24h()).apply();
                Toast.makeText(MainActivity.this, clock.is24h() ? "24-hour" : "12-hour",
                        Toast.LENGTH_SHORT).show();
            }
        });
        clock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                accentIdx = (accentIdx + 1) % ACCENTS.length;
                clock.setAccent(ACCENTS[accentIdx]);
                p.edit().putInt("accent", accentIdx).apply();
                return true;
            }
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(clock);
        float dp = getResources().getDisplayMetrics().density;
        TextView btn = new TextView(this);
        btn.setText("Set as wallpaper");
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(15);
        btn.setPadding((int) (22 * dp), (int) (12 * dp), (int) (22 * dp), (int) (12 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(30 * dp);
        bg.setStroke((int) (1.5f * dp), 0x88FFFFFF);
        btn.setBackground(bg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = (int) (48 * dp);
        root.addView(btn, blp);
        status = new TextView(this);
        status.setTextColor(0xFFFFC56B);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding((int) (24 * dp), 0, (int) (24 * dp), 0);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = (int) (110 * dp);
        root.addView(status, slp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWallpaperPicker(); }
        });
        setContentView(root);
    }

    private TextView status;

    private void showStatus(String msg) {
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
    }

    /** Returns null if the system can load our wallpaper, otherwise a reason. */
    private String checkWallpaperService() {
        try {
            Intent q = new Intent("android.service.wallpaper.WallpaperService").setPackage(getPackageName());
            java.util.List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentServices(q, android.content.pm.PackageManager.GET_META_DATA);
            if (list == null || list.isEmpty()) return "Wallpaper service not registered on this phone.";
            new android.app.WallpaperInfo(this, list.get(0)); // same parse the system does
            return null;
        } catch (Throwable t) {
            return "Wallpaper info error: " + t;
        }
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openWallpaperPicker() {
        String problem = checkWallpaperService();
        if (problem != null) { showStatus(problem); return; }
        ComponentName me = new ComponentName(this, ClockWallpaper.class);

        Intent direct = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        direct.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, me);
        if (tryStart(direct)) return;

        // Older Samsung / AOSP preview activity
        Intent preview = new Intent();
        preview.setClassName("com.android.wallpaper.livepicker",
                "com.android.wallpaper.livepicker.LiveWallpaperChange");
        preview.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, me);
        if (tryStart(preview)) return;

        if (tryStart(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))) {
            showStatus("Pick \"Dial Clock\" from the list.");
            return;
        }
        showStatus("This phone blocked the live wallpaper screen. Wallpaper service itself is OK.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        handler.post(drift);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(drift);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}
