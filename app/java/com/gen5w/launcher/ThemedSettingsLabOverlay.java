package com.gen5w.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gen5w.launcher.action.AppLauncher;
import com.gen5w.launcher.action.StockMenuCatalog;
import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;
import com.gen5w.launcher.model.ThemePack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive sandbox for testing Approach A:
 * Extracting OEM Setup & All-Menus drawables/labels dynamically from installed head-unit APKs
 * (com.android.settings & com.hkmc.system.app.homesetting) and re-theming them on the fly.
 */
final class ThemedSettingsLabOverlay extends FrameLayout {
    interface Listener {
        void onCloseRequested();
    }

    private static final class EntrySpec {
        final String id;
        final String resPkg;
        final String drawableName;
        final String stringName;
        final String fallbackTitle;
        final String stockCatalogId;
        final String targetPkg;
        final String targetCls;

        EntrySpec(String id, String resPkg, String drawableName, String stringName,
                String fallbackTitle, String stockCatalogId, String targetPkg, String targetCls) {
            this.id = id;
            this.resPkg = resPkg;
            this.drawableName = drawableName;
            this.stringName = stringName;
            this.fallbackTitle = fallbackTitle;
            this.stockCatalogId = stockCatalogId;
            this.targetPkg = targetPkg;
            this.targetCls = targetCls;
        }
    }

    private static final class PalettePreset {
        final String name;
        final int bgColor;
        final int cardFill;
        final int cardBorder;
        final int accent;
        final int titleColor;
        final int subColor;
        final int radiusPx;

        PalettePreset(String name, int bgColor, int cardFill, int cardBorder,
                int accent, int titleColor, int subColor, int radiusPx) {
            this.name = name;
            this.bgColor = bgColor;
            this.cardFill = cardFill;
            this.cardBorder = cardBorder;
            this.accent = accent;
            this.titleColor = titleColor;
            this.subColor = subColor;
            this.radiusPx = radiusPx;
        }
    }

    private static final int SOURCE_SETUP = 0;
    private static final int SOURCE_ALL_MENUS = 1;

    private static final int TINT_SHADED_CM = 0;
    private static final int TINT_FLAT_SRC_IN = 1;
    private static final int TINT_OEM_ORIGINAL = 2;

    private static final int CARD_GLASS = 0;
    private static final int CARD_OUTLINE = 1;
    private static final int CARD_PILL = 2;

    private static final List<EntrySpec> SETUP_ENTRIES = new ArrayList<EntrySpec>();
    private static final List<EntrySpec> ALL_MENU_ENTRIES = new ArrayList<EntrySpec>();

    static {
        String sPkg = "com.android.settings";
        SETUP_ENTRIES.add(new EntrySpec("veh", sPkg, "hom_usersettings_66", "vehicle_settings",
                "Vehicle", "veh_settings", "com.android.vehiclesettings", ".VehicleSetting"));
        SETUP_ENTRIES.add(new EntrySpec("ev", sPkg, "hom_ev_setting_66", "ev_setting",
                "EV", "ev3_main", "com.hkmc.daudio.app.ev3", ".MainActivity"));
        SETUP_ENTRIES.add(new EntrySpec("navi", sPkg, "hom_navi_66", "nevi_settings_title",
                "Navigation", "navi_app", "com.mnsoft.navi", ".NaviApp"));
        SETUP_ENTRIES.add(new EntrySpec("sound", sPkg, "hom_sound_66", "sound_setting_title",
                "Sound", "sound_settings", "com.android.soundsettings", ".SoundSetting"));
        SETUP_ENTRIES.add(new EntrySpec("devconn", sPkg, "hom_set_dv_connect_66", "device_connection_main",
                "Device Connections", "phone_proj_conn", "com.daudio.app.phoneprojection", ".settings.ui.DeviceConnectionSettings"));
        SETUP_ENTRIES.add(new EntrySpec("profile", sPkg, "hom_userprofile_66", "user_profile",
                "User Profile", "user_profile", "com.hkmc.system.app.userprofile", ".UserProfileMainActivity"));
        SETUP_ENTRIES.add(new EntrySpec("voice", sPkg, "hom_vr_66", "voice_settings_title",
                "Voice Recognition", "", "com.mobis.voicehandler", ""));
        SETUP_ENTRIES.add(new EntrySpec("layout", sPkg, "hom_theme_layout_66", "screen_layout_title",
                "Screen Layout", "theme_settings", "com.android.themesettings", ".MainActivity"));
        SETUP_ENTRIES.add(new EntrySpec("display", sPkg, "hom_display_66", "display_setting_new_title",
                "Display", "display_settings", "com.android.displaysettings", ".DisplaySetting"));
        SETUP_ENTRIES.add(new EntrySpec("button", sPkg, "hom_set_button_66", "button_settings_title",
                "Button", "button_settings", "com.android.buttonsettings", ".ButtonSettings"));
        SETUP_ENTRIES.add(new EntrySpec("bluelink", sPkg, "hom_blulink_set_66", "connected_services",
                "Bluelink", "bluelink_settings", "com.hkmc.telematics.apps.Vrm.main",
                "com.hkmc.telematics.apps.Vrm.settings.ui.BluelinkUvoSettingsMenu"));
        SETUP_ENTRIES.add(new EntrySpec("general", sPkg, "hom_set_general_66", "general_sett",
                "General", "general_settings", "com.android.generalsettings", ".GeneralSettings"));
        SETUP_ENTRIES.add(new EntrySpec("wifi", sPkg, "hom_network_66", "wifi",
                "Wi-Fi", "wifi_settings", "com.hkmc.wifisettings", ".settings.ui.NetworkConnectionSettings"));
        SETUP_ENTRIES.add(new EntrySpec("bt", sPkg, "hom_indi_mode_66", "bluetooth_setting_title",
                "Bluetooth", "bt_settings", "com.android.btsettings", "com.android.settings2.bluetooth.BluetoothSettings"));
        SETUP_ENTRIES.add(new EntrySpec("stock", sPkg, "hom_set_66", "setting_title",
                "Original Stock Setup", "setup_settings", "com.android.settings", ".Settings"));

        String hPkg = "com.hkmc.system.app.homesetting";
        ALL_MENU_ENTRIES.add(new EntrySpec("m_ev", hPkg, "hom_ev_66", "ev_setting",
                "EV", "ev3_main", "com.hkmc.daudio.app.ev3", ".MainActivity"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_clim", hPkg, "hom_climate_66", "generalsetting_home_display_app_list_6",
                "Climate", "climate_smart", "com.hkmc.telematics.service.apps.climate", ".activities.NewClimateControl"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_seat", hPkg, "hom_climate_seat_handle_heat_66", "",
                "Seat Warmer / Vent", "seat_front_ctrl", "com.hkmc.telematics.service.apps.climate", ".activities.SWRCFrontSeatHeatVentControl"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_map", hPkg, "hom_map_66", "nevi_settings_title",
                "Map", "map_app", "com.mnsoft.navi", ".MapApp"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_media", hPkg, "hom_media_66", "general_media",
                "Media", "media_gate", "com.hkmc.av.app.mediagate", ".MediaGateActivity"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_cp", hPkg, "hom_carplay_66", "",
                "Apple CarPlay", "carplay_dipo", "com.daudio.app.dipo", ".ui.DipoActivity"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_aa", hPkg, "hom_android_66", "",
                "Android Auto", "android_auto", "com.google.android.projection.sink", ".ui.MainActivity"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_bl", hPkg, "hom_bluelink_66", "connected_services",
                "Bluelink", "bluelink", "com.hkmc.telematics.apps.Vrm.main", ".BluelinkUvoMenu"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_pt", hPkg, "hom_driver_talk_66", "",
                "Passenger Talk", "passenger_talk", "com.android.rear_care", ".rear_talk.RearTalkType1Activity"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_di", hPkg, "hom_driv_info_66", "",
                "Driving Info", "driving_info", "com.hkmc.telematics.apps.Vrm.main", "com.hkmc.telematics.apps.Vrm.ecoDriving.ui.DrivingInfo"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_air", hPkg, "hom_aircleaner_66", "",
                "Air Cleaning", "smart_pure_air", "com.hkmc.app.smartpureair", ".MainActivity"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_man", hPkg, "hom_menual_66", "",
                "Web Manual", "manual_tip", "com.android.settings", ".customviews.ManualAndDrivingTip"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_drvm", hPkg, "hom_drvm_66", "",
                "Rear View Monitor", "drvm", "com.hkmc.system.app.rearcamera", ""));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_hd", hPkg, "hom_hddata_66", "",
                "HD Radio Data", "hd_data", "com.hkmc.av.app.hddata", ".HDDataMainActivity"));
        ALL_MENU_ENTRIES.add(new EntrySpec("m_edit", hPkg, "hom_set_66", "setting_title",
                "Edit Home Icons", "edit_stock_home", "com.hkmc.system.app.homesetting", ".MainListActivity"));
    }

    private final SafeClickSoundPlayer sound;
    private final Listener listener;
    private final LinearLayout gridHost;
    private final TextView statsView;
    private final TextView launchLogView;
    private final TextView sourceBtn;
    private final TextView paletteBtn;
    private final TextView tintBtn;
    private final TextView cardStyleBtn;
    private final TextView launchModeBtn;

    private ThemePack activeThemePack;
    private int sourceMode = SOURCE_SETUP;
    private int paletteIdx = 0; // 0 = Auto (Active ThemePack)
    private int tintMode = TINT_SHADED_CM;
    private int cardStyle = CARD_GLASS;
    private boolean forceRootAm = false;

    private final List<PalettePreset> fixedPresets = new ArrayList<PalettePreset>();
    private final Map<String, Resources> resCache = new HashMap<String, Resources>();

    ThemedSettingsLabOverlay(Context context, SafeClickSoundPlayer sound, Listener listener) {
        super(context);
        this.sound = sound;
        this.listener = listener;
        initPresets();

        setClickable(true);
        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(28, 16, 28, 16);
        addView(shell, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header + Control Toolbar
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 10);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("OEM Settings Extraction & Theme Lab");
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f);
        titleCol.addView(title);

        statsView = new TextView(context);
        statsView.setTextColor(0xFF94A3B8);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        statsView.setSingleLine(true);
        statsView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(statsView);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sourceBtn = makeToolbarBtn(context, "Source");
        sourceBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemedSettingsLabOverlay.this.sound.playClick(v);
                sourceMode = (sourceMode + 1) % 2;
                reload();
            }
        });
        header.addView(sourceBtn, marginRight(8));

        paletteBtn = makeToolbarBtn(context, "Palette");
        paletteBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemedSettingsLabOverlay.this.sound.playClick(v);
                paletteIdx = (paletteIdx + 1) % (fixedPresets.size() + 1);
                reload();
            }
        });
        header.addView(paletteBtn, marginRight(8));

        tintBtn = makeToolbarBtn(context, "Tint");
        tintBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemedSettingsLabOverlay.this.sound.playClick(v);
                tintMode = (tintMode + 1) % 3;
                reload();
            }
        });
        header.addView(tintBtn, marginRight(8));

        cardStyleBtn = makeToolbarBtn(context, "Card");
        cardStyleBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemedSettingsLabOverlay.this.sound.playClick(v);
                cardStyle = (cardStyle + 1) % 3;
                reload();
            }
        });
        header.addView(cardStyleBtn, marginRight(8));

        launchModeBtn = makeToolbarBtn(context, "Launch");
        launchModeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemedSettingsLabOverlay.this.sound.playClick(v);
                forceRootAm = !forceRootAm;
                updateToolbarLabels(resolveCurrentPalette());
            }
        });
        header.addView(launchModeBtn, marginRight(8));

        TextView closeBtn = makeToolbarBtn(context, "Close");
        stylePill(closeBtn, 0xFF1E293B, 0xFF64748B, 10f, 2);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemedSettingsLabOverlay.this.sound.playClick(v);
                if (ThemedSettingsLabOverlay.this.listener != null) {
                    ThemedSettingsLabOverlay.this.listener.onCloseRequested();
                }
            }
        });
        header.addView(closeBtn);
        shell.addView(header);

        // Diagnostic output banner
        launchLogView = new TextView(context);
        launchLogView.setText("Tap any tile to test live OEM icon/label extraction & launch shortcut back to the stock screen.");
        launchLogView.setTextColor(0xFFE2E8F0);
        launchLogView.setTypeface(Typeface.MONOSPACE);
        launchLogView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        launchLogView.setPadding(14, 8, 14, 8);
        launchLogView.setSingleLine(true);
        launchLogView.setEllipsize(TextUtils.TruncateAt.END);
        stylePill(launchLogView, 0xCC0D1727, 0x5538BDF8, 10f, 1);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logLp.bottomMargin = 12;
        shell.addView(launchLogView, logLp);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        gridHost = new LinearLayout(context);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(gridHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        reload();
    }

    private void initPresets() {
        fixedPresets.add(new PalettePreset("Cobalt Cyan", 0xEE060D1A, 0xD4101D30, 0x8838BDF8, 0xFF38BDF8, 0xFFF8FAFC, 0xFF7DD3FC, 16));
        fixedPresets.add(new PalettePreset("Amber Sunset", 0xEE180B04, 0xD42A1307, 0x88F59E0B, 0xFFF8A538, 0xFFFFFBEB, 0xFFFCD34D, 18));
        fixedPresets.add(new PalettePreset("Emerald EV", 0xEE041410, 0xD408221C, 0x8810B981, 0xFF10B981, 0xFFECFDF5, 0xFF6EE7B7, 16));
        fixedPresets.add(new PalettePreset("Crimson Sport", 0xEE170609, 0xD4260B10, 0x88EF4444, 0xFFEF4444, 0xFFFEF2F2, 0xFFFCA5A5, 12));
        fixedPresets.add(new PalettePreset("Violet Neon", 0xEE0D071A, 0xD41A0F32, 0x88A855F7, 0xFFA855F7, 0xFFFAF5FF, 0xFFD8B4FE, 20));
        fixedPresets.add(new PalettePreset("Stealth Mono", 0xEE090B0E, 0xD414181F, 0x8894A3B8, 0xFFE2E8F0, 0xFFFFFFFF, 0xFF94A3B8, 10));
    }

    void updateActiveThemePack(ThemePack pack) {
        this.activeThemePack = pack;
        if (paletteIdx == 0) {
            reload();
        }
    }

    private PalettePreset resolveCurrentPalette() {
        if (paletteIdx == 0 && activeThemePack != null) {
            int accent = activeThemePack.tokens != null ? activeThemePack.tokens.accentColor : 0xFF38BDF8;
            int bg = activeThemePack.background != null ? activeThemePack.background.primaryColor : 0xFF070C14;
            int bgOpaque = (bg & 0x00FFFFFF) | 0xEE000000;
            int sec = activeThemePack.background != null ? activeThemePack.background.secondaryColor : 0xFF0F2038;
            int cardFill = (sec & 0x00FFFFFF) | 0xD4000000;
            int border = (accent & 0x00FFFFFF) | 0x88000000;
            int radius = activeThemePack.tokens != null ? Math.max(8, Math.min(26, activeThemePack.tokens.cornerRadiusPx)) : 16;
            String packName = activeThemePack.name == null || activeThemePack.name.isEmpty() ? "ThemePack" : activeThemePack.name;
            return new PalettePreset("Auto (" + packName + ")", bgOpaque, cardFill, border, accent, 0xFFF8FAFC, accent, radius);
        }
        int idx = paletteIdx == 0 ? 0 : Math.min(fixedPresets.size() - 1, paletteIdx - 1);
        return fixedPresets.get(idx);
    }

    private void updateToolbarLabels(PalettePreset pal) {
        sourceBtn.setText(sourceMode == SOURCE_SETUP ? "Source: Setup (15)" : "Source: All Menus (15)");
        paletteBtn.setText("Palette: " + pal.name);
        tintBtn.setText(tintMode == TINT_SHADED_CM ? "Icon: 3D Shaded"
                : (tintMode == TINT_FLAT_SRC_IN ? "Icon: Flat SRC_IN" : "Icon: Original OEM"));
        cardStyleBtn.setText(cardStyle == CARD_GLASS ? "Card: Glass"
                : (cardStyle == CARD_OUTLINE ? "Card: Outline" : "Card: Pill"));
        launchModeBtn.setText(forceRootAm ? "Launch: Force Root am" : "Launch: Auto");

        stylePill(sourceBtn, 0xFF0C2D48, pal.accent, 10f, 2);
        stylePill(paletteBtn, 0xFF132338, pal.accent, 10f, 2);
        stylePill(tintBtn, 0xFF132338, pal.accent, 10f, 2);
        stylePill(cardStyleBtn, 0xFF132338, 0xFF475569, 10f, 2);
        stylePill(launchModeBtn, forceRootAm ? 0xFF311B4E : 0xFF132338, forceRootAm ? 0xFFA78BFA : 0xFF475569, 10f, 2);
    }

    void reload() {
        long t0 = SystemClock.elapsedRealtime();
        Context c = getContext();
        PackageManager pm = c.getPackageManager();
        PalettePreset pal = resolveCurrentPalette();

        setBackgroundColor(pal.bgColor);
        updateToolbarLabels(pal);
        gridHost.removeAllViews();

        List<EntrySpec> entries = sourceMode == SOURCE_SETUP ? SETUP_ENTRIES : ALL_MENU_ENTRIES;
        int extractedIcons = 0;
        int extractedLabels = 0;

        int cols = 5;
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = 12;
                gridHost.addView(row, rp);
            }
            EntrySpec spec = entries.get(i);
            ExtractedResult res = extractEntry(pm, spec);
            if (res.fromOemDrawable) extractedIcons++;
            if (res.fromOemString) extractedLabels++;

            View tile = buildTile(c, spec, res, pal);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, 146, 1f);
            if ((i % cols) < cols - 1) {
                tp.rightMargin = 12;
            }
            row.addView(tile, tp);
        }

        long elapsed = SystemClock.elapsedRealtime() - t0;
        String pkgName = sourceMode == SOURCE_SETUP ? "com.android.settings" : "com.hkmc.system.app.homesetting";
        statsView.setText("Extracted " + extractedIcons + "/" + entries.size() + " OEM icons & "
                + extractedLabels + "/" + entries.size() + " OEM strings from " + pkgName + " in " + elapsed + " ms");
    }

    private static final class ExtractedResult {
        Drawable icon;
        String label;
        boolean fromOemDrawable;
        boolean fromOemString;
        String sourceNote;
    }

    private ExtractedResult extractEntry(PackageManager pm, EntrySpec spec) {
        ExtractedResult out = new ExtractedResult();
        Resources res = getAppResources(pm, spec.resPkg);

        // 1. Extract OEM PNG Drawable from installed APK resources
        if (res != null && !spec.drawableName.isEmpty()) {
            int dId = res.getIdentifier(spec.drawableName, "drawable", spec.resPkg);
            if (dId != 0) {
                try {
                    Drawable raw = res.getDrawable(dId);
                    if (raw != null) {
                        Drawable.ConstantState cs = raw.getConstantState();
                        out.icon = cs != null ? cs.newDrawable(res).mutate() : raw.mutate();
                        out.fromOemDrawable = true;
                    }
                } catch (Throwable ignored) { }
            }
        }
        // Fallback: try com.android.settings if homesetting didn't have it, else app launcher icon
        if (out.icon == null && !"com.android.settings".equals(spec.resPkg)) {
            Resources sRes = getAppResources(pm, "com.android.settings");
            if (sRes != null && !spec.drawableName.isEmpty()) {
                int dId = sRes.getIdentifier(spec.drawableName, "drawable", "com.android.settings");
                if (dId != 0) {
                    try {
                        Drawable raw = sRes.getDrawable(dId);
                        if (raw != null) {
                            Drawable.ConstantState cs = raw.getConstantState();
                            out.icon = cs != null ? cs.newDrawable(sRes).mutate() : raw.mutate();
                            out.fromOemDrawable = true;
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }
        if (out.icon == null) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(spec.targetPkg, 0);
                Drawable appIc = pm.getApplicationIcon(ai);
                if (appIc != null) {
                    out.icon = appIc.mutate();
                }
            } catch (Throwable ignored) { }
        }

        // 2. Extract OEM localized string
        if (res != null && !spec.stringName.isEmpty()) {
            int sId = res.getIdentifier(spec.stringName, "string", spec.resPkg);
            if (sId != 0) {
                try {
                    String s = res.getString(sId);
                    if (s != null && !s.trim().isEmpty()) {
                        out.label = s.trim();
                        out.fromOemString = true;
                    }
                } catch (Throwable ignored) { }
            }
        }
        if (out.label == null && !"com.android.settings".equals(spec.resPkg) && !spec.stringName.isEmpty()) {
            Resources sRes = getAppResources(pm, "com.android.settings");
            if (sRes != null) {
                int sId = sRes.getIdentifier(spec.stringName, "string", "com.android.settings");
                if (sId != 0) {
                    try {
                        String s = sRes.getString(sId);
                        if (s != null && !s.trim().isEmpty()) {
                            out.label = s.trim();
                            out.fromOemString = true;
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }
        if (out.label == null) {
            out.label = spec.fallbackTitle;
        }

        out.sourceNote = (out.fromOemDrawable ? "OEM PNG" : "Fallback Icon")
                + " | " + (out.fromOemString ? "OEM Str" : "Catalog");
        return out;
    }

    private Resources getAppResources(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (resCache.containsKey(pkg)) {
            return resCache.get(pkg);
        }
        try {
            Resources r = pm.getResourcesForApplication(pkg);
            resCache.put(pkg, r);
            return r;
        } catch (Throwable ignored) {
            resCache.put(pkg, null);
            return null;
        }
    }

    private View buildTile(Context c, final EntrySpec spec, final ExtractedResult ex, PalettePreset pal) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(cardStyle == CARD_PILL ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(12, 10, 12, 10);

        if (cardStyle == CARD_GLASS) {
            stylePill(card, pal.cardFill, pal.cardBorder, pal.radiusPx, 2);
        } else if (cardStyle == CARD_OUTLINE) {
            stylePill(card, 0x22000000, pal.accent, pal.radiusPx, 3);
        } else {
            stylePill(card, pal.cardFill, pal.cardBorder, 32f, 2);
        }

        ImageView iv = new ImageView(c);
        if (ex.icon != null) {
            applyIconTint(ex.icon, pal.accent);
            iv.setImageDrawable(ex.icon);
        } else {
            TextView placeholder = new TextView(c);
            placeholder.setText(ex.label.substring(0, Math.min(2, ex.label.length())).toUpperCase());
            placeholder.setTextColor(pal.accent);
            placeholder.setTypeface(Typeface.DEFAULT_BOLD);
            placeholder.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
            placeholder.setGravity(Gravity.CENTER);
            card.addView(placeholder, new LinearLayout.LayoutParams(62, 62));
            iv = null;
        }
        if (iv != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(62, 62);
            if (cardStyle == CARD_PILL) {
                ip.rightMargin = 12;
            } else {
                ip.bottomMargin = 6;
            }
            card.addView(iv, ip);
        }

        LinearLayout txtCol = new LinearLayout(c);
        txtCol.setOrientation(LinearLayout.VERTICAL);
        txtCol.setGravity(cardStyle == CARD_PILL ? (Gravity.LEFT | Gravity.CENTER_VERTICAL) : Gravity.CENTER);

        TextView title = new TextView(c);
        title.setText(ex.label);
        title.setTextColor(pal.titleColor);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(cardStyle == CARD_PILL ? Gravity.LEFT : Gravity.CENTER);
        txtCol.addView(title);

        TextView badge = new TextView(c);
        badge.setText(ex.sourceNote);
        badge.setTextColor(pal.subColor);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f);
        badge.setSingleLine(true);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        badge.setGravity(cardStyle == CARD_PILL ? Gravity.LEFT : Gravity.CENTER);
        txtCol.addView(badge);

        card.addView(txtCol, new LinearLayout.LayoutParams(
                cardStyle == CARD_PILL ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                cardStyle == CARD_PILL ? 1f : 0f));

        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                launchEntry(spec, ex.label);
            }
        });
        return card;
    }

    private void applyIconTint(Drawable d, int accentColor) {
        if (d == null) return;
        d.clearColorFilter();
        if (tintMode == TINT_OEM_ORIGINAL) {
            return;
        }
        if (tintMode == TINT_FLAT_SRC_IN) {
            d.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
            return;
        }
        // TINT_SHADED_CM: preserve OEM luminance shading while recoloring to theme accent
        float rf = Color.red(accentColor) / 255f;
        float gf = Color.green(accentColor) / 255f;
        float bf = Color.blue(accentColor) / 255f;
        float wR = 0.15f * 1.18f;
        float wG = 0.55f * 1.18f;
        float wB = 0.30f * 1.18f;
        ColorMatrix cm = new ColorMatrix(new float[] {
                rf * wR, rf * wG, rf * wB, 0f, 10f * rf,
                gf * wR, gf * wG, gf * wB, 0f, 10f * gf,
                bf * wR, bf * wG, bf * wB, 0f, 10f * bf,
                0f,      0f,      0f,      1f, 0f
        });
        d.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    private void launchEntry(final EntrySpec spec, final String label) {
        final StockMenuCatalog.StockItem stockItem = !spec.stockCatalogId.isEmpty()
                ? StockMenuCatalog.findPresetItem(spec.stockCatalogId)
                : null;

        if (forceRootAm && stockItem != null && stockItem.mode != StockMenuCatalog.Mode.CLIMATE_SMART) {
            launchLogView.setText("Launching " + label + " via [ROOT am]...");
            stockItem.triggerViaRoot(new SwitcherClient.SimpleCallback() {
                @Override
                public void onResult(boolean ok, String message) {
                    launchLogView.setText(label + " -> [ROOT am] " + (ok ? "OK: " : "ERR: ") + message);
                }
            });
            return;
        }

        if (forceRootAm && !spec.targetPkg.isEmpty() && !spec.targetCls.isEmpty()) {
            String fullCls = spec.targetCls.startsWith(".") ? (spec.targetPkg + spec.targetCls) : spec.targetCls;
            launchLogView.setText("Launching " + label + " via [ROOT am] " + spec.targetPkg + "/" + fullCls + "...");
            SwitcherClient.runRootAmAsync("start", spec.targetPkg, fullCls, "", "", "", "",
                    new SwitcherClient.SimpleCallback() {
                        @Override
                        public void onResult(boolean ok, String message) {
                            launchLogView.setText(label + " -> [ROOT am] " + (ok ? "OK: " : "ERR: ") + message);
                        }
                    });
            return;
        }

        boolean ok;
        if (stockItem != null) {
            ok = AppLauncher.launchStockItem(getContext(), stockItem);
        } else {
            ok = AppLauncher.launchComponentOrPackage(getContext(), spec.targetPkg, spec.targetCls, "", "", "");
        }
        launchLogView.setText(label + " -> [AUTO] " + (ok ? "Launched " + spec.targetPkg : "Unavailable: " + spec.targetPkg));
        if (!ok) {
            Toast.makeText(getContext(), label + " not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private static TextView makeToolbarBtn(Context c, String text) {
        TextView btn = new TextView(c);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        btn.setPadding(12, 8, 12, 8);
        btn.setSingleLine(true);
        return btn;
    }

    private static LinearLayout.LayoutParams marginRight(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px;
        return lp;
    }

    private static void stylePill(View v, int fillColor, int strokeColor, float radiusPx, int strokePx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(strokePx, strokeColor);
        v.setBackground(gd);
    }
}
