package com.gen5w.launcher.render;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import com.gen5w.launcher.model.BackgroundSpec;

/**
 * Renders the home backdrop across procedural shaders (solid, gradient, aurora, grid, mesh),
 * static bitmaps, and converted animated media (frame sequences, GIF, or silent video).
 * Automatically pauses animations whenever hidden behind another app.
 */
public final class BackgroundHostView extends FrameLayout {
    private BackgroundSpec spec = new BackgroundSpec();
    private Bitmap bitmap;
    private final ProceduralBackdropView proceduralView;
    private final AnimatedMediaLayer mediaLayer;
    private final View scrimOverlay;

    public BackgroundHostView(Context context) {
        super(context);
        proceduralView = new ProceduralBackdropView(context);
        mediaLayer = new AnimatedMediaLayer(context);
        scrimOverlay = new View(context);

        addView(proceduralView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(mediaLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(scrimOverlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mediaLayer.setVisibility(GONE);
        scrimOverlay.setVisibility(GONE);
    }

    public void setSpec(BackgroundSpec nextSpec, AssetResolver assets) {
        this.spec = nextSpec == null ? new BackgroundSpec() : nextSpec;
        this.bitmap = (assets != null && this.spec.bitmapAsset != null && !this.spec.bitmapAsset.isEmpty())
                ? assets.loadBitmap(this.spec.bitmapAsset)
                : null;

        proceduralView.configure(this.spec, this.bitmap);

        if ("animated_frames".equals(this.spec.type)) {
            mediaLayer.setVisibility(VISIBLE);
            mediaLayer.setFrames(assets, this.spec.framesDir, this.spec.fps, true, 0);
            scrimOverlay.setBackgroundColor(this.spec.dimColor);
            scrimOverlay.setVisibility((this.spec.dimColor >>> 24) > 0 ? VISIBLE : GONE);
        } else if ("animated_gif".equals(this.spec.type)) {
            mediaLayer.setVisibility(VISIBLE);
            String ref = !this.spec.gifAsset.isEmpty() ? this.spec.gifAsset : this.spec.framesDir;
            mediaLayer.setGif(assets, ref, 0);
            scrimOverlay.setBackgroundColor(this.spec.dimColor);
            scrimOverlay.setVisibility((this.spec.dimColor >>> 24) > 0 ? VISIBLE : GONE);
        } else if ("video".equals(this.spec.type)) {
            mediaLayer.setVisibility(VISIBLE);
            String vRef = !this.spec.videoAsset.isEmpty() ? this.spec.videoAsset : this.spec.framesDir;
            mediaLayer.setVideo(assets, vRef, this.spec.bitmapAsset, 0);
            scrimOverlay.setBackgroundColor(this.spec.dimColor);
            scrimOverlay.setVisibility((this.spec.dimColor >>> 24) > 0 ? VISIBLE : GONE);
        } else {
            mediaLayer.clear();
            mediaLayer.setVisibility(GONE);
            scrimOverlay.setVisibility(GONE);
        }
    }

    private static final class ProceduralBackdropView extends View {
        private BackgroundSpec spec = new BackgroundSpec();
        private Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase = 0f;
        private ValueAnimator animator;
        private int cachedW = -1;
        private int cachedH = -1;
        private Shader baseShader;

        ProceduralBackdropView(Context context) {
            super(context);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(1.5f);
        }

        void configure(BackgroundSpec s, Bitmap bmp) {
            this.spec = s;
            this.bitmap = bmp;
            this.cachedW = -1;
            this.baseShader = null;
            updateAnimatorState();
            invalidate();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            updateAnimatorState(visibility == VISIBLE && getWindowToken() != null);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateAnimatorState();
        }

        @Override
        protected void onDetachedFromWindow() {
            stopAnimator();
            super.onDetachedFromWindow();
        }

        private void updateAnimatorState() {
            updateAnimatorState(getWindowVisibility() == VISIBLE && getVisibility() == VISIBLE && getWindowToken() != null);
        }

        private void updateAnimatorState(boolean shouldAnimate) {
            boolean isProceduralAnim = "animated_aurora".equals(spec.type)
                    || "animated_grid".equals(spec.type)
                    || "animated_mesh".equals(spec.type);
            if (!shouldAnimate || !isProceduralAnim) {
                stopAnimator();
                return;
            }
            if (animator != null) {
                return;
            }
            long periodMs = (long) Math.max(2000f, 8000f / Math.max(0.2f, spec.speed));
            animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
            animator.setDuration(periodMs);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    phase = ((Float) animation.getAnimatedValue()).floatValue();
                    invalidate();
                }
            });
            animator.start();
        }

        private void stopAnimator() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }

            if ("bitmap".equals(spec.type) && bitmap != null) {
                drawCenterCropBitmap(canvas, w, h, bitmap);
                if ((spec.dimColor >>> 24) > 0) {
                    paint.setShader(null);
                    paint.setColor(spec.dimColor);
                    canvas.drawRect(0, 0, w, h, paint);
                }
                return;
            }

            if ("solid".equals(spec.type)) {
                paint.setShader(null);
                paint.setColor(spec.primaryColor);
                canvas.drawRect(0, 0, w, h, paint);
                return;
            }

            if (cachedW != w || cachedH != h || baseShader == null) {
                cachedW = w;
                cachedH = h;
                baseShader = new LinearGradient(0f, 0f, w, h, spec.primaryColor, spec.secondaryColor, Shader.TileMode.CLAMP);
            }
            paint.setShader(baseShader);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            if ("animated_aurora".equals(spec.type) || "animated_mesh".equals(spec.type)) {
                drawAuroraOrbs(canvas, w, h, "animated_mesh".equals(spec.type));
            } else if ("animated_grid".equals(spec.type)) {
                drawGridSweep(canvas, w, h);
            }
        }

        private void drawAuroraOrbs(Canvas canvas, int w, int h, boolean meshExtras) {
            float cx1 = w * (0.28f + 0.14f * (float) Math.sin(phase));
            float cy1 = h * (0.35f + 0.12f * (float) Math.cos(phase * 0.7));
            float r1 = Math.max(w, h) * 0.55f;
            paint.setShader(new RadialGradient(
                    cx1, cy1, r1,
                    withAlpha(spec.accentColor, 118),
                    withAlpha(spec.accentColor, 0),
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx1, cy1, r1, paint);

            float cx2 = w * (0.75f + 0.12f * (float) Math.cos(phase + 1.2));
            float cy2 = h * (0.68f + 0.14f * (float) Math.sin(phase * 0.8));
            float r2 = Math.max(w, h) * 0.48f;
            paint.setShader(new RadialGradient(
                    cx2, cy2, r2,
                    withAlpha(spec.secondaryAccentColor, 105),
                    withAlpha(spec.secondaryAccentColor, 0),
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx2, cy2, r2, paint);

            if (meshExtras) {
                float cx3 = w * (0.5f + 0.22f * (float) Math.sin(phase * 1.3 + 2.1));
                float cy3 = h * (0.25f + 0.22f * (float) Math.cos(phase * 1.1));
                float r3 = Math.max(w, h) * 0.42f;
                paint.setShader(new RadialGradient(
                        cx3, cy3, r3,
                        withAlpha(spec.accentColor, 85),
                        withAlpha(spec.accentColor, 0),
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx3, cy3, r3, paint);

                linePaint.setColor(withAlpha(spec.accentColor, 36));
                for (int band = 0; band < 4; band++) {
                    float yBase = h * (0.25f + band * 0.16f);
                    float yWave = (float) Math.sin(phase + band * 0.9) * 34f;
                    canvas.drawLine(0f, yBase + yWave, w, yBase - yWave, linePaint);
                }
            }
            paint.setShader(null);
        }

        private void drawGridSweep(Canvas canvas, int w, int h) {
            int step = 80;
            float offset = (float) ((phase / (Math.PI * 2.0)) * step);
            linePaint.setColor(withAlpha(spec.accentColor, 42));
            for (float x = -step + offset; x < w; x += step) {
                canvas.drawLine(x, 0, x, h, linePaint);
            }
            for (float y = 0; y < h; y += step) {
                canvas.drawLine(0, y, w, y, linePaint);
            }
            float sweepX = (float) ((phase / (Math.PI * 2.0)) * w);
            paint.setShader(new LinearGradient(
                    sweepX - 180f, 0f, sweepX + 180f, 0f,
                    new int[] { 0x00000000, withAlpha(spec.accentColor, 72), 0x00000000 },
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        private static int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
        }

        private void drawCenterCropBitmap(Canvas canvas, int vw, int vh, Bitmap bmp) {
            float scale = Math.max((float) vw / bmp.getWidth(), (float) vh / bmp.getHeight());
            int dw = Math.round(bmp.getWidth() * scale);
            int dh = Math.round(bmp.getHeight() * scale);
            int left = (vw - dw) / 2;
            int top = (vh - dh) / 2;
            canvas.drawBitmap(bmp, null, new Rect(left, top, left + dw, top + dh), paint);
        }
    }
}
