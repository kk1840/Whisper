package com.stepos.whisper.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * 合并动画的特效层：卡片融合瞬间从中心爆一圈径向光晕和一圈火花粒子，约 700ms。
 * 平时不画任何东西（alpha 相位为 0），盖在卡片下面只在做 {@link #burst} 时显形。
 */
public class MergeFxView extends android.view.View {
    private static final int PARTICLES = 26;
    private static final long DURATION_MS = 700;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity = getResources().getDisplayMetrics().density;
    private final float[] mAngle = new float[PARTICLES];
    private final float[] mSpeed = new float[PARTICLES];
    private final float[] mStart = new float[PARTICLES];
    private final float[] mSize = new float[PARTICLES];
    private final int[] mColors = new int[PARTICLES];
    private int mGlowColor = 0xFF3D5AFE;
    /** 动画相位 0..1，0 和 1 都不画。 */
    private float mPhase;

    public MergeFxView(Context context) {
        this(context, null);
    }

    public MergeFxView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    /** 以用户颜色为基调播放一次爆发。 */
    public void burst(int baseColor) {
        mGlowColor = 0xFF000000 | baseColor;
        Random r = new Random();
        int[] palette = {0xFFFFFFFF, mGlowColor, 0xFF3D5AFE};
        for (int i = 0; i < PARTICLES; i++) {
            mAngle[i] = (float) (r.nextFloat() * Math.PI * 2);
            mSpeed[i] = 0.55f + r.nextFloat() * 0.45f;
            mStart[i] = 0.10f + r.nextFloat() * 0.16f;
            mSize[i] = 2.5f + r.nextFloat() * 3.5f;
            mColors[i] = palette[r.nextInt(palette.length)];
        }
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            mPhase = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mPhase <= 0f || mPhase >= 1f) {
            return;
        }
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;

        // 径向光晕：中心白、往外过渡到用户色再透明；快速起亮、随相位衰减。
        float glowRadius = Math.min(w, h) * (0.45f + 0.55f * mPhase);
        int alpha = Math.round(210 * (1f - mPhase) * Math.min(1f, mPhase * 4f));
        if (alpha > 2) {
            int g = mGlowColor;
            RadialGradient gradient = new RadialGradient(cx, cy, glowRadius,
                    new int[]{Color.argb(alpha, 0xFF, 0xFF, 0xFF),
                            Color.argb(alpha * 3 / 4, Color.red(g), Color.green(g), Color.blue(g)),
                            Color.argb(0, Color.red(g), Color.green(g), Color.blue(g))},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            mPaint.setShader(gradient);
            canvas.drawCircle(cx, cy, glowRadius, mPaint);
            mPaint.setShader(null);
        }

        // 火花粒子：从卡片边缘向外扩散，逐渐缩小、变淡。
        float travel = Math.min(w, h) * 0.42f;
        int fade = Math.round(255 * (1f - mPhase));
        for (int i = 0; i < PARTICLES; i++) {
            float r = travel * (mStart[i] + mSpeed[i] * mPhase);
            float x = cx + (float) Math.cos(mAngle[i]) * r * 1.35f;
            float y = cy + (float) Math.sin(mAngle[i]) * r;
            int c = mColors[i];
            mPaint.setColor(Color.argb(fade, Color.red(c), Color.green(c), Color.blue(c)));
            canvas.drawCircle(x, y, mSize[i] * mDensity * (1f - mPhase * 0.5f), mPaint);
        }
    }
}
