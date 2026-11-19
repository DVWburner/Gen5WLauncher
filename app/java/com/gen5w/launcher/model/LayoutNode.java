package com.gen5w.launcher.model;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Abstract base node in a ThemePack layout tree so containers (PanelSpec)
 * and leaves (WidgetSpec) share positioning, sizing, styling, and animation metadata.
 */
public abstract class LayoutNode {
    public String id = "";
    public float[] bounds; // optional [x, y, w, h] in reference 1920x720 coordinates
    public int width = -1; // > 0 explicit ref px, 0 = match parent/weighted, -1 = auto
    public int height = -1;
    public float flexWeight = 0f;
    public int colSpan = 1;
    public int rowSpan = 1;
    public int[] marginPx; // optional [left, top, right, bottom]
    public String horizontalAlign = ""; // "" | left | center | right
    public String verticalAlign = ""; // "" | top | center | bottom
    public WidgetStyle style = new WidgetStyle();
    public IndicatorStyle indicator;
    public AnimationSpec animation = new AnimationSpec();
    public ActionSpec action = new ActionSpec();

    protected void parseCommon(JSONObject obj) {
        if (obj == null) {
            return;
        }
        id = obj.optString("id", id);
        JSONArray b = obj.optJSONArray("bounds");
        if (b != null && b.length() >= 4) {
            bounds = new float[] {
                    (float) b.optDouble(0, 0),
                    (float) b.optDouble(1, 0),
                    (float) b.optDouble(2, 0),
                    (float) b.optDouble(3, 0)
            };
        }
        width = obj.optInt("width", width);
        height = obj.optInt("height", height);
        flexWeight = (float) obj.optDouble("flexWeight", flexWeight);
        colSpan = Math.max(1, obj.optInt("colSpan", colSpan));
        rowSpan = Math.max(1, obj.optInt("rowSpan", rowSpan));
        Object margObj = obj.opt("marginPx");
        if (margObj != null) {
            marginPx = new int[] { 0, 0, 0, 0 };
            parseInsets(margObj, marginPx);
        }
        horizontalAlign = obj.optString("horizontalAlign", horizontalAlign);
        verticalAlign = obj.optString("verticalAlign", verticalAlign);
        style = WidgetStyle.fromJson(obj.optJSONObject("style"));
        JSONObject indObj = obj.optJSONObject("indicator");
        if (indObj != null) {
            indicator = IndicatorStyle.fromJson(indObj);
        }
        animation = AnimationSpec.fromJson(obj.optJSONObject("animation"));
        action = ActionSpec.fromJson(obj.optJSONObject("action"));
    }

    public static void parseInsets(Object raw, int[] out) {
        if (out == null || out.length < 4) {
            return;
        }
        if (raw instanceof Number) {
            int v = Math.max(0, ((Number) raw).intValue());
            out[0] = out[1] = out[2] = out[3] = v;
        } else if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            if (a.length() >= 4) {
                for (int i = 0; i < 4; i++) {
                    out[i] = Math.max(0, a.optInt(i, 0));
                }
            } else if (a.length() == 2) {
                int h = Math.max(0, a.optInt(0, 0));
                int v = Math.max(0, a.optInt(1, 0));
                out[0] = out[2] = h;
                out[1] = out[3] = v;
            }
        }
    }
}
