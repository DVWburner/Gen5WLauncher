package com.gen5w.launcher.action;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.gen5w.launcher.ipc.SwitcherClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Complete catalog of stock Launcher_WP3 menu entries extracted from
 * com.android.launcher3.view.a (AllMenuEntryList) for on-vehicle verification.
 */
public final class StockMenuCatalog {
    public static final String CAT_CLIMATE_EV = "Climate & EV";
    public static final String CAT_NAVI_VEH = "Navi & Vehicle";
    public static final String CAT_MEDIA_RADIO = "Radio & Media";
    public static final String CAT_PHONE_REAR = "Phone & Rear";
    public static final String CAT_SYSTEM = "System & Tools";

    public enum Mode {
        ACTIVITY,
        BROADCAST,
        SERVICE,
        CLIMATE_SMART
    }

    public static final class StockItem {
        public final String id;
        public final String category;
        public final String title;
        public final String smaliRef;
        public final Mode mode;
        public final String pkg;
        public final String cls;
        public final String action;
        public final String extraKey;
        public final String extraType; // "", "int", "bool", "str"
        public final String extraVal;

        StockItem(String id, String category, String title, String smaliRef, Mode mode,
                String pkg, String cls, String action,
                String extraKey, String extraType, String extraVal) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.smaliRef = smaliRef;
            this.mode = mode;
            this.pkg = pkg;
            this.cls = cls;
            this.action = action;
            this.extraKey = extraKey;
            this.extraType = extraType;
            this.extraVal = extraVal;
        }

        public String describeTarget() {
            if (mode == Mode.CLIMATE_SMART) {
                return "NewClimateControl -> InteriorClimateNew -> InteriorClimate -> REQUEST_CLIMATE";
            }
            if (mode == Mode.BROADCAST) {
                String s = action;
                if (!extraKey.isEmpty()) {
                    s += " [" + extraKey + "=" + extraVal + "]";
                }
                return s;
            }
            String fullCls = (!pkg.isEmpty() && cls.startsWith(".")) ? (pkg + cls) : cls;
            String target = !pkg.isEmpty() ? (pkg + "/" + fullCls) : action;
            if (!action.isEmpty() && !pkg.isEmpty()) {
                target = action + " -> " + target;
            }
            if (!extraKey.isEmpty()) {
                target += " [" + extraKey + "=" + extraVal + "]";
            }
            return target;
        }

        public String resolveAvailability(PackageManager pm) {
            if (mode == Mode.BROADCAST) {
                if (pkg.isEmpty()) {
                    return "BCAST";
                }
                return isPackageInstalled(pm, pkg) ? "PKG+BCAST" : "PKG MISSING";
            }
            if (mode == Mode.CLIMATE_SMART) {
                return isPackageInstalled(pm, pkg) ? "INSTALLED" : "MISSING";
            }
            if (!pkg.isEmpty() && !cls.isEmpty()) {
                Intent i = new Intent(action.isEmpty() ? Intent.ACTION_MAIN : action);
                String fullCls = cls.startsWith(".") ? (pkg + cls) : cls;
                i.setComponent(new ComponentName(pkg, fullCls));
                if (mode == Mode.SERVICE) {
                    ResolveInfo sri = pm.resolveService(i, 0);
                    if (sri != null) return "SVC OK";
                    return isPackageInstalled(pm, pkg) ? "PKG OK" : "MISSING";
                }
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                if (list != null && !list.isEmpty()) {
                    return list.get(0).activityInfo != null && list.get(0).activityInfo.exported
                            ? "EXPORTED"
                            : "PRIVATE";
                }
                try {
                    pm.getActivityInfo(new ComponentName(pkg, fullCls), 0);
                    return "NO EXPORT";
                } catch (Throwable ignored) {
                    return isPackageInstalled(pm, pkg) ? "NO ACT" : "MISSING";
                }
            }
            return "UNKNOWN";
        }

        public boolean isTargetAvailable(PackageManager pm) {
            if (mode == Mode.BROADCAST) {
                return pkg.isEmpty() || isPackageInstalled(pm, pkg);
            }
            if (mode == Mode.CLIMATE_SMART) {
                return isPackageInstalled(pm, pkg);
            }
            if (pkg.isEmpty()) {
                return false;
            }
            if (cls.isEmpty()) {
                return isPackageInstalled(pm, pkg);
            }
            String fullCls = cls.startsWith(".") ? (pkg + cls) : cls;
            if (mode == Mode.SERVICE) {
                Intent si = new Intent(action);
                si.setComponent(new ComponentName(pkg, fullCls));
                return pm.resolveService(si, 0) != null || isPackageInstalled(pm, pkg);
            }
            try {
                pm.getActivityInfo(new ComponentName(pkg, fullCls), 0);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        public String triggerViaApp(Context context) {
            try {
                if (mode == Mode.CLIMATE_SMART) {
                    boolean ok = AppLauncher.launchClimate(context);
                    return ok ? "OK: climate fallback chain invoked" : "ERR: climate chain returned false";
                }
                if (mode == Mode.BROADCAST) {
                    Intent i = new Intent(action);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (!pkg.isEmpty()) i.setPackage(pkg);
                    applyExtra(i);
                    context.sendBroadcast(i);
                    return "OK: broadcast sent (" + action + ")";
                }
                if (mode == Mode.SERVICE) {
                    Intent i = new Intent();
                    if (!action.isEmpty()) i.setAction(action);
                    if (!pkg.isEmpty() && !cls.isEmpty()) {
                        i.setComponent(new ComponentName(pkg, cls.startsWith(".") ? (pkg + cls) : cls));
                    }
                    applyExtra(i);
                    ComponentName started = context.startService(i);
                    return started != null
                            ? "OK: service started (" + started.flattenToShortString() + ")"
                            : "ERR: startService returned null (not found or denied)";
                }
                Intent i = new Intent(action.isEmpty() ? Intent.ACTION_MAIN : action);
                if (!pkg.isEmpty() && !cls.isEmpty()) {
                    i.setComponent(new ComponentName(pkg, cls.startsWith(".") ? (pkg + cls) : cls));
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                applyExtra(i);
                context.startActivity(i);
                return "OK: activity started";
            } catch (Throwable t) {
                String clsName = t.getClass().getSimpleName();
                String msg = t.getMessage();
                return "ERR: " + clsName + (msg == null || msg.isEmpty() ? "" : (": " + msg));
            }
        }

        public void triggerViaRoot(SwitcherClient.SimpleCallback callback) {
            if (mode == Mode.BROADCAST) {
                SwitcherClient.runRootAmAsync("bcast", pkg, cls, action, extraKey, extraType, extraVal, callback);
            } else if (mode == Mode.SERVICE) {
                SwitcherClient.runRootAmAsync("service", pkg, cls, action, extraKey, extraType, extraVal, callback);
            } else {
                SwitcherClient.runRootAmAsync("start", pkg, cls, action, extraKey, extraType, extraVal, callback);
            }
        }

        private void applyExtra(Intent i) {
            applyIntentExtra(i, extraKey, extraType, extraVal);
        }
    }

    public static void applyIntentExtra(Intent i, String extraKey, String extraType, String extraVal) {
        if (i == null || extraKey == null || extraKey.isEmpty()) {
            return;
        }
        if ("int".equals(extraType)) {
            if (extraVal == null || extraVal.trim().isEmpty()) {
                return;
            }
            try {
                i.putExtra(extraKey, Integer.decode(extraVal.trim()).intValue());
            } catch (NumberFormatException ignored) {
            }
        } else if ("bool".equals(extraType)) {
            i.putExtra(extraKey, "true".equalsIgnoreCase(extraVal));
        } else if ("str".equals(extraType)) {
            i.putExtra(extraKey, extraVal == null ? "" : extraVal);
        }
    }

    public static StockItem findPresetItem(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        String k = key.trim().toLowerCase();
        if ("ev".equals(k)) k = "ev3_main";
        else if ("climate".equals(k)) k = "climate_smart";
        else if ("seat_front".equals(k) || "seats".equals(k)) k = "seat_front_ctrl";
        else if ("seat_rear".equals(k)) k = "seat_rear_ctrl";
        else if ("rear_climate".equals(k)) k = "climate_rear_req";
        else if ("radio".equals(k) || "sxm".equals(k)) k = "radio_na";
        else if ("media".equals(k) || "mediagate".equals(k)) k = "media_gate";
        else if ("bt_audio".equals(k) || "bluetooth_audio".equals(k)) k = "bt_audio";
        else if ("soundhound".equals(k)) k = "soundhound";
        else if ("doppler_radar".equals(k)) k = "doppler_radar";
        else if ("usb_music".equals(k) || "music".equals(k)) k = "usb_music";
        else if ("usb_video".equals(k) || "video".equals(k)) k = "usb_video";
        else if ("nature_sound".equals(k) || "nature".equals(k)) k = "nature_sound";
        else if ("navi".equals(k) || "navigation".equals(k)) k = "navi_app";
        else if ("map".equals(k)) k = "map_app";
        else if ("bluelink".equals(k) || "uvo".equals(k)) k = "bluelink";
        else if ("bluelink_settings".equals(k)) k = "bluelink_settings";
        else if ("maintenance".equals(k)) k = "maintenance";
        else if ("driving_info".equals(k) || "eco".equals(k)) k = "driving_info";
        else if ("phone".equals(k) || "bluetooth".equals(k)) k = "bt_dial";
        else if ("voice_dialer".equals(k)) k = "voice_dialer";
        else if ("carplay".equals(k)) k = "carplay_dipo";
        else if ("android_auto".equals(k) || "aa".equals(k)) k = "android_auto";
        else if ("projection".equals(k) || "device_connections".equals(k)) k = "phone_proj_conn";
        else if ("drvm".equals(k) || "camera".equals(k)) k = "drvm";
        else if ("quiet_mode".equals(k) || "quiet".equals(k)) k = "quiet_mode";
        else if ("passenger_talk".equals(k)) k = "passenger_talk";
        else if ("voice_memo".equals(k)) k = "voice_memo";
        else if ("valet_mode".equals(k) || "valet".equals(k)) k = "valet_mode";
        else if ("notifications".equals(k) || "notif".equals(k)) k = "notifications";
        else if ("vehicle_settings".equals(k) || "car_settings".equals(k)) k = "veh_settings";
        else if ("seat_settings".equals(k)) k = "seat_arrangement";
        else if ("sound_settings".equals(k)) k = "sound_settings";
        else if ("display_settings".equals(k)) k = "display_settings";
        else if ("screen_saver".equals(k)) k = "screen_saver";
        else if ("button_settings".equals(k)) k = "button_settings";
        else if ("general_settings".equals(k)) k = "general_settings";
        else if ("storage_settings".equals(k)) k = "storage_settings";
        else if ("bt_settings".equals(k)) k = "bt_settings";
        else if ("wifi_settings".equals(k)) k = "wifi_settings";
        else if ("user_profile".equals(k)) k = "user_profile";
        else if ("theme_settings".equals(k)) k = "theme_settings";
        else if ("edit_stock_home".equals(k)) k = "edit_stock_home";
        else if ("setup".equals(k) || "settings".equals(k)) k = "setup_settings";
        else if ("manual".equals(k) || "web_manual".equals(k)) k = "manual_tip";
        else if ("hmgapi".equals(k)) k = "hmgapi_mgr";
        else if ("stock_home".equals(k)) k = "stock_home";

        for (StockItem item : buildAll()) {
            if (item.id.equals(k)) {
                return item;
            }
        }
        return null;
    }

    private StockMenuCatalog() {}

    public static boolean isPackageInstalled(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static StockItem act(String id, String cat, String title, String ref, String pkg, String cls) {
        return new StockItem(id, cat, title, ref, Mode.ACTIVITY, pkg, cls, "", "", "", "");
    }

    private static StockItem actExtra(String id, String cat, String title, String ref, String pkg, String cls,
            String key, String type, String val) {
        return new StockItem(id, cat, title, ref, Mode.ACTIVITY, pkg, cls, "", key, type, val);
    }

    private static StockItem bcast(String id, String cat, String title, String ref, String action) {
        return new StockItem(id, cat, title, ref, Mode.BROADCAST, "", "", action, "", "", "");
    }

    private static StockItem bcastInt(String id, String cat, String title, String ref, String action, String key, int val) {
        return new StockItem(id, cat, title, ref, Mode.BROADCAST, "", "", action, key, "int", String.valueOf(val));
    }

    private static StockItem svc(String id, String cat, String title, String ref, String pkg, String cls, String action) {
        return new StockItem(id, cat, title, ref, Mode.SERVICE, pkg, cls, action, "", "", "");
    }

    public static List<StockItem> buildAll() {
        List<StockItem> list = new ArrayList<StockItem>();

        // --- Climate & EV ---
        list.add(new StockItem("climate_smart", CAT_CLIMATE_EV, "Climate (Auto Fallback Chain)",
                "HomeTile", Mode.CLIMATE_SMART, "com.hkmc.telematics.service.apps.climate",
                ".activities.NewClimateControl", "com.hkmc.telematics.service.apps.climate.REQUEST_CLIMATE",
                "activityEntryPoint", "int", "0"));
        list.add(actExtra("climate_new_ctrl", CAT_CLIMATE_EV, "Climate -> NewClimateControl",
                "ClimateManager.ac", "com.hkmc.telematics.service.apps.climate",
                ".activities.NewClimateControl", "CLIMATE_SHORT_KEY", "bool", "false"));
        list.add(act("climate_interior_new", CAT_CLIMATE_EV, "Climate -> InteriorClimateNew",
                "HKMC_Climate", "com.hkmc.telematics.service.apps.climate",
                ".activities.InteriorClimateNew"));
        list.add(act("climate_interior", CAT_CLIMATE_EV, "Climate -> InteriorClimate",
                "HKMC_Climate", "com.hkmc.telematics.service.apps.climate",
                ".activities.InteriorClimate"));
        list.add(bcastInt("climate_req", CAT_CLIMATE_EV, "Climate -> REQUEST_CLIMATE (ep=0)",
                "G.smali", "com.hkmc.telematics.service.apps.climate.REQUEST_CLIMATE",
                "activityEntryPoint", 0));
        list.add(bcastInt("climate_req1", CAT_CLIMATE_EV, "Climate -> REQUEST_CLIMATE (ep=1)",
                "G.smali", "com.hkmc.telematics.service.apps.climate.REQUEST_CLIMATE",
                "activityEntryPoint", 1));
        list.add(bcast("climate_rear_req", CAT_CLIMATE_EV, "Rear Climate -> REQUEST_REAR_CLIMATE",
                "at.smali", "com.hkmc.telematics.service.apps.climate.REQUEST_REAR_CLIMATE"));
        list.add(act("climate_rear_act", CAT_CLIMATE_EV, "Rear Climate -> UIRareClimate",
                "ac.1.smali", "com.hkmc.telematics.service.apps.climate",
                ".activities.UIRareClimate"));
        list.add(act("seat_front_ctrl", CAT_CLIMATE_EV, "Front Seat Heat & Ventilation",
                "ag.1.smali", "com.hkmc.telematics.service.apps.climate",
                ".activities.SWRCFrontSeatHeatVentControl"));
        list.add(act("seat_rear_ctrl", CAT_CLIMATE_EV, "Rear Seat Heat & Ventilation",
                "Recon", "com.hkmc.telematics.service.apps.climate",
                ".activities.RearSeatHeatVentControl"));
        list.add(act("seat_popup", CAT_CLIMATE_EV, "Seat Heat & Vent Popup",
                "Recon", "com.hkmc.telematics.service.apps.climate",
                ".activities.SeatHeatVentPopup"));
        list.add(bcast("seat_front_bcast", CAT_CLIMATE_EV, "Front Seat Heat/Vent (REQUEST_FRONT_SHVU)",
                "B.smali", "com.hkmc.telematics.service.apps.climate.REQUEST_FRONT_SHVU"));
        list.add(act("ev3_main", CAT_CLIMATE_EV, "EV (MainActivity)",
                "EV3", "com.hkmc.daudio.app.ev3", ".MainActivity"));
        list.add(act("ev3_ign", CAT_CLIMATE_EV, "EV (IgnCheckActivity)",
                "F.smali", "com.hkmc.daudio.app.ev3", ".IgnCheckActivity"));
        list.add(act("ev1_ign", CAT_CLIMATE_EV, "EV Legacy (app.ev.IgnCheckActivity)",
                "F.smali", "com.hkmc.daudio.app.ev", ".IgnCheckActivity"));
        list.add(act("hev_ign", CAT_CLIMATE_EV, "HEV (hev2.IgnCheckActivity)",
                "T.smali", "com.hkmc.daudio.app.hev2", ".ui.IgnCheckActivity"));
        list.add(act("phev_ign", CAT_CLIMATE_EV, "PHEV (phev2.IgnCheckActivity)",
                "ah.smali", "com.hkmc.daudio.app.phev2", ".IgnCheckActivity"));
        list.add(act("smart_pure_air", CAT_CLIMATE_EV, "Smart Pure Air",
                "ax.smali", "com.hkmc.app.smartpureair", ".MainActivity"));

        // --- Navigation, Telematics & Vehicle ---
        list.add(act("navi_app", CAT_NAVI_VEH, "Navigation (NaviApp)",
                "l.1.smali", "com.mnsoft.navi", ".NaviApp"));
        list.add(act("map_app", CAT_NAVI_VEH, "Map (MapApp)",
                "b.1.smali", "com.mnsoft.navi", ".MapApp"));
        list.add(bcast("navi_curpos", CAT_NAVI_VEH, "Navi Current Position (Broadcast)",
                "s.1.smali", "com.hkmc.intent.action.ACTION_NAVI_CURRENTPOS"));
        list.add(bcast("navi_menu", CAT_NAVI_VEH, "Navi Menu (Broadcast)",
                "w.1.smali", "com.hkmc.intent.action.ACTION_NAVI_MENU"));
        list.add(act("bluelink", CAT_NAVI_VEH, "BlueLink / Kia Connect Menu",
                "aI.1.smali", "com.hkmc.telematics.apps.Vrm.main", ".BluelinkUvoMenu"));
        list.add(act("bluelink_settings", CAT_NAVI_VEH, "BlueLink / Kia Connect Settings",
                "Recon", "com.hkmc.telematics.apps.Vrm.main",
                "com.hkmc.telematics.apps.Vrm.settings.ui.BluelinkUvoSettingsMenu"));
        list.add(act("maintenance", CAT_NAVI_VEH, "Maintenance & Service",
                "Recon", "com.hkmc.telematics.apps.Vrm.main",
                "com.hkmc.telematics.apps.Vrm.vehicleInfo.ui.MaintenanceWeb"));
        list.add(act("driving_info", CAT_NAVI_VEH, "Driving Info (Eco Driving)",
                "d.1.smali", "com.hkmc.telematics.apps.Vrm.main",
                "com.hkmc.telematics.apps.Vrm.ecoDriving.ui.DrivingInfo"));
        list.add(act("veh_settings", CAT_NAVI_VEH, "Vehicle Settings",
                "VehicleSetting", "com.android.vehiclesettings", ".VehicleSetting"));
        list.add(act("seat_arrangement", CAT_NAVI_VEH, "Seat Arrangement / Memory Settings",
                "ak.1.smali", "com.android.vehiclesettings",
                ".seatsteering.SeatArrangementSettings"));
        list.add(act("n_mode", CAT_NAVI_VEH, "N Mode",
                "X.smali", "com.hkmc.app.nmode.ui", ".NMode_MainActivity"));
        list.add(bcast("drvm", CAT_NAVI_VEH, "DRVM (Driving Rear View Monitor)",
                "aJ.smali", "com.hkmc.intent.action.DRVM_SHORTCUT"));
        list.add(bcastInt("valet_mode", CAT_NAVI_VEH, "Valet Mode (LAUNCH_APPLICATION 0xb7)",
                "m.1.smali", "com.hkmc.intent.action.LAUNCH_APPLICATION", "global.mode", 0xb7));
        list.add(bcastInt("notifications", CAT_NAVI_VEH, "Notifications (LAUNCH_APPLICATION 0xdd)",
                "A.smali", "com.hkmc.intent.action.LAUNCH_APPLICATION", "global.mode", 0xdd));
        list.add(bcast("carpay", CAT_NAVI_VEH, "In-Car Payment (Carpay)",
                "e.1.smali", "com.hkmc.intent.action.ACTION_NAVI_GCARPAY_LIST"));
        list.add(act("dvrs_act", CAT_NAVI_VEH, "DVRS Dashcam (Activity)",
                "W.smali", "com.daudio.app.dvrs", ".ui.DvrsActivity"));
        list.add(bcast("dvrs_bcast", CAT_NAVI_VEH, "DVRS Dashcam (startDVRS Broadcast)",
                "ai.smali", "com.daudio.app.dvrs.startDVRS"));
        list.add(act("iot_act", CAT_NAVI_VEH, "Car to Home / IoT (Activity)",
                "c.1.smali", "com.hkmc.telematics.apps.iot", ".MainActivity"));
        list.add(bcast("iot_bcast", CAT_NAVI_VEH, "Car to Home / IoT (Broadcast)",
                "o.1.smali", "com.hkmc.telematics.iot.launch"));

        // --- Radio & Media ---
        list.add(act("radio_na", CAT_MEDIA_RADIO, "Radio NA (RadioMainActivity)",
                "aj.1.smali", "com.hkmc.av.app.radiona", ".RadioMainActivity"));
        list.add(act("radio_drm", CAT_MEDIA_RADIO, "Radio DRM (RadioMainActivity)",
                "aj.1.smali", "com.hkmc.av.app.radiodrm", ".RadioMainActivity"));
        list.add(act("radio_ge", CAT_MEDIA_RADIO, "Radio GE (RadioMainActivity)",
                "aj.1.smali", "com.hkmc.av.app.radioge", ".RadioMainActivity"));
        list.add(act("media_gate", CAT_MEDIA_RADIO, "Media Gate (Activity)",
                "MediaGate", "com.hkmc.av.app.mediagate", ".MediaGateActivity"));
        list.add(act("bt_audio", CAT_MEDIA_RADIO, "Bluetooth Audio",
                "Recon", "com.daudio.av.app.btaudio.ui", ".BtMusicMainActivity"));
        list.add(bcast("mediagate_shortcut", CAT_MEDIA_RADIO, "Media Shortcut (MEDIAGATE_SHORTCUT)",
                "M.smali", "com.mobis.intent.action.MEDIAGATE_SHORTCUT"));
        list.add(act("usb_music", CAT_MEDIA_RADIO, "USB Music (MusicActivity)",
                "M.smali", "com.daudio.av.app.usbmusic", ".ui.MusicActivity"));
        list.add(act("usb_video", CAT_MEDIA_RADIO, "USB Video (VideoActivity)",
                "z.1.smali", "com.daudio.av.app.usbvideo", ".ui.VideoActivity"));
        list.add(act("nature_sound", CAT_MEDIA_RADIO, "Sounds of Nature",
                "J.smali", "com.hkmc.av.app.naturesound", ".ui.NatureSoundActivity"));
        list.add(act("hd_data", CAT_MEDIA_RADIO, "HD Radio Data",
                "E.smali", "com.hkmc.av.app.hddata", ".HDDataMainActivity"));
        list.add(act("doppler_radar", CAT_MEDIA_RADIO, "Weather Doppler Radar",
                "Recon", "com.hkmc.av.app.hddata", ".DopplerRadarActivity"));
        list.add(act("soundhound", CAT_MEDIA_RADIO, "SoundHound",
                "Recon", "com.SoundHound", "com.SoundHound.page.SoundHoundActivity"));
        list.add(act("driver_watch", CAT_MEDIA_RADIO, "Driver Watch",
                "h.1.smali", "com.hkmc.av.app.driverwatch", ".DriverWatchMainActivity"));
        list.add(bcastInt("global_mode_bc", CAT_MEDIA_RADIO, "Online/Streaming Mode (LAUNCH 0xbc)",
                "P.smali", "com.hkmc.intent.action.LAUNCH_APPLICATION", "global.mode", 0xbc));
        list.add(act("dmb", CAT_MEDIA_RADIO, "DMB TV",
                "am.1.smali", "com.daudio.av.app.dmb", ".ui.DMBActivity"));

        // --- Phone, Projection & Rear Comfort ---
        list.add(act("bt_dial", CAT_PHONE_REAR, "Bluetooth Phone (DialtactsActivity)",
                "aA.smali", "com.hkmc.bluetooth", "com.android.contacts.DialtactsActivity"));
        list.add(act("voice_dialer", CAT_PHONE_REAR, "Voice Dialer",
                "Recon", "com.android.voicedialer", ".VoiceDialerActivity"));
        list.add(svc("bt_shortcut", CAT_PHONE_REAR, "BT Phone Service Shortcut (PHONE_SHORTCUT)",
                "O.smali", "com.hkmc.bluetooth", "com.android.phone.services.BTHFPManager",
                "com.hkmc.intent.action.PHONE_SHORTCUT"));
        list.add(act("carplay_dipo", CAT_PHONE_REAR, "Apple CarPlay (DiPO)",
                "as.smali", "com.daudio.app.dipo", ".ui.DipoActivity"));
        list.add(act("android_auto", CAT_PHONE_REAR, "Android Auto (GPM)",
                "i.1.smali", "com.google.android.projection.sink", ".ui.MainActivity"));
        list.add(act("phone_proj_conn", CAT_PHONE_REAR, "Phone Projection Device Connections",
                "DevConn", "com.daudio.app.phoneprojection",
                ".settings.ui.DeviceConnectionSettings"));
        list.add(act("micom_proj_popup", CAT_PHONE_REAR, "Phone Projection Popup (Micom)",
                "S.smali", "com.mobis.system", ".MicomSystemPopup"));
        list.add(actExtra("quiet_mode", CAT_PHONE_REAR, "Quiet Mode",
                "aw.smali", "com.android.rear_care", ".RearCareModeMainActivity",
                "launch_by_menu_key", "bool", "true"));
        list.add(act("passenger_talk", CAT_PHONE_REAR, "Passenger Talk (Type1)",
                "I.smali", "com.android.rear_care", ".rear_talk.RearTalkType1Activity"));
        list.add(act("voice_memo", CAT_PHONE_REAR, "Voice Memo",
                "az.smali", "com.daudio.app.voicememo", ".MainActivity"));
        list.add(act("carlife", CAT_PHONE_REAR, "Baidu CarLife",
                "n.1.smali", "com.baidu.carlifevehicle", ".CarlifeActivity"));
        list.add(act("mirrorlink", CAT_PHONE_REAR, "MirrorLink",
                "N.smali", "com.daudio.app.mirrorlink", ".ui.MirrorLinkActivity"));

        // --- System & Tools ---
        list.add(act("setup_settings", CAT_SYSTEM, "Setup (System Settings)",
                "v.1.smali", "com.android.settings", ".Settings"));
        list.add(act("sound_settings", CAT_SYSTEM, "Sound Settings",
                "Recon", "com.android.soundsettings", ".SoundSetting"));
        list.add(act("display_settings", CAT_SYSTEM, "Display Settings",
                "Recon", "com.android.displaysettings", ".DisplaySetting"));
        list.add(act("screen_saver", CAT_SYSTEM, "Screen Saver",
                "Recon", "com.android.displaysettings", ".WaitDisplay"));
        list.add(act("button_settings", CAT_SYSTEM, "Steering / Button Settings",
                "Recon", "com.android.buttonsettings", ".ButtonSettings"));
        list.add(act("general_settings", CAT_SYSTEM, "General Settings",
                "Recon", "com.android.generalsettings", ".GeneralSettings"));
        list.add(act("storage_settings", CAT_SYSTEM, "Storage & Memory",
                "Recon", "com.android.generalsettings", ".deviceinfo.StorageMemory"));
        list.add(act("bt_settings", CAT_SYSTEM, "Bluetooth Settings",
                "Recon", "com.android.btsettings", "com.android.settings2.bluetooth.BluetoothSettings"));
        list.add(act("wifi_settings", CAT_SYSTEM, "Wi-Fi / Network Settings",
                "Recon", "com.hkmc.wifisettings", ".settings.ui.NetworkConnectionSettings"));
        list.add(act("user_profile", CAT_SYSTEM, "User Profile",
                "Recon", "com.hkmc.system.app.userprofile", ".UserProfileMainActivity"));
        list.add(act("theme_settings", CAT_SYSTEM, "Screen Theme Settings",
                "Recon", "com.android.themesettings", ".MainActivity"));
        list.add(act("edit_stock_home", CAT_SYSTEM, "Edit Home Icons",
                "Recon", "com.hkmc.system.app.homesetting", ".MainListActivity"));
        list.add(act("manual_tip", CAT_SYSTEM, "Web Manual / Driving Tip",
                "aq.smali", "com.android.settings", ".customviews.ManualAndDrivingTip"));
        list.add(act("driving_tip_only", CAT_SYSTEM, "Driving Tip Activity",
                "aq.smali", "com.android.settings", ".customviews.DrivingTip"));
        list.add(act("my_menu", CAT_SYSTEM, "My Menu",
                "C.smali", "com.hkmc.system.app.mymenu", ".MyMenuActivity"));
        list.add(act("sml_check", CAT_SYSTEM, "SML (SMLActivityStartCheck)",
                "U.smali", "com.android.sml", ".SMLActivityStartCheck"));
        list.add(act("stock_apps_menu", CAT_SYSTEM, "Stock All Menus (Launcher AppsMenu)",
                "ao.smali", "com.android.launcher3", ".view.AppsMenu"));
        list.add(act("stock_home", CAT_SYSTEM, "Stock Home (Launcher3)",
                "Stock", "com.android.launcher3", ".view.Launcher"));
        list.add(act("hmgapi_mgr", CAT_SYSTEM, "HMG-API Manager",
                "HMG-API", "hmgapi.app", ".MainActivity"));

        return Collections.unmodifiableList(list);
    }
}
