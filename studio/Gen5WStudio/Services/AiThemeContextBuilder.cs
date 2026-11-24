using System;
using System.Globalization;
using System.Linq;
using System.Text;
using Gen5WStudio.Models;

namespace Gen5WStudio.Services;

/// <summary>
/// Dynamically generates a copy/pastable AI Agent Theme-Building Specification from live
/// <see cref="PresetCatalog"/>, <see cref="ThemePackModel"/>, and <see cref="ThemePackageService"/> rules.
/// Because catalogs, sensors, actions, icons, widget kinds, and versions are read directly from
/// <see cref="PresetCatalog"/> at runtime, this prompt stays automatically synchronized whenever
/// Studio is updated.
/// </summary>
public static class AiThemeContextBuilder
{
    public const string ExampleJsonStartMarker = "<!-- EXAMPLE_LAYOUT_JSON_START -->";
    public const string ExampleJsonEndMarker = "<!-- EXAMPLE_LAYOUT_JSON_END -->";

    public static string BuildPrompt(ThemePackModel? activeTheme = null, bool includeActiveThemeJson = false)
    {
        var sb = new StringBuilder(32768);

        sb.AppendLine($"# Gen5W Launcher & Gen5W Studio — AI Theme Authoring Context (Studio v{PresetCatalog.StudioVersion})");
        sb.AppendLine();
        sb.AppendLine("You are building a **ThemePack v2** theme (`layout.json` + optional assets) for **Gen5WLauncher** and **Gen5W Studio** on Hyundai / Kia / Genesis **Gen5W 12.3\" widescreen head units** (`1920x720`, Android 4.4.2 KitKat API 19).");
        sb.AppendLine("Follow every schema, file-structure, security, and rendering rule below strictly so the generated theme opens cleanly in **Gen5W Studio** AND runs at 60 fps on the car head unit without errors.");
        sb.AppendLine();

        // 1. Display & Viewport Rules
        sb.AppendLine("## 1. Display Geometry, Viewport & Theme Tokens");
        sb.AppendLine("- **Reference Canvas:** Always author coordinates and font/padding sizes against `referenceWidth: 1920` and `referenceHeight: 720`.");
        sb.AppendLine("- **OEM Top Status Bar (`tokens.showSystemBar`):**");
        sb.AppendLine("  - `true`: Keeps the stock 90px Hyundai/Kia status bar visible at the top; the launcher viewport is `1920x630` and scales vertical layout metrics by `scaleY = 0.875` (`630 / 720`).");
        sb.AppendLine("  - `false`: Fullscreen `1920x720` (`1.0x` scale). Include a `clock` widget if hiding the OEM status bar.");
        sb.AppendLine("- **Units & Theme Tokens (`tokens` object):**");
        sb.AppendLine("  - `speedUnit`: `\"mph\"` (miles / mph) or `\"kmh\"` (km / km/h).");
        sb.AppendLine("  - `tempUnit`: `\"F\"` (°F) or `\"C\"` (°C).");
        sb.AppendLine("  - `soundMode`: `\"system\"`, `\"soft\"`, or `\"off\"`.");
        sb.AppendLine("  - `cornerRadiusPx`: Default card corner radius in px (e.g. `18`–`24`) used whenever a node omits `style.cornerRadiusPx`.");
        sb.AppendLine("  - `accentColor`, `okColor`, `warnColor`, `dangerColor`: `#AARRGGBB` or `#RRGGBB` hex colors.");
        sb.AppendLine("- **Color Format Rule (CRITICAL):** Every color string in `layout.json` MUST be `#RRGGBB` or `#AARRGGBB` hex (note Android alpha-first order: `#80112233` = 50% alpha, `R=11, G=22, B=33`). NEVER use CSS `rgba(...)`, `hsl(...)`, `#RGBA`, or named colors.");
        sb.AppendLine();

        // 2. Folder Layouts & Security Rules
        sb.AppendLine("## 2. Folder Layouts, USB Paths & Package Security Rules");
        sb.AppendLine("- **Option A — Single Theme Folder (open directly in Studio or zip for Theme Store):**");
        sb.AppendLine("  ```text");
        sb.AppendLine("  my-theme/");
        sb.AppendLine("  ├── layout.json              # REQUIRED (UTF-8 without BOM, LF line endings)");
        sb.AppendLine("  ├── thumbnail.png            # Optional 960x360 preview (Studio auto-captures on export/upload)");
        sb.AppendLine("  ├── icons/                   # Optional SVG vector icons (*.svg)");
        sb.AppendLine("  ├── img/                     # Optional element images (*.png, *.jpg, *.jpeg, *.webp)");
        sb.AppendLine("  ├── media/                   # Optional static wallpapers or GIFs (*.jpg, *.png, *.gif)");
        sb.AppendLine("  ├── fonts/                   # Optional custom fonts (*.ttf, *.otf)");
        sb.AppendLine("  └── anim/<slot_name>/        # Optional animated frame sequence");
        sb.AppendLine("      ├── frames.json          # {\"fps\": 16, \"frames\": [\"f_0001.jpg\", \"f_0002.jpg\", ...]}");
        sb.AppendLine("      └── f_0001.jpg ...");
        sb.AppendLine("  ```");
        sb.AppendLine("- **Option B — Multi-Theme Pack Folder (Studio Pack or Car FAT32 USB Drive):**");
        sb.AppendLine("  - Local Pack Folder: `<PackFolder>/packs/<theme-id>/layout.json` (each `<theme-id>` subfolder has its own `layout.json` and assets).");
        sb.AppendLine("  - Car USB Drive (installed / selectable themes): `<USB>/Gen5WLauncher/packs/<theme-id>/layout.json`.");
        sb.AppendLine("  - Car USB Live Preview Slot: `<USB>/Gen5WLauncher/pack/layout.json`.");
        sb.AppendLine("- **Theme ID Sanitization:** `id` must be lowercase `[a-z0-9_-]` (max 48 chars), matching its folder name in multi-theme packs.");
        sb.AppendLine("- **Valid Categories (`category`):** " + string.Join(", ", PresetCatalog.ThemeCategories.Select(c => $"`\"{c}\"`")));
        sb.AppendLine($"- **Recommended Compatibility Fields:** `\"formatVersion\": 2`, `\"minAppVersion\": \"{PresetCatalog.DefaultMinAppVersion}\"`, `\"compatibleVersions\": [\"{PresetCatalog.DefaultMinAppVersion}+\"]`.");
        sb.AppendLine("- **Strict Security Validator (`ThemePackageService` / `SafeThemeZipExtractor`):**");
        sb.AppendLine("  - Allowed file extensions ONLY: `.json`, `.svg`, `.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`, `.ttf`, `.otf`.");
        sb.AppendLine("  - `.json` files are ONLY allowed at root `layout.json`, root `manifest.json`, and `anim/<slot>/frames.json`.");
        sb.AppendLine("  - Gen5W Studio automatically packages **only** the asset files actively referenced by `layout.json` (and the active `background.type`) when building `.zip` bundles or exporting to USB.");
        sb.AppendLine("  - NEVER include `.html`, `.js`, `.sh`, `.ps1`, `.exe`, `.apk`, `.dex`, hidden OS files, symlinks, or `..` path segments.");
        sb.AppendLine("  - **KitKat Custom SVG Rules (`MiniSvgDrawable`):** Custom SVGs in `icons/*.svg` must be clean `<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">` using `<path>`, `<circle>`, `<rect>`, `<line>`, `<polyline>`, `<polygon>` (path commands `M,L,H,V,C,S,Q,T,A,Z`; `fill`, `stroke`, `stroke-width`, `stroke-linecap`, `stroke-linejoin`, `opacity`). NEVER use `<script>`, `on*` event attributes, external `xlink:href`, `<foreignObject>`, CSS `<style>` classes, `<filter>`, `<mask>`, or `<text>`.");
        sb.AppendLine();

        // 3. Backgrounds (Static, Procedural & Animated)
        sb.AppendLine("## 3. Backgrounds — Procedural Shaders, Static Wallpapers & Animated Loops");
        sb.AppendLine("Supported `background.type` values: " + string.Join(", ", PresetCatalog.BackgroundTypes.Select(b => $"`\"{b}\"`")));
        sb.AppendLine();
        sb.AppendLine("```json");
        sb.AppendLine("\"background\": {");
        sb.AppendLine("  \"type\": \"animated_aurora\",");
        sb.AppendLine("  \"primaryColor\": \"#FF060B14\",");
        sb.AppendLine("  \"secondaryColor\": \"#FF0E1F38\",");
        sb.AppendLine("  \"accentColor\": \"#FF195F91\",");
        sb.AppendLine("  \"secondaryAccentColor\": \"#FF0E5A68\",");
        sb.AppendLine("  \"dimColor\": \"#48060B12\",");
        sb.AppendLine("  \"bitmapAsset\": \"media/wallpaper.jpg\",");
        sb.AppendLine("  \"framesDir\": \"anim/backdrop\",");
        sb.AppendLine("  \"gifAsset\": \"media/loop.gif\",");
        sb.AppendLine("  \"fps\": 16,");
        sb.AppendLine("  \"speed\": 1.0");
        sb.AppendLine("}");
        sb.AppendLine("```");
        sb.AppendLine("- **Zero-Asset Animated Shaders (`animated_aurora`, `animated_mesh`, `animated_grid`):** Render GPU/Canvas procedural motion using `primaryColor`, `secondaryColor`, `accentColor`, `secondaryAccentColor`, `dimColor`, and `speed` (`0.2`–`3.0`). Require zero external image files.");
        sb.AppendLine("- **Static Wallpaper (`bitmap`) / Gradient (`gradient`) / Solid (`solid`):**");
        sb.AppendLine("  - `bitmap`: Set `\"type\": \"bitmap\"` and `\"bitmapAsset\": \"media/wallpaper.jpg\"` (`1920x720` JPEG/PNG). Optional `dimColor` (`#48060B12` or `#00000000` for no darkening).");
        sb.AppendLine("  - `gradient` / `solid`: Uses `primaryColor` -> `secondaryColor` (plus `accentColor` glow).");
        sb.AppendLine("- **Animated Video / Frame-Sequence Wallpaper (`animated_frames` — RECOMMENDED for custom video loops on KitKat):**");
        sb.AppendLine("  1. Set `\"type\": \"animated_frames\"`, `\"framesDir\": \"anim/backdrop\"`, and `\"fps\": 16` (`8`–`20` fps recommended).");
        sb.AppendLine("  2. Place extracted JPEG frames (`960x360` or `1280x480`, JPEG quality ~80–85, 4–10 seconds, max ~120 frames, total < 12 MB) inside `anim/backdrop/f_0001.jpg`, `f_0002.jpg`, etc.");
        sb.AppendLine("  3. Create `anim/backdrop/frames.json` listing the exact frame filenames in playback order:");
        sb.AppendLine("     ```json");
        sb.AppendLine("     { \"fps\": 16, \"frames\": [\"f_0001.jpg\", \"f_0002.jpg\", \"f_0003.jpg\"] }");
        sb.AppendLine("     ```");
        sb.AppendLine("  4. Example `ffmpeg` command to convert any `.mp4`/`.mov`/`.gif` into a KitKat-safe `960x360` sequence (or use Gen5W Studio's built-in **Import Video / GIF Wallpaper** converter):");
        sb.AppendLine("     ```bash");
        sb.AppendLine("     mkdir -p anim/backdrop && ffmpeg -y -i input.mp4 -t 6 -vf \"fps=16,scale=960:360:force_original_aspect_ratio=increase,crop=960:360\" -q:v 4 anim/backdrop/f_%04d.jpg");
        sb.AppendLine("     ```");
        sb.AppendLine("- **Animated GIF Wallpaper (`animated_gif`):** Set `\"type\": \"animated_gif\"`, `\"gifAsset\": \"media/loop.gif\"` (software-decoded safely on Android 4.4; keep resolution <= `960x360` and size <= 6 MB).");
        sb.AppendLine("- **Animated Per-Card / Per-Tile Backgrounds:** Any panel or `tile` can also play its own animated loop via `style.bgAnimAsset` (`\"anim/card_wave\"` or `\"media/card.gif\"`), `style.bgAnimFps` (`15`), and `style.bgDimAlpha` (`0`–`230`, default `95`), or show a static card image via `style.bgBitmapAsset` (`\"img/card.jpg\"`).");
        sb.AppendLine();

        // 4. Layout Containers & Composable Cards
        sb.AppendLine("## 4. Layout Containers (`type: \"panel\"`) & Composable Card Architecture");
        sb.AppendLine("The `root` node and any grouping container use `\"type\": \"panel\"` with one of 5 `layout` modes:");
        sb.AppendLine("- **`\"flex_col\"` / `\"flex_row\"`:** Vertical or horizontal flex stack.");
        sb.AppendLine("  - Uses `gapPx` (spacing between children in px) and `paddingPx: [left, top, right, bottom]`.");
        sb.AppendLine("  - Children with `\"flexWeight\": 1.0` (or any positive weight) share remaining main-axis space proportionally; children without `flexWeight` use explicit `width` / `height` (in `1920x720` px) or intrinsic size.");
        sb.AppendLine("  - Cross-axis positioning on children: `horizontalAlign` (`\"left\" | \"center\" | \"right\"`) in `flex_col`, `verticalAlign` (`\"top\" | \"center\" | \"bottom\"`) in `flex_row`.");
        sb.AppendLine("- **`\"grid\"`:** Uniform row/column grid.");
        sb.AppendLine("  - Panel properties: `columns` (default `3`), `rows` (default `2`), `gapPx` (default `16`), `paddingPx: [l, t, r, b]`.");
        sb.AppendLine("  - Child span properties: `colSpan` (`1..columns`), `rowSpan` (`1..rows`). Occupied multi-row/multi-column cells automatically advance subsequent siblings.");
        sb.AppendLine("- **`\"freeform\"`:** Exact pixel-positioned canvas (`1920x720` coordinate space relative to the freeform panel's inner box).");
        sb.AppendLine("  - Every child inside a `freeform` panel MUST specify `\"bounds\": [x, y, width, height]`. Children render in array order (later children layer on top of earlier children — great for layered HUD frames, wallpapers, and custom gauges).");
        sb.AppendLine("- **`\"pager\"`:** Swipeable horizontal multi-page workspace.");
        sb.AppendLine("  - Direct `children` of a `pager` MUST be `panel` nodes (e.g. `grid`, `flex_row`, or `freeform`), each with a `\"pageTitle\": \"Drive\"` label.");
        sb.AppendLine("  - Built-in Page Bar: controlled on the `pager` node via `\"showDots\": true` (or `false` to hide) and optional `\"indicator\": { ... }` styling.");
        sb.AppendLine("  - Standalone Page Bar: set `\"showDots\": false` on the `pager` and place a `{\"id\": \"page_bar\", \"kind\": \"page_indicator\", \"indicator\": { ... }}` widget in an outer `flex_col` / `flex_row` / `freeform` panel so it stays anchored anywhere on screen.");
        sb.AppendLine("- **Composable Cards (Whole-Panel Tap Target — Studio Best Practice):**");
        sb.AppendLine("  - Any `panel` with `style.transparent` omitted or `false` renders a styled card (`bgColor`, `bgEndColor`, `gradientAngle`, `borderColor`, `borderWidthPx`, `cornerRadiusPx`, `bgAnimAsset`).");
        sb.AppendLine("  - Setting `\"action\": { ... }` on that `panel` makes the **entire card** interactive (press scale + ripple/highlight), while you freely compose `text`, `progress_bar`, `soc_ring`, and `icon` children inside it (leave child `action` omitted or `{\"type\": \"none\"}`).");
        sb.AppendLine();

        // 5. Widget Kinds, Style, Indicator & Animation
        sb.AppendLine("## 5. Widget Kinds (`kind`), `style`, `indicator`, & `animation` Schema");
        sb.AppendLine("Supported leaf widget `kind` values (omit `type` or set `\"type\": \"widget\"`): " + string.Join(", ", PresetCatalog.WidgetKinds.Select(k => $"`\"{k}\"`")));
        sb.AppendLine("- `\"text\"` (alias `\"sensor\"`, `\"label\"`): Single or two-line typography/telemetry readout (`title`, optional `subtitle`, `telemetryKey`, `unit`, `wrapText`).");
        sb.AppendLine("- `\"progress_bar\"`: Horizontal or vertical live telemetry bar (`telemetryKey`, `minValue`, `maxValue`, `orientation`: `\"horizontal\" | \"vertical\"`, `showText`: `true | false`, `style.bgColor` track, `style.accentColor` -> `style.bgEndColor` fill gradient).");
        sb.AppendLine("- `\"soc_ring\"`: Circular arc gauge with live center value (`telemetryKey`, `minValue`, `maxValue`, `title`, `subtitle`, `style.iconSizePx` = ring diameter in px, default `88`).");
        sb.AppendLine("- `\"icon\"` / `\"image\"`: Renders SVG (`icon`: `\"icons/ev.svg\"` with optional `style.iconTint`) or raster image (`bitmapAsset`: `\"img/photo.png\"`, `scaleType`: `\"fit\" | \"cover\"`, `style.iconSizePx`).");
        sb.AppendLine("- `\"clock\"`: Live time (`h:mm a`) + uppercase date (`EEE, MMM d`); customizable via `style.titleSizePx`, `style.subtitleSizePx`, `style.textColor`, `style.accentColor`, `style.fontFamily`.");
        sb.AppendLine("- `\"telemetry_badge\"`: Compact top-bar sensor pill (uppercase `title` label above live `telemetryKey` value).");
        sb.AppendLine("- `\"tile\"`: Ready-made action/telemetry card with `icon`, `title`, `subtitle`, optional top-right `badgeText` pill, and `action`.");
        sb.AppendLine("- `\"action_pill\"`: Compact horizontal capsule button (`icon` + `title` + optional `subtitle`, default `cornerRadiusPx: 999`).");
        sb.AppendLine("- `\"ev_hero\"`: Large preset EV SOC + range + status card (or build a custom `panel` for full layout control).");
        sb.AppendLine("- `\"switcher_status\"`: Displays active default-home & 5-layer failsafe status (pair with `\"action\": {\"type\": \"open_switcher\"}`).");
        sb.AppendLine("- `\"page_indicator\"`: Standalone interactive page indicator bar linked to the workspace `pager`.");
        sb.AppendLine("- `\"divider\"`: 1–2px visual line using `style.borderColor`.");
        sb.AppendLine("- `\"spacer\"`: Empty flex spacer (typically `\"flexWeight\": 1` inside `flex_row` or `flex_col`).");
        sb.AppendLine();
        sb.AppendLine("### `style` Object Properties (all optional)");
        sb.AppendLine("```json");
        sb.AppendLine("\"style\": {");
        sb.AppendLine("  \"transparent\": false,");
        sb.AppendLine("  \"bgColor\": \"#D4111D2D\",");
        sb.AppendLine("  \"bgEndColor\": \"#D4162840\",");
        sb.AppendLine("  \"gradientAngle\": 135,");
        sb.AppendLine("  \"pressedColor\": \"#EE1E3A5F\",");
        sb.AppendLine("  \"borderColor\": \"#4438BDF8\",");
        sb.AppendLine("  \"borderWidthPx\": 2,");
        sb.AppendLine("  \"accentColor\": \"#FF38BDF8\",");
        sb.AppendLine("  \"textColor\": \"#FFF1F5F9\",");
        sb.AppendLine("  \"subTextColor\": \"#FF94A3B8\",");
        sb.AppendLine("  \"iconTint\": \"#FF38BDF8\",");
        sb.AppendLine("  \"cornerRadiusPx\": 20,");
        sb.AppendLine("  \"iconSizePx\": 48,");
        sb.AppendLine("  \"titleSizePx\": 24,");
        sb.AppendLine("  \"subtitleSizePx\": 15,");
        sb.AppendLine("  \"textAlign\": \"left\",");
        sb.AppendLine("  \"verticalAlign\": \"center\",");
        sb.AppendLine("  \"fontFamily\": \"sans-serif\",");
        sb.AppendLine("  \"fontWeight\": \"bold\",");
        sb.AppendLine("  \"fontItalic\": false,");
        sb.AppendLine("  \"opacity\": 1.0,");
        sb.AppendLine("  \"bgBitmapAsset\": \"img/card_bg.jpg\",");
        sb.AppendLine("  \"bgAnimAsset\": \"anim/card_loop\",");
        sb.AppendLine("  \"bgAnimFps\": 15,");
        sb.AppendLine("  \"bgDimAlpha\": 95,");
        sb.AppendLine("  \"paddingPx\": [18, 14, 18, 14]");
        sb.AppendLine("}");
        sb.AppendLine("```");
        sb.AppendLine("- **`gradientAngle` Android Octants:** `0` (Left->Right), `45` (BottomLeft->TopRight), `90` (Bottom->Top), `135` (BottomRight->TopLeft), `180` (Right->Left), `225` (TopRight->BottomLeft), `270` (Top->Bottom), `315` (TopLeft->BottomRight).");
        sb.AppendLine("- **Fonts (`style.fontFamily`):** Built-in presets: " + string.Join(", ", PresetCatalog.FontPresets.Where(f => !string.IsNullOrEmpty(f)).Select(f => $"`\"{f}\"`")) + ", OR a bundled font file path `\"fonts/myfont.ttf\"` / `\"fonts/myfont.otf\"`.");
        sb.AppendLine("- **Idle & Press Animations (`animation` object on any panel or widget):**");
        sb.AppendLine("  - `pressScale`: e.g. `0.96`");
        sb.AppendLine("  - `enterDelayMs`: `0..1500`, `enterDurationMs`: `120..900`");
        sb.AppendLine("  - `idleEffect`: " + string.Join(", ", PresetCatalog.IdleEffects.Select(fx => $"`\"{fx}\"`")));
        sb.AppendLine("  - `idleSpeed`: `0.4`–`2.5` (default `1.0`), `idleIntensity`: `0.3`–`2.0` (default `1.0`).");
        sb.AppendLine("- **Pager `indicator` Object Properties (on `pager` panel or `page_indicator` widget):**");
        sb.AppendLine("  - `mode`: `\"pills\"` (shows `pageTitle` chips), `\"numbers\"` (`1, 2, 3`), `\"dots\"` (compact capsules), or `\"none\"`.");
        sb.AppendLine("  - `align`: `\"left\" | \"center\" | \"right\"`, `activeFillColor`, `inactiveFillColor`, `activeBorderColor`, `inactiveBorderColor`, `activeTextColor`, `inactiveTextColor`, `fontFamily`, `fontWeight`, `fontItalic`, `textSizePx`, `cornerRadiusPx`, `borderWidthPx`, `gapPx`, `padXPx`, `padYPx`, `dotWidthPx`, `dotHeightPx`, `activeDotWidthPx`.");
        sb.AppendLine();

        // 6. Live Telemetry Binding & Full Sensor Catalog
        sb.AppendLine("## 6. Live Telemetry Binding Rules & Complete Sensor Catalog");
        sb.AppendLine("- **Primary Sensor (`telemetryKey`):** Bind a widget (`text`, `progress_bar`, `soc_ring`, `telemetry_badge`, `tile`, `action_pill`) to any sensor key below.");
        sb.AppendLine("  - If `title` is empty or `\"{value}\"` on a `text` widget, it displays the formatted live value (e.g. `\"78%\"`, `\"72°F\"`, `\"LOCKED\"`).");
        sb.AppendLine("  - On `tile` or `action_pill`, if `title` is static (e.g. `\"Climate\"`) and `subtitle` is empty or `\"State: {value}\"`, `{value}` resolves `telemetryKey` automatically.");
        sb.AppendLine("- **Multi-Sensor String Interpolation (`{any_sensor_key}`):** `title`, `subtitle`, and `badgeText` can embed **any number** of sensor keys in curly braces, e.g. `\"subtitle\": \"SOC {hv.soc} • Range {hv.range} • Pack {hv.battery_temp_min}\"`.");
        sb.AppendLine("- **Custom `unit` Override:** Leave `unit` omitted/empty to use the sensor's smart formatted string (respecting `tokens.speedUnit` and `tokens.tempUnit`), or set `unit` explicitly when needed.");
        sb.AppendLine("- **Direct Standalone Vehicle Bus Notes (`v0.5.0`, no HMG-API required):**");
        sb.AppendLine("  - `tyre.fl_psi`, `tyre.fr_psi`, `tyre.rl_psi`, `tyre.rr_psi` decode live CAN-FD `0x03A1` TPMS pressures once rolling (automatically falling back to `\"OK\"` / `\"LOW\"` while parked before TPMS RF sensors wake, and supporting `\"psi\"`, `\"bar\"`, or `\"kPa\"` `unit` overrides).");
        sb.AppendLine("  - `energy.drivetrain_kw`, `energy.climate_kw`, `energy.electronics_w`, and `energy.care_kw` report live EV Energy Flow directly from `IHEVService` with zero HMG-API dependency.");
        sb.AppendLine();
        sb.AppendLine("### Available Live Telemetry Keys (`telemetryKey`)");
        foreach (var s in PresetCatalog.TelemetrySensors)
        {
            if (s.IsHeader)
            {
                string cleanHdr = s.Display.Replace("────", "").Trim();
                sb.AppendLine();
                sb.AppendLine($"#### {cleanHdr}");
                continue;
            }
            if (string.IsNullOrEmpty(s.Key)) continue;
            string unitNote = string.IsNullOrEmpty(s.DefaultUnit) ? "auto" : s.DefaultUnit;
            sb.AppendLine(string.Create(CultureInfo.InvariantCulture,
                $"- `{s.Key}` — {s.Display} (unit: `{unitNote}`, gauge range: `{s.DefaultMin}`..`{s.DefaultMax}`)"));
        }
        sb.AppendLine();

        // 7. Interactive Actions, OEM Shortcuts & Live Car Controls
        sb.AppendLine("## 7. Tap Actions (`action`) — Launcher Built-ins, OEM Shortcuts (`preset`), & Live Car Controls (`car_cmd`)");
        sb.AppendLine("Attach an `action` object to any interactive `panel`, `tile`, `action_pill`, `icon`, `image`, `text`, `soc_ring`, or `ev_hero`:");
        sb.AppendLine("- **Built-in Launcher Actions:**");
        sb.AppendLine("  - `{\"type\": \"open_drawer\"}` — Open All Apps & Stock Menu Drawer");
        sb.AppendLine("  - `{\"type\": \"open_car_controls\"}` — Open Live Car Controls Popup Console (AC, Doors, Hatch, Windows, Seats)");
        sb.AppendLine("  - `{\"type\": \"open_theme_store\"}` — Open Online Theme Store");
        sb.AppendLine("  - `{\"type\": \"open_themes\"}` — Open Installed Themes & USB Importer");
        sb.AppendLine("  - `{\"type\": \"open_switcher\"}` — Open Home Switcher & 5-Layer Failsafe Dialog");
        sb.AppendLine("  - `{\"type\": \"open_stock_tester\"}` — Open Stock Shortcut Diagnostic Tester");
        sb.AppendLine("  - `{\"type\": \"launch_stock\"}` — Jump to Original Hyundai/Kia Stock Home Screen");
        sb.AppendLine("  - `{\"type\": \"launch_climate\"}` — Open OEM Climate Control Screen (auto-detects car firmware)");
        sb.AppendLine("  - `{\"type\": \"goto_page\", \"cmd\": \"next\"}` — Switch pager page (`\"next\"`, `\"prev\"`, or 0-based page index `\"0\"`, `\"1\"`, `\"2\"`...)");
        sb.AppendLine("  - `{\"type\": \"toggle_bar\"}` — Toggle 90px OEM System Status Bar");
        sb.AppendLine("  - `{\"type\": \"cycle_sound\"}` — Cycle Click Sound (`system` / `soft` / `off`)");
        sb.AppendLine("  - `{\"type\": \"toggle_bg\"}` — Cycle Backdrop Preview Mode");
        sb.AppendLine("- **Custom Android Component / Broadcast Actions (for sideloaded or specific OEM apps):**");
        sb.AppendLine("  - Activity launch: `{\"type\": \"launch\", \"package\": \"org.videolan.vlc\", \"class\": \"org.videolan.vlc.StartActivity\"}` (omit `class` to launch package default launcher activity; use `\"root_start\"` or `\"root_service\"` for non-exported OEM components).");
        sb.AppendLine("  - Broadcast: `{\"type\": \"broadcast\", \"intentAction\": \"com.example.ACTION\", \"package\": \"com.example\", \"extraKey\": \"k\", \"extraType\": \"int\", \"extraVal\": \"1\"}` (or `\"root_broadcast\"`).");
        sb.AppendLine("- **OEM Head-Unit Shortcuts (`{\"type\": \"preset\", \"cmd\": \"<cmd>\"}`) & Direct Vehicle Controls (`{\"type\": \"car_cmd\", \"cmd\": \"<cmd>\"}`):**");
        sb.AppendLine("  > Tip: For `car_cmd` buttons, bind `telemetryKey` to the paired sensor shown below and use `\"{value}\"` in `subtitle` so the button reflects live vehicle state!");

        foreach (var p in PresetCatalog.ActionPresets)
        {
            if (p.IsHeader)
            {
                string cleanHdr = p.Display.Replace("────", "").Trim();
                sb.AppendLine();
                sb.AppendLine($"#### {cleanHdr}");
                continue;
            }
            if (p.ActionType == "none") continue;
            string cmdPart = string.IsNullOrEmpty(p.Cmd)
                ? $"{{\"type\": \"{p.ActionType}\"}}"
                : $"{{\"type\": \"{p.ActionType}\", \"cmd\": \"{p.Cmd}\"}}";
            string sensorPart = string.IsNullOrEmpty(p.DefaultSensorKey)
                ? ""
                : $" -> pair with `telemetryKey: \"{p.DefaultSensorKey}\"`";
            sb.AppendLine($"- `{cmdPart}` — {p.Display}{sensorPart}");
        }
        sb.AppendLine();
        sb.AppendLine("- **Additional Per-Window Partial / Inching Commands (`car_cmd`):** For `fl`, `fr`, `rl`, or `rr`, you may also use `window_<pos>_half` (50%), `window_<pos>_vent` (25%), `window_<pos>_inch_up` (~1\" up), or `window_<pos>_inch_down` (~1\" down).");
        sb.AppendLine();

        // 8. Built-in Icons & Validated Complete Example
        sb.AppendLine("## 8. Built-in Vector Icons (Always Available Without Extra Files)");
        sb.AppendLine($"Gen5W Launcher & Gen5W Studio bundle these {PresetCatalog.BuiltInIcons.Count} crisp SVG icons automatically:");
        sb.AppendLine(string.Join(", ", PresetCatalog.BuiltInIcons.Select(i => $"`\"{i}\"`")));
        sb.AppendLine();

        sb.AppendLine("## 9. Complete Validated ThemePack v2 `layout.json` Example");
        sb.AppendLine(ExampleJsonStartMarker);
        sb.AppendLine("```json");
        sb.AppendLine(BuildCanonicalExampleJson().TrimEnd());
        sb.AppendLine("```");
        sb.AppendLine(ExampleJsonEndMarker);

        if (includeActiveThemeJson && activeTheme != null)
        {
            sb.AppendLine();
            sb.AppendLine($"## 10. Currently Open Theme (`{activeTheme.Name}` — `{activeTheme.Id}`) for Remixing / Editing");
            sb.AppendLine("```json");
            sb.AppendLine(activeTheme.ToJsonString().TrimEnd());
            sb.AppendLine("```");
        }

        return sb.ToString().Replace("\r\n", "\n");
    }

    public static string BuildCanonicalExampleJson()
    {
        return $$"""
        {
          "formatVersion": 2,
          "id": "neon-horizon-ev",
          "name": "Neon Horizon EV",
          "author": "AI Theme Builder",
          "version": "1.0.0",
          "revision": 1,
          "category": "EV & Telemetry",
          "minAppVersion": "{{PresetCatalog.DefaultMinAppVersion}}",
          "compatibleVersions": [
            "{{PresetCatalog.DefaultMinAppVersion}}+"
          ],
          "description": "Two-page widescreen EV cockpit with composable telemetry cards, live HVAC/door controls, and procedural aurora backdrop.",
          "referenceWidth": 1920,
          "referenceHeight": 720,
          "tokens": {
            "showSystemBar": true,
            "soundMode": "system",
            "speedUnit": "mph",
            "tempUnit": "F",
            "cornerRadiusPx": 20,
            "accentColor": "#FF38BDF8",
            "okColor": "#FF10B981",
            "warnColor": "#FFF59E0B",
            "dangerColor": "#FFEF4444"
          },
          "background": {
            "type": "animated_aurora",
            "primaryColor": "#FF060B14",
            "secondaryColor": "#FF0E1F38",
            "accentColor": "#FF195F91",
            "secondaryAccentColor": "#FF0E5A68",
            "dimColor": "#44060B12",
            "fps": 16,
            "speed": 1.0
          },
          "root": {
            "id": "root_shell",
            "type": "panel",
            "layout": "flex_col",
            "gapPx": 12,
            "paddingPx": [24, 14, 24, 14],
            "style": { "transparent": true },
            "children": [
              {
                "id": "top_bar",
                "type": "panel",
                "layout": "flex_row",
                "height": 68,
                "gapPx": 10,
                "paddingPx": [16, 8, 16, 8],
                "style": {
                  "bgColor": "#C80D1626",
                  "borderColor": "#4438BDF8",
                  "borderWidthPx": 1,
                  "cornerRadiusPx": 16
                },
                "children": [
                  {
                    "id": "hdr_clock",
                    "kind": "clock",
                    "width": 200,
                    "style": { "transparent": true, "titleSizePx": 26, "subtitleSizePx": 12 }
                  },
                  {
                    "id": "hdr_gear",
                    "kind": "telemetry_badge",
                    "width": 135,
                    "title": "DRIVE",
                    "telemetryKey": "gear_speed",
                    "style": { "bgColor": "#88132338", "borderColor": "#3338BDF8", "borderWidthPx": 1, "cornerRadiusPx": 12 }
                  },
                  {
                    "id": "hdr_ext",
                    "kind": "telemetry_badge",
                    "width": 120,
                    "title": "OUTSIDE",
                    "telemetryKey": "outside_temp_f",
                    "style": { "bgColor": "#88132338", "borderColor": "#3338BDF8", "borderWidthPx": 1, "cornerRadiusPx": 12 }
                  },
                  { "id": "hdr_spacer", "kind": "spacer", "flexWeight": 1 },
                  {
                    "id": "hdr_console",
                    "kind": "action_pill",
                    "width": 190,
                    "title": "Car Controls",
                    "subtitle": "AC & Body",
                    "icon": "icons/car.svg",
                    "style": { "bgColor": "#BB0E3554", "borderColor": "#8838BDF8", "borderWidthPx": 1 },
                    "action": { "type": "open_car_controls" }
                  },
                  {
                    "id": "hdr_apps",
                    "kind": "action_pill",
                    "width": 165,
                    "title": "All Apps",
                    "icon": "icons/apps.svg",
                    "style": { "bgColor": "#AA152236", "borderColor": "#4464748B", "borderWidthPx": 1 },
                    "action": { "type": "open_drawer" }
                  }
                ]
              },
              {
                "id": "main_pager",
                "type": "panel",
                "layout": "pager",
                "flexWeight": 1,
                "showDots": true,
                "indicator": { "mode": "pills", "align": "center" },
                "style": { "transparent": true },
                "children": [
                  {
                    "id": "page_cockpit",
                    "type": "panel",
                    "layout": "grid",
                    "pageTitle": "EV Cockpit",
                    "columns": 3,
                    "rows": 2,
                    "gapPx": 14,
                    "style": { "transparent": true },
                    "children": [
                      {
                        "id": "ev_card",
                        "type": "panel",
                        "layout": "flex_col",
                        "rowSpan": 2,
                        "gapPx": 12,
                        "paddingPx": [24, 20, 24, 20],
                        "style": {
                          "bgColor": "#D80E1A2B",
                          "bgEndColor": "#D8132942",
                          "gradientAngle": 135,
                          "borderColor": "#6638BDF8",
                          "borderWidthPx": 2
                        },
                        "animation": { "pressScale": 0.98, "idleEffect": "ev_reactive" },
                        "action": { "type": "preset", "cmd": "ev" },
                        "children": [
                          {
                            "id": "ev_hdr",
                            "kind": "text",
                            "title": "HV BATTERY • {hv.ready}",
                            "subtitle": "Range {hv.range} • Pack Min {hv.battery_temp_min}",
                            "style": { "transparent": true, "titleSizePx": 18, "subtitleSizePx": 14, "textColor": "#FF7DD3FC" }
                          },
                          {
                            "id": "ev_soc_big",
                            "kind": "text",
                            "title": "{value}",
                            "telemetryKey": "hv.soc",
                            "style": { "transparent": true, "titleSizePx": 72, "fontWeight": "bold" }
                          },
                          {
                            "id": "ev_bar",
                            "kind": "progress_bar",
                            "height": 22,
                            "telemetryKey": "hv.soc",
                            "minValue": 0,
                            "maxValue": 100,
                            "style": { "bgColor": "#551E293B", "accentColor": "#FF38BDF8", "bgEndColor": "#FF10B981", "cornerRadiusPx": 11 }
                          },
                          { "id": "ev_sp", "kind": "spacer", "flexWeight": 1 },
                          {
                            "id": "ev_aux",
                            "kind": "text",
                            "title": "12V Aux {battery12v.soc} • Plug {hv.plug_state}",
                            "style": { "transparent": true, "titleSizePx": 16, "textColor": "#FF94A3B8" }
                          }
                        ]
                      },
                      {
                        "id": "tile_navi",
                        "kind": "tile",
                        "title": "Navigation",
                        "subtitle": "Map & Guidance",
                        "icon": "icons/navi.svg",
                        "action": { "type": "preset", "cmd": "map" }
                      },
                      {
                        "id": "tile_proj",
                        "kind": "tile",
                        "title": "CarPlay / AA",
                        "subtitle": "Phone Projection",
                        "icon": "icons/projection.svg",
                        "action": { "type": "preset", "cmd": "projection" }
                      },
                      {
                        "id": "tile_climate",
                        "kind": "tile",
                        "title": "Climate {climate.driver_temp}",
                        "subtitle": "HVAC: {value} • Fan: {climate.fan_speed}",
                        "badgeText": "AC:{climate.ac}",
                        "icon": "icons/climate.svg",
                        "telemetryKey": "climate.on",
                        "action": { "type": "car_cmd", "cmd": "climate_toggle" }
                      },
                      {
                        "id": "tile_locks",
                        "kind": "tile",
                        "title": "Door Locks",
                        "subtitle": "{value} • {door.any_open}",
                        "icon": "icons/shield.svg",
                        "telemetryKey": "door.lock_state",
                        "action": { "type": "car_cmd", "cmd": "door_lock_toggle" }
                      }
                    ]
                  },
                  {
                    "id": "page_controls",
                    "type": "panel",
                    "layout": "grid",
                    "pageTitle": "Quick Controls",
                    "columns": 4,
                    "rows": 2,
                    "gapPx": 14,
                    "style": { "transparent": true },
                    "children": [
                      {
                        "id": "c_seat_drv",
                        "kind": "tile",
                        "title": "Driver Heat",
                        "subtitle": "{value}",
                        "icon": "icons/seat.svg",
                        "telemetryKey": "seat_heat.driver",
                        "action": { "type": "car_cmd", "cmd": "seat_heat_driver_cycle" }
                      },
                      {
                        "id": "c_wheel",
                        "kind": "tile",
                        "title": "Wheel Heat",
                        "subtitle": "{value}",
                        "icon": "icons/steering.svg",
                        "telemetryKey": "wheel_heat",
                        "action": { "type": "car_cmd", "cmd": "wheel_heat_toggle" }
                      },
                      {
                        "id": "c_trunk",
                        "kind": "tile",
                        "title": "Trunk / Hatch",
                        "subtitle": "{value}",
                        "icon": "icons/car.svg",
                        "telemetryKey": "trunk.open",
                        "action": { "type": "car_cmd", "cmd": "trunk_toggle" }
                      },
                      {
                        "id": "c_chg_door",
                        "kind": "tile",
                        "title": "Charge Door",
                        "subtitle": "{value}",
                        "icon": "icons/ev.svg",
                        "telemetryKey": "charge_door.open",
                        "action": { "type": "car_cmd", "cmd": "charge_door_toggle" }
                      },
                      {
                        "id": "c_media",
                        "kind": "tile",
                        "title": "Radio / SXM",
                        "subtitle": "Vol: {audio.volume}",
                        "icon": "icons/radio.svg",
                        "action": { "type": "preset", "cmd": "radio" }
                      },
                      {
                        "id": "c_setup",
                        "kind": "tile",
                        "title": "Vehicle Setup",
                        "subtitle": "OEM Settings",
                        "icon": "icons/settings.svg",
                        "action": { "type": "preset", "cmd": "vehicle_settings" }
                      },
                      {
                        "id": "c_store",
                        "kind": "tile",
                        "title": "Theme Store",
                        "subtitle": "Online Catalog",
                        "icon": "icons/store.svg",
                        "action": { "type": "open_theme_store" }
                      },
                      {
                        "id": "c_stock",
                        "kind": "tile",
                        "title": "Stock Home",
                        "subtitle": "OEM Launcher",
                        "icon": "icons/stock.svg",
                        "action": { "type": "launch_stock" }
                      }
                    ]
                  }
                ]
              }
            ]
          }
        }
        """;
    }

    public static string ExtractEmbeddedExampleJson(string promptText)
    {
        int s = promptText.IndexOf(ExampleJsonStartMarker, StringComparison.Ordinal);
        int e = promptText.IndexOf(ExampleJsonEndMarker, StringComparison.Ordinal);
        if (s < 0 || e <= s)
            throw new InvalidOperationException("AI Context prompt missing example JSON markers.");
        string block = promptText.Substring(s + ExampleJsonStartMarker.Length, e - (s + ExampleJsonStartMarker.Length)).Trim();
        if (block.StartsWith("```json", StringComparison.Ordinal))
            block = block["```json".Length..];
        if (block.EndsWith("```", StringComparison.Ordinal))
            block = block[..^3];
        return block.Trim();
    }
}
