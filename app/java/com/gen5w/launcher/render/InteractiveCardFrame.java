package com.gen5w.launcher.render;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import com.gen5w.launcher.model.AnimationSpec;
import com.gen5w.launcher.model.WidgetStyle;

/**
 * Interactive card/panel host supporting gradient backgrounds, static bitmap fills,
 * animated tile frame loops / GIFs, and subtle border glow or diagonal shimmer animations.
 */
public final class InteractiveCardFrame extends FrameLayout {
    private Bitmap bgBitmap;
    private AnimatedMediaLayer animLayer;
    private boolean shimmerEnabled = false;
    private boolean glowEnabled = false;
    private float animSpeed = 1.0f;
    private int glowColor = 0xFF38BDF8;
    private int dimAlpha = 80;

    private float phase = 0f;
    private ValueAnimator fxAnimator;
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private float cornerRadiusPx = 20f;

    public InteractiveCardFrame(Context context) {
        super(context);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(3f);
    }

    public void configureVisualEffects(WidgetStyle style, AnimationSpec anim, AssetResolver assets, float scaledRadiusPx) {
        this.cornerRadiusPx = scaledRadiusPx;
        String effect = (anim != null && anim.idleEffect != null) ? anim.idleEffect : "none";
        this.shimmerEnabled = "shimmer".equals(effect);
        this.glowEnabled = "glow".equals(effect) || "border_glow".equals(effect) || "ev_reactive".equals(effect);
        this.animSpeed = anim != null ? anim.idleSpeed : 1.0f;
        this.glowColor = style != null ? style.accentColor : 0xFF38BDF8;
        this.dimAlpha = style != null ? style.bgDimAlpha : 85;

        if (style != null && assets != null && !style.bgAnimAsset.isEmpty()) {
            ensureAnimLayer();
            if (AnimatedMediaLayer.isGifAsset(style.bgAnimAsset)) {
                animLayer.setGif(assets, style.bgAnimAsset, dimAlpha);
            } else if (AnimatedMediaLayer.isVideoAsset(style.bgAnimAsset)) {
                animLayer.setVideo(assets, style.bgAnimAsset, style.bgBitmapAsset, dimAlpha);
            } else {
                animLayer.setFrames(assets, style.bgAnimAsset, style.bgAnimFps, true, dimAlpha);
            }
        } else if (style != null && assets != null && !style.bgBitmapAsset.isEmpty()) {
            this.bgBitmap = assets.loadBitmap(style.bgBitmapAsset);
        }

        if (shimmerEnabled || glowEnabled || bgBitmap != null) {
            setWillNotDraw(false);
        }
        syncAnimator();
    }

    private void ensureAnimLayer() {
        if (animLayer == null) {
            animLayer = new AnimatedMediaLayer(getContext());
            addView(animLayer, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    public void setDynamicGlowColor(int color) {
        if (this.glowColor == color) {
            return;
        }
        this.glowColor = color;
        invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncAnimator(visibility == VISIBLE && getWindowToken() != null && getVisibility() == VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    private void syncAnimator() {
        syncAnimator(getWindowVisibility() == VISIBLE && getVisibility() == VISIBLE && getWindowToken() != null);
    }

    private void syncAnimator(boolean visible) {
        if (!visible || (!shimmerEnabled && !glowEnabled)) {
            stopAnimator();
            return;
        }
        if (fxAnimator != null) {
            return;
        }
        long dur = (long) Math.max(900f, 3200f / Math.max(0.2f, animSpeed));
        fxAnimator = ValueAnimator.ofFloat(0f, 1f);
        fxAnimator.setDuration(dur);
        fxAnimator.setRepeatCount(ValueAnimator.INFINITE);
        fxAnimator.setInterpolator(new LinearInterpolator());
        fxAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = ((Float) animation.getAnimatedValue()).floatValue();
                invalidate();
            }
        });
        fxAnimator.start();
    }

    private void stopAnimator() {
        if (fxAnimator != null) {
            fxAnimator.cancel();
            fxAnimator = null;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0 && bgBitmap != null && !bgBitmap.isRecycled()) {
            float scale = Math.max((float) w / bgBitmap.getWidth(), (float) h / bgBitmap.getHeight());
            int dw = Math.round(bgBitmap.getWidth() * scale);
            int dh = Math.round(bgBitmap.getHeight() * scale);
            int left = (w - dw) / 2;
            int top = (h - dh) / 2;
            canvas.drawBitmap(bgBitmap, null, new Rect(left, top, left + dw, top + dh), bmpPaint);
            if (dimAlpha > 0) {
                cardRect.set(0f, 0f, w, h);
                fxPaint.setShader(null);
                fxPaint.setColor((dimAlpha << 24) | 0x060B12);
                canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, fxPaint);
            }
        }

        super.dispatchDraw(canvas);

        if (w <= 0 || h <= 0) {
            return;
        }
        if (shimmerEnabled) {
            cardRect.set(0f, 0f, w, h);
            float x = -w * 0.4f + (w * 1.8f) * phase;
            fxPaint.setShader(new LinearGradient(
                    x - 140f, 0f, x + 140f, h * 0.7f,
                    new int[] { 0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF },
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, fxPaint);
            fxPaint.setShader(null);
        }
        if (glowEnabled) {
            cardRect.set(2f, 2f, w - 2f, h - 2f);
            float pulse = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0));
            int alpha = Math.round(pulse * 120f);
            glowPaint.setColor((glowColor & 0x00FFFFFF) | (alpha << 24));
            canvas.drawRoundRect(cardRect, cornerRadiusPx, cornerRadiusPx, glowPaint);
        }
    }
}
