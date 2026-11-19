package com.gen5w.launcher.model;

import org.json.JSONObject;

/**
 * Styling and visibility configuration for pager indicators and standalone page_indicator widgets.
 */
public final class IndicatorStyle {
    public String mode = "pills"; // pills | dots | numbers | none
    public String align = ""; // "" (auto -> center / node align) | left | center | right
    public int activeFillColor = 0; // 0 = derive from activeBorder / accent
    public int inactiveFillColor = 0x3C1E293B;
    public int activeBorderColor = 0; // 0 = use node style accentColor
    public int inactiveBorderColor = 0x5A64748B;
    public int activeTextColor = 0xFFFFFFFF;
    public int inactiveTextColor = 0xFFCBD5E1;
    public String fontFamily = "";
    public String fontWeight = "bold"; // bold | normal
    public boolean fontItalic = false;
    public float textSizePx = 13f;
    public int cornerRadiusPx = 12;
    public int borderWidthPx = 1;
    public int gapPx = 10;
    public int padXPx = 14;
    public int padYPx = 4;
    public int dotWidthPx = 28;
    public int dotHeightPx = 10;
    public int activeDotWidthPx = 0;

    public static IndicatorStyle fromJson(JSONObject obj) {
        IndicatorStyle s = new IndicatorStyle();
        if (obj == null) {
            return s;
        }
        s.mode = obj.optString("mode", s.mode).trim();
        if (s.mode.isEmpty()) {
            s.mode = "pills";
        }
        s.align = obj.optString("align", s.align).trim();
        s.activeFillColor = WidgetStyle.parseColor(obj.optString("activeFillColor", null), 0);
        s.inactiveFillColor = WidgetStyle.parseColor(obj.optString("inactiveFillColor", null), s.inactiveFillColor);
        s.activeBorderColor = WidgetStyle.parseColor(obj.optString("activeBorderColor", null), 0);
        s.inactiveBorderColor = WidgetStyle.parseColor(obj.optString("inactiveBorderColor", null), s.inactiveBorderColor);
        s.activeTextColor = WidgetStyle.parseColor(obj.optString("activeTextColor", null), s.activeTextColor);
        s.inactiveTextColor = WidgetStyle.parseColor(obj.optString("inactiveTextColor", null), s.inactiveTextColor);
        s.fontFamily = obj.optString("fontFamily", s.fontFamily);
        s.fontWeight = obj.optString("fontWeight", s.fontWeight);
        s.fontItalic = obj.optBoolean("fontItalic", s.fontItalic);
        if (obj.has("textSizePx") && !obj.isNull("textSizePx")) {
            s.textSizePx = Math.max(6f, (float) obj.optDouble("textSizePx", s.textSizePx));
        }
        if (obj.has("cornerRadiusPx") && !obj.isNull("cornerRadiusPx")) {
            s.cornerRadiusPx = Math.max(0, obj.optInt("cornerRadiusPx", s.cornerRadiusPx));
        }
        if (obj.has("borderWidthPx") && !obj.isNull("borderWidthPx")) {
            s.borderWidthPx = Math.max(0, obj.optInt("borderWidthPx", s.borderWidthPx));
        }
        if (obj.has("gapPx") && !obj.isNull("gapPx")) {
            s.gapPx = Math.max(0, obj.optInt("gapPx", s.gapPx));
        }
        if (obj.has("padXPx") && !obj.isNull("padXPx")) {
            s.padXPx = Math.max(0, obj.optInt("padXPx", s.padXPx));
        }
        if (obj.has("padYPx") && !obj.isNull("padYPx")) {
            s.padYPx = Math.max(0, obj.optInt("padYPx", s.padYPx));
        }
        if (obj.has("dotWidthPx") && !obj.isNull("dotWidthPx")) {
            s.dotWidthPx = Math.max(2, obj.optInt("dotWidthPx", s.dotWidthPx));
        }
        if (obj.has("dotHeightPx") && !obj.isNull("dotHeightPx")) {
            s.dotHeightPx = Math.max(2, obj.optInt("dotHeightPx", s.dotHeightPx));
        }
        if (obj.has("activeDotWidthPx") && !obj.isNull("activeDotWidthPx")) {
            s.activeDotWidthPx = Math.max(0, obj.optInt("activeDotWidthPx", s.activeDotWidthPx));
        }
        return s;
    }

    public boolean isDisabled() {
        return "none".equalsIgnoreCase(mode) || "hidden".equalsIgnoreCase(mode) || "off".equalsIgnoreCase(mode);
    }

    public int resolveActiveBorderColor(int fallbackAccent) {
        return activeBorderColor != 0 ? activeBorderColor : fallbackAccent;
    }

    public int resolveActiveFillColor(int fallbackAccent) {
        if (activeFillColor != 0) {
            return activeFillColor;
        }
        int border = resolveActiveBorderColor(fallbackAccent);
        if ("dots".equalsIgnoreCase(mode)) {
            return border;
        }
        return (border & 0x00FFFFFF) | 0x64000000;
    }

    public IndicatorStyle copy() {
        IndicatorStyle c = new IndicatorStyle();
        c.mode = mode;
        c.align = align;
        c.activeFillColor = activeFillColor;
        c.inactiveFillColor = inactiveFillColor;
        c.activeBorderColor = activeBorderColor;
        c.inactiveBorderColor = inactiveBorderColor;
        c.activeTextColor = activeTextColor;
        c.inactiveTextColor = inactiveTextColor;
        c.fontFamily = fontFamily;
        c.fontWeight = fontWeight;
        c.fontItalic = fontItalic;
        c.textSizePx = textSizePx;
        c.cornerRadiusPx = cornerRadiusPx;
        c.borderWidthPx = borderWidthPx;
        c.gapPx = gapPx;
        c.padXPx = padXPx;
        c.padYPx = padYPx;
        c.dotWidthPx = dotWidthPx;
        c.dotHeightPx = dotHeightPx;
        c.activeDotWidthPx = activeDotWidthPx;
        return c;
    }
}
