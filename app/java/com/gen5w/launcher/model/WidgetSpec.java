package com.gen5w.launcher.model;

import org.json.JSONObject;

/**
 * Leaf node representing a visual or interactive widget in a ThemePack.
 */
public final class WidgetSpec extends LayoutNode {
    public String kind = "tile"; // tile | ev_hero | soc_ring | ring_gauge | progress_bar | text | sensor | icon | image | telemetry_badge | clock | switcher_status | action_pill | page_indicator | label | divider | spacer
    public String title = "";
    public String subtitle = "";
    public String icon = "";
    public String bitmapAsset = "";
    public String animIcon = "";
    public String badgeText = "";
    public String telemetryKey = "";
    public String unit = "";
    public Double minValue;
    public Double maxValue;
    public String orientation = "horizontal"; // horizontal | vertical
    public boolean showText = false;
    public boolean wrapText = false;
    public int maxLines = 1;
    public String scaleType = "fit"; // fit | cover

    public static WidgetSpec fromJson(JSONObject obj) {
        WidgetSpec w = new WidgetSpec();
        if (obj == null) {
            return w;
        }
        w.parseCommon(obj);
        w.kind = obj.optString("kind", obj.optString("type", w.kind));
        w.title = obj.optString("title", "");
        w.subtitle = obj.optString("subtitle", "");
        w.icon = obj.optString("icon", "");
        w.bitmapAsset = obj.optString("bitmapAsset", "");
        w.animIcon = obj.optString("animIcon", "");
        w.badgeText = obj.optString("badgeText", "");
        w.telemetryKey = obj.optString("telemetryKey", "");
        w.unit = obj.optString("unit", "");
        if (obj.has("minValue") && !obj.isNull("minValue")) {
            w.minValue = Double.valueOf(obj.optDouble("minValue", 0.0));
        }
        if (obj.has("maxValue") && !obj.isNull("maxValue")) {
            w.maxValue = Double.valueOf(obj.optDouble("maxValue", 100.0));
        }
        w.orientation = obj.optString("orientation", w.orientation);
        w.showText = obj.optBoolean("showText", false);
        w.wrapText = obj.optBoolean("wrapText", false);
        w.maxLines = Math.max(1, obj.optInt("maxLines", w.wrapText ? 4 : 1));
        w.scaleType = obj.optString("scaleType", w.scaleType);
        return w;
    }
}
