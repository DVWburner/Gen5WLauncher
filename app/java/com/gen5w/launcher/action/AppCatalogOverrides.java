package com.gen5w.launcher.action;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Curated human-readable app names, default SVG icons, visibility defaults, and
 * SharedPreferences-backed user overrides (hide/exclude apps, custom names, custom SVG icons).
 */
public final class AppCatalogOverrides {
    private static final String PREFS_NAME = "gen5w_app_overrides_v1";
    private static final String KEY_OVERRIDES_JSON = "overrides_json";
    private static final String KEY_PREFER_SVG = "prefer_svg_icons";

    public static final String ICON_MODE_AUTO = "";
    public static final String ICON_MODE_APK = "apk";

    public static final class IconChoice {
        public final String svgPath;
        public final String label;

        IconChoice(String svgPath, String label) {
            this.svgPath = svgPath;
            this.label = label;
        }
    }

    public static final class CuratedMeta {
        public final String primaryName;
        public final List<String> suggestions;
        public final String svgPath;
        public final String category;
        public final boolean defaultHidden;

        CuratedMeta(String primaryName, String[] suggestions, String svgPath, String category, boolean defaultHidden) {
            this.primaryName = primaryName;
            this.suggestions = suggestions == null
                    ? new ArrayList<String>()
                    : Arrays.asList(suggestions);
            this.svgPath = svgPath;
            this.category = category;
            this.defaultHidden = defaultHidden;
        }
    }

    public static final class OverrideEntry {
        public boolean hasHiddenOverride;
        public boolean hidden;
        public String customLabel = "";
        public String iconOverride = "";
    }

    private static final List<IconChoice> ICON_CHOICES = Arrays.asList(
            new IconChoice("icons/ev.svg", "EV / Bolt"),
            new IconChoice("icons/battery.svg", "Battery"),
            new IconChoice("icons/climate.svg", "Climate"),
            new IconChoice("icons/fan.svg", "Fan"),
            new IconChoice("icons/temp.svg", "Temp"),
            new IconChoice("icons/defrost.svg", "Defrost"),
            new IconChoice("icons/seat.svg", "Seat"),
            new IconChoice("icons/steering.svg", "Steering"),
            new IconChoice("icons/car.svg", "Car"),
            new IconChoice("icons/navi.svg", "Navigation"),
            new IconChoice("icons/camera.svg", "Camera"),
            new IconChoice("icons/radio.svg", "Radio"),
            new IconChoice("icons/media.svg", "Media"),
            new IconChoice("icons/music.svg", "Music"),
            new IconChoice("icons/volume.svg", "Volume"),
            new IconChoice("icons/projection.svg", "Projection"),
            new IconChoice("icons/bluetooth.svg", "Bluetooth"),
            new IconChoice("icons/phone.svg", "Phone"),
            new IconChoice("icons/wifi.svg", "Wi-Fi"),
            new IconChoice("icons/user.svg", "Profile"),
            new IconChoice("icons/settings.svg", "Settings"),
            new IconChoice("icons/wrench.svg", "Tools"),
            new IconChoice("icons/shield.svg", "Security"),
            new IconChoice("icons/store.svg", "Store"),
            new IconChoice("icons/apps.svg", "Apps"),
            new IconChoice("icons/home.svg", "Home"),
            new IconChoice("icons/stock.svg", "Stock"),
            new IconChoice("icons/power.svg", "Power")
    );

    private static final Map<String, CuratedMeta> KNOWN_COMPONENTS = new LinkedHashMap<String, CuratedMeta>();
    private static final Map<String, CuratedMeta> KNOWN_PACKAGES = new LinkedHashMap<String, CuratedMeta>();
    private static final Map<String, CuratedMeta> KNOWN_STOCK_IDS = new LinkedHashMap<String, CuratedMeta>();

    static {
        // Core Mod / Community Ecosystem
        comp("hmgapi.app/hmgapi.app.MainActivity",
                "HMG-API", new String[] { "HMG-API", "HMG-API Manager", "Mod Platform" },
                "icons/shield.svg", "Mod Platform", false);
        comp("com.nemesis.batterywatchdog/com.nemesis.batterywatchdog.MainActivity",
                "Battery Watchdog", new String[] { "Battery Watchdog", "12V Guard", "Battery Monitor" },
                "icons/battery.svg", "HMG-API Mod", false);
        comp("com.plexnemesis.app/com.plexnemesis.app.MainActivity",
                "Plex Nemesis", new String[] { "Plex Nemesis", "NEMESIS Hub", "Car Tweaks" },
                "icons/shield.svg", "NEMESIS Installer", false);
        comp("com.gen5w.themer/com.gen5w.themer.MainActivity",
                "Gen5W Themer", new String[] { "Gen5W Themer", "Theme Manager", "Themes" },
                "icons/store.svg", "Themes & Store", false);
        comp("com.gen5w.launcher/com.gen5w.launcher.ThemeManagerActivity",
                "Gen5W Themes", new String[] { "Gen5W Themes", "Theme Store", "Launcher Themes" },
                "icons/store.svg", "Themes & Store", false);
        comp("com.paytondev.precondition/com.paytondev.launchpad.MainActivity",
                "Precondition", new String[] { "Precondition", "Battery Preconditioning", "EV Warmup" },
                "icons/battery.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);

        // EV, Hybrid & Driving
        comp("com.hkmc.daudio.app.ev3/com.hkmc.daudio.app.ev3.MainActivity",
                "EV & Energy", new String[] { "EV & Energy", "EV Charge", "Battery & Range" },
                "icons/ev.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.daudio.app.ev3/com.hkmc.daudio.app.ev3.IgnCheckActivity",
                "EV & Energy (Ign)", new String[] { "EV & Energy", "EV Ignition Check", "EV Charge" },
                "icons/ev.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);
        comp("com.hkmc.daudio.app.ev3/com.hkmc.daudio.app.ev3.drivemode.DriveModeVCUCheckActivity",
                "Drive Mode Check", new String[] { "Drive Mode Check", "EV VCU Check", "Drive Mode" },
                "icons/car.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);
        pkg("com.hkmc.daudio.app.ev",
                "EV & Energy", new String[] { "EV & Energy", "Electric", "Charge Status" },
                "icons/ev.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        pkg("com.hkmc.daudio.app.hev2",
                "Hybrid Info", new String[] { "Hybrid Info", "HEV Energy", "Hybrid" },
                "icons/ev.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        pkg("com.hkmc.daudio.app.phev2",
                "Plug-In Hybrid", new String[] { "Plug-In Hybrid", "PHEV", "EV & Energy" },
                "icons/ev.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.app.nmode.ui/com.hkmc.app.nmode.ui.NMode_MainActivity",
                "N Mode", new String[] { "N Mode", "Track Performance", "Sport Cluster" },
                "icons/car.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.app.smartpureair/com.hkmc.app.smartpureair.MainActivity",
                "Smart Pure Air", new String[] { "Smart Pure Air", "Air Purifier", "Cabin Filter" },
                "icons/fan.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.UIRareClimate",
                "Rear Climate UI", new String[] { "Rear Climate UI", "Rear HVAC", "Back Cabin" },
                "icons/climate.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);

        // Climate & Seats (clean primary vs duplicate popup/legacy activities hidden by default)
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.NewClimateControl",
                "Climate Control", new String[] { "Climate Control", "Climate", "HVAC" },
                "icons/climate.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.UIClimate",
                "Climate (Classic UI)", new String[] { "Climate Classic", "Climate UI", "HVAC Legacy" },
                "icons/climate.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.InteriorClimate",
                "Cabin Climate", new String[] { "Cabin Climate", "Interior Climate", "Climate" },
                "icons/climate.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.InteriorClimateNew",
                "Interior Climate", new String[] { "Interior Climate", "Rear Climate", "Cabin HVAC" },
                "icons/climate.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.SWRCFrontSeatHeatVentControl",
                "Seats & Steering", new String[] { "Seats & Steering", "Heated Seats", "Seat Warmer" },
                "icons/seat.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.RearSeatHeatVentControl",
                "Rear Seats", new String[] { "Rear Seats", "Back Seat Heat", "Rear Comfort" },
                "icons/seat.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.SeatHeatVentPopup",
                "Seat Climate Popup", new String[] { "Seat Popup", "Seat Warmer Popup", "Seats" },
                "icons/seat.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);
        comp("com.hkmc.telematics.service.apps.climate/com.hkmc.telematics.service.apps.climate.activities.DialogActivity",
                "Climate Dialog", new String[] { "Climate Dialog", "Climate Popup", "HVAC Alert" },
                "icons/climate.svg", StockMenuCatalog.CAT_CLIMATE_EV, true);

        // Navigation, Maps & BlueLink
        comp("com.mnsoft.navi/com.mnsoft.navi.NaviApp",
                "Navigation", new String[] { "Navigation", "Maps", "GPS" },
                "icons/navi.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        comp("com.mnsoft.navi/com.mnsoft.navi.MapApp",
                "Map", new String[] { "Map", "Split Map", "GPS Map" },
                "icons/navi.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        comp("com.hkmc.telematics.apps.Vrm.main/com.hkmc.telematics.apps.Vrm.ecoDriving.ui.DrivingInfo",
                "Driving Info", new String[] { "Driving Info", "Trip Efficiency", "Energy History" },
                "icons/car.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        comp("com.android.vehiclesettings/com.android.vehiclesettings.seatsteering.SeatArrangementSettings",
                "Seat Memory", new String[] { "Seat Memory", "Seat Position", "Relaxation" },
                "icons/seat.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        comp("com.hkmc.telematics.apps.Vrm.main/com.hkmc.telematics.apps.Vrm.main.BluelinkUvoMenu",
                "BlueLink / Connect", new String[] { "BlueLink / Connect", "Kia Connect", "Connected Services" },
                "icons/wifi.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        comp("com.hkmc.telematics.apps.Vrm.main/com.hkmc.telematics.apps.Vrm.settings.ui.BluelinkUvoSettingsMenu",
                "Connected Settings", new String[] { "Connected Settings", "BlueLink Settings", "Connect Setup" },
                "icons/wifi.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.hkmc.telematics.apps.Vrm.main/com.hkmc.telematics.apps.Vrm.settings.ui.SettingMenuUVO",
                "UVO Settings", new String[] { "UVO Settings", "BlueLink Setup", "Telematics" },
                "icons/wifi.svg", StockMenuCatalog.CAT_SYSTEM, true);
        comp("com.hkmc.telematics.apps.Vrm.main/com.hkmc.telematics.apps.Vrm.vehicleInfo.ui.MaintenanceWeb",
                "Maintenance", new String[] { "Maintenance", "Service Schedule", "Vehicle Care" },
                "icons/wrench.svg", StockMenuCatalog.CAT_NAVI_VEH, true);

        // Radio & Media
        comp("com.hkmc.av.app.radiona/com.hkmc.av.app.radiona.RadioMainActivity",
                "Radio / SiriusXM", new String[] { "Radio / SiriusXM", "Radio", "FM / AM / SXM" },
                "icons/radio.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        pkg("com.hkmc.av.app.radiodrm",
                "Radio", new String[] { "Radio", "FM / AM", "Digital Radio" },
                "icons/radio.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        pkg("com.hkmc.av.app.radioge",
                "Radio", new String[] { "Radio", "DAB / FM", "Tuner" },
                "icons/radio.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        comp("com.hkmc.av.app.mediagate/com.hkmc.av.app.mediagate.MediaGateActivity",
                "Media Sources", new String[] { "Media Sources", "Media", "Audio Hub" },
                "icons/media.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        comp("com.daudio.av.app.btaudio.ui/com.daudio.av.app.btaudio.ui.BtMusicMainActivity",
                "Bluetooth Audio", new String[] { "Bluetooth Audio", "BT Music", "Streaming" },
                "icons/bluetooth.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        comp("com.daudio.av.app.usbmusic/com.daudio.av.app.usbmusic.ui.MusicActivity",
                "USB Music", new String[] { "USB Music", "USB Audio", "Music Player" },
                "icons/music.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        pkg("com.daudio.av.app.usbvideo",
                "USB Video", new String[] { "USB Video", "Video Player", "Movies" },
                "icons/media.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        comp("com.hkmc.av.app.naturesound/com.hkmc.av.app.naturesound.ui.NatureSoundActivity",
                "Sounds of Nature", new String[] { "Sounds of Nature", "Ambient Audio", "Relax" },
                "icons/music.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        comp("com.SoundHound/com.SoundHound.page.SoundHoundActivity",
                "SoundHound", new String[] { "SoundHound", "Song ID", "Music Search" },
                "icons/music.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        comp("com.hkmc.av.app.hddata/com.hkmc.av.app.hddata.HDDataMainActivity",
                "HD Radio Data", new String[] { "HD Radio Data", "HD Traffic", "HD Weather" },
                "icons/radio.svg", StockMenuCatalog.CAT_MEDIA_RADIO, false);
        comp("com.hkmc.av.app.hddata/com.hkmc.av.app.hddata.DopplerRadarActivity",
                "Weather Radar", new String[] { "Weather Radar", "Doppler Radar", "Live Weather" },
                "icons/navi.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        comp("com.hkmc.av.app.hddata/com.hkmc.av.app.hddata.DataServicesPopUpActivity",
                "HD Data Popup", new String[] { "HD Data Popup", "HD Radio Alert", "Data Services" },
                "icons/radio.svg", StockMenuCatalog.CAT_MEDIA_RADIO, true);

        // Phone, Projection & Cabin Features
        comp("com.hkmc.bluetooth/com.android.contacts.DialtactsActivity",
                "Bluetooth Phone", new String[] { "Bluetooth Phone", "Phone", "Dialer & Contacts" },
                "icons/phone.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        comp("com.daudio.app.dipo/com.daudio.app.dipo.ui.DipoActivity",
                "Apple CarPlay", new String[] { "Apple CarPlay", "CarPlay", "iPhone Projection" },
                "icons/projection.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        comp("com.google.android.projection.sink/com.google.android.projection.sink.ui.MainActivity",
                "Android Auto", new String[] { "Android Auto", "Android Projection", "AA" },
                "icons/projection.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        comp("com.daudio.app.phoneprojection/com.daudio.app.phoneprojection.settings.ui.DeviceConnectionSettings",
                "Phone Projection", new String[] { "Phone Projection", "Device Connections", "CarPlay & AA Setup" },
                "icons/projection.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        comp("com.android.rear_care/com.android.rear_care.RearCareModeMainActivity",
                "Rear Seat Features", new String[] { "Rear Seat Features", "Quiet Mode & Talk", "Rear Cabin" },
                "icons/volume.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        comp("com.daudio.app.voicememo/com.daudio.app.voicememo.MainActivity",
                "Voice Memo", new String[] { "Voice Memo", "Audio Recorder", "Voice Notes" },
                "icons/media.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        comp("com.daudio.app.voicememo/com.daudio.app.voicememo.StorageFullPopUpActivity",
                "Voice Memo Storage", new String[] { "Voice Memo Storage", "Recorder Alert", "Memo Alert" },
                "icons/media.svg", StockMenuCatalog.CAT_PHONE_REAR, true);
        comp("com.daudio.app.voicememo/com.daudio.app.voicememo.VoiceMemoActivityPopup",
                "Voice Memo Popup", new String[] { "Voice Memo Popup", "Quick Memo", "Voice Notes" },
                "icons/media.svg", StockMenuCatalog.CAT_PHONE_REAR, true);
        comp("com.hkmc.mediazen.siri/com.hkmc.mediazen.siri.MainActivity",
                "Siri Eyes Free", new String[] { "Siri Eyes Free", "Apple Voice", "Siri" },
                "icons/phone.svg", StockMenuCatalog.CAT_PHONE_REAR, true);
        comp("com.mobis.voicehandler/com.mobis.voicehandler.MainActivity",
                "Voice Assistant", new String[] { "Voice Assistant", "Voice Commands", "Speech" },
                "icons/phone.svg", StockMenuCatalog.CAT_PHONE_REAR, true);
        comp("com.android.voicedialer/com.android.voicedialer.VoiceDialerActivity",
                "Voice Dialer", new String[] { "Voice Dialer", "Voice Call", "Handsfree Dial" },
                "icons/phone.svg", StockMenuCatalog.CAT_PHONE_REAR, true);

        // Settings & System
        comp("com.android.vehiclesettings/com.android.vehiclesettings.VehicleSetting",
                "Vehicle Settings", new String[] { "Vehicle Settings", "Car Settings", "Driver Assistance" },
                "icons/car.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.soundsettings/com.android.soundsettings.SoundSetting",
                "Sound Settings", new String[] { "Sound Settings", "Audio & EQ", "Volume & Chimes" },
                "icons/volume.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.displaysettings/com.android.displaysettings.DisplaySetting",
                "Display Settings", new String[] { "Display Settings", "Screen Brightness", "Display" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.displaysettings/com.android.displaysettings.WaitDisplay",
                "Screen Saver", new String[] { "Screen Saver", "Clock Standby", "Screen Off" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, true);
        comp("com.android.generalsettings/com.android.generalsettings.GeneralSettings",
                "General Settings", new String[] { "General Settings", "Date & Units", "System Setup" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.generalsettings/com.android.generalsettings.LocalePickerCheckActivity",
                "Language & Locale", new String[] { "Language & Locale", "Region", "Language" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, true);
        comp("com.android.generalsettings/com.android.generalsettings.deviceinfo.StorageMemory",
                "Storage & Memory", new String[] { "Storage & Memory", "System Storage", "Memory" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, true);
        comp("com.android.btsettings/com.android.settings2.bluetooth.BluetoothSettings",
                "Bluetooth Settings", new String[] { "Bluetooth Settings", "Pair Phone", "Bluetooth" },
                "icons/bluetooth.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.hkmc.wifisettings/com.hkmc.wifisettings.settings.ui.NetworkConnectionSettings",
                "Wi-Fi Settings", new String[] { "Wi-Fi Settings", "Network", "Wi-Fi" },
                "icons/wifi.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.buttonsettings/com.android.buttonsettings.ButtonSettings",
                "Button Settings", new String[] { "Button Settings", "Star Button", "Wheel Controls" },
                "icons/steering.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.hkmc.system.app.userprofile/com.hkmc.system.app.userprofile.UserProfileMainActivity",
                "User Profile", new String[] { "User Profile", "Driver Account", "Profile" },
                "icons/user.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.themesettings/com.android.themesettings.MainActivity",
                "Screen Themes", new String[] { "Screen Themes", "Cluster Style", "Layout Settings" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.themesettings/com.android.themesettings.WaitDisplay",
                "Theme Screen Saver", new String[] { "Theme Screen Saver", "Standby Screen", "Clock Off" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, true);
        comp("com.android.settings/com.android.settings.Settings",
                "Setup", new String[] { "Setup", "All Settings", "System Setup" },
                "icons/settings.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.hkmc.system.app.homesetting/com.hkmc.system.app.homesetting.MainListActivity",
                "Edit Stock Home", new String[] { "Edit Stock Home", "Stock Icons", "Stock Layout" },
                "icons/home.svg", StockMenuCatalog.CAT_SYSTEM, false);
        comp("com.android.development/com.android.development.Development",
                "Dev Tools", new String[] { "Dev Tools", "Android Debug", "Developer" },
                "icons/wrench.svg", StockMenuCatalog.CAT_SYSTEM, true);
        comp("com.mobis.system/com.mobis.system.MicomSystemPopup",
                "Micom System Popup", new String[] { "Micom Popup", "System Dialog", "Micom" },
                "icons/wrench.svg", StockMenuCatalog.CAT_SYSTEM, true);
        pkg("com.daudio.app.dvrs",
                "Dashcam (DVRS)", new String[] { "Dashcam (DVRS)", "Drive Recorder", "Built-In Cam" },
                "icons/camera.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        pkg("com.hkmc.telematics.apps.iot",
                "Car to Home (IoT)", new String[] { "Car to Home (IoT)", "Smart Home", "IoT" },
                "icons/home.svg", StockMenuCatalog.CAT_NAVI_VEH, false);

        // Broadcast / Stock ID catalog items
        stock("drvm", "Rear Camera", new String[] { "Rear Camera", "Backup View", "DRVM" }, "icons/camera.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        stock("quiet_mode", "Quiet Mode", new String[] { "Quiet Mode", "Rear Mute", "Audio Sleep" }, "icons/volume.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        stock("passenger_talk", "Passenger Talk", new String[] { "Passenger Talk", "Driver Mic", "Cabin Mic" }, "icons/user.svg", StockMenuCatalog.CAT_PHONE_REAR, false);
        stock("valet_mode", "Valet Mode", new String[] { "Valet Mode", "Privacy Lock", "Security" }, "icons/shield.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        stock("climate_rear_req", "Rear Climate", new String[] { "Rear Climate", "Back HVAC", "Cabin Climate" }, "icons/climate.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        stock("seat_arrangement", "Seat Memory", new String[] { "Seat Memory", "Seat Position", "Relaxation" }, "icons/seat.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        stock("smart_pure_air", "Smart Pure Air", new String[] { "Smart Pure Air", "Air Purifier", "Cabin Filter" }, "icons/fan.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        stock("n_mode", "N Mode", new String[] { "N Mode", "Track Performance", "Sport Cluster" }, "icons/car.svg", StockMenuCatalog.CAT_CLIMATE_EV, false);
        stock("driving_info", "Driving Info", new String[] { "Driving Info", "Trip Efficiency", "Energy History" }, "icons/car.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        stock("notifications", "Notifications", new String[] { "Notifications", "Messages", "Alerts" }, "icons/apps.svg", StockMenuCatalog.CAT_SYSTEM, false);
        stock("my_menu", "My Menu", new String[] { "My Menu", "Favorites", "Shortcuts" }, "icons/apps.svg", StockMenuCatalog.CAT_SYSTEM, false);
        stock("manual_tip", "Web Manual", new String[] { "Web Manual", "Owner Guide", "QR Manual" }, "icons/apps.svg", StockMenuCatalog.CAT_SYSTEM, false);
        stock("carpay", "CarPay", new String[] { "CarPay", "In-Car Pay", "Parking Pay" }, "icons/shield.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
        stock("map_app", "Map", new String[] { "Map", "Split Map", "GPS Map" }, "icons/navi.svg", StockMenuCatalog.CAT_NAVI_VEH, false);
    }

    private static void comp(String componentKey, String primary, String[] suggestions, String svg, String category, boolean defaultHidden) {
        KNOWN_COMPONENTS.put(componentKey, new CuratedMeta(primary, suggestions, svg, category, defaultHidden));
    }

    private static void pkg(String packageName, String primary, String[] suggestions, String svg, String category, boolean defaultHidden) {
        KNOWN_PACKAGES.put(packageName, new CuratedMeta(primary, suggestions, svg, category, defaultHidden));
    }

    private static void stock(String stockId, String primary, String[] suggestions, String svg, String category, boolean defaultHidden) {
        KNOWN_STOCK_IDS.put(stockId, new CuratedMeta(primary, suggestions, svg, category, defaultHidden));
    }

    private AppCatalogOverrides() {
    }

    public static List<IconChoice> getIconChoices() {
        return ICON_CHOICES;
    }

    public static CuratedMeta resolveMeta(String entryKey, String pkg, String cls, String stockId) {
        if (entryKey != null) {
            CuratedMeta byComp = KNOWN_COMPONENTS.get(entryKey);
            if (byComp != null) {
                return byComp;
            }
        }
        if (pkg != null && cls != null && !pkg.isEmpty() && !cls.isEmpty()) {
            String fullCls = cls.startsWith(".") ? (pkg + cls) : cls;
            CuratedMeta byComp = KNOWN_COMPONENTS.get(pkg + "/" + fullCls);
            if (byComp != null) {
                return byComp;
            }
        }
        if (stockId != null && !stockId.isEmpty()) {
            CuratedMeta byStock = KNOWN_STOCK_IDS.get(stockId);
            if (byStock != null) {
                return byStock;
            }
        }
        if (pkg != null && !pkg.isEmpty()) {
            CuratedMeta byPkg = KNOWN_PACKAGES.get(pkg);
            if (byPkg != null) {
                return byPkg;
            }
        }
        return null;
    }

    public static boolean isPreferSvgIcons(Context context) {
        if (context == null) {
            return true;
        }
        return prefs(context).getBoolean(KEY_PREFER_SVG, true);
    }

    public static void setPreferSvgIcons(Context context, boolean preferSvg) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(KEY_PREFER_SVG, preferSvg).apply();
    }

    public static synchronized Map<String, OverrideEntry> loadOverrides(Context context) {
        Map<String, OverrideEntry> out = new HashMap<String, OverrideEntry>();
        if (context == null) {
            return out;
        }
        try {
            String raw = prefs(context).getString(KEY_OVERRIDES_JSON, "");
            if (raw == null || raw.isEmpty()) {
                return out;
            }
            JSONObject root = new JSONObject(raw);
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = root.optJSONObject(k);
                if (o == null) {
                    continue;
                }
                OverrideEntry entry = new OverrideEntry();
                if (o.has("hidden")) {
                    entry.hasHiddenOverride = true;
                    entry.hidden = o.optBoolean("hidden", false);
                }
                entry.customLabel = o.optString("label", "").trim();
                entry.iconOverride = o.optString("icon", "").trim();
                out.put(k, entry);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized void setHidden(Context context, String entryKey, boolean hidden) {
        if (context == null || entryKey == null || entryKey.isEmpty()) {
            return;
        }
        Map<String, OverrideEntry> map = loadOverrides(context);
        OverrideEntry e = map.get(entryKey);
        if (e == null) {
            e = new OverrideEntry();
            map.put(entryKey, e);
        }
        e.hasHiddenOverride = true;
        e.hidden = hidden;
        saveOverrides(context, map);
    }

    public static synchronized void setCustomization(
            Context context,
            String entryKey,
            boolean hidden,
            String customLabel,
            String iconOverride) {
        if (context == null || entryKey == null || entryKey.isEmpty()) {
            return;
        }
        Map<String, OverrideEntry> map = loadOverrides(context);
        OverrideEntry e = map.get(entryKey);
        if (e == null) {
            e = new OverrideEntry();
            map.put(entryKey, e);
        }
        e.hasHiddenOverride = true;
        e.hidden = hidden;
        e.customLabel = customLabel == null ? "" : customLabel.trim();
        e.iconOverride = iconOverride == null ? "" : iconOverride.trim();
        saveOverrides(context, map);
    }

    public static synchronized void clearEntryOverride(Context context, String entryKey) {
        if (context == null || entryKey == null || entryKey.isEmpty()) {
            return;
        }
        Map<String, OverrideEntry> map = loadOverrides(context);
        if (map.remove(entryKey) != null) {
            saveOverrides(context, map);
        }
    }

    public static synchronized void resetAllOverrides(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().remove(KEY_OVERRIDES_JSON).putBoolean(KEY_PREFER_SVG, true).apply();
    }

    public static List<String> buildSuggestionsForEntry(
            String defaultLabel,
            String pkg,
            String cls,
            CuratedMeta meta) {
        List<String> list = new ArrayList<String>();
        addUniqueSuggestion(list, defaultLabel);
        if (meta != null) {
            addUniqueSuggestion(list, meta.primaryName);
            for (String s : meta.suggestions) {
                addUniqueSuggestion(list, s);
            }
        }
        if (cls != null && !cls.isEmpty()) {
            String shortCls = AppLauncher.humanizeTokenForUi(cls);
            addUniqueSuggestion(list, shortCls);
        }
        if (pkg != null && !pkg.isEmpty()) {
            int dot = pkg.lastIndexOf('.');
            String tail = dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
            if (!"app".equalsIgnoreCase(tail) && !"main".equalsIgnoreCase(tail) && !"ui".equalsIgnoreCase(tail)) {
                addUniqueSuggestion(list, AppLauncher.humanizeTokenForUi(tail));
            }
        }
        return list;
    }

    private static void addUniqueSuggestion(List<String> list, String s) {
        if (s == null) {
            return;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return;
        }
        for (String existing : list) {
            if (existing.equalsIgnoreCase(t)) {
                return;
            }
        }
        list.add(t);
    }

    private static void saveOverrides(Context context, Map<String, OverrideEntry> map) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, OverrideEntry> item : map.entrySet()) {
                OverrideEntry e = item.getValue();
                if (e == null) {
                    continue;
                }
                if (!e.hasHiddenOverride && e.customLabel.isEmpty() && e.iconOverride.isEmpty()) {
                    continue;
                }
                JSONObject o = new JSONObject();
                if (e.hasHiddenOverride) {
                    o.put("hidden", e.hidden);
                }
                if (!e.customLabel.isEmpty()) {
                    o.put("label", e.customLabel);
                }
                if (!e.iconOverride.isEmpty()) {
                    o.put("icon", e.iconOverride);
                }
                root.put(item.getKey(), o);
            }
            prefs(context).edit().putString(KEY_OVERRIDES_JSON, root.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String friendlyIconName(String svgPath) {
        if (svgPath == null || svgPath.isEmpty()) {
            return "Auto";
        }
        if (ICON_MODE_APK.equals(svgPath)) {
            return "Original APK";
        }
        for (IconChoice ic : ICON_CHOICES) {
            if (ic.svgPath.equals(svgPath)) {
                return ic.label;
            }
        }
        int slash = svgPath.lastIndexOf('/');
        String base = slash >= 0 ? svgPath.substring(slash + 1) : svgPath;
        return base.replace(".svg", "").toUpperCase(Locale.US);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
