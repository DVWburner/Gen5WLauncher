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
    public bool IsHeader { get; }

    public ActionPresetOption(string display, string actionType, string cmd,
        string defaultIcon, string defaultTitle, string defaultSubtitle, bool isHeader = false)
    {
        Display = display;
        ActionType = actionType;
        Cmd = cmd;
        DefaultIcon = defaultIcon;
        DefaultTitle = defaultTitle;
        DefaultSubtitle = defaultSubtitle;
        IsHeader = isHeader;
    }

    public static ActionPresetOption Category(string title) =>
        new($"\u2500\u2500\u2500\u2500  {title}  \u2500\u2500\u2500\u2500", "", "", "", "", "", isHeader: true);

    public override string ToString() => Display;
}

public static class PresetCatalog
{
    public const string StudioVersion = "0.3.4";
    public const string DefaultMinAppVersion = "0.3.4";

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
        new("Jump To Page", "goto_page", "next", "icons/home.svg", "Next Page", "Workspace Switch")
    };

    public static IReadOnlyList<string> BuiltInIcons { get; } = new List<string>
    {
        "icons/ev.svg",
        "icons/climate.svg",
        "icons/projection.svg",
        "icons/radio.svg",
        "icons/media.svg",
        "icons/apps.svg",
        "icons/navi.svg",
        "icons/settings.svg",
        "icons/power.svg",
        "icons/home.svg",
        "icons/stock.svg",
        "icons/store.svg"
    };

    public sealed record TelemetrySensorOption(
        string Key,
        string Display,
        string DefaultUnit,
        double DefaultMin,
        double DefaultMax)
    {
        public string DropdownLabel => string.IsNullOrEmpty(Key) ? Display : $"{Key} \u2014 {Display}";
        public override string ToString() => Key;
    }

    public static IReadOnlyList<TelemetrySensorOption> TelemetrySensors { get; } = new List<TelemetrySensorOption>
    {
        new("", "(None — Static Text / Value)", "", 0, 100),
        new("hv.soc", "HV Battery State of Charge (%)", "%", 0, 100),
        new("battery12v.soc", "12V Aux Battery State of Charge (%)", "%", 0, 100),
        new("hv.battery_temp_min", "HV Pack Min Cell Temp (Uses Theme Unit)", "", -20, 60),
        new("hv.battery_temp_min_c", "HV Pack Min Cell Temp (Always °C)", "°C", -20, 60),
        new("hv.battery_temp_min_f", "HV Pack Min Cell Temp (Always °F)", "°F", 0, 140),
        new("hv.health", "HV Battery Health Index", "", 0, 100),
        new("outside_temp_f", "Outside Ambient Temp (Always °F)", "°F", -20, 120),
        new("outside_temp", "Outside Ambient Temp (Always °C)", "°C", -30, 50),
        new("speed", "Vehicle Speed (Uses Theme Unit)", "", 0, 180),
        new("speed_mph", "Vehicle Speed (Always mph)", "mph", 0, 120),
        new("speed_kmh", "Vehicle Speed (Always km/h)", "km/h", 0, 180),
        new("odometer", "Odometer (Uses Theme Unit)", "", 0, 300000),
        new("odometer_mi", "Odometer (Always mi)", "mi", 0, 200000),
        new("odometer_km", "Odometer (Always km)", "km", 0, 300000),
        new("gear_speed", "Gear / Speed Combo (Uses Theme Unit)", "", 0, 180),
        new("gear.park", "Park Gear Status (PARK / DRIVE)", "", 0, 1),
        new("parking_brake", "Electronic Parking Brake", "", 0, 1),
        new("charging", "Charging Status (CHARGING / IDLE)", "", 0, 1),
        new("hv.ready", "EV Ready Status (READY / STANDBY)", "", 0, 1),
        new("hv.conditioning", "Battery Conditioning Setting", "", 0, 2),
        new("hv.conditioning_active", "Battery Conditioning Active", "", 0, 1),
        new("conditioning.state", "Conditioning Request State", "", 0, 1),
        new("moving", "Vehicle Moving (0/1)", "", 0, 1),
        new("driving.restricted", "Driving Restriction Active", "", 0, 1),
        new("steering.angle", "Steering Wheel Angle (°)", "°", -540, 540),
        new("pedal.accel", "Accelerator Pedal (raw)", "", 0, 100),
        new("brake.pressure", "Brake Cylinder Pressure (raw)", "", 0, 100),
        new("wheel_heat", "Steering Wheel Heat (ON/OFF)", "", 0, 1),
        new("defogger", "Rear Defogger (ON/OFF)", "", 0, 1),
        new("seat_heat.driver", "Driver Seat Heat (0-3)", "", 0, 3),
        new("seat_heat.passenger", "Passenger Seat Heat (0-3)", "", 0, 3),
        new("seat_heat.rear_left", "Rear Left Seat Heat (0-3)", "", 0, 3),
        new("seat_heat.rear_right", "Rear Right Seat Heat (0-3)", "", 0, 3),
        new("seat_vent.driver", "Driver Seat Vent (0-3)", "", 0, 3),
        new("seat_vent.passenger", "Passenger Seat Vent (0-3)", "", 0, 3),
        new("battery12v.saver", "Aux 12V Battery Saver Flag", "", 0, 1)
    };

    public static TelemetrySensorOption? FindSensorInfo(string? key)
    {
        if (string.IsNullOrWhiteSpace(key)) return TelemetrySensors[0];
        string norm = key.Trim();
        string mapped = norm switch
        {
            "hv_soc" => "hv.soc",
            "aux12v_soc" => "battery12v.soc",
            "outside_temp_c" => "outside_temp",
            "hv_ready" => "hv.ready",
            "gear_park" => "gear.park",
            "driving_restricted" => "driving.restricted",
            _ => norm
        };
        foreach (var opt in TelemetrySensors)
        {
            if (string.Equals(opt.Key, mapped, StringComparison.OrdinalIgnoreCase)) return opt;
        }
        return null;
    }

    public static IReadOnlyList<string> TelemetryKeys { get; } = TelemetrySensors.Select(s => s.Key).ToList();

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
