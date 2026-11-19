package com.gen5w.launcher.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Container node holding grouped panels and widgets in freeform, flex, or grid arrangements.
 */
public final class PanelSpec extends LayoutNode {
    public String layout = "flex_col"; // freeform | flex_row | flex_col | grid | pager
    public String pageTitle = "";
    public boolean showDots = true;
    public int columns = 3;
    public int rows = 2;
    public int gapPx = 16;
    public int[] paddingPx = new int[] { 0, 0, 0, 0 };
    public final List<LayoutNode> children = new ArrayList<LayoutNode>();

    public static PanelSpec fromJson(JSONObject obj) {
        PanelSpec p = new PanelSpec();
        if (obj == null) {
            return p;
        }
        p.parseCommon(obj);
        p.layout = obj.optString("layout", p.layout);
        p.pageTitle = obj.optString("pageTitle", obj.optString("title", ""));
        p.showDots = obj.optBoolean("showDots", true);
        p.columns = Math.max(1, obj.optInt("columns", p.columns));
        p.rows = Math.max(1, obj.optInt("rows", p.rows));
        p.gapPx = Math.max(0, obj.optInt("gapPx", p.gapPx));
        LayoutNode.parseInsets(obj.opt("paddingPx"), p.paddingPx);
        if (!obj.has("paddingPx") && p.style != null && p.style.paddingPx != null) {
            System.arraycopy(p.style.paddingPx, 0, p.paddingPx, 0, 4);
        }

        JSONArray arr = obj.optJSONArray("children");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject childObj = arr.optJSONObject(i);
                if (childObj == null) {
                    continue;
                }
                String type = childObj.optString("type", "");
                if ("panel".equals(type) || childObj.has("children") || childObj.has("layout")) {
                    p.children.add(PanelSpec.fromJson(childObj));
                } else {
                    p.children.add(WidgetSpec.fromJson(childObj));
                }
            }
        }
        return p;
    }
}
