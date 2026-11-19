package com.gen5w.launcher.model;

import org.json.JSONObject;

/**
 * Global design tokens and runtime defaults encoded inside a ThemePack.
 */
public final class ThemeTokens {
    public boolean showSystemBar = true;
    public String soundMode = "system"; // system | soft | off
    public String speedUnit = "kmh"; // kmh | mph
    public String tempUnit = "F"; // F | C
    public int cornerRadiusPx = 20;
    public int accentColor = 0xFF38BDF8;
    public int dangerColor = 0xFFEF4444;
    public int okColor = 0xFF10B981;
    public int warnColor = 0xFFF59E0B;

    public static ThemeTokens fromJson(JSONObject obj) {
        ThemeTokens t = new ThemeTokens();
        if (obj == null) {
            return t;
        }
        t.showSystemBar = obj.optBoolean("showSystemBar", t.showSystemBar);
        t.soundMode = obj.optString("soundMode", t.soundMode);
        t.speedUnit = obj.optString("speedUnit", t.speedUnit);
        t.tempUnit = obj.optString("tempUnit", t.tempUnit);
        t.cornerRadiusPx = obj.optInt("cornerRadiusPx", t.cornerRadiusPx);
        t.accentColor = WidgetStyle.parseColor(obj.optString("accentColor", null), t.accentColor);
        t.dangerColor = WidgetStyle.parseColor(obj.optString("dangerColor", null), t.dangerColor);
        t.okColor = WidgetStyle.parseColor(obj.optString("okColor", null), t.okColor);
        t.warnColor = WidgetStyle.parseColor(obj.optString("warnColor", null), t.warnColor);
        return t;
    }
}
