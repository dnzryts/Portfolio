package com.uliptracker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * Launch animation of the app logo: the ring sweeps round, the growth line draws itself,
 * the end dot pops, then everything zooms gently out to reveal the app.
 */
public class IntroView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG), title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float t;    // 0..1 over the whole animation
    private final float dp;
    private boolean still;          // just the finished logo, sized to the view (for headers and the welcome screen)
    private String greeting;        // e.g. "Welcome back, Asha"
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);

    public IntroView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFF4CD787);
        dot.setColor(0xFF4CD787);
        title.setColor(Ui.TEXT);
        title.setTextAlign(Paint.Align.CENTER);
        title.setTextSize(20 * dp);
        title.setFakeBoldText(true);
        sub.setColor(Ui.SUB);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setTextSize(15 * dp);
        setClickable(true);   // swallow touches while showing
    }

    /** A static logo: no background, no title. */
    public static IntroView logo(Context c) {
        IntroView v = new IntroView(c);
        v.still = true;
        v.t = 0.77f;
        v.setClickable(false);
        return v;
    }

    public void setGreeting(String g) { greeting = g; }

    public void play(final ViewGroup parent) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(greeting != null ? 1900 : 1250);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) { t = (Float) v.getAnimatedValue(); invalidate(); }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { parent.removeView(IntroView.this); }
        });
        a.start();
    }

    private static float seg(float t, float a, float b) { return Math.max(0, Math.min(1, (t - a) / (b - a))); }

    private static float ease(float x) { return 1 - (float) Math.pow(1 - x, 3); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float exitAt = greeting != null ? 0.84f : 0.78f;
        float out = still ? 0 : ease(seg(t, exitAt, 1f));          // exit
        if (!still) {
            bg.setColor(Ui.BG);
            bg.setAlpha((int) (255 * (1 - out)));
            c.drawRect(0, 0, w, h, bg);
        }
        float s = still ? Math.min(w, h) * 0.5f : Math.min(w, h) * 0.30f * (1 + out * 0.6f);
        float cx = w / 2, cy = still ? h / 2 : h / 2 - 20 * dp;
        int alpha = (int) (255 * (1 - out));
        // badge background
        float pop = ease(seg(t, 0f, 0.25f));
        bg.setShader(new LinearGradient(cx - s, cy - s, cx + s, cy + s, 0xFF2F6FE0, 0xFF7C5CE0, Shader.TileMode.CLAMP));
        bg.setAlpha(alpha);
        float bs = s * (0.6f + 0.4f * pop);
        r.set(cx - bs, cy - bs, cx + bs, cy + bs);
        c.drawRoundRect(r, bs * 0.45f, bs * 0.45f, bg);
        bg.setShader(null);
        // ring sweep
        float sweep = 300 * ease(seg(t, 0.12f, 0.5f));
        ring.setStrokeWidth(s * 0.11f);
        ring.setColor(0xFFFFFFFF);
        ring.setAlpha((int) (alpha * 0.95f));
        float rr = s * 0.56f;
        r.set(cx - rr, cy - rr, cx + rr, cy + rr);
        c.drawArc(r, 120, sweep, false, ring);
        // growth line
        float lp = ease(seg(t, 0.38f, 0.7f));
        float[][] pts = {{-0.42f, 0.30f}, {-0.12f, 0.02f}, {0.08f, 0.16f}, {0.42f, -0.30f}};
        Path p = new Path();
        p.moveTo(cx + pts[0][0] * s, cy + pts[0][1] * s);
        float total = 0;
        float[] lens = new float[3];
        for (int i = 0; i < 3; i++) { lens[i] = (float) Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]); total += lens[i]; }
        float want = lp * total, acc = 0;
        float ex = cx + pts[0][0] * s, ey = cy + pts[0][1] * s;
        for (int i = 0; i < 3 && want > acc; i++) {
            float f = Math.min(1, (want - acc) / lens[i]);
            ex = cx + (pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f) * s;
            ey = cy + (pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f) * s;
            p.lineTo(ex, ey);
            acc += lens[i];
        }
        line.setStrokeWidth(s * 0.11f);
        line.setAlpha(alpha);
        if (lp > 0) c.drawPath(p, line);
        // end dot pop
        float dpop = still ? 1 : seg(t, 0.66f, 0.78f);
        if (dpop > 0) {
            float k = dpop < 0.6f ? dpop / 0.6f * 1.35f : 1.35f - (dpop - 0.6f) / 0.4f * 0.35f;
            dot.setAlpha(alpha);
            c.drawCircle(ex, ey, s * 0.10f * k, dot);
        }
        if (still) return;
        // name
        float tt = ease(seg(t, 0.45f, 0.75f));
        title.setAlpha((int) (alpha * tt));
        title.setColor(Ui.TEXT);
        title.setAlpha((int) (alpha * tt));
        c.drawText("Portfolio", cx, cy + s * 1.25f + (1 - tt) * 12 * dp, title);
        if (greeting != null) {
            float gt = ease(seg(t, 0.6f, 0.8f));
            sub.setAlpha((int) (alpha * gt));
            c.drawText(greeting, cx, cy + s * 1.25f + 30 * dp + (1 - gt) * 10 * dp, sub);
        }
    }
}
