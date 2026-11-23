package com.dialclock;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/** Live wallpaper showing the rotating dial clock (home + lock screen). */
public class ClockWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() { return new ClockEngine(); }

    private class ClockEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private ClockView clock;
        private boolean visible;
        private int width, height;
        private long lastDrift;
        private final Runnable frame = new Runnable() {
            @Override public void run() { drawFrame(); }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            clock = new ClockView(ClockWallpaper.this);
            loadPrefs();
        }

        private void loadPrefs() {
            SharedPreferences p = getSharedPreferences("clock", MODE_PRIVATE);
            clock.set24h(p.getBoolean("24h", true));
            clock.setAccent(MainActivity.ACCENTS[p.getInt("accent", 0) % MainActivity.ACCENTS.length]);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            handler.removeCallbacks(frame);
            if (v) { loadPrefs(); drawFrame(); }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w; height = h;
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            visible = false;
            handler.removeCallbacks(frame);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            handler.removeCallbacks(frame);
        }

        private void drawFrame() {
            long now = System.currentTimeMillis();
            if (now - lastDrift > 60_000) {   // small shift each minute against burn-in
                lastDrift = now;
                float d = getResources().getDisplayMetrics().density * 4;
                clock.setBurnInOffset((float) (Math.random() * 2 - 1) * d, (float) (Math.random() * 2 - 1) * d);
            }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    c.drawColor(Color.BLACK);
                    clock.drawClock(c, width > 0 ? width : c.getWidth(), height > 0 ? height : c.getHeight());
                }
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (IllegalArgumentException ignored) { }
                }
            }
            handler.removeCallbacks(frame);
            if (visible) handler.postDelayed(frame, 40); // ~25 fps, smooth rotation
        }
    }
}
