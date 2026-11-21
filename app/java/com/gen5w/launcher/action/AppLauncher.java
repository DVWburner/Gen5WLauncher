package com.gen5w.launcher.action;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.LruCache;
import android.widget.Toast;

import com.gen5w.launcher.ipc.SwitcherClient;
import com.gen5w.launcher.model.ActionSpec;
import com.gen5w.launcher.render.AssetResolver;
import com.gen5w.launcher.render.MiniSvgDrawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves and launches OEM automotive apps and side-loaded Android apps cleanly.
 */
public final class AppLauncher {
    private static final String SELF_HOME_COMPONENT = "com.gen5w.launcher/com.gen5w.launcher.MainActivity";
    private static final Uri STOCK_ALL_MENU_URI =
            Uri.parse("content://com.hkmc.system.app.homesetting.provider/all_menu");

    public static final class AppEntry {
        public final String entryKey;
        public final String label;
        public final String defaultLabel;
        public final String customLabel;
        public final String subtitle;
        public final String packageName;
        public final String className;
        public final Drawable icon;
        public final boolean isOemPreset;
        public final boolean isHmgApi;
        public final String badgeText;
        public final StockMenuCatalog.StockItem stockItem;
        public final String iconOverride;
        public final String resolvedSvgPath;
        public final boolean hidden;
        public final List<String> nameSuggestions;

        AppEntry(
                String entryKey,
                String label,
                String defaultLabel,
                String customLabel,
                String subtitle,
                String packageName,
                String className,
                Drawable icon,
                boolean isOemPreset,
                boolean isHmgApi,
                String badgeText,
                StockMenuCatalog.StockItem stockItem,
                String iconOverride,
                String resolvedSvgPath,
                boolean hidden,
                List<String> nameSuggestions) {
            this.entryKey = entryKey == null ? "" : entryKey;
            this.label = label == null ? "" : label;
            this.defaultLabel = defaultLabel == null ? this.label : defaultLabel;
            this.customLabel = customLabel == null ? "" : customLabel;
            this.subtitle = subtitle == null ? packageName : subtitle;
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
            this.icon = icon;
            this.isOemPreset = isOemPreset;
            this.isHmgApi = isHmgApi;
            this.badgeText = badgeText == null ? "" : badgeText;
            this.stockItem = stockItem;
            this.iconOverride = iconOverride == null ? "" : iconOverride;
            this.resolvedSvgPath = resolvedSvgPath == null ? "" : resolvedSvgPath;
            this.hidden = hidden;
            this.nameSuggestions = nameSuggestions == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(nameSuggestions));
        }
    }

    public static final class DrawerSections {
        public final List<AppEntry> stockAndHmgApi;
        public final List<AppEntry> allApps;
        public final int hiddenCount;

        DrawerSections(List<AppEntry> stockAndHmgApi, List<AppEntry> allApps, int hiddenCount) {
            this.stockAndHmgApi = stockAndHmgApi;
            this.allApps = allApps;
            this.hiddenCount = hiddenCount;
        }
    }

    private AppLauncher() {}

    public static boolean launchAction(final Context context, ActionSpec action) {
        if (action == null) {
            return false;
        }
        if ("preset".equals(action.type)) {
            return launchPreset(context, action.cmd);
        }
        if ("launch_climate".equals(action.type)) {
            return launchClimate(context);
        }
        if ("broadcast".equals(action.type)) {
            return sendActionBroadcast(context, action);
        }
        if ("root_broadcast".equals(action.type) || "root_start".equals(action.type) || "root_service".equals(action.type)) {
            String amMode = "root_broadcast".equals(action.type) ? "bcast"
                    : ("root_service".equals(action.type) ? "service" : "start");
            SwitcherClient.runRootAmAsync(
                    amMode,
                    action.pkg,
                    action.cls,
                    action.actionName,
                    action.extraKey,
                    action.extraType,
                    action.extraVal,
                    new SwitcherClient.SimpleCallback() {
                        @Override
                        public void onResult(boolean ok, String message) {
                            if (!ok) {
                                Toast.makeText(context, "Action failed: " + message, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return true;
        }
        if ("launch_projection".equals(action.type)) {
            return launchProjection(context, action);
        }
        if ("car_cmd".equals(action.type)) {
            return sendCarCommand(context, action.cmd, action.extraVal);
        }
        return launchComponentOrPackage(
                context,
                action.pkg,
                action.cls,
                action.actionName,
                action.fallbackPkg,
                action.fallbackCls);
    }

    public static boolean launchEntry(Context context, AppEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.stockItem != null) {
            return launchStockItem(context, entry.stockItem);
        }
        return launchComponentOrPackage(context, entry.packageName, entry.className, "", "", "");
    }

    public static boolean launchPreset(final Context context, String presetKey) {
        if ("ev".equalsIgnoreCase(presetKey) || "energy_info".equalsIgnoreCase(presetKey)) {
            return launchFirstInstalledCandidate(context, "EV / Hybrid Info", new String[][] {
                    { "com.hkmc.daudio.app.ev3", "com.hkmc.daudio.app.ev3.MainActivity" },
                    { "com.hkmc.daudio.app.ev", "com.hkmc.daudio.app.ev.IgnCheckActivity" },
                    { "com.hkmc.daudio.app.hev2", "com.hkmc.daudio.app.hev2.ui.IgnCheckActivity" },
                    { "com.hkmc.daudio.app.phev2", "com.hkmc.daudio.app.phev2.IgnCheckActivity" }
            });
        }
        if ("climate".equalsIgnoreCase(presetKey)) {
            return launchClimate(context);
        }
        if ("seat_front".equalsIgnoreCase(presetKey) || "seats".equalsIgnoreCase(presetKey)) {
            if (launchFirstInstalledCandidate(context, null, new String[][] {
                    { "com.hkmc.telematics.service.apps.climate", "com.hkmc.telematics.service.apps.climate.activities.SWRCFrontSeatHeatVentControl" },
                    { "com.hkmc.telematics.service.apps.climate", "com.hkmc.telematics.service.apps.climate.activities.SeatHeatVentPopup" }
            })) {
                return true;
            }
            if (StockMenuCatalog.isPackageInstalled(context.getPackageManager(), "com.hkmc.telematics.service.apps.climate")) {
                return launchStockItem(context, StockMenuCatalog.findPresetItem("seat_front_bcast"));
            }
            Toast.makeText(context, "Seat heat/vent control is not available on this car", Toast.LENGTH_SHORT).show();
            return false;
        }
        if ("seat_rear".equalsIgnoreCase(presetKey)) {
            return launchFirstInstalledCandidate(context, "Rear Seat Heat & Ventilation", new String[][] {
                    { "com.hkmc.telematics.service.apps.climate", "com.hkmc.telematics.service.apps.climate.activities.RearSeatHeatVentControl" },
                    { "com.hkmc.telematics.service.apps.climate", "com.hkmc.telematics.service.apps.climate.activities.SeatHeatVentPopup" }
            });
        }
        if ("projection".equalsIgnoreCase(presetKey)) {
            ActionSpec dummy = new ActionSpec();
            dummy.pkg = "com.daudio.app.phoneprojection";
            dummy.cls = "com.daudio.app.phoneprojection.settings.ui.DeviceConnectionSettings";
            return launchProjection(context, dummy);
        }
        if ("stock_home".equalsIgnoreCase(presetKey)) {
            return launchStockHome(context);
        }
        if ("radio".equalsIgnoreCase(presetKey) || "sxm".equalsIgnoreCase(presetKey)) {
            return launchFirstInstalledCandidate(context, "Radio", new String[][] {
                    { "com.hkmc.av.app.radiona", "com.hkmc.av.app.radiona.RadioMainActivity" },
                    { "com.hkmc.av.app.radiodrm", "com.hkmc.av.app.radiodrm.RadioMainActivity" },
                    { "com.hkmc.av.app.radioge", "com.hkmc.av.app.radioge.RadioMainActivity" }
            });
        }
        if ("bluelink_settings".equalsIgnoreCase(presetKey)) {
            return launchFirstInstalledCandidate(context, "Connected Services Settings", new String[][] {
                    { "com.hkmc.telematics.apps.Vrm.main", "com.hkmc.telematics.apps.Vrm.settings.ui.BluelinkUvoSettingsMenu" },
                    { "com.hkmc.telematics.apps.Vrm.main", "com.hkmc.telematics.apps.Vrm.settings.ui.SettingMenuUVO" }
            });
        }
        if ("screen_saver".equalsIgnoreCase(presetKey)) {
            return launchFirstInstalledCandidate(context, "Screen Saver", new String[][] {
                    { "com.android.displaysettings", "com.android.displaysettings.WaitDisplay" },
                    { "com.android.themesettings", "com.android.themesettings.WaitDisplay" }
            });
        }
        if ("dvrs_act".equalsIgnoreCase(presetKey)) {
            if (!StockMenuCatalog.isPackageInstalled(context.getPackageManager(), "com.daudio.app.dvrs")) {
                Toast.makeText(context, "Built-in Dashcam is not installed on this car", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (tryStartComponent(context, "com.daudio.app.dvrs", "com.daudio.app.dvrs.ui.DvrsActivity")) {
                return true;
            }
            return launchStockItem(context, StockMenuCatalog.findPresetItem("dvrs_bcast"));
        }
        if ("iot_act".equalsIgnoreCase(presetKey)) {
            if (!StockMenuCatalog.isPackageInstalled(context.getPackageManager(), "com.hkmc.telematics.apps.iot")) {
                Toast.makeText(context, "Car-to-Home IoT is not installed on this car", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (tryStartComponent(context, "com.hkmc.telematics.apps.iot", "com.hkmc.telematics.apps.iot.MainActivity")) {
                return true;
            }
            return launchStockItem(context, StockMenuCatalog.findPresetItem("iot_bcast"));
        }
        if ("nemesis".equalsIgnoreCase(presetKey)) {
            return launchNemesisEntry(context);
        }
        if ("car_controls_popup".equalsIgnoreCase(presetKey)) {
            Toast.makeText(context, "Open Live Car Controls from the Home launcher", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (presetKey != null && presetKey.toLowerCase(Locale.US).startsWith("car_cmd:")) {
            return sendCarCommand(context, presetKey.substring("car_cmd:".length()), "");
        }

        StockMenuCatalog.StockItem item = StockMenuCatalog.findPresetItem(presetKey);
        if (item == null) {
            Toast.makeText(context, "Unknown shortcut preset: " + presetKey, Toast.LENGTH_SHORT).show();
            return false;
        }
        return launchStockItem(context, item);
    }

    private static boolean sendCarCommand(final Context context, final String cmd, final String arg) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return false;
        }
        SwitcherClient.sendCarCommandAsync(cmd.trim(), arg == null ? "" : arg, new SwitcherClient.SimpleCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (!ok && message != null && !message.isEmpty()) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
        return true;
    }

    public static boolean launchStockItem(final Context context, final StockMenuCatalog.StockItem item) {
        if (item == null) {
            return false;
        }
        if (item.mode == StockMenuCatalog.Mode.CLIMATE_SMART) {
            return launchClimate(context);
        }
        PackageManager pm = context.getPackageManager();
        if (item.mode == StockMenuCatalog.Mode.BROADCAST) {
            if (!item.pkg.isEmpty() && !StockMenuCatalog.isPackageInstalled(pm, item.pkg)) {
                Toast.makeText(context, item.title + " is not available on this car", Toast.LENGTH_SHORT).show();
                return false;
            }
            item.triggerViaRoot(new SwitcherClient.SimpleCallback() {
                @Override
                public void onResult(boolean ok, String message) {
                    if (!ok) {
                        Toast.makeText(context, item.title + " unavailable on this car", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return true;
        }

        String res = item.triggerViaApp(context);
        if (res.startsWith("OK:")) {
            return true;
        }
        if (!item.pkg.isEmpty() && !StockMenuCatalog.isPackageInstalled(pm, item.pkg)) {
            Toast.makeText(context, item.title + " is not installed on this car", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!item.isTargetAvailable(pm)) {
            if (!item.pkg.isEmpty() && tryLaunchPackage(context, item.pkg)) {
                return true;
            }
            Toast.makeText(context, item.title + " is not supported on this car", Toast.LENGTH_SHORT).show();
            return false;
        }
        item.triggerViaRoot(new SwitcherClient.SimpleCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (!ok) {
                    Toast.makeText(context, "Unable to open " + item.title, Toast.LENGTH_SHORT).show();
                }
            }
        });
        return true;
    }

    private static boolean launchNemesisEntry(final Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
        if (resolved != null) {
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName == null ? "" : ri.activityInfo.packageName;
                String cls = ri.activityInfo.name == null ? "" : ri.activityInfo.name;
                CharSequence cs = ri.loadLabel(pm);
                String lbl = cs == null ? "" : cs.toString();
                if ((pkg + " " + cls + " " + lbl).toLowerCase(Locale.US).contains("nemesis")) {
                    if (tryStartComponent(context, pkg, cls)) return true;
                }
            }
        }
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SwitcherClient.ModLauncherItem> mods = SwitcherClient.fetchHmgApiLauncherEntriesBlocking();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        for (SwitcherClient.ModLauncherItem m : mods) {
                            String hay = (m.modId + " " + m.packageName + " " + m.className + " " + m.title).toLowerCase(Locale.US);
                            if (hay.contains("nemesis")) {
                                if (launchComponentOrPackage(context, m.packageName, m.className, "", "", "")) {
                                    return;
                                }
                            }
                        }
                        Toast.makeText(context, "NEMESIS Manager shortcut is not installed on this car", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "launch-nemesis");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static boolean launchFirstInstalledCandidate(Context context, String unavailableLabel, String[][] pairs) {
        PackageManager pm = context.getPackageManager();
        for (String[] pair : pairs) {
            String pkg = pair[0];
            String cls = pair[1];
            if (tryStartComponent(context, pkg, cls)) {
                return true;
            }
            if (isComponentPresent(pm, pkg, cls)) {
                SwitcherClient.runRootAmAsync("start", pkg, cls, "", "", "", "", new SwitcherClient.SimpleCallback() {
                    @Override
                    public void onResult(boolean ok, String message) {
                        if (!ok) {
                            Toast.makeText(context, "Unable to open component", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                return true;
            }
        }
        for (String[] pair : pairs) {
            if (tryLaunchPackage(context, pair[0])) {
                return true;
            }
        }
        if (unavailableLabel != null && !unavailableLabel.isEmpty()) {
            Toast.makeText(context, unavailableLabel + " is not available on this car", Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    public static boolean launchClimate(final Context context) {
        String pkg = "com.hkmc.telematics.service.apps.climate";
        String[] activities = new String[] {
                pkg + ".activities.NewClimateControl",
                pkg + ".activities.InteriorClimateNew",
                pkg + ".activities.InteriorClimate"
        };
        for (String act : activities) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(pkg, act));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("CLIMATE_SHORT_KEY", false);
            if (tryStartIntent(context, intent)) {
                return true;
            }
        }
        if (tryLaunchPackage(context, pkg)) {
            return true;
        }
        sendActionBroadcast(context, "com.hkmc.telematics.service.apps.climate.REQUEST_CLIMATE", "activityEntryPoint", 0);
        SwitcherClient.runRootAmAsync("bcast", "", "",
                "com.hkmc.telematics.service.apps.climate.REQUEST_CLIMATE",
                "activityEntryPoint", "int", "0",
                new SwitcherClient.SimpleCallback() {
                    @Override
                    public void onResult(boolean ok, String message) {
                        if (!ok) {
                            Toast.makeText(context, "Climate launch: " + message, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        return true;
    }

    public static boolean sendActionBroadcast(Context context, ActionSpec action) {
        if (action == null || action.actionName == null || action.actionName.isEmpty()) {
            return false;
        }
        try {
            Intent intent = new Intent(action.actionName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!action.pkg.isEmpty()) {
                intent.setPackage(action.pkg);
            }
            if (!action.extraKey.isEmpty()) {
                StockMenuCatalog.applyIntentExtra(intent, action.extraKey, action.extraType, action.extraVal);
            }
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            Toast.makeText(context, "Unable to send " + action.actionName, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    public static boolean sendActionBroadcast(Context context, String actionName, String extraIntKey, int extraIntValue) {
        if (actionName == null || actionName.isEmpty()) {
            return false;
        }
        try {
            Intent intent = new Intent(actionName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (extraIntKey != null && !extraIntKey.isEmpty()) {
                intent.putExtra(extraIntKey, extraIntValue);
            }
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            Toast.makeText(context, "Unable to send " + actionName, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    public static boolean launchStockHome(Context context) {
        boolean ok = tryStartComponent(context, "com.android.launcher3", "com.android.launcher3.view.Launcher");
        if (!ok) {
            ok = tryStartComponent(context, "com.android.launcher", "com.android.launcher2.view.Launcher");
        }
        if (!ok) {
            Toast.makeText(context, "Stock launcher activity unavailable", Toast.LENGTH_SHORT).show();
        }
        return ok;
    }

    public static boolean launchComponentOrPackage(
            final Context context,
            String pkg,
            String cls,
            String intentAction,
            String fallbackPkg,
            String fallbackCls) {
        if ("com.hkmc.telematics.service.apps.climate".equals(pkg)
                && (cls == null || cls.isEmpty() || cls.endsWith(".UIClimate"))) {
            return launchClimate(context);
        }
        if (tryStartComponent(context, pkg, cls)) {
            return true;
        }
        if (intentAction != null && !intentAction.isEmpty()) {
            Intent byAction = new Intent(intentAction);
            byAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tryStartIntent(context, byAction)) {
                return true;
            }
        }
        PackageManager pm = context.getPackageManager();
        if (pkg != null && !pkg.isEmpty() && cls != null && !cls.isEmpty() && isComponentPresent(pm, pkg, cls)) {
            String fullCls = cls.startsWith(".") ? (pkg + cls) : cls;
            final String label = pkg;
            SwitcherClient.runRootAmAsync("start", pkg, fullCls, intentAction, "", "", "",
                    new SwitcherClient.SimpleCallback() {
                        @Override
                        public void onResult(boolean ok, String message) {
                            if (!ok) {
                                Toast.makeText(context, "Unable to open " + label, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            return true;
        }
        if (pkg != null && !pkg.isEmpty() && tryLaunchPackage(context, pkg)) {
            return true;
        }
        if (tryStartComponent(context, fallbackPkg, fallbackCls)) {
            return true;
        }
        if (fallbackPkg != null && !fallbackPkg.isEmpty() && tryLaunchPackage(context, fallbackPkg)) {
            return true;
        }
        final String label = (pkg != null && !pkg.isEmpty()) ? pkg : intentAction;
        Toast.makeText(context, "Not available on this car: " + label, Toast.LENGTH_SHORT).show();
        return false;
    }

    private static boolean isComponentPresent(PackageManager pm, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty() || cls == null || cls.isEmpty()) {
            return false;
        }
        try {
            String fullCls = cls.startsWith(".") ? (pkg + cls) : cls;
            pm.getActivityInfo(new ComponentName(pkg, fullCls), 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean launchProjection(Context context, ActionSpec action) {
        String[][] candidates = new String[][] {
                { "com.daudio.app.dipo", "" },
                { "com.google.android.projection.sink", "" },
                { action.pkg, action.cls },
                { "com.daudio.app.phoneprojection", "com.daudio.app.phoneprojection.settings.ui.DeviceConnectionSettings" }
        };
        for (String[] pair : candidates) {
            if (pair[0] != null && !pair[0].isEmpty()) {
                if (!pair[1].isEmpty() && tryStartComponent(context, pair[0], pair[1])) {
                    return true;
                }
                if (tryLaunchPackage(context, pair[0])) {
                    return true;
                }
            }
        }
        Toast.makeText(context, "Connect phone or open Device Connections", Toast.LENGTH_SHORT).show();
        return false;
    }

    public static List<AppEntry> loadAllApps(Context context) {
        DrawerSections sec = loadDrawerSections(context, null, false);
        List<AppEntry> merged = new ArrayList<AppEntry>(sec.stockAndHmgApi.size() + sec.allApps.size());
        merged.addAll(sec.stockAndHmgApi);
        merged.addAll(sec.allApps);
        return merged;
    }

    public static DrawerSections loadDrawerSections(Context context, List<SwitcherClient.ModLauncherItem> modEntries) {
        return loadDrawerSections(context, modEntries, false);
    }

    public static DrawerSections loadDrawerSections(
            Context context,
            List<SwitcherClient.ModLauncherItem> modEntries,
            boolean includeHidden) {
        PackageManager pm = context.getPackageManager();
        Map<String, AppCatalogOverrides.OverrideEntry> overrides = AppCatalogOverrides.loadOverrides(context);
        boolean preferSvg = AppCatalogOverrides.isPreferSvgIcons(context);

        List<StockMenuCatalog.StockItem> stockCatalog = StockMenuCatalog.buildAll();
        Map<String, StockMenuCatalog.StockItem> stockByComponent = new LinkedHashMap<String, StockMenuCatalog.StockItem>();
        Map<String, StockMenuCatalog.StockItem> stockById = new LinkedHashMap<String, StockMenuCatalog.StockItem>();
        for (StockMenuCatalog.StockItem item : stockCatalog) {
            stockById.put(item.id, item);
            if (!item.pkg.isEmpty() && !item.cls.isEmpty()) {
                stockByComponent.put(componentKey(item.pkg, item.cls), item);
            }
        }

        Map<String, SwitcherClient.ModLauncherItem> modByComp = new LinkedHashMap<String, SwitcherClient.ModLauncherItem>();
        if (modEntries != null) {
            for (SwitcherClient.ModLauncherItem m : modEntries) {
                if (m == null || m.packageName.isEmpty() || m.className.isEmpty()) continue;
                String key = componentKey(m.packageName, m.className);
                if (SELF_HOME_COMPONENT.equals(key)) continue;
                modByComp.put(key, m);
            }
        }

        // --- SECTION 1: Stock Launcher (Launcher_WP3 + HomeSetting order) + HMG-API & NEMESIS first ---
        Map<String, AppEntry> stockMap = new LinkedHashMap<String, AppEntry>();

        // 1a. Read saved stock Launcher menu order if HomeSetting provider allows access on this unit
        readSavedStockHomeMenu(context, pm, stockByComponent, modByComp, stockMap, overrides, preferSvg);

        // 1b. Curated Stock Launcher (Launcher_WP3) menu entries verified installed on this car
        addFirstAvailableStockItem(context, pm, stockMap, modByComp, stockById, "EV & Energy",
                new String[] { "ev3_main", "ev1_ign", "hev_ign", "phev_ign" }, overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("navi_app"), "Navigation", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("map_app"), "Map", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("climate_smart"), "Climate", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("seat_front_ctrl"), "Seats & Steering", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("seat_rear_ctrl"), "Rear Seats", overrides, preferSvg);
        if (StockMenuCatalog.isPackageInstalled(pm, "com.hkmc.telematics.service.apps.climate")) {
            addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("climate_rear_req"), "Rear Climate", overrides, preferSvg);
        }
        addFirstAvailableStockItem(context, pm, stockMap, modByComp, stockById, "Radio / SiriusXM",
                new String[] { "radio_na", "radio_drm", "radio_ge" }, overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("media_gate"), "Media", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("bt_audio"), "Bluetooth Audio", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("usb_music"), "USB Music", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("usb_video"), "USB Video", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("nature_sound"), "Sounds of Nature", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("bt_dial"), "Bluetooth Phone", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("carplay_dipo"), "Apple CarPlay", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("android_auto"), "Android Auto", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("phone_proj_conn"), "Phone Projection", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("voice_memo"), "Voice Memo", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("drvm"), "Rear Camera", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("quiet_mode"), "Quiet Mode", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("passenger_talk"), "Passenger Talk", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("bluelink"), "BlueLink / Connect", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("driving_info"), "Driving Info", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("valet_mode"), "Valet Mode", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("notifications"), "Notifications", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("hd_data"), "HD Radio Data", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("doppler_radar"), "Weather Radar", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("soundhound"), "SoundHound", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("smart_pure_air"), "Smart Pure Air", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("n_mode"), "N Mode", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("dvrs_act"), "Dashcam (DVRS)", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("iot_act"), "Car to Home (IoT)", overrides, preferSvg);
        if (StockMenuCatalog.isPackageInstalled(pm, "com.mnsoft.navi")) {
            addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("carpay"), "CarPay", overrides, preferSvg);
        }
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("veh_settings"), "Vehicle Settings", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("seat_arrangement"), "Seat Memory", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("sound_settings"), "Sound Settings", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("display_settings"), "Display Settings", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("general_settings"), "General Settings", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("bt_settings"), "Bluetooth Settings", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("wifi_settings"), "Wi-Fi Settings", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("button_settings"), "Button Settings", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("user_profile"), "User Profile", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("theme_settings"), "Screen Themes", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("my_menu"), "My Menu", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("manual_tip"), "Web Manual", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("setup_settings"), "Setup", overrides, preferSvg);
        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockById.get("edit_stock_home"), "Edit Stock Home", overrides, preferSvg);

        // 1c. HMG-API Manager & every custom mod launcher entry injected via provides.launcher
        addHmgApiManagerIfInstalled(context, pm, stockMap, overrides, preferSvg);
        for (SwitcherClient.ModLauncherItem m : modByComp.values()) {
            addModEntryToStockSection(context, pm, stockMap, m, overrides, preferSvg);
        }

        // --- SECTION 2: More Complete All Installed Apps (multi-activity OEM + system + 3rd party) ---
        Map<String, AppEntry> allMap = new LinkedHashMap<String, AppEntry>();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
        Map<String, Integer> pkgLabelCount = new HashMap<String, Integer>();
        if (resolved != null) {
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) continue;
                String key = componentKey(ri.activityInfo.packageName, ri.activityInfo.name);
                if (SELF_HOME_COMPONENT.equals(key)) continue;
                CharSequence cs = ri.loadLabel(pm);
                String rawLabel = cs != null && cs.length() > 0 ? cs.toString() : ri.activityInfo.packageName;
                String countKey = ri.activityInfo.packageName + "|" + rawLabel;
                Integer cur = pkgLabelCount.get(countKey);
                pkgLabelCount.put(countKey, cur == null ? 1 : cur + 1);
            }
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String cls = normalizeClassName(pkg, ri.activityInfo.name);
                String key = componentKey(pkg, cls);
                if (SELF_HOME_COMPONENT.equals(key) || allMap.containsKey(key)) {
                    continue;
                }
                CharSequence cs = ri.loadLabel(pm);
                String baseLabel = cs != null && cs.length() > 0 ? cs.toString() : pkg;
                StockMenuCatalog.StockItem knownStock = stockByComponent.get(key);
                String label = cleanDisplayLabel(baseLabel, pkg, cls, knownStock);
                Integer dupCount = pkgLabelCount.get(pkg + "|" + baseLabel);
                if (dupCount != null && dupCount.intValue() > 1) {
                    if (knownStock != null && !knownStock.title.isEmpty()) {
                        label = stripArrowDetails(knownStock.title);
                    } else {
                        String tag = humanizeToken(shortClassName(cls));
                        if (!tag.isEmpty() && !label.toLowerCase(Locale.US).contains(tag.toLowerCase(Locale.US))) {
                            label = label + " (" + tag + ")";
                        }
                    }
                }
                Drawable rawIcon = null;
                try {
                    rawIcon = ri.loadIcon(pm);
                } catch (Throwable ignored) {
                }
                SwitcherClient.ModLauncherItem mod = modByComp.get(key);
                if (mod != null && !mod.title.isEmpty()) {
                    label = mod.title;
                }
                byte[] customBytes = (mod != null && mod.iconBytes != null && mod.iconBytes.length > 0) ? mod.iconBytes : null;
                boolean isSpecialMod = mod != null || isTopEcosystemEntry(label, pkg, cls, "");
                AppEntry entry = createConfiguredEntry(
                        context, pm, key, label, pkg, cls, rawIcon, customBytes,
                        knownStock, knownStock != null, isSpecialMod,
                        isSpecialMod ? "HMG-API" : "", overrides, preferSvg);
                allMap.put(key, entry);
                // Promote NEMESIS / HMG-API / Gen5W Themes discovered via CATEGORY_LAUNCHER into top section too
                if (entry.isHmgApi && !stockMap.containsKey(key)) {
                    stockMap.put(key, entry);
                }
            }
        }

        // Also ensure mod entries & installed StockMenuCatalog activities without CATEGORY_LAUNCHER appear in All Apps
        for (SwitcherClient.ModLauncherItem m : modByComp.values()) {
            String key = componentKey(m.packageName, m.className);
            if (SELF_HOME_COMPONENT.equals(key) || allMap.containsKey(key)) continue;
            if (!isComponentPresent(pm, m.packageName, m.className)) continue;
            String label = !m.title.isEmpty() ? m.title : loadActivityLabel(pm, m.packageName, m.className, m.modId);
            String fullCls = normalizeClassName(m.packageName, m.className);
            AppEntry entry = createConfiguredEntry(
                    context, pm, key, label, m.packageName, fullCls, null, m.iconBytes,
                    null, false, true, "HMG-API", overrides, preferSvg);
            allMap.put(key, entry);
        }
        for (StockMenuCatalog.StockItem item : stockCatalog) {
            if (item.mode == StockMenuCatalog.Mode.BROADCAST || item.pkg.isEmpty() || item.cls.isEmpty()) continue;
            String fullCls = normalizeClassName(item.pkg, item.cls);
            String key = componentKey(item.pkg, fullCls);
            if (SELF_HOME_COMPONENT.equals(key) || allMap.containsKey(key)) continue;
            if (!item.isTargetAvailable(pm)) continue;
            String label = stripArrowDetails(item.title);
            boolean isSpecial = isTopEcosystemEntry(label, item.pkg, fullCls, "");
            AppEntry entry = createConfiguredEntry(
                    context, pm, key, label, item.pkg, fullCls, null, null,
                    item, true, isSpecial, isSpecial ? "HMG-API" : "", overrides, preferSvg);
            allMap.put(key, entry);
        }

        Set<String> hiddenKeys = new LinkedHashSet<String>();
        for (AppEntry e : stockMap.values()) {
            if (e.hidden) hiddenKeys.add(e.entryKey);
        }
        for (AppEntry e : allMap.values()) {
            if (e.hidden) hiddenKeys.add(e.entryKey);
        }

        List<AppEntry> stockList = new ArrayList<AppEntry>();
        for (AppEntry e : stockMap.values()) {
            if (includeHidden || !e.hidden) {
                stockList.add(e);
            }
        }
        // Float NEMESIS, HMG-API, Gen5W Themes & mods to the very top of Section 1 while preserving curated order for the rest
        final Map<AppEntry, Integer> stockOrigIdx = new HashMap<AppEntry, Integer>();
        for (int i = 0; i < stockList.size(); i++) {
            stockOrigIdx.put(stockList.get(i), i);
        }
        Collections.sort(stockList, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int ra = topRank(a);
                int rb = topRank(b);
                if (ra != rb) return ra - rb;
                if (ra < 4) return a.label.compareToIgnoreCase(b.label);
                Integer ia = stockOrigIdx.get(a);
                Integer ib = stockOrigIdx.get(b);
                return (ia == null ? 0 : ia) - (ib == null ? 0 : ib);
            }
        });

        List<AppEntry> allList = new ArrayList<AppEntry>();
        for (AppEntry e : allMap.values()) {
            if (includeHidden || !e.hidden) {
                allList.add(e);
            }
        }
        Collections.sort(allList, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int ra = topRank(a);
                int rb = topRank(b);
                if (ra != rb) return ra - rb;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return new DrawerSections(stockList, allList, hiddenKeys.size());
    }

    private static AppEntry createConfiguredEntry(
            Context ctx,
            PackageManager pm,
            String entryKey,
            String rawLabel,
            String pkg,
            String fullCls,
            Drawable preloadedIcon,
            byte[] modIconBytes,
            StockMenuCatalog.StockItem stockItem,
            boolean isOemPreset,
            boolean isModEntry,
            String badgeText,
            Map<String, AppCatalogOverrides.OverrideEntry> overrides,
            boolean preferSvg) {
        String stockId = stockItem != null ? stockItem.id : "";
        AppCatalogOverrides.CuratedMeta meta = AppCatalogOverrides.resolveMeta(entryKey, pkg, fullCls, stockId);

        String defaultLabel;
        if (isModEntry && rawLabel != null && !rawLabel.trim().isEmpty() && !looksLikePackageName(rawLabel)) {
            defaultLabel = rawLabel.trim();
        } else if (meta != null && !meta.primaryName.isEmpty()) {
            defaultLabel = meta.primaryName;
        } else if (rawLabel != null && !rawLabel.trim().isEmpty()) {
            defaultLabel = cleanDisplayLabel(rawLabel, pkg, fullCls, stockItem);
        } else {
            defaultLabel = cleanDisplayLabel("", pkg, fullCls, stockItem);
        }

        AppCatalogOverrides.OverrideEntry ov = overrides != null ? overrides.get(entryKey) : null;
        String customLabel = ov != null ? ov.customLabel : "";
        String finalLabel = !customLabel.isEmpty() ? customLabel : defaultLabel;
        boolean isTop = isModEntry || isTopEcosystemEntry(finalLabel, pkg, fullCls, "");
        String finalBadge = isTop ? "HMG-API" : (badgeText == null ? "" : badgeText);
        boolean hidden = (ov != null && ov.hasHiddenOverride) ? ov.hidden : (meta != null && meta.defaultHidden);

        String iconOverride = ov != null ? ov.iconOverride : "";
        String autoSvg = (meta != null && !meta.svgPath.isEmpty())
                ? meta.svgPath
                : pickFallbackSvgPath(finalLabel, pkg, fullCls, stockItem, isTop);
        String[] resolvedSvgOut = new String[1];
        Drawable icon = resolveAppIcon(
                ctx, pm, pkg, fullCls, preloadedIcon, modIconBytes,
                finalLabel, stockItem, isTop, preferSvg, iconOverride, autoSvg, resolvedSvgOut);

        String sub = (meta != null && !meta.category.isEmpty() && !isTop)
                ? meta.category
                : cleanSubtitle(finalLabel, pkg, isModEntry ? "mod" : "", stockItem, isTop);
        List<String> suggestions = AppCatalogOverrides.buildSuggestionsForEntry(defaultLabel, pkg, fullCls, meta);
        return new AppEntry(
                entryKey, finalLabel, defaultLabel, customLabel, sub, pkg, fullCls,
                icon, isOemPreset, isTop, finalBadge, stockItem,
                iconOverride, resolvedSvgOut[0], hidden, suggestions);
    }

    private static void readSavedStockHomeMenu(
            Context context,
            PackageManager pm,
            Map<String, StockMenuCatalog.StockItem> stockByComponent,
            Map<String, SwitcherClient.ModLauncherItem> modByComp,
            Map<String, AppEntry> stockMap,
            Map<String, AppCatalogOverrides.OverrideEntry> overrides,
            boolean preferSvg) {
        Cursor cur = null;
        try {
            cur = context.getContentResolver().query(STOCK_ALL_MENU_URI, null, null, null, null);
            if (cur == null) return;
            int itemsCol = cur.getColumnIndex("menu_items");
            if (itemsCol < 0) return;
            while (cur.moveToNext()) {
                String json = cur.getString(itemsCol);
                if (json == null || json.isEmpty()) continue;
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.optJSONArray("menuItems");
                if (arr == null || arr.length() == 0) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String pkg = it.optString("packageName", "").trim();
                    String cls = it.optString("className", "").trim();
                    if (pkg.isEmpty() || "Empty view".equals(pkg) || cls.isEmpty()) continue;
                    String fullCls = normalizeClassName(pkg, cls);
                    String key = componentKey(pkg, fullCls);
                    if (SELF_HOME_COMPONENT.equals(key) || stockMap.containsKey(key)) continue;

                    SwitcherClient.ModLauncherItem mod = modByComp.get(key);
                    if (mod != null) {
                        addModEntryToStockSection(context, pm, stockMap, mod, overrides, preferSvg);
                        continue;
                    }
                    StockMenuCatalog.StockItem stockItem = stockByComponent.get(key);
                    if (stockItem != null) {
                        addStockItemIfAvailable(context, pm, stockMap, modByComp, stockItem, stockItem.title, overrides, preferSvg);
                        continue;
                    }
                    if (!isComponentPresent(pm, pkg, fullCls)) continue;
                    String label = cleanDisplayLabel(loadActivityLabel(pm, pkg, fullCls, shortClassName(fullCls)), pkg, fullCls, null);
                    stockMap.put(key, createConfiguredEntry(
                            context, pm, key, label, pkg, fullCls, null, null,
                            null, true, false, "STOCK", overrides, preferSvg));
                }
                if (!stockMap.isEmpty()) {
                    break; // Use the active/first valid profile row
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) {
                try { cur.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void addFirstAvailableStockItem(
            Context context,
            PackageManager pm,
            Map<String, AppEntry> out,
            Map<String, SwitcherClient.ModLauncherItem> modByComp,
            Map<String, StockMenuCatalog.StockItem> stockById,
            String overrideLabel,
            String[] candidateIds,
            Map<String, AppCatalogOverrides.OverrideEntry> overrides,
            boolean preferSvg) {
        for (String id : candidateIds) {
            StockMenuCatalog.StockItem item = stockById.get(id);
            if (item != null && item.isTargetAvailable(pm)) {
                addStockItemIfAvailable(context, pm, out, modByComp, item, overrideLabel, overrides, preferSvg);
                return;
            }
        }
    }

    private static void addStockItemIfAvailable(
            Context context,
            PackageManager pm,
            Map<String, AppEntry> out,
            Map<String, SwitcherClient.ModLauncherItem> modByComp,
            StockMenuCatalog.StockItem item,
            String friendlyLabel,
            Map<String, AppCatalogOverrides.OverrideEntry> overrides,
            boolean preferSvg) {
        if (item == null || !item.isTargetAvailable(pm)) return;
        String key = item.mode == StockMenuCatalog.Mode.BROADCAST
                ? ("bcast:" + item.id)
                : componentKey(item.pkg, normalizeClassName(item.pkg, item.cls));
        if (out.containsKey(key)) return;
        if (!item.pkg.isEmpty() && !item.cls.isEmpty() && modByComp.containsKey(componentKey(item.pkg, item.cls))) {
            return;
        }
        String label = (friendlyLabel != null && !friendlyLabel.isEmpty()) ? friendlyLabel : stripArrowDetails(item.title);
        String fullCls = normalizeClassName(item.pkg, item.cls);
        out.put(key, createConfiguredEntry(
                context, pm, key, label, item.pkg, fullCls, null, null,
                item, true, false, "STOCK", overrides, preferSvg));
    }

    private static void addHmgApiManagerIfInstalled(
            Context context,
            PackageManager pm,
            Map<String, AppEntry> out,
            Map<String, AppCatalogOverrides.OverrideEntry> overrides,
            boolean preferSvg) {
        String pkg = "hmgapi.app";
        String cls = "hmgapi.app.MainActivity";
        String key = componentKey(pkg, cls);
        if (out.containsKey(key) || !StockMenuCatalog.isPackageInstalled(pm, pkg)) return;
        out.put(key, createConfiguredEntry(
                context, pm, key, "HMG-API", pkg, cls, null, null,
                null, false, true, "HMG-API", overrides, preferSvg));
    }

    private static void addModEntryToStockSection(
            Context context,
            PackageManager pm,
            Map<String, AppEntry> out,
            SwitcherClient.ModLauncherItem mod,
            Map<String, AppCatalogOverrides.OverrideEntry> overrides,
            boolean preferSvg) {
        String fullCls = normalizeClassName(mod.packageName, mod.className);
        String key = componentKey(mod.packageName, fullCls);
        if (SELF_HOME_COMPONENT.equals(key)) return;
        if (!StockMenuCatalog.isPackageInstalled(pm, mod.packageName)) return;
        String label = !mod.title.isEmpty() ? mod.title : loadActivityLabel(pm, mod.packageName, fullCls, mod.modId);
        out.put(key, createConfiguredEntry(
                context, pm, key, label, mod.packageName, fullCls, null, mod.iconBytes,
                null, false, true, "HMG-API", overrides, preferSvg));
    }

    private static int topRank(AppEntry e) {
        String lbl = (e.label == null ? "" : e.label).toLowerCase(Locale.US);
        String pkg = (e.packageName == null ? "" : e.packageName).toLowerCase(Locale.US);
        String cls = (e.className == null ? "" : e.className).toLowerCase(Locale.US);
        if (lbl.contains("nemesis") || pkg.contains("nemesis") || cls.contains("nemesis")) return 0;
        if ("hmgapi.app".equals(pkg) || "hmg-api".equals(lbl) || pkg.startsWith("hmgapi.")) return 1;
        if ("com.gen5w.launcher".equals(pkg)) return 2;
        if (e.isHmgApi) return 3;
        return 4;
    }

    private static boolean isTopEcosystemEntry(String label, String pkg, String cls, String modId) {
        String s = ((label == null ? "" : label) + " " + (pkg == null ? "" : pkg) + " "
                + (cls == null ? "" : cls) + " " + (modId == null ? "" : modId)).toLowerCase(Locale.US);
        return s.contains("nemesis") || s.contains("hmgapi") || s.contains("hmg-api")
                || "com.gen5w.launcher".equalsIgnoreCase(pkg);
    }

    private static String cleanSubtitle(String label, String pkg, String modId, StockMenuCatalog.StockItem stock, boolean isHmgApi) {
        String lblLow = label == null ? "" : label.toLowerCase(Locale.US);
        String pkgLow = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        String modLow = modId == null ? "" : modId.toLowerCase(Locale.US);
        if (lblLow.contains("nemesis") || pkgLow.contains("nemesis") || modLow.contains("nemesis")) {
            return "NEMESIS Installer";
        }
        if ("hmgapi.app".equals(pkgLow) || "hmg-api".equals(lblLow)) {
            return "Mod Platform";
        }
        if ("com.gen5w.launcher".equals(pkgLow)) {
            return "Themes & Store";
        }
        if (isHmgApi) {
            return "HMG-API Mod";
        }
        if (stock != null && stock.category != null && !stock.category.isEmpty()) {
            return stock.category;
        }
        if (pkgLow.contains("settings") || lblLow.contains("setting") || lblLow.contains("setup") || pkgLow.contains("userprofile")) {
            return StockMenuCatalog.CAT_SYSTEM;
        }
        if (pkgLow.contains("climate") || pkgLow.contains("ev") || pkgLow.contains("hev") || pkgLow.contains("pureair") || pkgLow.contains("rear_care")) {
            return StockMenuCatalog.CAT_CLIMATE_EV;
        }
        if (pkgLow.contains("navi") || pkgLow.contains("dvrs") || pkgLow.contains("iot") || pkgLow.contains("vrm")) {
            return StockMenuCatalog.CAT_NAVI_VEH;
        }
        if (pkgLow.contains("radio") || pkgLow.contains("media") || pkgLow.contains("usbmusic") || pkgLow.contains("usbvideo")
                || pkgLow.contains("btaudio") || pkgLow.contains("naturesound") || pkgLow.contains("hddata") || pkgLow.contains("soundhound")) {
            return StockMenuCatalog.CAT_MEDIA_RADIO;
        }
        if (pkgLow.contains("bluetooth") || pkgLow.contains("dipo") || pkgLow.contains("projection") || pkgLow.contains("voicedialer") || pkgLow.contains("voicememo")) {
            return StockMenuCatalog.CAT_PHONE_REAR;
        }
        if (pkgLow.startsWith("com.android.") || pkgLow.startsWith("com.hkmc.") || pkgLow.startsWith("com.daudio.")
                || pkgLow.startsWith("com.mobis.") || pkgLow.startsWith("com.mnsoft.") || "android".equals(pkgLow)) {
            return "OEM Vehicle App";
        }
        return "Installed App";
    }

    private static String stripArrowDetails(String raw) {
        if (raw == null) return "";
        int arrow = raw.indexOf("->");
        if (arrow > 0) {
            return raw.substring(0, arrow).trim();
        }
        return raw;
    }

    private static String cleanDisplayLabel(String rawLabel, String pkg, String cls, StockMenuCatalog.StockItem stock) {
        String s = rawLabel == null ? "" : rawLabel.trim();
        if (stock != null && !stock.title.isEmpty()
                && (s.isEmpty() || s.equalsIgnoreCase(pkg) || looksLikePackageName(s) || s.startsWith("HKMC_"))) {
            return stripArrowDetails(stock.title);
        }
        if (s.isEmpty() || looksLikePackageName(s)) {
            String fromCls = humanizeToken(shortClassName(cls));
            if (!fromCls.isEmpty() && !"Main".equalsIgnoreCase(fromCls)) return fromCls;
            String fromPkg = humanizeToken(lastSegment(pkg));
            return fromPkg.isEmpty() ? "Application" : fromPkg;
        }
        return s;
    }

    private static boolean looksLikePackageName(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.US);
        return t.startsWith("com.") || t.startsWith("org.") || t.startsWith("net.")
                || t.startsWith("android.") || t.startsWith("hmgapi.");
    }

    private static String lastSegment(String dotted) {
        if (dotted == null || dotted.isEmpty()) return "";
        int dot = dotted.lastIndexOf('.');
        return dot >= 0 && dot + 1 < dotted.length() ? dotted.substring(dot + 1) : dotted;
    }

    static String humanizeTokenForUi(String token) {
        return humanizeToken(shortClassName(token));
    }

    private static String humanizeToken(String token) {
        if (token == null || token.isEmpty()) return "";
        String t = token.replace("Activity", "").replace("Main", "").replace('_', ' ').trim();
        if (t.isEmpty()) t = token;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(t.charAt(i - 1))) {
                out.append(' ');
            }
            out.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        return out.toString().trim();
    }

    private static final Object DEFAULT_ICON_LOCK = new Object();
    private static int[] cachedDefaultIconPixels;
    private static boolean defaultIconChecked = false;
    private static Drawable.ConstantState cachedDefaultConstantState;
    private static final Map<String, Bitmap> SVG_BADGE_BMP_CACHE = new HashMap<String, Bitmap>();

    private static Drawable resolveAppIcon(
            Context ctx,
            PackageManager pm,
            String pkg,
            String cls,
            Drawable preloaded,
            byte[] modIconBytes,
            String label,
            StockMenuCatalog.StockItem stockItem,
            boolean isHmgApi,
            boolean preferSvg,
            String iconOverride,
            String autoSvg,
            String[] resolvedSvgOut) {
        boolean forceApk = AppCatalogOverrides.ICON_MODE_APK.equals(iconOverride);

        if (ctx != null && !forceApk && iconOverride != null && !iconOverride.isEmpty()) {
            Drawable forcedSvg = buildBadgedSvgIcon(ctx, iconOverride, isHmgApi);
            if (forcedSvg != null) {
                resolvedSvgOut[0] = iconOverride;
                return forcedSvg;
            }
        }

        if (ctx != null && modIconBytes != null && modIconBytes.length > 0) {
            Drawable d = decodeBytesDrawable(ctx, modIconBytes);
            if (d != null) return d;
        }

        if (ctx != null && preferSvg && !forceApk && autoSvg != null && !autoSvg.isEmpty()) {
            Drawable svgIcon = buildBadgedSvgIcon(ctx, autoSvg, isHmgApi);
            if (svgIcon != null) {
                resolvedSvgOut[0] = autoSvg;
                return svgIcon;
            }
        }

        Drawable icon = preloaded;
        if (icon == null && pkg != null && !pkg.isEmpty()) {
            icon = loadComponentIcon(ctx, pm, pkg, cls, null);
        }

        if (forceApk && icon != null) {
            resolvedSvgOut[0] = "";
            return icon;
        }

        boolean missingOrDefault = (icon == null) || !hasCustomIconResource(pm, pkg, cls) || isDefaultAndroidIcon(pm, icon);
        if (!missingOrDefault) {
            resolvedSvgOut[0] = "";
            return icon;
        }

        if (ctx != null) {
            String fallbackSvg = (autoSvg != null && !autoSvg.isEmpty()) ? autoSvg : "icons/apps.svg";
            Drawable badged = buildBadgedSvgIcon(ctx, fallbackSvg, isHmgApi);
            if (badged != null) {
                resolvedSvgOut[0] = fallbackSvg;
                return badged;
            }
        }
        return null; // AppDrawerOverlay draws sleek rounded monogram badge when null
    }

    private static boolean hasCustomIconResource(PackageManager pm, String pkg, String cls) {
        if (pm == null || pkg == null || pkg.isEmpty()) return false;
        try {
            if (cls != null && !cls.isEmpty()) {
                ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, normalizeClassName(pkg, cls)), 0);
                if (ai != null) {
                    if (ai.icon != 0) return true;
                    if (ai.applicationInfo != null && ai.applicationInfo.icon != 0) return true;
                    return false;
                }
            }
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            return appInfo != null && appInfo.icon != 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isDefaultAndroidIcon(PackageManager pm, Drawable candidate) {
        if (pm == null || candidate == null) return true;
        try {
            synchronized (DEFAULT_ICON_LOCK) {
                if (!defaultIconChecked) {
                    defaultIconChecked = true;
                    Drawable def = pm.getDefaultActivityIcon();
                    if (def != null) {
                        cachedDefaultConstantState = def.getConstantState();
                        cachedDefaultIconPixels = renderSample24x24(def);
                    }
                }
            }
            Drawable.ConstantState cs = candidate.getConstantState();
            if (cs != null && cachedDefaultConstantState != null && cs.equals(cachedDefaultConstantState)) {
                return true;
            }
            if (cachedDefaultIconPixels == null) return false;
            int[] sample = renderSample24x24(candidate);
            if (sample == null) return false;
            int matches = 0;
            for (int i = 0; i < sample.length; i++) {
                int a = cachedDefaultIconPixels[i];
                int b = sample[i];
                int da = Math.abs(((a >> 24) & 0xff) - ((b >> 24) & 0xff));
                int dr = Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff));
                int dg = Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff));
                int db = Math.abs((a & 0xff) - (b & 0xff));
                if (da <= 18 && dr <= 18 && dg <= 18 && db <= 18) {
                    matches++;
                }
            }
            return matches >= (sample.length * 90) / 100;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int[] renderSample24x24(Drawable d) {
        if (d == null) return null;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Rect prev = new Rect(d.getBounds());
            d.setBounds(0, 0, 24, 24);
            d.draw(canvas);
            d.setBounds(prev);
            int[] px = new int[24 * 24];
            bmp.getPixels(px, 0, 24, 0, 0, 24, 24);
            return px;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static String pickFallbackSvgPath(
            String label,
            String pkg,
            String cls,
            StockMenuCatalog.StockItem stockItem,
            boolean isHmgApi) {
        String s = ((label == null ? "" : label) + " " + (pkg == null ? "" : pkg) + " "
                + (cls == null ? "" : cls) + " " + (stockItem == null ? "" : stockItem.id)).toLowerCase(Locale.US);
        if (s.contains("nemesis") || s.contains("hmgapi") || s.contains("hmg-api")) {
            return "icons/shield.svg";
        }
        if (s.contains("gen5w.launcher") || s.contains("themer") || s.contains("store")) {
            return "icons/store.svg";
        }
        if (s.contains("seat") || s.contains("swrc")) {
            return "icons/seat.svg";
        }
        if (s.contains("steering") || s.contains("buttonsettings")) {
            return "icons/steering.svg";
        }
        if (s.contains("battery") || s.contains("precondition") || s.contains("12v")) {
            return "icons/battery.svg";
        }
        if (s.contains(".ev") || s.contains("ev3") || s.contains("hev") || s.contains("phev") || s.contains("energy")) {
            return "icons/ev.svg";
        }
        if (s.contains("climate") || s.contains("hvac") || s.contains("pureair") || s.contains("pure air")) {
            return "icons/climate.svg";
        }
        if (s.contains("defrost") || s.contains("defogger")) {
            return "icons/defrost.svg";
        }
        if (s.contains("camera") || s.contains("drvm") || s.contains("dvrs") || s.contains("dashcam")) {
            return "icons/camera.svg";
        }
        if (s.contains("navi") || s.contains("map_app") || s.contains(".map") || s.contains("radar")) {
            return "icons/navi.svg";
        }
        if (s.contains("bluetooth") || s.contains("btsettings") || s.contains("btaudio")) {
            return "icons/bluetooth.svg";
        }
        if (s.contains("phone") || s.contains("dialer") || s.contains("contacts") || s.contains("siri") || s.contains("voicehandler")) {
            return "icons/phone.svg";
        }
        if (s.contains("wifi") || s.contains("bluelink") || s.contains("connect") || s.contains("vrm")) {
            return "icons/wifi.svg";
        }
        if (s.contains("sound") || s.contains("volume") || s.contains("quiet") || s.contains("rear_care")) {
            return "icons/volume.svg";
        }
        if (s.contains("music") || s.contains("naturesound") || s.contains("soundhound")) {
            return "icons/music.svg";
        }
        if (s.contains("radio") || s.contains("hddata")) {
            return "icons/radio.svg";
        }
        if (s.contains("mediagate") || s.contains("media") || s.contains("video") || s.contains("voicememo")) {
            return "icons/media.svg";
        }
        if (s.contains("dipo") || s.contains("carplay") || s.contains("projection") || s.contains("android_auto")
                || s.contains("android auto") || s.contains("carlife") || s.contains("mirrorlink")) {
            return "icons/projection.svg";
        }
        if (s.contains("userprofile") || s.contains("profile") || s.contains("passenger")) {
            return "icons/user.svg";
        }
        if (s.contains("vehicle") || s.contains("driving_info") || s.contains("n_mode") || s.contains("n mode")) {
            return "icons/car.svg";
        }
        if (s.contains("development") || s.contains("micom") || s.contains("maintenance")) {
            return "icons/wrench.svg";
        }
        if (s.contains("valet") || s.contains("carpay") || s.contains("security")) {
            return "icons/shield.svg";
        }
        if (s.contains("launcher3") || s.contains("homesetting") || s.contains("stock home") || s.contains("iot") || s.contains("home")) {
            return "icons/home.svg";
        }
        if (s.contains("power")) {
            return "icons/power.svg";
        }
        if (s.contains("setting") || s.contains("setup") || s.contains("storage") || s.contains("theme") || s.contains("locale") || s.contains("display")) {
            return "icons/settings.svg";
        }
        if (isHmgApi) {
            return "icons/shield.svg";
        }
        return "icons/apps.svg";
    }

    public static Drawable buildBadgedSvgIcon(Context ctx, String svgRelPath, boolean highlight) {
        try {
            String cacheKey = svgRelPath + (highlight ? "|hi" : "|std");
            Bitmap cached;
            synchronized (SVG_BADGE_BMP_CACHE) {
                cached = SVG_BADGE_BMP_CACHE.get(cacheKey);
            }
            if (cached == null) {
                InputStream is = ctx.getAssets().open("packs/default/" + svgRelPath);
                byte[] buf = new byte[1024];
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int n;
                try {
                    while ((n = is.read(buf)) > 0) {
                        baos.write(buf, 0, n);
                    }
                } finally {
                    is.close();
                }
                MiniSvgDrawable svg = MiniSvgDrawable.parse(baos.toString("UTF-8"));
                int size = 64;
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);

                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(highlight ? 0xFF15385A : 0xFF112944);
                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(2f);
                stroke.setColor(highlight ? 0xCC38BDF8 : 0x7738BDF8);
                RectF r = new RectF(1f, 1f, size - 1f, size - 1f);
                canvas.drawRoundRect(r, 15f, 15f, bg);
                canvas.drawRoundRect(r, 15f, 15f, stroke);

                ColorFilter cf = new PorterDuffColorFilter(highlight ? 0xFF7DD3FC : 0xFFE2F2FF, PorterDuff.Mode.SRC_IN);
                svg.setColorFilter(cf);
                int pad = 14;
                svg.setBounds(pad, pad, size - pad, size - pad);
                svg.draw(canvas);

                synchronized (SVG_BADGE_BMP_CACHE) {
                    SVG_BADGE_BMP_CACHE.put(cacheKey, bmp);
                }
                cached = bmp;
            }
            return new BitmapDrawable(ctx.getResources(), cached);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final LruCache<String, Bitmap> MOD_ICON_BMP_CACHE = new LruCache<String, Bitmap>(24);

    private static Drawable loadComponentIcon(Context ctx, PackageManager pm, String pkg, String cls, byte[] modIconBytes) {
        if (ctx != null && modIconBytes != null && modIconBytes.length > 0) {
            Drawable d = decodeBytesDrawable(ctx, componentKey(pkg, cls) + "@" + java.util.Arrays.hashCode(modIconBytes), modIconBytes);
            if (d != null) return d;
        }
        try {
            if (cls != null && !cls.isEmpty()) {
                return pm.getActivityIcon(new ComponentName(pkg, normalizeClassName(pkg, cls)));
            }
        } catch (Throwable ignored) {
        }
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Drawable decodeBytesDrawable(Context context, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return decodeBytesDrawable(context, "bytes:" + java.util.Arrays.hashCode(bytes) + ":" + bytes.length, bytes);
    }

    private static Drawable decodeBytesDrawable(Context context, String cacheKey, byte[] bytes) {
        try {
            Bitmap cached;
            synchronized (MOD_ICON_BMP_CACHE) {
                cached = MOD_ICON_BMP_CACHE.get(cacheKey);
            }
            if (cached == null || cached.isRecycled()) {
                cached = AssetResolver.decodeBitmapBounded(bytes, 128, 128);
                if (cached != null) {
                    synchronized (MOD_ICON_BMP_CACHE) {
                        MOD_ICON_BMP_CACHE.put(cacheKey, cached);
                    }
                }
            }
            if (cached != null) {
                return new BitmapDrawable(context.getResources(), cached);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String loadActivityLabel(PackageManager pm, String pkg, String cls, String fallback) {
        try {
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, normalizeClassName(pkg, cls)), 0);
            CharSequence cs = ai.loadLabel(pm);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {
        }
        try {
            CharSequence appLbl = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (appLbl != null && appLbl.length() > 0) return appLbl.toString();
        } catch (Throwable ignored) {
        }
        return (fallback != null && !fallback.isEmpty()) ? fallback : pkg;
    }

    private static String normalizeClassName(String pkg, String cls) {
        if (cls == null || cls.isEmpty()) return "";
        return cls.startsWith(".") ? (pkg + cls) : cls;
    }

    private static String componentKey(String pkg, String cls) {
        return (pkg == null ? "" : pkg) + "/" + normalizeClassName(pkg, cls);
    }

    private static String shortClassName(String cls) {
        if (cls == null || cls.isEmpty()) return "";
        int dot = cls.lastIndexOf('.');
        return dot >= 0 && dot + 1 < cls.length() ? cls.substring(dot + 1) : cls;
    }

    private static boolean tryStartComponent(Context context, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty() || cls == null || cls.isEmpty()) {
            return false;
        }
        String fullCls = cls.startsWith(".") ? pkg + cls : cls;
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(pkg, fullCls));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStartIntent(context, intent);
    }

    private static boolean tryLaunchPackage(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return tryStartIntent(context, launch);
    }

    private static boolean tryStartIntent(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
