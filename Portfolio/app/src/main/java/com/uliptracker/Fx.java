package com.uliptracker;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Decorative drawables: the Dashboard's market-mood background, and colour blending. */
public final class Fx {

    private Fx() { }

    /** Blend two colours: t = 0 gives a, t = 1 gives b. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, aa = a >>> 24;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255, ba = b >>> 24;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ------------------------------------------------------------------ market mood
    /**
     * Page background that leans green on up days and red on down days: soft glowing blobs that drift slowly.
     * It redraws itself about 20 times a second only while it's on screen.
     */
    public static class Mood extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int base, mood, accent;
        private final float strength;
        private final long t0 = SystemClock.uptimeMillis();
        private final Runnable tick = new Runnable() { @Override public void run() { invalidateSelf(); } };

        /** @param dayPct today's change of the whole portfolio, % */
        public Mood(double dayPct) {
            base = Ui.BG;
            boolean up = dayPct >= 0;
            mood = up ? 0xFF1FBF72 : 0xFFE5484D;
            accent = Ui.ACCENT;
            strength = (float) Math.max(0.35, Math.min(1, Math.abs(dayPct) / 1.5));
        }

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            c.drawColor(base);
            float t = (SystemClock.uptimeMillis() - t0) / 1000f;
            int a = Ui.LIGHT ? 0x38 : 0x55;
            int ma = (int) (a * strength);
            // mood glow, top right, drifting
            blob(c, w * (0.78f + 0.08f * (float) Math.sin(t * 0.21)), h * (0.10f + 0.04f * (float) Math.cos(t * 0.17)),
                    w * 0.85f, mood, ma);
            // second mood glow, lower left
            blob(c, w * (0.12f + 0.07f * (float) Math.cos(t * 0.13)), h * (0.42f + 0.05f * (float) Math.sin(t * 0.11)),
                    w * 0.75f, mood, (int) (ma * 0.7f));
            // accent glow so the glass has colour behind it
            blob(c, w * (0.30f + 0.10f * (float) Math.sin(t * 0.09 + 1)), h * (0.02f + 0.03f * (float) Math.sin(t * 0.15)),
                    w * 0.7f, accent, (int) (a * 0.6f));
            // glows along the bottom, so the area around the floating menu has colour too (most visible in portrait)
            boolean tall = h > w;
            blob(c, w * (0.85f + 0.06f * (float) Math.sin(t * 0.12 + 2)), h * (tall ? 0.97f : 0.92f) + h * 0.02f * (float) Math.cos(t * 0.16),
                    w * (tall ? 0.80f : 0.55f), mood, (int) (ma * 0.75f));
            blob(c, w * (0.18f + 0.08f * (float) Math.cos(t * 0.10 + 1)), h * (tall ? 1.02f : 0.98f),
                    w * (tall ? 0.70f : 0.45f), accent, (int) (a * 0.55f));
            // shimmer: a faint light sweeping across every ~9 s
            float sweep = ((t % 9f) / 9f) * 2.2f - 0.6f;
            paint.setShader(new LinearGradient(w * sweep - w * 0.35f, 0, w * sweep + w * 0.35f, h * 0.35f,
                    new int[]{0, Ui.LIGHT ? 0x14FFFFFF : 0x0DFFFFFF, 0}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * 0.6f, paint);
            paint.setShader(null);
            scheduleSelf(tick, SystemClock.uptimeMillis() + 50);
        }

        private void blob(Canvas c, float x, float y, float r, int color, int alpha) {
            int col = (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
            paint.setShader(new RadialGradient(x, y, r, new int[]{col, col & 0x00FFFFFF | ((alpha / 3) << 24), 0},
                    new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, paint);
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
