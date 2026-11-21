using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using Gen5WStudio.Models;

namespace Gen5WStudio.Services;

public sealed class ActionPresetOption
{
    public string Display { get; }
    public string ActionType { get; }
    public string Cmd { get; }
    public string DefaultIcon { get; }
    public string DefaultTitle { get; }
    public string DefaultSubtitle { get; }
    public string DefaultSensorKey { get; }
    public bool IsHeader { get; }

    public ActionPresetOption(string display, string actionType, string cmd,
        string defaultIcon, string defaultTitle, string defaultSubtitle,
        string defaultSensorKey = "", bool isHeader = false)
    {
        Display = display;
        ActionType = actionType;
        Cmd = cmd;
        DefaultIcon = defaultIcon;
        DefaultTitle = defaultTitle;
        DefaultSubtitle = defaultSubtitle;
        DefaultSensorKey = defaultSensorKey;
        IsHeader = isHeader;
    }

    public static ActionPresetOption Category(string title) =>
        new($"\u2500\u2500\u2500\u2500  {title}  \u2500\u2500\u2500\u2500", "", "", "", "", "", "", isHeader: true);

    public override string ToString() => Display;
}

public static class PresetCatalog
{
    public const string StudioVersion = "0.4.9";
    public const string DefaultMinAppVersion = "0.4.0";

    public static IReadOnlyList<string> ThemeCategories { get; } = new List<string>
    {
        "EV & Telemetry",
        "Minimal",
        "OEM+",
        "Sports / Performance",
        "Animated",
        "Anime / Gaming",
        "Nature",
        "Dark / AMOLED",
        "Utility",
        "Other"
    };

    public static IReadOnlyList<ActionPresetOption> ActionPresets { get; } = new List<ActionPresetOption>
    {
        new("(Custom / None)", "none", "", "icons/apps.svg", "Custom Tile", ""),

        ActionPresetOption.Category("EV, HYBRID & DRIVING"),
        new("EV & Energy (Auto EV / HEV / PHEV)", "preset", "ev", "icons/ev.svg", "EV & Energy", "Battery, Charging & Range"),
        new("EV Menu (EV3 Main)", "preset", "ev3_main", "icons/ev.svg", "EV Menu", "Battery & Energy"),
        new("EV Menu (EV3 Ignition)", "preset", "ev3_ign", "icons/ev.svg", "EV Ignition", "Battery & Energy"),
        new("EV Legacy Menu", "preset", "ev1_ign", "icons/ev.svg", "EV Legacy", "Energy Monitor"),
        new("Driving Info & Efficiency (Eco)", "preset", "driving_info", "icons/ev.svg", "Driving Info", "Trip & Efficiency"),
        new("Hybrid / HEV Info (Direct)", "preset", "hev_ign", "icons/ev.svg", "Hybrid Info", "HEV Energy Monitor"),
        new("Plug-in Hybrid / PHEV Info (Direct)", "preset", "phev_ign", "icons/ev.svg", "PHEV Info", "PHEV Energy Monitor"),
        new("Hyundai N Mode", "preset", "n_mode", "icons/ev.svg", "N Mode", "Performance Gauges"),

        ActionPresetOption.Category("CLIMATE, SEATS & AIR"),
        new("Climate Control (Auto Fallback)", "launch_climate", "", "icons/climate.svg", "Climate", "HVAC, Seats & Defog"),
        new("Climate Control (NewClimateControl)", "preset", "climate_new_ctrl", "icons/climate.svg", "Climate", "Full Climate Screen"),
        new("Climate Control (InteriorClimateNew)", "preset", "climate_interior_new", "icons/climate.svg", "Interior Climate", "Climate Screen"),
        new("Climate Control (InteriorClimate Classic)", "preset", "climate_interior", "icons/climate.svg", "Interior Climate", "Classic Climate"),
        new("Climate Overlay (Broadcast Entry 0)", "preset", "climate_req", "icons/climate.svg", "Climate Bar", "Quick Climate"),
        new("Climate Overlay (Broadcast Entry 1)", "preset", "climate_req1", "icons/climate.svg", "Climate Overlay", "Quick Climate"),
        new("Seat Heat & Vent Popup", "preset", "seat_popup", "icons/climate.svg", "Seat Heat & Vent", "Quick Seat Popup"),
        new("Front Seats & Steering Wheel Heat/Vent", "preset", "seat_front", "icons/climate.svg", "Seats & Steering", "Heat & Ventilation"),
        new("Front Seats Heat/Vent (Broadcast Popup)", "preset", "seat_front_bcast", "icons/climate.svg", "Front Seats", "Quick Heat & Vent"),
        new("Rear Seat Heat & Ventilation", "preset", "seat_rear", "icons/climate.svg", "Rear Seats", "Heat & Ventilation"),
        new("Rear Climate Control (Broadcast)", "preset", "rear_climate", "icons/climate.svg", "Rear Climate", "Rear HVAC"),
        new("Rear Climate Control (Direct Activity)", "preset", "climate_rear_act", "icons/climate.svg", "Rear Climate", "Rear HVAC"),
        new("Smart Pure Air", "preset", "smart_pure_air", "icons/climate.svg", "Pure Air", "Cabin Air Ionizer"),

        ActionPresetOption.Category("PHONE & PROJECTION"),
        new("CarPlay / Android Auto (Smart Auto)", "preset", "projection", "icons/projection.svg", "CarPlay / AA", "Phone Projection"),
        new("Apple CarPlay", "preset", "carplay", "icons/projection.svg", "Apple CarPlay", "iPhone Projection"),
        new("Android Auto", "preset", "android_auto", "icons/projection.svg", "Android Auto", "Google Projection"),
        new("Phone Projection Connections", "preset", "device_connections", "icons/projection.svg", "Connections", "CarPlay & AA Setup"),
        new("Phone Projection Popup", "preset", "micom_proj_popup", "icons/projection.svg", "Projection", "Quick Popup"),
        new("Bluetooth Phone", "preset", "phone", "icons/projection.svg", "Phone", "Bluetooth Hands-Free"),
        new("Bluetooth Phone Shortcut (Service)", "preset", "bt_shortcut", "icons/projection.svg", "Phone", "Hands-Free Call"),
        new("Voice Dialer", "preset", "voice_dialer", "icons/projection.svg", "Voice Dialer", "Hands-Free Calling"),
        new("Baidu CarLife", "preset", "carlife", "icons/projection.svg", "CarLife", "Baidu Projection"),
        new("MirrorLink", "preset", "mirrorlink", "icons/projection.svg", "MirrorLink", "Phone Mirroring"),

        ActionPresetOption.Category("NAVIGATION, CAMERA & SAFETY"),
        new("Navigation", "preset", "navi", "icons/navi.svg", "Navigation", "Map & Route Guidance"),
        new("Live Map", "preset", "map", "icons/navi.svg", "Map", "Live Navigation Map"),
        new("Navigation Current Position", "preset", "navi_curpos", "icons/navi.svg", "Current Location", "Map Cursor"),
        new("Navigation Menu", "preset", "navi_menu", "icons/navi.svg", "Navi Menu", "Search & Destinations"),
        new("Rear View Camera", "preset", "drvm", "icons/stock.svg", "Rear Camera", "Rear View Monitor"),
        new("Built-in Dashcam (DVRS)", "preset", "dvrs_act", "icons/stock.svg", "Dashcam", "Drive Recorder"),
        new("Built-in Dashcam (DVRS Broadcast)", "preset", "dvrs_bcast", "icons/stock.svg", "Dashcam", "Quick Recorder"),
        new("BlueLink / Kia Connect", "preset", "bluelink", "icons/power.svg", "Connected Services", "BlueLink / Kia Connect"),
        new("BlueLink / Kia Connect Settings", "preset", "bluelink_settings", "icons/settings.svg", "Connect Settings", "Modem & Services"),
        new("Maintenance & Service Intervals", "preset", "maintenance", "icons/settings.svg", "Maintenance", "Service Schedule"),
        new("Car-to-Home Smart Control (IoT)", "preset", "iot_act", "icons/home.svg", "Car to Home", "Smart Home IoT"),
        new("Car-to-Home Smart Control (Broadcast)", "preset", "iot_bcast", "icons/home.svg", "Car to Home", "Quick IoT"),
        new("In-Car Payment (CarPay)", "preset", "carpay", "icons/power.svg", "In-Car Payment", "CarPay Wallet"),
        new("Valet Mode", "preset", "valet_mode", "icons/power.svg", "Valet Mode", "Security Lockout"),
        new("System Notifications", "preset", "notifications", "icons/home.svg", "Notifications", "Vehicle Alerts"),

        ActionPresetOption.Category("RADIO, AUDIO & MEDIA"),
        new("Radio / SiriusXM (Auto Region)", "preset", "radio", "icons/radio.svg", "Radio / SXM", "FM \u2022 AM \u2022 SiriusXM"),
        new("Radio NA / SiriusXM (Direct)", "preset", "radio_na", "icons/radio.svg", "Radio / SXM", "North America"),
        new("Radio DAB / DRM (Direct)", "preset", "radio_drm", "icons/radio.svg", "Radio DAB", "Digital Radio"),
        new("Radio Global FM / AM (Direct)", "preset", "radio_ge", "icons/radio.svg", "Radio FM/AM", "Broadcast Radio"),
        new("Media Sources", "preset", "mediagate", "icons/media.svg", "Media", "Media & Sources"),
        new("Media Source Shortcut", "preset", "mediagate_shortcut", "icons/media.svg", "Next Media", "Media Shortcut"),
        new("Bluetooth Audio", "preset", "bt_audio", "icons/media.svg", "BT Audio", "Bluetooth Music"),
        new("USB Music", "preset", "usb_music", "icons/media.svg", "USB Music", "Audio Player"),
        new("USB Video", "preset", "usb_video", "icons/media.svg", "USB Video", "Video Player"),
        new("Sounds of Nature", "preset", "nature_sound", "icons/radio.svg", "Sounds of Nature", "Ambient Soundscapes"),
        new("HD Radio Data", "preset", "hd_data", "icons/radio.svg", "HD Data", "Traffic & Info"),
        new("Weather Doppler Radar", "preset", "doppler_radar", "icons/navi.svg", "Weather Radar", "HD Radio Radar"),
        new("SoundHound Music Search", "preset", "soundhound", "icons/media.svg", "SoundHound", "Identify Playing Song"),
        new("Voice Memo Recorder", "preset", "voice_memo", "icons/media.svg", "Voice Memo", "Audio Recorder"),
        new("Quiet Mode", "preset", "quiet_mode", "icons/radio.svg", "Quiet Mode", "Rear Audio Focus"),
        new("Passenger Talk Intercom", "preset", "passenger_talk", "icons/radio.svg", "Passenger Talk", "Cabin Intercom"),
        new("Driver Watch Monitor", "preset", "driver_watch", "icons/stock.svg", "Driver Watch", "Driver Attention"),
        new("Online / Streaming Media", "preset", "global_mode_bc", "icons/media.svg", "Streaming Media", "Online Audio"),
        new("DMB Digital TV", "preset", "dmb", "icons/media.svg", "DMB TV", "Digital Broadcast"),

        ActionPresetOption.Category("SETTINGS & PREFERENCES"),
        new("System Setup (All Settings)", "preset", "setup", "icons/settings.svg", "Setup", "Head Unit Settings"),
        new("Vehicle Settings (ADAS, Doors, Lights)", "preset", "vehicle_settings", "icons/settings.svg", "Vehicle Setup", "ADAS, Lights, Doors"),
        new("Seat Position & Steering Memory", "preset", "seat_settings", "icons/settings.svg", "Seat Memory", "Position & Steering"),
        new("Sound & Audio Settings", "preset", "sound_settings", "icons/settings.svg", "Sound Settings", "EQ, Balance & Alerts"),
        new("Display & Screen Settings", "preset", "display_settings", "icons/settings.svg", "Display Settings", "Brightness & Screen"),
        new("Screen Saver / Standby Clock", "preset", "screen_saver", "icons/home.svg", "Screen Saver", "Standby Display"),
        new("Steering Wheel & Button Settings", "preset", "button_settings", "icons/settings.svg", "Button Setup", "Star & Steering Keys"),
        new("General System Settings", "preset", "general_settings", "icons/settings.svg", "General Settings", "Date, Units & Info"),
        new("Storage & Memory Info", "preset", "storage_settings", "icons/settings.svg", "Storage", "Memory Usage"),
        new("Bluetooth Settings", "preset", "bt_settings", "icons/settings.svg", "BT Settings", "Pair & Manage Devices"),
        new("Wi-Fi / Network Settings", "preset", "wifi_settings", "icons/settings.svg", "Wi-Fi Settings", "Network Connections"),
        new("User Profile Switcher", "preset", "user_profile", "icons/home.svg", "User Profile", "Driver Profiles"),
        new("Stock Screen Theme Settings", "preset", "theme_settings", "icons/home.svg", "OEM Themes", "Stock Screen Style"),
        new("Interior Mood Lamp (SML)", "preset", "sml_check", "icons/power.svg", "Mood Lamp", "Ambient Lighting"),
        new("My Menu", "preset", "my_menu", "icons/apps.svg", "My Menu", "Custom Shortcuts"),
        new("Owner's Web Manual", "preset", "manual", "icons/apps.svg", "Manual", "Owner Guide"),
        new("Driving Tips Guide", "preset", "driving_tip_only", "icons/apps.svg", "Driving Tips", "System Tips"),

        ActionPresetOption.Category("LAUNCHER, THEME STORE & MODS"),
        new("All Apps Drawer (Stock + Installed)", "open_drawer", "", "icons/apps.svg", "All Apps", "Stock Menu & Installed"),
        new("Theme Store (Online)", "open_theme_store", "", "icons/store.svg", "Theme Store", "Browse & Download Themes"),
        new("Installed Themes & USB Importer", "open_themes", "", "icons/home.svg", "Themes", "Import & Switch Themes"),
        new("HMG-API Manager", "preset", "hmgapi", "icons/power.svg", "HMG-API", "Mods & Vehicle Bus"),
        new("NEMESIS Manager", "preset", "nemesis", "icons/store.svg", "NEMESIS", "Mod Installer"),
        new("Stock Hyundai / Kia Home Screen", "launch_stock", "", "icons/stock.svg", "Stock Home", "Original Car Home"),
        new("Stock All Menus Screen", "preset", "stock_apps_menu", "icons/apps.svg", "Stock Menus", "Original Menu Grid"),
        new("Edit Stock Home Icons", "preset", "edit_stock_home", "icons/settings.svg", "Edit Stock Home", "Reorder OEM Icons"),
        new("Launcher Failsafe & Switcher Status", "open_switcher", "", "icons/power.svg", "Failsafe", "5-Layer Protection"),
        new("Stock Buttons Diagnostic Tester", "open_stock_tester", "", "icons/apps.svg", "Button Tester", "Test OEM Shortcuts"),
        new("Toggle Top System Status Bar", "toggle_bar", "", "icons/home.svg", "Top Bar", "Toggle Fullscreen"),
        new("Cycle Click Sound Effect", "cycle_sound", "", "icons/radio.svg", "Click Audio", "System / Soft / Off"),
        new("Cycle Backdrop Preview Effect", "toggle_bg", "", "icons/home.svg", "Backdrop Fx", "Preview Modes"),
        new("Jump To Page", "goto_page", "next", "icons/home.svg", "Next Page", "Workspace Switch"),

        ActionPresetOption.Category("LIVE CAR CONTROLS — POPUP CONSOLE & DOORS / LOCKS"),
        new("Live Car Controls (Popup Console)", "open_car_controls", "", "icons/car.svg", "Car Controls", "AC, Doors, Trunk & Windows"),
        new("Lock All Doors", "car_cmd", "door_lock", "icons/car.svg", "Lock Doors", "Central Door Lock", "door.lock_state"),
        new("Unlock All Doors", "car_cmd", "door_unlock", "icons/car.svg", "Unlock Doors", "Central Door Unlock", "door.lock_state"),
        new("Toggle Door Locks", "car_cmd", "door_lock_toggle", "icons/car.svg", "Locks", "Lock / Unlock Doors", "door.lock_state"),
        new("Open All Doors (Parked)", "car_cmd", "door_open_all", "icons/car.svg", "Open Doors", "Release Door Latches", "door.any_open"),
        new("Close All Doors", "car_cmd", "door_close_all", "icons/car.svg", "Close Doors", "Close Power Doors", "door.any_open"),

        ActionPresetOption.Category("LIVE CAR CONTROLS — TRUNK / LIFTGATE & CHARGE PORT"),
        new("Open Trunk / Liftgate (Park Only)", "car_cmd", "trunk_open", "icons/car.svg", "Open Trunk", "Open Power Liftgate", "trunk.open"),
        new("Close Trunk / Liftgate (Park Only)", "car_cmd", "trunk_close", "icons/car.svg", "Close Trunk", "Close Power Liftgate", "trunk.open"),
        new("Toggle Trunk / Liftgate (Park Only)", "car_cmd", "trunk_toggle", "icons/car.svg", "Trunk / Hatch", "Open / Close Liftgate", "trunk.open"),
        new("Set Trunk Opening Height — Level 1", "car_cmd", "trunk_height_set:1", "icons/car.svg", "Trunk Lv1", "Lowest Liftgate Height", "trunk.open"),
        new("Set Trunk Opening Height — Level 2", "car_cmd", "trunk_height_set:2", "icons/car.svg", "Trunk Lv2", "Medium-Low Liftgate", "trunk.open"),
        new("Set Trunk Opening Height — Level 3", "car_cmd", "trunk_height_set:3", "icons/car.svg", "Trunk Lv3", "Medium-High Liftgate", "trunk.open"),
        new("Set Trunk Opening Height — Full Open", "car_cmd", "trunk_height_set:4", "icons/car.svg", "Trunk Full", "Full Liftgate Height", "trunk.open"),
        new("Set Trunk Opening Speed — Normal", "car_cmd", "trunk_speed_set:1", "icons/car.svg", "Trunk Normal", "Normal Liftgate Speed", "trunk.open"),
        new("Set Trunk Opening Speed — Fast", "car_cmd", "trunk_speed_set:2", "icons/car.svg", "Trunk Fast", "Fast Liftgate Speed", "trunk.open"),
        new("Open Charge Port Door (Park Only)", "car_cmd", "charge_door_open", "icons/ev.svg", "Open Charge", "Open Charge Port in Park", "charge_door.open"),
        new("Close Charge Port Door (Park Only)", "car_cmd", "charge_door_close", "icons/ev.svg", "Close Charge", "Close Charge Port in Park", "charge_door.open"),
        new("Toggle Charge Port Door (Park Only)", "car_cmd", "charge_door_toggle", "icons/ev.svg", "Charge Door", "Toggle Charge Flap", "charge_door.open"),

        ActionPresetOption.Category("LIVE CAR CONTROLS — POWER WINDOWS & SUNROOF"),
        new("Open All Windows (Full Down)", "car_cmd", "window_all_open", "icons/car.svg", "Windows Down", "Roll Down All 4 Windows", "window.fl"),
        new("Open All Windows 50% (Half)", "car_cmd", "window_all_half", "icons/car.svg", "Windows 50%", "Half-Open All 4 Windows", "window.fl"),
        new("Vent All Windows (25%)", "car_cmd", "window_all_vent", "icons/car.svg", "Vent Windows", "Crack All 4 Windows", "window.fl"),
        new("Close All Windows (Full Up)", "car_cmd", "window_all_close", "icons/car.svg", "Windows Up", "Roll Up All 4 Windows", "window.fl"),
        new("Driver Window Full Down (FL)", "car_cmd", "window_fl_open", "icons/car.svg", "FL Down", "Open Driver Window", "window.fl"),
        new("Driver Window Full Up (FL)", "car_cmd", "window_fl_close", "icons/car.svg", "FL Up", "Close Driver Window", "window.fl"),
        new("Passenger Window Full Down (FR)", "car_cmd", "window_fr_open", "icons/car.svg", "FR Down", "Open Front-Right Window", "window.fr"),
        new("Passenger Window Full Up (FR)", "car_cmd", "window_fr_close", "icons/car.svg", "FR Up", "Close Front-Right Window", "window.fr"),
        new("Rear-Left Window Full Down (RL)", "car_cmd", "window_rl_open", "icons/car.svg", "RL Down", "Open Rear-Left Window", "window.rl"),
        new("Rear-Left Window Full Up (RL)", "car_cmd", "window_rl_close", "icons/car.svg", "RL Up", "Close Rear-Left Window", "window.rl"),
        new("Rear-Right Window Full Down (RR)", "car_cmd", "window_rr_open", "icons/car.svg", "RR Down", "Open Rear-Right Window", "window.rr"),
        new("Rear-Right Window Full Up (RR)", "car_cmd", "window_rr_close", "icons/car.svg", "RR Up", "Close Rear-Right Window", "window.rr"),
        new("Open Sunroof", "car_cmd", "sunroof_open", "icons/car.svg", "Roof Open", "Open Sunroof Glass", "sunroof.open"),
        new("Close Sunroof", "car_cmd", "sunroof_close", "icons/car.svg", "Roof Close", "Close Sunroof Glass", "sunroof.open"),
        new("Open Sunroof Shade", "car_cmd", "sunshade_open", "icons/car.svg", "Shade Open", "Open Sunroof Blind", "sunroof.open"),
        new("Close Sunroof Shade", "car_cmd", "sunshade_close", "icons/car.svg", "Shade Close", "Close Sunroof Blind", "sunroof.open"),

        ActionPresetOption.Category("LIVE CAR CONTROLS — CLIMATE POWER, MODES & DEFROST"),
        new("Climate Power Toggle (On / Off)", "car_cmd", "climate_toggle", "icons/climate.svg", "Climate", "Toggle HVAC Power", "climate.on"),
        new("Climate Power ON", "car_cmd", "climate_on", "icons/climate.svg", "HVAC ON", "Turn Climate System On", "climate.on"),
        new("Climate Power OFF", "car_cmd", "climate_off", "icons/climate.svg", "HVAC OFF", "Turn Climate System Off", "climate.on"),
        new("Climate AUTO Mode Toggle", "car_cmd", "climate_auto_toggle", "icons/climate.svg", "AUTO", "Toggle Auto Climate", "climate.auto"),
        new("Set Climate AUTO ON", "car_cmd", "climate_auto_on", "icons/climate.svg", "AUTO ON", "Enable Automatic Climate", "climate.auto"),
        new("Set Climate AUTO OFF", "car_cmd", "climate_auto_off", "icons/climate.svg", "AUTO OFF", "Disable Automatic Climate", "climate.auto"),
        new("A/C Compressor Toggle", "car_cmd", "climate_ac_toggle", "icons/climate.svg", "A/C", "Toggle Air Conditioning", "climate.ac"),
        new("Set A/C Compressor ON", "car_cmd", "climate_ac_on", "icons/climate.svg", "A/C ON", "Turn Air Conditioning On", "climate.ac"),
        new("Set A/C Compressor OFF", "car_cmd", "climate_ac_off", "icons/climate.svg", "A/C OFF", "Turn Air Conditioning Off", "climate.ac"),
        new("Cabin Heater (HEAT) Toggle", "car_cmd", "climate_heat_toggle", "icons/temp.svg", "HEAT", "Toggle Cabin Heater", "climate.heat"),
        new("Set Cabin Heater ON", "car_cmd", "climate_heat_on", "icons/temp.svg", "HEAT ON", "Turn Cabin Heater On", "climate.heat"),
        new("Set Cabin Heater OFF", "car_cmd", "climate_heat_off", "icons/temp.svg", "HEAT OFF", "Turn Cabin Heater Off", "climate.heat"),
        new("MAX A/C Cool Down", "car_cmd", "climate_max_ac", "icons/climate.svg", "MAX A/C", "LO + Max Fan + A/C + Recirc", "climate.ac"),
        new("MAX Windshield Defrost", "car_cmd", "climate_max_defrost", "icons/defrost.svg", "MAX Defrost", "Max Defrost + A/C + Fresh Air", "climate.front_defrost"),
        new("Dual-Zone Temp SYNC Toggle", "car_cmd", "climate_sync_toggle", "icons/temp.svg", "SYNC", "Sync Driver & Pass Temp", "climate.sync"),
        new("Set Dual-Zone SYNC ON", "car_cmd", "climate_sync_on", "icons/temp.svg", "SYNC ON", "Enable Dual-Zone SYNC", "climate.sync"),
        new("Set Dual-Zone SYNC OFF", "car_cmd", "climate_sync_off", "icons/temp.svg", "SYNC OFF", "Independent Driver & Pass", "climate.sync"),
        new("Air Recirculation Toggle", "car_cmd", "climate_recirc_toggle", "icons/fan.svg", "Recirc", "Toggle Cabin Air Intake", "climate.recirc"),
        new("Set Recirculation ON", "car_cmd", "climate_recirc_on", "icons/fan.svg", "Recirc ON", "Recirculate Cabin Air", "climate.recirc"),
        new("Set Fresh Outside Air", "car_cmd", "climate_fresh_air", "icons/fan.svg", "Fresh Air", "Draw Outside Fresh Air", "climate.recirc"),
        new("Driver-Only Climate Toggle", "car_cmd", "climate_driver_only_toggle", "icons/user.svg", "Driver Only", "Toggle Driver-Only HVAC", "climate.driver_only"),
        new("Set Driver-Only Climate ON", "car_cmd", "climate_driver_only_on", "icons/user.svg", "Drv Only ON", "Enable Driver-Only Airflow", "climate.driver_only"),
        new("Set Driver-Only Climate OFF", "car_cmd", "climate_driver_only_off", "icons/user.svg", "Drv Only OFF", "All Cabin Zones Active", "climate.driver_only"),
        new("Front Windshield Defrost Toggle", "car_cmd", "climate_front_defrost_toggle", "icons/defrost.svg", "Front Defrost", "Toggle Front Defroster", "climate.front_defrost"),
        new("Set Front Defrost ON", "car_cmd", "climate_front_defrost_on", "icons/defrost.svg", "F. Def ON", "Enable Front Defrost", "climate.front_defrost"),
        new("Set Front Defrost OFF", "car_cmd", "climate_front_defrost_off", "icons/defrost.svg", "F. Def OFF", "Disable Front Defrost", "climate.front_defrost"),

        ActionPresetOption.Category("LIVE CAR CONTROLS — SET AC TEMP, VENTS & FAN SPEED"),
        new("Cycle Air Vent Mode", "car_cmd", "climate_vent_cycle", "icons/fan.svg", "Vent Mode", "Cycle Face / Floor / Def", "climate.vent_mode"),
        new("Set Air Vent — Face", "car_cmd", "climate_vent_set:1", "icons/fan.svg", "Vent: Face", "Upper Dash Vents", "climate.vent_mode"),
        new("Set Air Vent — Bi-Level (Face+Floor)", "car_cmd", "climate_vent_set:2", "icons/fan.svg", "Vent: Bi-Lvl", "Face & Floor Vents", "climate.vent_mode"),
        new("Set Air Vent — Floor", "car_cmd", "climate_vent_set:3", "icons/fan.svg", "Vent: Floor", "Footwell Vents", "climate.vent_mode"),
        new("Set Air Vent — Floor + Defrost", "car_cmd", "climate_vent_set:4", "icons/defrost.svg", "Vent: Flr+Def", "Floor & Windshield Vents", "climate.vent_mode"),
        new("Set Air Vent — Defrost", "car_cmd", "climate_vent_set:5", "icons/defrost.svg", "Vent: Defrost", "Windshield Defrost Vents", "climate.vent_mode"),
        new("Set AC Temp — LO (Max Cool)", "car_cmd", "climate_set_temp:LO", "icons/temp.svg", "Temp LO", "Set Cabin Temp to LO", "climate.driver_temp"),
        new("Set AC Temp — 66°F (19°C)", "car_cmd", "climate_set_temp:66", "icons/temp.svg", "Set 66°", "Set Cabin Target to 66°F", "climate.driver_temp"),
        new("Set AC Temp — 68°F (20°C)", "car_cmd", "climate_set_temp:68", "icons/temp.svg", "Set 68°", "Set Cabin Target to 68°F", "climate.driver_temp"),
        new("Set AC Temp — 70°F (21°C)", "car_cmd", "climate_set_temp:70", "icons/temp.svg", "Set 70°", "Set Cabin Target to 70°F", "climate.driver_temp"),
        new("Set AC Temp — 72°F (22°C)", "car_cmd", "climate_set_temp:72", "icons/temp.svg", "Set 72°", "Set Cabin Target to 72°F", "climate.driver_temp"),
        new("Set AC Temp — 74°F (23°C)", "car_cmd", "climate_set_temp:74", "icons/temp.svg", "Set 74°", "Set Cabin Target to 74°F", "climate.driver_temp"),
        new("Set AC Temp — 76°F (24°C)", "car_cmd", "climate_set_temp:76", "icons/temp.svg", "Set 76°", "Set Cabin Target to 76°F", "climate.driver_temp"),
        new("Set AC Temp — HI (Max Heat)", "car_cmd", "climate_set_temp:HI", "icons/temp.svg", "Temp HI", "Set Cabin Temp to HI", "climate.driver_temp"),
        new("Driver Temp + Step", "car_cmd", "climate_driver_temp_up", "icons/temp.svg", "Driver +", "Raise Driver Target Temp", "climate.driver_temp"),
        new("Driver Temp - Step", "car_cmd", "climate_driver_temp_down", "icons/temp.svg", "Driver -", "Lower Driver Target Temp", "climate.driver_temp"),
        new("Passenger Temp + Step", "car_cmd", "climate_passenger_temp_up", "icons/temp.svg", "Pass +", "Raise Passenger Target Temp", "climate.passenger_temp"),
        new("Passenger Temp - Step", "car_cmd", "climate_passenger_temp_down", "icons/temp.svg", "Pass -", "Lower Passenger Target Temp", "climate.passenger_temp"),
        new("Climate Fan Speed +", "car_cmd", "climate_fan_up", "icons/fan.svg", "Fan +", "Increase Blower Speed", "climate.fan_speed"),
        new("Climate Fan Speed -", "car_cmd", "climate_fan_down", "icons/fan.svg", "Fan -", "Decrease Blower Speed", "climate.fan_speed"),
        new("Set Fan Speed — OFF (0)", "car_cmd", "climate_fan_set:0", "icons/fan.svg", "Fan OFF", "Turn Blower Fan Off", "climate.fan_speed"),
        new("Set Fan Speed — Level 1", "car_cmd", "climate_fan_set:1", "icons/fan.svg", "Fan Lv1", "Set Blower Level 1", "climate.fan_speed"),
        new("Set Fan Speed — Level 2", "car_cmd", "climate_fan_set:2", "icons/fan.svg", "Fan Lv2", "Set Blower Level 2", "climate.fan_speed"),
        new("Set Fan Speed — Level 3", "car_cmd", "climate_fan_set:3", "icons/fan.svg", "Fan Lv3", "Set Blower Level 3", "climate.fan_speed"),
        new("Set Fan Speed — Level 4", "car_cmd", "climate_fan_set:4", "icons/fan.svg", "Fan Lv4", "Set Blower Level 4", "climate.fan_speed"),
        new("Set Fan Speed — Level 5", "car_cmd", "climate_fan_set:5", "icons/fan.svg", "Fan Lv5", "Set Blower Level 5", "climate.fan_speed"),
        new("Set Fan Speed — Level 6", "car_cmd", "climate_fan_set:6", "icons/fan.svg", "Fan Lv6", "Set Blower Level 6", "climate.fan_speed"),
        new("Set Fan Speed — Level 7", "car_cmd", "climate_fan_set:7", "icons/fan.svg", "Fan Lv7", "Set Blower Level 7", "climate.fan_speed"),
        new("Set Fan Speed — Level 8 (Max)", "car_cmd", "climate_fan_set:8", "icons/fan.svg", "Fan Max", "Set Blower Level 8", "climate.fan_speed"),

        ActionPresetOption.Category("LIVE CAR CONTROLS — CABIN EXTRAS & REAR HVAC"),
        new("Smart Ventilation Toggle", "car_cmd", "climate_smart_vent_toggle", "icons/climate.svg", "Smart Vent", "Toggle Smart Ventilation", "climate.smart_vent"),
        new("Auto Defog Toggle", "car_cmd", "climate_auto_defog_toggle", "icons/defrost.svg", "Auto Defog", "Toggle Auto Windshield Defog", "climate.auto_defog"),
        new("After-Blow Evaporator Dry Toggle", "car_cmd", "climate_after_blow_toggle", "icons/fan.svg", "After-Blow", "Toggle After-Blow Fan", "climate.after_blow"),
        new("Air Cleaning Ionizer Toggle", "car_cmd", "climate_air_clean_toggle", "icons/climate.svg", "Air Clean", "Toggle Cabin Air Clean", "climate.air_clean"),
        new("Rear Climate Power Toggle", "car_cmd", "rear_climate_toggle", "icons/climate.svg", "Rear HVAC", "Toggle Rear Climate Zone", "climate.rear_on"),
        new("Rear Climate AUTO Toggle", "car_cmd", "rear_climate_auto_toggle", "icons/climate.svg", "Rear AUTO", "Toggle Rear Auto Mode", "climate.rear_on"),
        new("Rear Climate Lock Toggle", "car_cmd", "rear_climate_lock_toggle", "icons/climate.svg", "Rear Lock", "Lock Rear HVAC Controls", "climate.rear_lock"),
        new("Rear Climate Temp +", "car_cmd", "rear_climate_temp_up", "icons/temp.svg", "Rear +", "Raise Rear Target Temp", "climate.rear_on"),
        new("Rear Climate Temp -", "car_cmd", "rear_climate_temp_down", "icons/temp.svg", "Rear -", "Lower Rear Target Temp", "climate.rear_on"),
        new("Rear Climate Fan +", "car_cmd", "rear_climate_fan_up", "icons/fan.svg", "R. Fan +", "Raise Rear Blower Speed", "climate.rear_on"),
        new("Rear Climate Fan -", "car_cmd", "rear_climate_fan_down", "icons/fan.svg", "R. Fan -", "Lower Rear Blower Speed", "climate.rear_on"),

        ActionPresetOption.Category("LIVE CAR CONTROLS — SEATS, STEERING WHEEL & AUDIO"),
        new("Driver Seat Heat (Cycle 0-3)", "car_cmd", "seat_heat_driver_cycle", "icons/seat.svg", "D. Seat Heat", "Cycle High / Med / Low / Off", "seat_heat.driver"),
        new("Set Driver Seat Heat — Off", "car_cmd", "seat_heat_driver_set:0", "icons/seat.svg", "D. Heat Off", "Turn Driver Seat Heat Off", "seat_heat.driver"),
        new("Set Driver Seat Heat — High (3)", "car_cmd", "seat_heat_driver_set:3", "icons/seat.svg", "D. Heat Hi", "Driver Seat Heat Level 3", "seat_heat.driver"),
        new("Passenger Seat Heat (Cycle 0-3)", "car_cmd", "seat_heat_passenger_cycle", "icons/seat.svg", "P. Seat Heat", "Cycle High / Med / Low / Off", "seat_heat.passenger"),
        new("Set Passenger Seat Heat — Off", "car_cmd", "seat_heat_passenger_set:0", "icons/seat.svg", "P. Heat Off", "Turn Pass Seat Heat Off", "seat_heat.passenger"),
        new("Set Passenger Seat Heat — High (3)", "car_cmd", "seat_heat_passenger_set:3", "icons/seat.svg", "P. Heat Hi", "Pass Seat Heat Level 3", "seat_heat.passenger"),
        new("Driver Seat Vent (Cycle 0-3)", "car_cmd", "seat_vent_driver_cycle", "icons/seat.svg", "D. Seat Vent", "Cycle Seat Cooling", "seat_vent.driver"),
        new("Passenger Seat Vent (Cycle 0-3)", "car_cmd", "seat_vent_passenger_cycle", "icons/seat.svg", "P. Seat Vent", "Cycle Seat Cooling", "seat_vent.passenger"),
        new("Rear-Left Seat Heat (Cycle 0-3)", "car_cmd", "seat_heat_rl_cycle", "icons/seat.svg", "RL Heat", "Cycle Rear-Left Seat Heat", "seat_heat.rear_left"),
        new("Rear-Right Seat Heat (Cycle 0-3)", "car_cmd", "seat_heat_rr_cycle", "icons/seat.svg", "RR Heat", "Cycle Rear-Right Seat Heat", "seat_heat.rear_right"),
        new("Steering Wheel Heater Toggle", "car_cmd", "wheel_heat_toggle", "icons/steering.svg", "Wheel Heat", "Toggle Heated Wheel", "wheel_heat"),
        new("Rear Window Defogger Toggle", "car_cmd", "rear_defrost_toggle", "icons/defrost.svg", "Rear Defog", "Toggle Rear Defroster", "defogger"),
        new("Audio Volume +", "car_cmd", "volume_up", "icons/volume.svg", "Vol +", "Increase Active Volume", "audio.volume"),
        new("Audio Volume -", "car_cmd", "volume_down", "icons/volume.svg", "Vol -", "Decrease Active Volume", "audio.volume"),
        new("Audio Mute Toggle", "car_cmd", "volume_mute_toggle", "icons/volume.svg", "Mute", "Toggle Media Mute", "audio.muted"),
        new("HV Battery Preconditioning", "car_cmd", "precondition_toggle", "icons/battery.svg", "Precond", "Start / Stop Battery Heating", "hv.conditioning_active")
    };

    public static IReadOnlyList<string> BuiltInIcons { get; } = new List<string>
    {
        "icons/ev.svg",
        "icons/battery.svg",
        "icons/climate.svg",
        "icons/fan.svg",
        "icons/temp.svg",
        "icons/defrost.svg",
        "icons/seat.svg",
        "icons/steering.svg",
        "icons/car.svg",
        "icons/navi.svg",
        "icons/camera.svg",
        "icons/radio.svg",
        "icons/media.svg",
        "icons/music.svg",
        "icons/volume.svg",
        "icons/projection.svg",
        "icons/bluetooth.svg",
        "icons/phone.svg",
        "icons/wifi.svg",
        "icons/user.svg",
        "icons/settings.svg",
        "icons/wrench.svg",
        "icons/shield.svg",
        "icons/store.svg",
        "icons/apps.svg",
        "icons/home.svg",
        "icons/stock.svg",
        "icons/power.svg"
    };

    public sealed record TelemetrySensorOption(
        string Key,
        string Display,
        string DefaultUnit,
        double DefaultMin,
        double DefaultMax,
        bool IsHeader = false)
    {
        public string DropdownLabel => IsHeader || string.IsNullOrEmpty(Key) ? Display : $"{Key} \u2014 {Display}";
        public override string ToString() => Key;

        public static TelemetrySensorOption Category(string title) =>
            new("", $"\u2500\u2500\u2500\u2500  {title}  \u2500\u2500\u2500\u2500", "", 0, 1, IsHeader: true);
    }

    public static IReadOnlyList<TelemetrySensorOption> TelemetrySensors { get; } = new List<TelemetrySensorOption>
    {
        new("", "(None — Static Text / Value)", "", 0, 100),

        TelemetrySensorOption.Category("EV BATTERY, RANGE & CHARGING"),
        new("hv.soc", "HV Battery State of Charge (%)", "%", 0, 100),
        new("hv.range", "EV Estimated Range (Uses Theme Unit)", "", 0, 350),
        new("hv.range_mi", "EV Estimated Range (Always mi)", "mi", 0, 350),
        new("hv.range_km", "EV Estimated Range (Always km)", "km", 0, 550),
        new("charging", "Charging Status (CHARGING / IDLE)", "", 0, 1),
        new("charging.remain_min", "Charging Time Remaining", "", 0, 600),
        new("hv.plug_state", "EV Charge Plug Connected (PLUGGED / UNPLUGGED)", "", 0, 2),
        new("hv.v2l_active", "Vehicle-to-Load V2L Active (ACTIVE / OFF)", "", 0, 1),
        new("hv.ready", "EV Ready Status (READY / STANDBY)", "", 0, 1),
        new("hv.conditioning", "Battery Conditioning Setting", "", 0, 2),
        new("hv.conditioning_active", "Battery Conditioning Active", "", 0, 1),
        new("conditioning.state", "Conditioning Request State", "", 0, 1),
        new("hv.battery_temp_min", "HV Pack Min Cell Temp (Uses Theme Unit)", "", -20, 60),
        new("hv.battery_temp_min_c", "HV Pack Min Cell Temp (Always °C)", "°C", -20, 60),
        new("hv.battery_temp_min_f", "HV Pack Min Cell Temp (Always °F)", "°F", 0, 140),
        new("hv.health", "HV Battery Health Index", "", 0, 100),

        TelemetrySensorOption.Category("SPEED, DRIVE, GEAR & PEDALS"),
        new("speed", "Vehicle Speed (Uses Theme Unit)", "", 0, 180),
        new("speed_mph", "Vehicle Speed (Always mph)", "mph", 0, 120),
        new("speed_kmh", "Vehicle Speed (Always km/h)", "km/h", 0, 180),
        new("gear_speed", "Gear / Speed Combo (Uses Theme Unit)", "", 0, 180),
        new("gear.park", "Park Gear Status (PARK / DRIVE)", "", 0, 1),
        new("parking_brake", "Electronic Parking Brake", "", 0, 1),
        new("odometer", "Odometer (Uses Theme Unit)", "", 0, 300000),
        new("odometer_mi", "Odometer (Always mi)", "mi", 0, 200000),
        new("odometer_km", "Odometer (Always km)", "km", 0, 300000),
        new("moving", "Vehicle Moving (0/1)", "", 0, 1),
        new("driving.restricted", "Driving Restriction Active", "", 0, 1),
        new("steering.angle", "Steering Wheel Angle (°)", "°", -540, 540),
        new("pedal.accel", "Accelerator Pedal (%)", "%", 0, 100),
        new("brake.pressure", "Brake Cylinder Pressure (raw)", "", 0, 100),
        new("guide_lines", "Rear Camera Dynamic Guidelines (ON / OFF)", "", 0, 1),

        TelemetrySensorOption.Category("CLIMATE & HVAC (TEMPS, FAN & MODES)"),
        new("outside_temp_f", "Outside Ambient Temp (Always °F)", "°F", -20, 120),
        new("outside_temp", "Outside Ambient Temp (Always °C)", "°C", -30, 50),
        new("climate.on", "Climate Power (ON / OFF)", "", 0, 1),
        new("climate.auto", "Climate AUTO Mode (ON / OFF)", "", 0, 1),
        new("climate.auto_level", "Climate AUTO Intensity Step (0-3)", "", 0, 3),
        new("climate.ac", "Climate A/C State (ON / OFF)", "", 0, 1),
        new("climate.heat", "Cabin Heater HEAT State (ON / OFF)", "", 0, 1),
        new("climate.temp_unit", "HVAC Display Temp Unit (C / F)", "", 0, 1),
        new("climate.driver_temp", "Driver Climate Target Temp", "", 60, 90),
        new("climate.passenger_temp", "Passenger Climate Target Temp", "", 60, 90),
        new("climate.fan_speed", "Climate Blower Fan Speed (0-8)", "", 0, 8),
        new("climate.vent_mode", "Climate Air Vent Mode", "", 0, 10),
        new("climate.recirc", "Air Recirculation (ON / OFF)", "", 0, 1),
        new("climate.sync", "Dual-Zone Temp SYNC (ON / OFF)", "", 0, 1),
        new("climate.driver_only", "Climate Driver Only (ON / OFF)", "", 0, 1),
        new("climate.front_defrost", "Front Windshield Defrost (ON / OFF)", "", 0, 1),
        new("climate.rear_on", "Rear Climate Zone Power (ON / OFF)", "", 0, 1),
        new("climate.rear_lock", "Rear Climate Controls Lock (ON / OFF)", "", 0, 1),
        new("climate.air_clean", "Air Cleaning Ionizer (ON / OFF)", "", 0, 1),
        new("climate.smart_vent", "Smart Ventilation (ON / OFF)", "", 0, 1),
        new("climate.auto_defog", "Auto Windshield Defog (ON / OFF)", "", 0, 1),
        new("climate.after_blow", "After-Blow Evaporator Fan (ON / OFF)", "", 0, 1),

        TelemetrySensorOption.Category("SEATS, STEERING WHEEL & DEFOGGER"),
        new("seat_heat.driver", "Driver Seat Heat (0-3)", "", 0, 3),
        new("seat_heat.passenger", "Passenger Seat Heat (0-3)", "", 0, 3),
        new("seat_heat.rear_left", "Rear Left Seat Heat (0-3)", "", 0, 3),
        new("seat_heat.rear_right", "Rear Right Seat Heat (0-3)", "", 0, 3),
        new("seat_vent.driver", "Driver Seat Vent (0-3)", "", 0, 3),
        new("seat_vent.passenger", "Passenger Seat Vent (0-3)", "", 0, 3),
        new("wheel_heat", "Steering Wheel Heat (ON / OFF)", "", 0, 1),
        new("defogger", "Rear Defogger (ON / OFF)", "", 0, 1),

        TelemetrySensorOption.Category("DOORS, LOCKS, TRUNK/HATCH & WINDOWS"),
        new("door.lock_state", "All Doors Lock Status (LOCKED / UNLOCKED)", "", 0, 1),
        new("door.any_open", "Any Door Open (ALL CLOSED / DOOR OPEN)", "", 0, 1),
        new("door.fl", "Front-Left Door (CLOSED / OPEN)", "", 0, 1),
        new("door.fr", "Front-Right Door (CLOSED / OPEN)", "", 0, 1),
        new("door.rl", "Rear-Left Door (CLOSED / OPEN)", "", 0, 1),
        new("door.rr", "Rear-Right Door (CLOSED / OPEN)", "", 0, 1),
        new("door.lock.fl", "Front-Left Door Lock (LOCKED / UNLOCKED)", "", 0, 1),
        new("door.lock.fr", "Front-Right Door Lock (LOCKED / UNLOCKED)", "", 0, 1),
        new("door.lock.rl", "Rear-Left Door Lock (LOCKED / UNLOCKED)", "", 0, 1),
        new("door.lock.rr", "Rear-Right Door Lock (LOCKED / UNLOCKED)", "", 0, 1),
        new("trunk.open", "Trunk / Liftgate State (CLOSED / OPEN)", "", 0, 1),
        new("trunk.ptg_state", "Power Tailgate Open Flag (CLOSED / OPEN)", "", 0, 1),
        new("charge_door.open", "EV Charge Port Door (CLOSED / OPEN)", "", 0, 1),
        new("hood.open", "Hood / Frunk State (CLOSED / OPEN)", "", 0, 1),
        new("window.fl", "Front-Left Window (CLOSED / OPEN)", "", 0, 1),
        new("window.fr", "Front-Right Window (CLOSED / OPEN)", "", 0, 1),
        new("window.rl", "Rear-Left Window (CLOSED / OPEN)", "", 0, 1),
        new("window.rr", "Rear-Right Window (CLOSED / OPEN)", "", 0, 1),
        new("sunroof.open", "Sunroof State (CLOSED / OPEN)", "", 0, 1),

        TelemetrySensorOption.Category("AUDIO, TIRES, 12V & ENERGY DRAW"),
        new("audio.volume", "Active Audio Volume Level", "", 0, 45),
        new("audio.muted", "Audio Mute Status (MUTED / ACTIVE)", "", 0, 1),
        new("battery12v.soc", "12V Aux Battery State of Charge (%)", "%", 0, 100),
        new("battery12v.saver", "Aux 12V Battery Saver Flag", "", 0, 1),
        new("tyre.fl_status", "Front-Left Tire Status (OK / LOW)", "", 0, 1),
        new("tyre.fr_status", "Front-Right Tire Status (OK / LOW)", "", 0, 1),
        new("tyre.rl_status", "Rear-Left Tire Status (OK / LOW)", "", 0, 1),
        new("tyre.rr_status", "Rear-Right Tire Status (OK / LOW)", "", 0, 1),
        new("tyre.fl_psi", "Front-Left Tire Pressure (psi)", "psi", 0, 60),
        new("tyre.fr_psi", "Front-Right Tire Pressure (psi)", "psi", 0, 60),
        new("tyre.rl_psi", "Rear-Left Tire Pressure (psi)", "psi", 0, 60),
        new("tyre.rr_psi", "Rear-Right Tire Pressure (psi)", "psi", 0, 60),
        new("energy.drivetrain_kw", "Drivetrain Power Draw (kW)", "kW", 0, 250),
        new("energy.climate_kw", "Climate Power Draw (kW)", "kW", 0, 10),
        new("energy.electronics_w", "Electronics Load (W)", "W", 0, 1500),
        new("energy.care_kw", "Battery Care Power Draw (kW)", "kW", 0, 10)
    };

    public static TelemetrySensorOption? FindSensorInfo(string? key)
    {
        if (string.IsNullOrWhiteSpace(key)) return TelemetrySensors[0];
        string norm = key.Trim();
        string mapped = norm switch
        {
            "hv_soc" => "hv.soc",
            "hv_range" => "hv.range",
            "aux12v_soc" => "battery12v.soc",
            "outside_temp_c" => "outside_temp",
            "hv_ready" => "hv.ready",
            "gear_park" => "gear.park",
            "driving_restricted" => "driving.restricted",
            "climate_temp_driver" => "climate.driver_temp",
            "climate_temp_passenger" => "climate.passenger_temp",
            "climate_fan" => "climate.fan_speed",
            "volume" => "audio.volume",
            _ => norm
        };
        foreach (var opt in TelemetrySensors)
        {
            if (opt.IsHeader) continue;
            if (string.Equals(opt.Key, mapped, StringComparison.OrdinalIgnoreCase)) return opt;
        }
        return null;
    }

    public static IReadOnlyList<string> TelemetryKeys { get; } =
        TelemetrySensors.Where(s => !s.IsHeader).Select(s => s.Key).ToList();

    public static IReadOnlyList<string> FontPresets { get; } = new List<string>
    {
        "",
        "sans-serif",
        "sans-serif-light",
        "sans-serif-thin",
        "sans-serif-condensed",
        "serif",
        "monospace"
    };

    public static IReadOnlyList<string> WidgetKinds { get; } = new List<string>
    {
        "text",
        "progress_bar",
        "soc_ring",
        "icon",
        "image",
        "clock",
        "telemetry_badge",
        "switcher_status",
        "action_pill",
        "ev_hero",
        "tile",
        "page_indicator",
        "label",
        "divider",
        "spacer"
    };

    public static IReadOnlyList<string> BackgroundTypes { get; } = new List<string>
    {
        "animated_aurora",
        "animated_grid",
        "animated_mesh",
        "animated_frames",
        "animated_gif",
        "bitmap",
        "gradient",
        "solid"
    };

    public static IReadOnlyList<string> IdleEffects { get; } = new List<string>
    {
        "none",
        "pulse",
        "breathe",
        "float",
        "shimmer",
        "glow",
        "border_glow",
        "ev_reactive"
    };

    public static ThemePackModel CreateFreeformStarterPack()
    {
        return new ThemePackModel
        {
            FormatVersion = 2,
            Id = "freeform-starter",
            Name = "Custom Freeform Canvas",
            Author = "Gen5WStudio",
            Version = "1.0.0",
            Background = new BackgroundSpecModel
            {
                Type = "animated_aurora",
                PrimaryColor = "#FF070D18",
                SecondaryColor = "#FF10233F",
                AccentColor = "#FF38BDF8",
                SecondaryAccentColor = "#FF10B981",
                DimColor = "#44060B12"
            },
            Root = new LayoutNodeModel
            {
                Id = "freeform_root",
                Type = "panel",
                Layout = "freeform",
                Style = new WidgetStyleModel { Transparent = true },
                Children = new List<LayoutNodeModel>
                {
                    new()
                    {
                        Id = "ff_clock",
                        Kind = "clock",
                        Bounds = new double[] { 36, 26, 280, 96 },
                        Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 40, SubtitleSizePx = 18 }
                    },
                    new()
                    {
                        Id = "ff_hv_ring",
                        Kind = "soc_ring",
                        TelemetryKey = "hv_soc",
                        Title = "HV Battery",
                        Subtitle = "EV Screen",
                        Bounds = new double[] { 36, 146, 280, 320 },
                        Action = new ActionSpecModel { Type = "preset", Cmd = "ev" },
                        Style = new WidgetStyleModel { BgColor = "#D4111D2D", BorderColor = "#5538BDF8", AccentColor = "#FF38BDF8", CornerRadiusPx = 24, IconSizePx = 148 }
                    },
                    new()
                    {
                        Id = "ff_climate",
                        Kind = "tile",
                        Title = "Climate",
                        Subtitle = "HVAC & Defog",
                        Icon = "icons/climate.svg",
                        Bounds = new double[] { 344, 146, 360, 210 },
                        Action = new ActionSpecModel { Type = "launch_climate" },
                        Style = new WidgetStyleModel { BgColor = "#D4111D2D", BorderColor = "#5560A5FA", CornerRadiusPx = 22 }
                    },
                    new()
                    {
                        Id = "ff_navi",
                        Kind = "tile",
                        Title = "Navigation",
                        Subtitle = "Map & Guidance",
                        Icon = "icons/navi.svg",
                        Bounds = new double[] { 724, 146, 360, 210 },
                        Action = new ActionSpecModel { Type = "preset", Cmd = "navi" },
                        Style = new WidgetStyleModel { BgColor = "#D4111D2D", BorderColor = "#5534D399", CornerRadiusPx = 22 }
                    },
                    new()
                    {
                        Id = "ff_themes",
                        Kind = "action_pill",
                        Title = "ThemePacks",
                        Icon = "icons/home.svg",
                        Bounds = new double[] { 36, 610, 230, 74 },
                        Action = new ActionSpecModel { Type = "open_themes" },
                        Style = new WidgetStyleModel { BgColor = "#BB0E3B3E", BorderColor = "#8810B981", CornerRadiusPx = 16, IconSizePx = 30, TitleSizePx = 19 }
                    },
                    new()
                    {
                        Id = "ff_switcher",
                        Kind = "switcher_status",
                        Title = "SWITCHER",
                        Bounds = new double[] { 1550, 26, 330, 76 },
                        Action = new ActionSpecModel { Type = "open_switcher" },
                        Style = new WidgetStyleModel { BgColor = "#CC122840", BorderColor = "#8838BDF8", CornerRadiusPx = 16, TitleSizePx = 18, SubtitleSizePx = 13 }
                    }
                }
            }
        };
    }

    public static string SanitizePackId(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "custom-theme";
        var sb = new StringBuilder();
        bool prevDash = false;
        foreach (char ch in raw.Trim().ToLowerInvariant())
        {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'))
            {
                sb.Append(ch);
                prevDash = false;
            }
            else if (!prevDash && sb.Length > 0)
            {
                sb.Append('-');
                prevDash = true;
            }
        }
        string cleaned = sb.ToString().Trim('-');
        return string.IsNullOrEmpty(cleaned) ? "custom-theme" : cleaned;
    }

    public static ThemePackModel CreateNewPack(
        string id,
        string name,
        string author,
        string starterStyle,
        bool showSystemBar,
        ThemePackModel? cobaltTemplate = null,
        string speedUnit = "mph",
        string tempUnit = "F")
    {
        string safeId = SanitizePackId(id);
        string safeName = string.IsNullOrWhiteSpace(name) ? "Custom Theme" : name.Trim();
        string safeAuthor = string.IsNullOrWhiteSpace(author) ? "Gen5WStudio" : author.Trim();

        ThemePackModel pack = starterStyle switch
        {
            "cobalt_v2" => (cobaltTemplate ?? new ThemePackModel()).DeepClone(),
            "freeform_starter" => CreateFreeformStarterPack(),
            "clone_current" => (cobaltTemplate ?? CreateBlankFreeformPack()).DeepClone(),
            "blank_grid" => CreateBlankGridPack(),
            "blank_multipage" => CreateBlankMultiPagePack(),
            _ => CreateBlankFreeformPack()
        };

        pack.FormatVersion = 2;
        pack.Id = safeId;
        pack.Name = safeName;
        pack.Author = safeAuthor;
        pack.Version = "1.0.0";
        pack.Tokens ??= new ThemeTokensModel();
        pack.Tokens.ShowSystemBar = showSystemBar;
        pack.Tokens.SpeedUnit = string.Equals(speedUnit, "kmh", StringComparison.OrdinalIgnoreCase) ? "kmh" : "mph";
        pack.Tokens.TempUnit = string.Equals(tempUnit, "C", StringComparison.OrdinalIgnoreCase) ? "C" : "F";
        pack.Normalize();
        return pack;
    }

    private static ThemePackModel CreateBlankFreeformPack()
    {
        return new ThemePackModel
        {
            FormatVersion = 2,
            Version = "1.0.0",
            Background = new BackgroundSpecModel
            {
                Type = "gradient",
                PrimaryColor = "#FF0A0F17",
                SecondaryColor = "#FF121C2D",
                AccentColor = "#FF38BDF8",
                SecondaryAccentColor = "#FF10B981",
                DimColor = "#44060B12"
            },
            Root = new LayoutNodeModel
            {
                Id = "canvas_root",
                Type = "panel",
                Layout = "freeform",
                Style = new WidgetStyleModel { Transparent = true },
                Children = new List<LayoutNodeModel>
                {
                    new()
                    {
                        Id = "ff_themes",
                        Kind = "action_pill",
                        Title = "ThemePacks",
                        Icon = "icons/home.svg",
                        Bounds = new double[] { 1310, 24, 220, 68 },
                        Action = new ActionSpecModel { Type = "open_themes" },
                        Style = new WidgetStyleModel { BgColor = "#BB0E3B3E", BorderColor = "#8810B981", CornerRadiusPx = 16, IconSizePx = 28, TitleSizePx = 18 }
                    },
                    new()
                    {
                        Id = "ff_switcher",
                        Kind = "switcher_status",
                        Title = "SWITCHER",
                        Bounds = new double[] { 1550, 24, 330, 68 },
                        Action = new ActionSpecModel { Type = "open_switcher" },
                        Style = new WidgetStyleModel { BgColor = "#CC122840", BorderColor = "#8838BDF8", CornerRadiusPx = 16, TitleSizePx = 18, SubtitleSizePx = 13 }
                    }
                }
            }
        };
    }

    private static ThemePackModel CreateBlankGridPack()
    {
        return new ThemePackModel
        {
            FormatVersion = 2,
            Version = "1.0.0",
            Background = new BackgroundSpecModel
            {
                Type = "gradient",
                PrimaryColor = "#FF0A0F17",
                SecondaryColor = "#FF121C2D",
                AccentColor = "#FF38BDF8",
                SecondaryAccentColor = "#FF10B981",
                DimColor = "#44060B12"
            },
            Root = new LayoutNodeModel
            {
                Id = "grid_root",
                Type = "panel",
                Layout = "flex_col",
                GapPx = 14,
                PaddingPx = new[] { 24, 16, 24, 16 },
                Style = new WidgetStyleModel { Transparent = true },
                Children = new List<LayoutNodeModel>
                {
                    CreateMinimalTopBar(),
                    new()
                    {
                        Id = "main_grid",
                        Type = "panel",
                        Layout = "grid",
                        Columns = 4,
                        Rows = 2,
                        GapPx = 14,
                        PaddingPx = new[] { 10, 10, 10, 10 },
                        FlexWeight = 1.0,
                        Style = new WidgetStyleModel { BgColor = "#220D1A2B", BorderColor = "#3338BDF8", CornerRadiusPx = 22 },
                        Children = new List<LayoutNodeModel>()
                    }
                }
            }
        };
    }

    private static ThemePackModel CreateBlankMultiPagePack()
    {
        return new ThemePackModel
        {
            FormatVersion = 2,
            Version = "1.0.0",
            Background = new BackgroundSpecModel
            {
                Type = "gradient",
                PrimaryColor = "#FF0A0F17",
                SecondaryColor = "#FF121C2D",
                AccentColor = "#FF38BDF8",
                SecondaryAccentColor = "#FF10B981",
                DimColor = "#44060B12"
            },
            Root = new LayoutNodeModel
            {
                Id = "pages_root",
                Type = "panel",
                Layout = "flex_col",
                GapPx = 12,
                PaddingPx = new[] { 24, 14, 24, 14 },
                Style = new WidgetStyleModel { Transparent = true },
                Children = new List<LayoutNodeModel>
                {
                    CreateMinimalTopBar(),
                    new()
                    {
                        Id = "theme_pager",
                        Type = "panel",
                        Layout = "pager",
                        ShowDots = true,
                        FlexWeight = 1.0,
                        Style = new WidgetStyleModel { Transparent = true },
                        Children = new List<LayoutNodeModel>
                        {
                            new()
                            {
                                Id = "page_1",
                                Type = "panel",
                                Layout = "grid",
                                PageTitle = "Page 1",
                                Columns = 4,
                                Rows = 2,
                                GapPx = 14,
                                Style = new WidgetStyleModel { BgColor = "#180D1A2B", BorderColor = "#2838BDF8", CornerRadiusPx = 20 },
                                Children = new List<LayoutNodeModel>()
                            },
                            new()
                            {
                                Id = "page_2",
                                Type = "panel",
                                Layout = "grid",
                                PageTitle = "Page 2",
                                Columns = 4,
                                Rows = 2,
                                GapPx = 14,
                                Style = new WidgetStyleModel { BgColor = "#180D1A2B", BorderColor = "#2838BDF8", CornerRadiusPx = 20 },
                                Children = new List<LayoutNodeModel>()
                            }
                        }
                    }
                }
            }
        };
    }

    private static LayoutNodeModel CreateMinimalTopBar()
    {
        return new LayoutNodeModel
        {
            Id = "minimal_bar",
            Type = "panel",
            Layout = "flex_row",
            Height = 62,
            GapPx = 10,
            Style = new WidgetStyleModel { Transparent = true },
            Children = new List<LayoutNodeModel>
            {
                new()
                {
                    Id = "bar_spacer",
                    Kind = "spacer",
                    FlexWeight = 1.0
                },
                new()
                {
                    Id = "bar_themes",
                    Kind = "action_pill",
                    Title = "ThemePacks",
                    Icon = "icons/home.svg",
                    Width = 200,
                    Action = new ActionSpecModel { Type = "open_themes" },
                    Style = new WidgetStyleModel { BgColor = "#BB0E3B3E", BorderColor = "#8810B981", CornerRadiusPx = 14, IconSizePx = 24, TitleSizePx = 16 }
                },
                new()
                {
                    Id = "bar_switcher",
                    Kind = "switcher_status",
                    Title = "SWITCHER",
                    Width = 300,
                    Action = new ActionSpecModel { Type = "open_switcher" },
                    Style = new WidgetStyleModel { BgColor = "#CC122840", BorderColor = "#8838BDF8", CornerRadiusPx = 14, TitleSizePx = 16, SubtitleSizePx = 12 }
                }
            }
        };
    }

    public static LayoutNodeModel CreatePresetEvHeroPanel()
    {
        return new LayoutNodeModel
        {
            Id = "ev_panel_" + Guid.NewGuid().ToString("N")[..4],
            Type = "panel",
            Layout = "flex_col",
            Width = 520,
            GapPx = 12,
            Action = new ActionSpecModel { Type = "preset", Cmd = "ev" },
            Style = new WidgetStyleModel
            {
                BgColor = "#D60F1928",
                BgEndColor = "#D8132640",
                GradientAngle = 135,
                PressedColor = "#EE182B44",
                BorderColor = "#5538BDF8",
                BorderWidthPx = 2,
                CornerRadiusPx = 24,
                AccentColor = "#FF38BDF8",
                PaddingPx = new[] { 24, 20, 24, 20 }
            },
            Children = new List<LayoutNodeModel>
            {
                new()
                {
                    Id = "ev_hdr_" + Guid.NewGuid().ToString("N")[..4],
                    Type = "panel",
                    Layout = "flex_row",
                    Height = 52,
                    GapPx = 12,
                    Style = new WidgetStyleModel { Transparent = true },
                    Children = new List<LayoutNodeModel>
                    {
                        new()
                        {
                            Id = "ev_ic_" + Guid.NewGuid().ToString("N")[..4],
                            Kind = "icon",
                            Icon = "icons/ev.svg",
                            Width = 44,
                            Height = 44,
                            Style = new WidgetStyleModel { Transparent = true, IconSizePx = 42 }
                        },
                        new()
                        {
                            Id = "ev_titles_" + Guid.NewGuid().ToString("N")[..4],
                            Type = "panel",
                            Layout = "flex_col",
                            FlexWeight = 1,
                            GapPx = 2,
                            Style = new WidgetStyleModel { Transparent = true },
                            Children = new List<LayoutNodeModel>
                            {
                                new()
                                {
                                    Id = "ev_lbl_" + Guid.NewGuid().ToString("N")[..4],
                                    Kind = "text",
                                    Title = "EV TELEMETRY",
                                    Style = new WidgetStyleModel { Transparent = true, FontWeight = "bold", TitleSizePx = 20, TextColor = "#FFF1F5F9" }
                                },
                                new()
                                {
                                    Id = "ev_sub_" + Guid.NewGuid().ToString("N")[..4],
                                    Kind = "text",
                                    TelemetryKey = "charging",
                                    Title = "{hv.ready} • {charging}",
                                    Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 14, TextColor = "#FF38BDF8" }
                                }
                            }
                        }
                    }
                },
                new()
                {
                    Id = "ev_soc_txt_" + Guid.NewGuid().ToString("N")[..4],
                    Kind = "text",
                    TelemetryKey = "hv.soc",
                    Title = "{value}",
                    Style = new WidgetStyleModel { Transparent = true, FontFamily = "sans-serif-condensed", FontWeight = "bold", TitleSizePx = 80, TextColor = "#FFFFFFFF" }
                },
                new()
                {
                    Id = "ev_soc_bar_" + Guid.NewGuid().ToString("N")[..4],
                    Kind = "progress_bar",
                    TelemetryKey = "hv.soc",
                    MinValue = 0,
                    MaxValue = 100,
                    Height = 22,
                    Style = new WidgetStyleModel { BgColor = "#55293B52", AccentColor = "#FF38BDF8", BgEndColor = "#FF10B981", CornerRadiusPx = 11 }
                },
                new()
                {
                    Id = "ev_metrics_" + Guid.NewGuid().ToString("N")[..4],
                    Type = "panel",
                    Layout = "flex_row",
                    Height = 36,
                    GapPx = 14,
                    Style = new WidgetStyleModel { Transparent = true },
                    Children = new List<LayoutNodeModel>
                    {
                        new()
                        {
                            Id = "ev_12v_" + Guid.NewGuid().ToString("N")[..4],
                            Kind = "text",
                            TelemetryKey = "battery12v.soc",
                            Title = "12V: {value}",
                            FlexWeight = 1,
                            Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 16, TextColor = "#FF94A3B8" }
                        },
                        new()
                        {
                            Id = "ev_temp_" + Guid.NewGuid().ToString("N")[..4],
                            Kind = "text",
                            TelemetryKey = "hv.battery_temp_min",
                            Title = "Pack: {value}",
                            FlexWeight = 1,
                            Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 16, TextColor = "#FF94A3B8" }
                        },
                        new()
                        {
                            Id = "ev_ext_" + Guid.NewGuid().ToString("N")[..4],
                            Kind = "text",
                            TelemetryKey = "outside_temp_f",
                            Title = "Ext: {value}",
                            FlexWeight = 1,
                            Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 16, TextColor = "#FF94A3B8" }
                        }
                    }
                }
            }
        };
    }

    public static LayoutNodeModel CreatePresetActionTilePanel(
        string title = "Custom Tile",
        string subtitle = "Entire panel is tap action",
        string icon = "icons/apps.svg",
        string actionType = "preset",
        string cmd = "ev")
    {
        return new LayoutNodeModel
        {
            Id = "tile_panel_" + Guid.NewGuid().ToString("N")[..4],
            Type = "panel",
            Layout = "flex_col",
            GapPx = 8,
            Action = new ActionSpecModel { Type = actionType, Cmd = cmd },
            Style = new WidgetStyleModel
            {
                BgColor = "#D4111D2D",
                BgEndColor = "#D4162840",
                GradientAngle = 135,
                PressedColor = "#EE1C3352",
                BorderColor = "#4438BDF8",
                BorderWidthPx = 2,
                CornerRadiusPx = 22,
                AccentColor = "#FF38BDF8",
                PaddingPx = new[] { 22, 18, 22, 18 }
            },
            Children = new List<LayoutNodeModel>
            {
                new()
                {
                    Id = "tile_ic_" + Guid.NewGuid().ToString("N")[..4],
                    Kind = "icon",
                    Icon = icon,
                    Width = 54,
                    Height = 54,
                    Style = new WidgetStyleModel { Transparent = true, IconSizePx = 54 }
                },
                new()
                {
                    Id = "tile_t_" + Guid.NewGuid().ToString("N")[..4],
                    Kind = "text",
                    Title = title,
                    Style = new WidgetStyleModel { Transparent = true, FontWeight = "bold", TitleSizePx = 25, TextColor = "#FFF1F5F9" }
                },
                new()
                {
                    Id = "tile_s_" + Guid.NewGuid().ToString("N")[..4],
                    Kind = "text",
                    Title = subtitle,
                    Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 15, TextColor = "#FF94A3B8" }
                }
            }
        };
    }

    public static LayoutNodeModel CreatePresetActionPillPanel(
        string title = "Quick Action",
        string subtitle = "",
        string icon = "icons/home.svg",
        string actionType = "open_themes",
        string cmd = "")
    {
        var textChildren = new List<LayoutNodeModel>
        {
            new()
            {
                Id = "pill_t_" + Guid.NewGuid().ToString("N")[..4],
                Kind = "text",
                Title = title,
                Style = new WidgetStyleModel { Transparent = true, FontWeight = "bold", TitleSizePx = 18, TextColor = "#FFF1F5F9" }
            }
        };
        if (!string.IsNullOrWhiteSpace(subtitle))
        {
            textChildren.Add(new LayoutNodeModel
            {
                Id = "pill_s_" + Guid.NewGuid().ToString("N")[..4],
                Kind = "text",
                Title = subtitle,
                Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 12, TextColor = "#FF38BDF8" }
            });
        }

        return new LayoutNodeModel
        {
            Id = "pill_panel_" + Guid.NewGuid().ToString("N")[..4],
            Type = "panel",
            Layout = "flex_row",
            FlexWeight = 1,
            GapPx = 10,
            VerticalAlign = "center",
            Action = new ActionSpecModel { Type = actionType, Cmd = cmd },
            Style = new WidgetStyleModel
            {
                BgColor = "#BB10243C",
                BorderColor = "#6638BDF8",
                BorderWidthPx = 2,
                CornerRadiusPx = 16,
                AccentColor = "#FF38BDF8",
                PaddingPx = new[] { 14, 6, 14, 6 }
            },
            Children = new List<LayoutNodeModel>
            {
                new()
                {
                    Id = "pill_ic_" + Guid.NewGuid().ToString("N")[..4],
                    Kind = "icon",
                    Icon = icon,
                    Width = 28,
                    Height = 28,
                    VerticalAlign = "center",
                    Style = new WidgetStyleModel { Transparent = true, IconSizePx = 28 }
                },
                new()
                {
                    Id = "pill_txt_" + Guid.NewGuid().ToString("N")[..4],
                    Type = "panel",
                    Layout = "flex_col",
                    FlexWeight = 1,
                    VerticalAlign = "center",
                    Style = new WidgetStyleModel { Transparent = true },
                    Children = textChildren
                }
            }
        };
    }

    public static LayoutNodeModel BreakApartLegacyWidget(LayoutNodeModel legacy)
    {
        string kind = (legacy.Kind ?? "tile").ToLowerInvariant();
        LayoutNodeModel converted = kind switch
        {
            "ev_hero" => CreatePresetEvHeroPanel(),
            "action_pill" => CreatePresetActionPillPanel(legacy.Title ?? "Action", legacy.Subtitle ?? "", legacy.Icon ?? "icons/home.svg"),
            "soc_ring" or "telemetry_gauge" => new LayoutNodeModel
            {
                Type = "panel",
                Layout = "flex_col",
                GapPx = 8,
                HorizontalAlign = "center",
                Style = new WidgetStyleModel { PaddingPx = new[] { 16, 14, 16, 14 } },
                Children = new List<LayoutNodeModel>
                {
                    new()
                    {
                        Id = legacy.Id + "_ring",
                        Kind = "soc_ring",
                        TelemetryKey = legacy.TelemetryKey ?? "hv.soc",
                        Unit = legacy.Unit,
                        MinValue = legacy.MinValue,
                        MaxValue = legacy.MaxValue,
                        Style = new WidgetStyleModel { Transparent = true, IconSizePx = legacy.Style?.IconSizePx ?? 96, AccentColor = legacy.Style?.AccentColor ?? "#FF38BDF8" }
                    },
                    new()
                    {
                        Id = legacy.Id + "_title",
                        Kind = "text",
                        Title = legacy.Title ?? "Sensor",
                        Style = new WidgetStyleModel { Transparent = true, FontWeight = "bold", TitleSizePx = legacy.Style?.TitleSizePx ?? 18, TextAlign = "center", TextColor = legacy.Style?.TextColor ?? "#FFF1F5F9" }
                    }
                }
            },
            _ => CreatePresetActionTilePanel(legacy.Title ?? "Tile", legacy.Subtitle ?? "", legacy.Icon ?? "icons/apps.svg")
        };

        converted.Id = legacy.Id;
        converted.Width = legacy.Width;
        converted.Height = legacy.Height;
        converted.FlexWeight = legacy.FlexWeight;
        converted.ColSpan = legacy.ColSpan;
        converted.RowSpan = legacy.RowSpan;
        converted.Bounds = legacy.Bounds != null ? (double[])legacy.Bounds.Clone() : null;
        converted.MarginPx = legacy.MarginPx != null ? (int[])legacy.MarginPx.Clone() : null;
        converted.Action = legacy.Action ?? converted.Action;
        converted.Animation = legacy.Animation;

        if (legacy.Style != null)
        {
            converted.Style ??= new WidgetStyleModel();
            if (!string.IsNullOrEmpty(legacy.Style.BgColor)) converted.Style.BgColor = legacy.Style.BgColor;
            if (!string.IsNullOrEmpty(legacy.Style.BgEndColor)) converted.Style.BgEndColor = legacy.Style.BgEndColor;
            if (legacy.Style.GradientAngle != null) converted.Style.GradientAngle = legacy.Style.GradientAngle;
            if (!string.IsNullOrEmpty(legacy.Style.PressedColor)) converted.Style.PressedColor = legacy.Style.PressedColor;
            if (!string.IsNullOrEmpty(legacy.Style.BorderColor)) converted.Style.BorderColor = legacy.Style.BorderColor;
            if (legacy.Style.BorderWidthPx != null) converted.Style.BorderWidthPx = legacy.Style.BorderWidthPx;
            if (legacy.Style.CornerRadiusPx != null) converted.Style.CornerRadiusPx = legacy.Style.CornerRadiusPx;
            if (!string.IsNullOrEmpty(legacy.Style.AccentColor)) converted.Style.AccentColor = legacy.Style.AccentColor;
            if (legacy.Style.Transparent != null) converted.Style.Transparent = legacy.Style.Transparent;
            if (legacy.Style.Opacity != null) converted.Style.Opacity = legacy.Style.Opacity;
            if (!string.IsNullOrEmpty(legacy.Style.BgBitmapAsset)) converted.Style.BgBitmapAsset = legacy.Style.BgBitmapAsset;
            if (!string.IsNullOrEmpty(legacy.Style.BgAnimAsset)) converted.Style.BgAnimAsset = legacy.Style.BgAnimAsset;
        }
        converted.NormalizeRecursive();
        return converted;
    }
}
