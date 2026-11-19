package com.gen5w.launcher.model;

import org.json.JSONObject;

/**
 * Declarative animation hints per panel/widget, ready for rich Windows editor presets.
 */
public final class AnimationSpec {
    public float pressScale = 0.96f;
    public int enterDelayMs = 0;
    public int enterDurationMs = 240;
    public String idleEffect = "none"; // none | glow | pulse | breathe | float | shimmer | border_glow | ev_reactive
    public float idleSpeed = 1.0f;
    public float idleIntensity = 1.0f;

    public static AnimationSpec fromJson(JSONObject obj) {
        AnimationSpec spec = new AnimationSpec();
        if (obj == null) {
            return spec;
        }
        spec.pressScale = (float) obj.optDouble("pressScale", spec.pressScale);
        spec.enterDelayMs = Math.max(0, obj.optInt("enterDelayMs", spec.enterDelayMs));
        spec.enterDurationMs = Math.max(0, obj.optInt("enterDurationMs", spec.enterDurationMs));
        spec.idleEffect = obj.optString("idleEffect", spec.idleEffect);
        spec.idleSpeed = (float) Math.max(0.2, Math.min(4.0, obj.optDouble("idleSpeed", spec.idleSpeed)));
        spec.idleIntensity = (float) Math.max(0.1, Math.min(2.0, obj.optDouble("idleIntensity", spec.idleIntensity)));
        return spec;
    }
}
