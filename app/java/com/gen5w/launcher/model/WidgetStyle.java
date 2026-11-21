package com.gen5w.launcher.model;

import android.graphics.Color;

import org.json.JSONObject;

/**
 * Visual styling properties for panels and widgets.
 * Supports safe fallback color parsing from #AARRGGBB or #RRGGBB hex strings.
 */
public final class WidgetStyle {
    public int bgColor = 0xD0121A26;
    public int bgEndColor = 0; // 0 = solid fill, else gradient to bgEndColor
    public int gradientAngle = 135;
    public int pressedColor = 0xEE1C2D42;
    public int borderColor = 0x44568BB5;
    public int accentColor = 0xFF38BDF8;
    public int textColor = 0xFFF1F5F9;
    public int subTextColor = 0xFF94A3B8;
    public int iconTint = 0; // 0 = preserve icon colors, else PorterDuff SRC_IN tint
    public int borderWidthPx = 2;
    public int cornerRadiusPx = 20;
    public int iconSizePx = 56;
    public float titleSizePx = 26f;
    public float subtitleSizePx = 17f;
    public boolean transparent = false;
    public String textAlign = "left"; // left | center | right
    public String verticalAlign = "center"; // top | center | bottom
    public String fontFamily = ""; // "" | sans-serif | sans-serif-light | sans-serif-thin | sans-serif-condensed | serif | monospace | fonts/xxx.ttf
    public String fontWeight = ""; // "" | normal | bold
    public boolean fontItalic = false;
    public float opacity = 1.0f;
    public String bgBitmapAsset = "";
    public String bgAnimAsset = ""; // frames directory (with frames.json or images) or .gif/.mp4
    public int bgAnimFps = 15;
    public int bgDimAlpha = 95;
    public int[] paddingPx;
    public boolean hasCornerRadiusOverride = false;
    public boolean hasAccentColorOverride = false;

    public static WidgetStyle fromJson(JSONObject obj) {
        WidgetStyle s = new WidgetStyle();
        if (obj == null) {
            return s;
        }
        s.hasCornerRadiusOverride = obj.has("cornerRadiusPx");
        s.hasAccentColorOverride = obj.has("accentColor");
        s.transparent = obj.optBoolean("transparent", false);
        if (s.transparent) {
            s.bgColor = 0x00000000;
            s.borderColor = 0x00000000;
            s.borderWidthPx = 0;
        }
        s.bgColor = parseColor(obj.optString("bgColor", null), s.bgColor);
        s.bgEndColor = parseColor(obj.optString("bgEndColor", obj.optString("gradientEndColor", null)), s.bgEndColor);
        s.gradientAngle = obj.optInt("gradientAngle", s.gradientAngle);
        s.pressedColor = parseColor(obj.optString("pressedColor", null), s.pressedColor);
        s.borderColor = parseColor(obj.optString("borderColor", null), s.borderColor);
        s.accentColor = parseColor(obj.optString("accentColor", null), s.accentColor);
        s.textColor = parseColor(obj.optString("textColor", null), s.textColor);
        s.subTextColor = parseColor(obj.optString("subTextColor", null), s.subTextColor);
        s.iconTint = parseColor(obj.optString("iconTint", null), s.iconTint);
        s.borderWidthPx = obj.optInt("borderWidthPx", s.borderWidthPx);
        s.cornerRadiusPx = obj.optInt("cornerRadiusPx", s.cornerRadiusPx);
        s.iconSizePx = obj.optInt("iconSizePx", s.iconSizePx);
        if (obj.has("titleSizePx")) {
            s.titleSizePx = (float) obj.optDouble("titleSizePx", s.titleSizePx);
        } else if (obj.has("fontSizePx")) {
            s.titleSizePx = (float) obj.optDouble("fontSizePx", s.titleSizePx);
        }
        s.subtitleSizePx = (float) obj.optDouble("subtitleSizePx", s.subtitleSizePx);
        s.textAlign = obj.optString("textAlign", s.textAlign);
        s.verticalAlign = obj.optString("verticalAlign", s.verticalAlign);
        s.fontFamily = obj.optString("fontFamily", s.fontFamily);
        s.fontWeight = obj.optString("fontWeight", s.fontWeight);
        s.fontItalic = obj.optBoolean("fontItalic", s.fontItalic);
        s.opacity = Math.max(0f, Math.min(1f, (float) obj.optDouble("opacity", s.opacity)));
        s.bgBitmapAsset = obj.optString("bgBitmapAsset", "");
        s.bgAnimAsset = obj.optString("bgAnimAsset", obj.optString("animAsset", ""));
        s.bgAnimFps = Math.max(1, Math.min(30, obj.optInt("bgAnimFps", obj.optInt("animFps", s.bgAnimFps))));
        s.bgDimAlpha = Math.max(0, Math.min(230, obj.optInt("bgDimAlpha", s.bgDimAlpha)));
        Object padObj = obj.opt("paddingPx");
        if (padObj != null) {
            s.paddingPx = new int[] { 0, 0, 0, 0 };
            LayoutNode.parseInsets(padObj, s.paddingPx);
        }
        return s;
    }

    public static int parseColor(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        String trimmed = hex.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            if (!trimmed.startsWith("#")) {
                trimmed = "#" + trimmed;
            }
            return Color.parseColor(trimmed);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
