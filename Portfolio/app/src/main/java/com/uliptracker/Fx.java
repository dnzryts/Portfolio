package com.uliptracker;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import java.util.Random;

/** Decorative drawables: the Dashboard's market-mood background and frosted-glass surfaces. */
public final class Fx {

    private Fx() { }

    private static Bitmap noise;

    /** A small tile of fine grain, shared by every glass surface. */
    static synchronized Bitmap noise() {
        if (noise == null) {
            int n = 96;
            int[] px = new int[n * n];
            Random r = new Random(11);
            for (int i = 0; i < px.length; i++) {
                int v = r.nextInt(256);
                int a = 10 + r.nextInt(16);
                px[i] = (a << 24) | (v << 16) | (v << 8) | v;
            }
            noise = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }
        return noise;
    }

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

    // ------------------------------------------------------------------ frosted glass
    /**
     * A frosted-glass surface: a see-through fill (gradient or plain), a soft light from the top-left,
     * fine grain, a bright top edge and a thin outline.
     */
    public static class Glass extends Drawable {
        private final int[] colors;
        private final float radius, dp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), grain = new Paint(Paint.ANTI_ALIAS_FLAG),
                edge = new Paint(Paint.ANTI_ALIAS_FLAG), light = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final boolean onColour;

        /** @param colors one colour (plain) or several (top-left → bottom-right gradient), alpha included */
        public Glass(int[] colors, float radius, float dp, boolean onColour) {
            this.colors = colors;
            this.radius = radius;
            this.dp = dp;
            this.onColour = onColour;
            grain.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, dp));
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            r.set(b.left + dp / 2, b.top + dp / 2, b.right - dp / 2, b.bottom - dp / 2);
            path.reset();
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
            if (colors.length > 1) fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, colors, null, Shader.TileMode.CLAMP));
            else { fill.setShader(null); fill.setColor(colors[0]); }
            int hi = onColour || !Ui.LIGHT ? 0x2EFFFFFF : 0x66FFFFFF;
            light.setShader(new LinearGradient(r.left, r.top, r.left + r.width() * 0.6f, r.top + r.height() * 0.9f,
                    new int[]{hi, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{onColour || !Ui.LIGHT ? 0x66FFFFFF : 0xCCFFFFFF, onColour || !Ui.LIGHT ? 0x14FFFFFF : 0x22000000},
                    null, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            c.drawPath(path, fill);
            c.drawPath(path, light);
            c.drawPath(path, grain);
            c.drawPath(path, edge);
        }

        @Override
        public void getOutline(android.graphics.Outline o) { o.setRoundRect(getBounds(), radius); o.setAlpha(0f); }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
