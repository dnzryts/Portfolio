package com.dialclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Calendar;

/**
 * Rotating-dial clock: big hour on the left, a ring of minute numbers that
 * turns smoothly so the current minute always sits inside a pill marker.
 */
public class ClockView extends View {

    private final Paint hourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean use24h = true;
    private int accent = Color.WHITE;
    private float burnInX = 0f, burnInY = 0f;

    public ClockView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);

        hourPaint.setColor(Color.WHITE);
        hourPaint.setTypeface(thin);
        hourPaint.setTextAlign(Paint.Align.RIGHT);

        numPaint.setColor(Color.WHITE);
        numPaint.setTypeface(light);
        numPaint.setTextAlign(Paint.Align.CENTER);

        tickPaint.setColor(Color.WHITE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        pillPaint.setStyle(Paint.Style.STROKE);
        pillPaint.setColor(Color.WHITE);

        pillTextPaint.setColor(Color.WHITE);
        pillTextPaint.setTypeface(light);
        pillTextPaint.setTextAlign(Paint.Align.CENTER);

        secPaint.setColor(Color.WHITE);
        secPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void set24h(boolean v) { use24h = v; invalidate(); }
    public boolean is24h() { return use24h; }
    public void setAccent(int color) { accent = color; invalidate(); }

    /** Small drift to protect OLED panels when shown for a long time. */
    public void setBurnInOffset(float dx, float dy) { burnInX = dx; burnInY = dy; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawClock(canvas, getWidth(), getHeight());
        postInvalidateOnAnimation();
    }

    /** Draws the clock; also used by the live wallpaper. */
    public void drawClock(Canvas canvas, float w, float h) {
        float s = Math.min(w, h);

        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (!use24h) { hour = hour % 12; if (hour == 0) hour = 12; }
        int minute = now.get(Calendar.MINUTE);
        float sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f;
        float minuteF = minute + sec / 60f;

        // Dial geometry: centre sits left of the hour so the right-hand arc is visible.
        float radius = s * 0.36f;
        float cx = w * 0.5f - radius * 0.35f + burnInX;
        float cy = h * 0.5f + burnInY;
        float pillX = cx + radius;          // 3 o'clock position = "now"

        // Rotation: minute value m is drawn at angle (m - minuteF) * 6 degrees.
        canvas.save();

        // Ticks (one per minute) and second ticks (inner ring)
        for (int m = 0; m < 60; m++) {
            double ang = Math.toRadians((m - minuteF) * 6.0);
            float fade = fadeFor(ang);
            if (fade <= 0.02f) continue;
            if (Math.abs(Math.toDegrees(normalize(ang))) < 11) continue; // keep pill clear
            boolean major = m % 5 == 0;
            float r1 = radius - s * (major ? 0.075f : 0.06f);
            float r2 = radius - s * 0.05f;
            tickPaint.setStrokeWidth(s * (major ? 0.006f : 0.003f));
            tickPaint.setAlpha((int) (255 * fade * (major ? 0.9f : 0.5f)));
            float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
            canvas.drawLine(cx + r1 * cos, cy + r1 * sin, cx + r2 * cos, cy + r2 * sin, tickPaint);
        }

        // Minute labels every 5 minutes, skipping the one under the pill
        numPaint.setTextSize(s * 0.055f);
        float textOff = (numPaint.descent() + numPaint.ascent()) / 2f;
        for (int m = 0; m < 60; m += 5) {
            double ang = Math.toRadians((m - minuteF) * 6.0);
            float fade = fadeFor(ang);
            if (fade <= 0.02f) continue;
            if (Math.abs(Math.toDegrees(normalize(ang))) < 9) continue; // hidden by pill
            float r = radius + s * 0.0f;
            float x = cx + r * (float) Math.cos(ang);
            float y = cy + r * (float) Math.sin(ang);
            numPaint.setAlpha((int) (255 * fade * 0.85f));
            canvas.drawText(String.format("%02d", m), x, y - textOff, numPaint);
        }
        canvas.restore();

        // Seconds sweep: thin arc of dots inside the pill side
        float secR = radius - s * 0.11f;
        for (int i = 0; i < 60; i++) {
            double ang = Math.toRadians((i - sec) * 6.0);
            float fade = fadeFor(ang) * 0.6f;
            if (fade <= 0.03f) continue;
            if (Math.abs(Math.toDegrees(normalize(ang))) < 14) continue;
            secPaint.setStrokeWidth(s * 0.004f);
            secPaint.setAlpha((int) (255 * fade));
            canvas.drawPoint(cx + secR * (float) Math.cos(ang), cy + secR * (float) Math.sin(ang), secPaint);
        }

        // Pill marker around current minute
        pillTextPaint.setTextSize(s * 0.085f);
        float pw = s * 0.21f, ph = s * 0.115f;
        rect.set(pillX - pw / 2, cy - ph / 2, pillX + pw / 2, cy + ph / 2);
        pillPaint.setStrokeWidth(s * 0.006f);
        pillPaint.setColor(accent);
        canvas.drawRoundRect(rect, ph / 2, ph / 2, pillPaint);
        float pOff = (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f;
        canvas.drawText(String.format("%02d", minute), pillX, cy - pOff, pillTextPaint);

        // Big hour
        hourPaint.setTextSize(s * 0.30f);
        float hOff = (hourPaint.descent() + hourPaint.ascent()) / 2f;
        String hs = use24h ? String.format("%02d", hour) : String.valueOf(hour);
        canvas.drawText(hs, pillX - pw / 2 - s * 0.05f, cy - hOff, hourPaint);
    }

    /** Fade numbers out as they move away from the "now" position (angle 0). */
    private static float fadeFor(double ang) {
        double d = Math.abs(Math.toDegrees(normalize(ang)));
        if (d > 110) return 0f;
        return (float) Math.pow(1.0 - d / 110.0, 1.4);
    }

    private static double normalize(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
