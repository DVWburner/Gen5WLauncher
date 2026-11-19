package com.gen5w.launcher.model;

import org.json.JSONObject;

/**
 * Animated or static backdrop definition for the home screen.
 */
public final class BackgroundSpec {
    public String type = "animated_aurora"; // solid | gradient | bitmap | animated_aurora | animated_grid | animated_mesh | animated_frames | animated_gif | video
    public int primaryColor = 0xFF070C14;
    public int secondaryColor = 0xFF0F2038;
    public int accentColor = 0xFF1E6B9E;
    public int secondaryAccentColor = 0xFF164E63;
    public int dimColor = 0x48060B12;
    public String bitmapAsset = "";
    public String framesDir = "";
    public String gifAsset = "";
    public String videoAsset = "";
    public int fps = 16;
    public float speed = 1.0f;

    public static BackgroundSpec fromJson(JSONObject obj) {
        BackgroundSpec bg = new BackgroundSpec();
        if (obj == null) {
            return bg;
        }
        bg.type = obj.optString("type", bg.type);
        bg.primaryColor = WidgetStyle.parseColor(obj.optString("primaryColor", null), bg.primaryColor);
        bg.secondaryColor = WidgetStyle.parseColor(obj.optString("secondaryColor", null), bg.secondaryColor);
        bg.accentColor = WidgetStyle.parseColor(obj.optString("accentColor", null), bg.accentColor);
        bg.secondaryAccentColor = WidgetStyle.parseColor(obj.optString("secondaryAccentColor", null), bg.secondaryAccentColor);
        bg.dimColor = WidgetStyle.parseColor(obj.optString("dimColor", null), bg.dimColor);
        bg.bitmapAsset = obj.optString("bitmapAsset", obj.optString("posterAsset", ""));
        bg.framesDir = obj.optString("framesDir", obj.optString("animAsset", ""));
        bg.gifAsset = obj.optString("gifAsset", "");
        bg.videoAsset = obj.optString("videoAsset", "");
        bg.fps = Math.max(1, Math.min(30, obj.optInt("fps", bg.fps)));
        bg.speed = (float) obj.optDouble("speed", 1.0);
        return bg;
    }
}
