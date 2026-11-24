# Changelog

## v0.5.0 — 2026-11-23

### Standalone Native NEMESIS Architecture (Zero HMG-API Dependency)
- **Removed `HMG-API` Dependency 100% (`nemesis/NEMESIS.data.in`, `scripts/build.ps1`, `HomeSwitcher`, `DirectCarServiceBridge`):**
  - Packaged `Gen5WLauncher` as a standalone native **NEMESIS `1.1`** `type=application,service` package (`id=gen5w.launcher`, no `depends=hmgapi`), allowing users to run Gen5W Launcher alongside any other head unit mods without HMG-API conflicts.
  - Replaced all `HmgApi` client/IPC calls and build-time references with direct Gen5W OEM Binder services (`IHEVService`, `ITM_HSCANService`, `IBCMService`, `IDATCService`, `IClusterService`, `IRegulationManager`, `IModeService`, `ISoundService`), pure Java JSON stubs (`SimpleJsonParser`), and direct USB safety mount scanning (`UsbSafetyMonitor`).
  - Added automatic seamless migration from `v0.4.x` (`hmgapi.gen5w-launcher`) in `nemesis/scripts/preinstall.sh` + `HomeSwitcher.restoreUpgradeBackupIfNeeded()`: backs up existing downloaded themes and preferences before cleanly removing the legacy HMG-API mod, restores ownership/SELinux context after APK installation, and preserves active Default Home & Failsafe state across upgrades.
  - Added an in-app **Auto-Activate on Cold Boot** toggle (`SET_AUTO_BOOT`) inside Default Home / Failsafe and native `/data/nemesis/apps/*/NEMESIS.data` `[app.*]` discovery in the All Apps drawer (while opportunistically listing legacy HMG-API apps if present).

### Vehicle Telemetry & Car Control Fixes
- **Fixed EV Energy Flow Sensors (`DirectCarServiceBridge`):**
  - Implemented exact OEM `HKMC_TAppEV3` (`IHEVService` Gen2 CAN-FD & Gen1 EV) energy formulas for `energy.drivetrain_kw` (signed kW), `energy.climate_kw`, `energy.electronics_w`, and `energy.care_kw`.
- **Live Tire Pressure PSI + Parked Status Fallback (`DirectCarServiceBridge`, `LayoutEngine`):**
  - Implemented live per-wheel PSI decoding directly from CAN-FD `0x03A1` (`TPMS_01_200ms_1`) via `ITM_HSCANService.getOneTimeServiceData()` (`FR`/`FL`/`RR`/`RL`), supporting `"psi"`, `"bar"`, and `"kPa"` `unit` formatting.
  - While parked before TPMS RF wheel sensors wake up, `tyre.*_psi` automatically falls back to tire status (`"OK"` / `"LOW"`) instead of staying blank or failing.
- **Direct Binder EV & Cabin Controls (`DirectCarServiceBridge`):**
  - Migrated `hv.soc`, `odometer`, `hv.conditioning`, `hv.conditioning_failure`, `hv.v2l_active`, `hv.plug_state`, `hv.health`, `hv.battery_temp_min` (`IClusterService` callback), `battery12v.*`, `driving.restricted`, `charge_door`, `wheel_heat_toggle`, and battery preconditioning keepalive state machine directly onto OEM Binder APIs.

### Gen5W Studio — Referenced-Asset Packaging & Unused File Pruning (`v0.5.0`)
- **Excluded Unreferenced Workspace Assets from `.zip` Bundles & USB Exports (`ThemePackageService`, `PackWorkspaceService`, `UploadThemeDialog`):**
  - **Save Safe Theme `.zip` / Upload to Theme Store** (`BuildSingleThemeZip` & `AuditWorkspaceTheme`) and **Export to USB / Save Theme Copy** (`CopyReferencedThemeToDirectory`) now package **only** assets actively referenced by `layout.json` and the active `background.type`.
  - Prunes inactive background asset fields and stale widget icon fields on export, and adds a **Clean Unused** button inside **Upload / Package Theme**.
  - Updated bundled NEMESIS USB installer (`gen5w-launcher-nemesis.zip`) and `NemesisInstallerService` to `v0.5.0` standalone (`packages/gen5w.launcher.zip`), hardened bitmap decoding in `StudioCanvasControl`, and updated `--self-test` verification.

---

## v0.4.10 — 2026-11-20

### Gen5W Studio — Referenced-Asset Packaging & Unused File Pruning
- **Excluded Unreferenced Workspace Assets from `.zip` Bundles & USB Exports (`ThemePackageService`, `PackWorkspaceService`, `UploadThemeDialog`):**
  - Previously, trying multiple static wallpapers (`media/*.jpg`), converting multiple animated video/GIF loops (`anim/backdrop_HHmmss/*`), testing custom fonts/images, or seeding the 28 starter icons (`icons/*.svg`) left all unused files in the workspace folder and packaged *every* file into the final `.zip` and USB folder.
  - Added full layout-tree & active-backdrop asset dependency resolution (`ThemePackageService.CollectReferencedAssets` & `PruneInactiveFieldsForRelease`):
    - **Save Safe Theme `.zip` / Upload to Theme Store** (`BuildSingleThemeZip` & `AuditWorkspaceTheme`) and **Export to USB / Save Theme Copy** (`CopyReferencedThemeToDirectory`) now package **only** the assets actively referenced by `layout.json` and the active `background.type` (automatically excluding old wallpapers, unused `anim/*` frame directories, unused fonts, and unused icons).
    - Inactive background asset properties (e.g. a leftover `framesDir` after switching `background.type` to `bitmap` or `animated_aurora`) and stale icon fields on non-icon widgets are automatically pruned from the exported `layout.json`.
    - Added a **Clean Unused** button and skipped-file summary inside the **Upload / Package Theme** audit panel so creators can also optionally delete unused custom assets from their local workspace folder on disk.

---

## v0.4.9 — 2026-11-20

### Bug Fixes
- **Fixed `-40°F` Ambient Temperature Bug on Celsius (`°C`) Vehicles & Cross-Unit HVAC Conversion (`TemperatureNormalizer`, `DirectCarServiceBridge`, `HomeSwitcher`, `TelemetryModel`, `LayoutEngine`):**
  - On Celsius Gen5W vehicles (Canada, Europe, UK, Australia, Korea, or vehicles set to `°C`), Hyundai's `DATCService` leaves the unused `DATC_AmbientTempF` CAN byte at `0x00` and computes `0 - 0x28 = -40.0°F` while `getAmbientTempC()` holds the real ambient temperature (e.g. `14.0°C`).
  - Added hardware-verified `getAmbientTempStateF()` + `getTempUnit()` inspection in `DirectCarServiceBridge` and multi-layer normalization (`TemperatureNormalizer`) in `HomeSwitcher` and `TelemetryModel` (including raw `HMG-API` readings) so `14°C` accurately converts to `57°F` across all `°F` themes and `weather_badge` widgets (and symmetrically for `°C` themes on `°F` cars, while still preserving genuine `-40°` readings).
  - Fixed `climate.driver_temp` and `climate.passenger_temp` in `LayoutEngine` so themes specifying an explicit `°F` or `°C` unit override automatically convert from the vehicle's native HVAC unit (`21.5°C -> 71°F`, `72°F -> 22°C`).

---

## v0.4.8 — 2026-11-20

Major feature, stability, telemetry, and Studio upgrade since `v0.3.9`.

### Highlights
- **Native Android Home Picker Restored (`HomeSwitcher` & `PreferredHomeHelper`):**
  - Pressing the physical **HOME** button after installing `Gen5WLauncher` brings up Android 4.4.2's native *"Select a Home app"* (`Launcher` vs `Gen5W Home` — *Just once* / *Always*) dialog.
  - Selecting **`Gen5W Home` -> `Always`** is automatically detected and persisted across power cycles, cold boots, and NEMESIS updates without overwriting `packages.xml` back to Stock Home on shutdown.
  - Added a 1-tap **"Choose Default Home App (System Prompt)"** button inside **Default Home / Failsafe** (`PROMPT_CHOOSER`).
- **100 ms Unified Direct-Binder Vehicle Telemetry & Car Controls (`DirectCarServiceBridge`, `CarControlOverlayDialog`):**
  - Expanded live vehicle telemetry to **97 sensors** across `BCM`, `DATC`, `HSCAN`, `HEV`, `Audio`, and `HMG-API` with fast `100 ms` polling for driving/climate/door/window states and isolated background polling for slow/blocking readings.
  - Added in-theme **Car Control** actions (`car_cmd`) and an interactive **Car Control Console** overlay for climate/HVAC presets, dual-zone temp steppers, fan & vent modes, seat heat/ventilation, steering wheel heat, defoggers, door locks, power trunk/hatch, charge door, battery preconditioning, audio volume/mute, and windows/sunshade.
  - Corrected Gen5W window travel polarity and full-auto travel codes (`5 = Full Up`, `6 = Full Down`, `4 = 50% Half`, `3 = 25% Vent`, `1/2 = Inch Up/Down`) plus `All_Window_Req(1)` / `All_Sunroof_Req(1)` / `All_PTGCNTL_Req(1)` VR TX signal enabling.
  - Hardened car command execution with per-command `1.8s` timeouts, duplicate in-flight guards, and self-healing worker pools so unsupported vehicle features never block subsequent taps.
- **Long-Running Charging / V2L & IPC Stability Fixes:**
  - Fixed a critical Android 4.4 KitKat `LocalServerSocket.accept()` file-descriptor leak (`libcore.io.Libcore.os.close(fd)`) that previously exhausted socket FDs during extended charging or V2L sessions.
  - Replaced unbounded thread creation in `SwitcherClient` and `HomeSwitcher` with bounded `ThreadPoolExecutor` pools, non-overlapping status polls, and timeout-guarded `runCommandCapture` subprocess readers.
  - Removed false-positive missing-PID watchdog trips when Android reclaims the backgrounded launcher during CarPlay/Android Auto or navigation.
- **In-Car Theme Store Enhancements (`ThemeManagerActivity`, `StoreTlsHelper`):**
  - Added a creator callout banner in the **Theme Store** online browser pointing users to **[market.ioniqthemes.com](https://market.ioniqthemes.com)** to build and share custom themes with **Gen5W Studio**.
  - Expanded embedded root CA trust store (`ISRG Root X1/X2`, `GTS Root R1/R4`, `DigiCert Global Root G2`) for reliable HTTPS connections on Android 4.4.2.
- **Gen5W Studio Upgrades (`v0.4.8`):**
  - Added an **AI Context** toolbar button & dialog (`AiThemeContextBuilder`) generating an always-synchronized, copy-pasteable specification prompt (all rules, layout modes, 28 SVG icons, 97 sensor keys, car control commands, and animated background formats) so any AI assistant can build valid `ThemePack v2` themes.
  - Fixed nested `freeform` panel drag/click coordinate math (`StudioCanvasControl.cs`) so dragging or clicking items inside nested `freeform` containers preserves parent-inner-relative coordinates cleanly.
  - Added Windows Media Foundation video/GIF-to-frame-sequence conversion (`MediaConverterService`), animated tile & backdrop support, expanded action/sensor presets (`PresetCatalog`), and 16 additional built-in vector SVG icons (28 total).

---

## v0.3.9 — 2026-11-18
- Initial public release of **Gen5WLauncher**, **HomeSwitcher** 5-layer failsafe service, in-car **Theme Store**, and **Gen5W Studio** for Windows (`win-x64`).
