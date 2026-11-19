package com.gen5w.launcher.model;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Top-level ThemePack v1/v2 contract authored by Gen5W Studio (Windows) or bundled default.
 */
public final class ThemePack {
    public int formatVersion = 2;
    public String id = "default";
    public String name = "Gen5W Default EV";
    public String author = "Gen5WLauncher";
    public String version = "0.2.0";
    public String description = "";
    public String sourceLabel = "assets";
    public String baseDir = "";
    public int referenceWidth = 1920;
    public int referenceHeight = 720;
    public ThemeTokens tokens = new ThemeTokens();
    public BackgroundSpec background = new BackgroundSpec();
    public PanelSpec root = new PanelSpec();

    public static ThemePack fromJson(String jsonText, String sourceLabel, String baseDir) throws Exception {
        JSONObject obj = new JSONObject(jsonText);
        ThemePack pack = new ThemePack();
        pack.formatVersion = obj.optInt("formatVersion", 1);
        pack.id = obj.optString("id", pack.id);
        pack.name = obj.optString("name", pack.name);
        pack.author = obj.optString("author", pack.author);
        pack.version = obj.optString("version", pack.version);
        pack.description = obj.optString("description", "");
        pack.sourceLabel = sourceLabel;
        pack.baseDir = baseDir == null ? "" : baseDir;
        pack.referenceWidth = Math.max(320, obj.optInt("referenceWidth", 1920));
        pack.referenceHeight = Math.max(240, obj.optInt("referenceHeight", 720));
        pack.tokens = ThemeTokens.fromJson(obj.optJSONObject("tokens"));
        pack.background = BackgroundSpec.fromJson(obj.optJSONObject("background"));

        JSONObject rootObj = obj.optJSONObject("root");
        if (rootObj != null) {
            pack.root = PanelSpec.fromJson(rootObj);
        } else if (obj.has("pages")) {
            // Support v2 packs written as top-level "pages" array directly
            JSONArray pagesArr = obj.optJSONArray("pages");
            PanelSpec pager = new PanelSpec();
            pager.id = "root_pager";
            pager.layout = "pager";
            pager.style.transparent = true;
            if (pagesArr != null) {
                for (int i = 0; i < pagesArr.length(); i++) {
                    JSONObject pObj = pagesArr.optJSONObject(i);
                    if (pObj != null) {
                        pager.children.add(PanelSpec.fromJson(pObj));
                    }
                }
            }
            pack.root = pager;
        } else {
            throw new IllegalArgumentException("ThemePack missing root panel or pages");
        }
        return pack;
    }
}
