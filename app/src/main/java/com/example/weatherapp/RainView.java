package com.example.weatherapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

public class RainView extends View {

    private static final int DROP_COUNT = 120;
    private static final long FRAME_MS = 28;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();

    private final float[] dx     = new float[DROP_COUNT];
    private final float[] dy     = new float[DROP_COUNT];
    private final float[] speed  = new float[DROP_COUNT];
    private final float[] len    = new float[DROP_COUNT];
    private final float[] alpha  = new float[DROP_COUNT];

    private int w, h;
    private boolean running = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            step();
            invalidate();
            if (running) handler.postDelayed(this, FRAME_MS);
        }
    };

    public RainView(Context ctx) { super(ctx); init(); }
    public RainView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw; h = nh;
        scatter(true);
    }

    private void scatter(boolean randomY) {
        for (int i = 0; i < DROP_COUNT; i++) {
            dx[i]    = rnd.nextFloat() * (w + 60) - 30;
            dy[i]    = randomY ? rnd.nextFloat() * h : -rnd.nextFloat() * h * 0.5f;
            speed[i] = 12 + rnd.nextFloat() * 16;
            len[i]   = 18 + rnd.nextFloat() * 28;
            alpha[i] = 0.25f + rnd.nextFloat() * 0.5f;
        }
    }

    private void step() {
        for (int i = 0; i < DROP_COUNT; i++) {
            dy[i] += speed[i];
            dx[i] += speed[i] * 0.18f;
            if (dy[i] > h + len[i]) {
                dy[i] = -len[i] - rnd.nextFloat() * 40;
                dx[i] = rnd.nextFloat() * (w + 60) - 30;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < DROP_COUNT; i++) {
            int a = (int)(alpha[i] * 255);
            paint.setARGB(a, 160, 210, 255);
            float ex = dx[i] + len[i] * 0.18f;
            float ey = dy[i] + len[i];
            canvas.drawLine(dx[i], dy[i], ex, ey, paint);
            // faint reflection tail
            paint.setARGB(a / 3, 160, 210, 255);
            canvas.drawLine(dx[i], dy[i], dx[i] - len[i] * 0.06f, dy[i] - len[i] * 0.3f, paint);
        }
    }

    public void startRain() {
        if (running) return;
        running = true;
        setVisibility(VISIBLE);
        handler.post(frame);
    }

    public void stopRain() {
        running = false;
        handler.removeCallbacks(frame);
        setVisibility(GONE);
    }
}
