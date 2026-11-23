package com.uliptracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Simple drawn icons for the bottom bar: 0 dashboard, 1 portfolio, 2 indexes, 3 more, 6 analytics. */
public class NavIcon extends View {
    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private int color = Ui.SUB;
    private boolean filled;

    public NavIcon(Context c, int kind) {
        super(c);
        this.kind = kind;
        dp = Ui.dp(c);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setState(int color, boolean filled) { this.color = color; this.filled = filled; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
        float sw = 1.9f * dp;
        p.setColor(color);
        p.setStrokeWidth(sw);
        p.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        float m = s * 0.16f, a = ox + m, b = oy + m, e = s - 2 * m;
        switch (kind) {
            case 0: { // dashboard: four tiles
                float g = e * 0.14f, t = (e - g) / 2;
                float r = 2.5f * dp;
                c.drawRoundRect(new RectF(a, b, a + t, b + t * 1.25f), r, r, p);
                c.drawRoundRect(new RectF(a + t + g, b, a + e, b + t * 0.75f), r, r, p);
                c.drawRoundRect(new RectF(a, b + t * 1.25f + g, a + t, b + e), r, r, p);
                c.drawRoundRect(new RectF(a + t + g, b + t * 0.75f + g, a + e, b + e), r, r, p);
                break;
            }
            case 1: { // portfolio: pie with a lifted wedge
                RectF o = new RectF(a, b + e * 0.06f, a + e * 0.94f, b + e);
                c.drawArc(o, 0, 270, true, p);
                RectF w = new RectF(a + e * 0.06f, b, a + e, b + e * 0.94f);
                c.drawArc(w, 270, 90, true, p);
                break;
            }
            case 2: { // indexes: rising line with axis
                p.setStyle(Paint.Style.STROKE);
                Path l = new Path();
                l.moveTo(a, b + e * 0.78f);
                l.lineTo(a + e * 0.32f, b + e * 0.48f);
                l.lineTo(a + e * 0.55f, b + e * 0.64f);
                l.lineTo(a + e, b + e * 0.18f);
                if (filled) p.setStrokeWidth(sw * 1.35f);
                c.drawPath(l, p);
                p.setStrokeWidth(sw);
                c.drawLine(a + e * 0.74f, b + e * 0.18f, a + e, b + e * 0.18f, p);
                c.drawLine(a + e, b + e * 0.18f, a + e, b + e * 0.44f, p);
                c.drawLine(a, b + e, a + e, b + e, p);
                break;
            }
            case 6: { // analytics: rising bars with a forecast spark
                float bw = e * 0.2f, gap = e * 0.1f, r = 2 * dp;
                float[] hts = {0.38f, 0.6f, 0.86f};
                for (int i = 0; i < 3; i++) {
                    float x0 = a + i * (bw + gap);
                    c.drawRoundRect(new RectF(x0, b + e * (1 - hts[i]), x0 + bw, b + e), r, r, p);
                }
                p.setStyle(Paint.Style.FILL);
                float cx = a + e * 0.86f, cy = b + e * 0.16f, k = e * 0.15f;
                Path st = new Path();
                st.moveTo(cx, cy - k); st.quadTo(cx, cy, cx + k, cy); st.quadTo(cx, cy, cx, cy + k);
                st.quadTo(cx, cy, cx - k, cy); st.quadTo(cx, cy, cx, cy - k); st.close();
                c.drawPath(st, p);
                break;
            }
            case 4:
            case 5: { // eye / eye with slash
                p.setStyle(Paint.Style.STROKE);
                Path eye = new Path();
                float cy = b + e / 2;
                eye.moveTo(a, cy);
                eye.quadTo(a + e / 2, cy - e * 0.62f, a + e, cy);
                eye.quadTo(a + e / 2, cy + e * 0.62f, a, cy);
                c.drawPath(eye, p);
                c.drawCircle(a + e / 2, cy, e * 0.15f, p);
                if (kind == 5) c.drawLine(a + e * 0.08f, b + e * 0.08f, a + e * 0.92f, b + e * 0.92f, p);
                break;
            }
            default: { // more: three lines
                p.setStyle(Paint.Style.STROKE);
                if (filled) p.setStrokeWidth(sw * 1.35f);
                for (int i = 0; i < 3; i++) {
                    float y = b + e * (0.2f + 0.3f * i);
                    c.drawLine(a + e * 0.08f, y, a + e * 0.92f, y, p);
                }
            }
        }
    }
}
