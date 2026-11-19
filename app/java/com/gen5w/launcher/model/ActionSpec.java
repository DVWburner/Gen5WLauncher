package com.gen5w.launcher.model;

import org.json.JSONObject;

/**
 * Interactive action metadata attached to tiles and buttons.
 */
public final class ActionSpec {
    public String type = "none"; // none | preset | launch | launch_climate | launch_projection | broadcast | root_broadcast | root_start | root_service | goto_page | open_drawer | open_switcher | open_themes | open_theme_store | open_stock_tester | switcher_cmd | toggle_bar | toggle_bg | cycle_sound | launch_stock | reload_pack
    public String pkg = "";
    public String cls = "";
    public String actionName = "";
    public String extraIntKey = "";
    public int extraIntValue = 0;
    public String extraKey = "";
    public String extraType = ""; // "" | "int" | "bool" | "str"
    public String extraVal = "";
    public String fallbackPkg = "";
    public String fallbackCls = "";
    public String cmd = "";

    public static ActionSpec fromJson(JSONObject obj) {
        ActionSpec a = new ActionSpec();
        if (obj == null) {
            return a;
        }
        a.type = obj.optString("type", a.type);
        a.pkg = obj.optString("package", obj.optString("pkg", ""));
        a.cls = obj.optString("class", obj.optString("cls", ""));
        a.actionName = obj.optString("intentAction", obj.optString("action", ""));
        a.extraIntKey = obj.optString("extraIntKey", "");
        a.extraIntValue = obj.optInt("extraIntValue", 0);
        a.extraKey = obj.optString("extraKey", a.extraIntKey);
        a.extraType = obj.optString("extraType", a.extraIntKey.isEmpty() ? "" : "int");
        a.extraVal = obj.optString("extraVal", a.extraIntKey.isEmpty() ? "" : String.valueOf(a.extraIntValue));
        a.fallbackPkg = obj.optString("fallbackPackage", "");
        a.fallbackCls = obj.optString("fallbackClass", "");
        a.cmd = obj.optString("cmd", obj.optString("preset", obj.optString("page", obj.optString("tab", ""))));
        return a;
    }
}
