package com.uliptracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The round refresh button in page headers. While prices refresh, a ring around it fills as each price arrives
 * and the arrow turns; between refreshes a faint ring counts down to the next automatic refresh.
 */
public class RefreshRing extends View {

    // shared state, set by MainActivity (headers are rebuilt often, the state outlives them)
    public static volatile boolean busy;
    public static volatile int done, total;
    public static volatile long lastAt, intervalMs, finishedAt;

    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), ir = new RectF();
    private final Path head = new Path();
    private float shown;

    public RefreshRing(Context c) {
        super(c);
        dp = Ui.dp(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeWidth(1.8f * dp);
        shown = busy && total > 0 ? (float) done / total : 0f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
        float sw = 2.2f * dp, rad = Math.min(w, h) / 2 - sw / 2 - 0.5f * dp;
        long now = SystemClock.uptimeMillis(), wall = System.currentTimeMillis();
        bg.setColor(Ui.CARD2);
        c.drawCircle(cx, cy, Math.min(w, h) / 2, bg);
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        ring.setStrokeWidth(sw);
        float spin = 0;
        if (busy) {
            float target = total > 0 ? Math.max(0.06f, (float) done / total) : 0.06f;
            shown += (target - shown) * 0.15f;
            ring.setColor((Ui.ACCENT & 0x00FFFFFF) | 0x33000000);
            c.drawCircle(cx, cy, rad, ring);
            ring.setColor(Ui.ACCENT);
            c.drawArc(r, -90, 360 * shown, false, ring);
            // a short bright comet running round, so it never looks stuck
            float a = (now % 1100) / 1100f * 360f;
            ring.setColor((Ui.ACCENT & 0x00FFFFFF) | 0x66000000);
            c.drawArc(r, a - 90, 40, false, ring);
            spin = (now % 900) / 900f * 360f;
            postInvalidateOnAnimation();
        } else if (wall - finishedAt < 700) {
            // finished: full ring that fades out
            float f = (wall - finishedAt) / 700f;
            ring.setColor((Ui.ACCENT & 0x00FFFFFF) | ((int) (255 * (1 - f)) << 24));
            c.drawCircle(cx, cy, rad, ring);
            shown = 0f;
            postInvalidateOnAnimation();
        } else if (intervalMs > 0 && lastAt > 0) {
            // countdown to the next automatic refresh: the ring empties as the time runs out
            float left = 1f - Math.max(0, Math.min(1, (float) (wall - lastAt) / intervalMs));
            ring.setStrokeWidth(2f * dp);
            ring.setColor((Ui.SUB & 0x00FFFFFF) | 0x26000000);
            c.drawCircle(cx, cy, rad, ring);
            ring.setColor((Ui.ACCENT & 0x00FFFFFF) | 0x8C000000);
            if (left > 0) c.drawArc(r, -90, 360 * left, false, ring);
            postInvalidateDelayed(intervalMs <= 120_000 ? 500 : 5000);
        }
        // the arrow: an open circle with a head
        icon.setColor(busy ? Ui.ACCENT : Ui.TEXT);
        float ri = Math.min(w, h) * 0.21f;
        c.save();
        c.rotate(spin, cx, cy);
        ir.set(cx - ri, cy - ri, cx + ri, cy + ri);
        c.drawArc(ir, -60, 290, false, icon);
        double end = Math.toRadians(-60);
        float ex = cx + (float) (ri * Math.cos(end)), ey = cy + (float) (ri * Math.sin(end));
        head.reset();
        head.moveTo(ex - 3.6f * dp, ey - 1f * dp);
        head.lineTo(ex, ey);
        head.lineTo(ex - 0.5f * dp, ey + 3.6f * dp);
        c.drawPath(head, icon);
        c.restore();
    }
}
