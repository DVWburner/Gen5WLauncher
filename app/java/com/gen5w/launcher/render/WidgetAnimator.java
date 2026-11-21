package com.gen5w.launcher.render;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import com.gen5w.launcher.model.AnimationSpec;

/**
 * Attaches touch press-spring animations, entrance reveals, and leak-free continuous
 * idle tile/panel animations (pulse, breathe, float, glow/ev_reactive) on API 19.
 */
public final class WidgetAnimator {
    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator();

    private WidgetAnimator() {}

    public static final class IdleHandle {
        public final View target;
        public final Animator animator;
        private boolean paused = false;

        IdleHandle(View target, Animator animator) {
            this.target = target;
            this.animator = animator;
        }

        public void setPaused(boolean nextPaused) {
            if (this.paused == nextPaused) {
                return;
            }
            this.paused = nextPaused;
            if (nextPaused) {
                animator.cancel();
                resetTransform(target);
            } else if (target.getWindowToken() != null && target.getVisibility() == View.VISIBLE) {
                if (!animator.isStarted()) {
                    animator.start();
                }
            }
        }

        public void release() {
            paused = true;
            animator.cancel();
            resetTransform(target);
        }

        private static void resetTransform(View v) {
            if (v != null) {
                v.setAlpha(1f);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setTranslationY(0f);
            }
        }
    }

    public static IdleHandle attach(final View view, final AnimationSpec spec) {
        return attach(view, spec, true);
    }

    public static IdleHandle attach(final View view, final AnimationSpec spec, boolean interactive) {
        if (view == null || spec == null) {
            return null;
        }
        if (interactive) {
            final float pressScale = (spec.pressScale > 0.5f && spec.pressScale < 1.0f) ? spec.pressScale : 0.96f;
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.animate().cancel();
                            v.animate().scaleX(pressScale).scaleY(pressScale)
                                    .setDuration(75).setInterpolator(DECEL).start();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.animate().cancel();
                            v.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(140).setInterpolator(DECEL).start();
                            break;
                        default:
                            break;
                    }
                    return false;
                }
            });
        }

        if (spec.enterDelayMs > 0 || spec.enterDurationMs > 0) {
            view.setAlpha(0f);
            view.setTranslationY(12f);
            view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(spec.enterDelayMs)
                    .setDuration(Math.max(120, spec.enterDurationMs))
                    .setInterpolator(DECEL)
                    .start();
        }

        final Animator idle = createIdleAnimator(view, spec);
        if (idle != null) {
            final IdleHandle handle = new IdleHandle(view, idle);
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    if (!handle.paused && !idle.isStarted()) {
                        idle.start();
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    idle.cancel();
                }
            });
            if (view.getWindowToken() != null) {
                idle.start();
            }
            return handle;
        }
        return null;
    }

    public static Animator createIdleAnimator(View target, AnimationSpec spec) {
        String fx = spec.idleEffect == null ? "none" : spec.idleEffect;
        long dur = (long) Math.max(700f, 2200f / Math.max(0.2f, spec.idleSpeed));
        float amp = spec.idleIntensity;

        if ("pulse".equals(fx)) {
            float minAlpha = Math.max(0.45f, 1.0f - 0.24f * amp);
            ObjectAnimator a = ObjectAnimator.ofFloat(target, "alpha", 1.0f, minAlpha, 1.0f);
            a.setDuration(dur);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new LinearInterpolator());
            return a;
        }
        if ("breathe".equals(fx)) {
            float hi = 1.0f + 0.016f * amp;
            float lo = 1.0f - 0.012f * amp;
            ObjectAnimator sx = ObjectAnimator.ofFloat(target, "scaleX", lo, hi, lo);
            sx.setDuration(dur);
            sx.setRepeatCount(ValueAnimator.INFINITE);
            ObjectAnimator sy = ObjectAnimator.ofFloat(target, "scaleY", lo, hi, lo);
            sy.setDuration(dur);
            sy.setRepeatCount(ValueAnimator.INFINITE);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(sx, sy);
            return set;
        }
        if ("float".equals(fx)) {
            float dy = 4f * amp;
            ObjectAnimator ty = ObjectAnimator.ofFloat(target, "translationY", -dy, dy, -dy);
            ty.setDuration(dur);
            ty.setRepeatCount(ValueAnimator.INFINITE);
            return ty;
        }
        return null;
    }

    public static ObjectAnimator createPulseAnimator(View target) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(target, "alpha", 1.0f, 0.72f, 1.0f);
        anim.setDuration(1600L);
        anim.setRepeatCount(ObjectAnimator.INFINITE);
        return anim;
    }
}
