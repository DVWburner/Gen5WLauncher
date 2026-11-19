package com.gen5w.launcher.svc;

import android.content.ComponentName;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Base64;

import com.gen5w.launcher.ipc.VehicleQueryClient;

import hmgapi.client.HmgApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Root Dalvik service started by hmgapid after Android boots (sys.boot_completed=1).
 * Manages preferred HOME switching and enforces all 5 failsafe layers:
 *   1. USB Kill-Switch (REVERT_LAUNCHER.txt / SAFE_MODE.txt)
 *   2. Persistent boot-strike breaker (strikes >= 2)
 *   3. 10s cold-boot grace window before activation
 *   4. 60s rolling PID crash watchdog (3+ crashes -> auto revert)
 *   5. 45s Deadman's Switch on manual activation
 */
public final class HomeSwitcher {
    static final String SOCKET_NAME = "gen5w_launcher_ctl";
    private static final String CUSTOM_PKG = "com.gen5w.launcher";
    private static final int EXPECTED_APP_VERSION_CODE = 16;
    private static final long LOOP_TICK_MS = 2_000L;
    private static final long BOOT_GRACE_MS = 10_000L;
    private static final long HEALTH_CONFIRM_MS = 25_000L;
    private static final long DEADMAN_TIMEOUT_MS = 45_000L;
    private static final long FAST_VEHICLE_POLL_MS = 500L;
    private static final long IDLE_VEHICLE_POLL_MS = 2000L;
    private static final long VEHICLE_DEMAND_WINDOW_MS = 5000L;

    private final Object vehiclePollLock = new Object();
    private final Set<String> extraVehicleKeys = new LinkedHashSet<String>();
    private final VehicleQueryClient vehicleClient = new VehicleQueryClient();
    private volatile JSONObject cachedVehicleJson;
    private volatile long lastVehicleDemandMs = 0L;
    private volatile long lastVehiclePollMs = 0L;

    private final SwitcherStateStore store;
    private final PreferredHomeHelper pmHelper = new PreferredHomeHelper();
    private final ProcessWatchHelper procWatch = new ProcessWatchHelper();
    private final UsbSafetyMonitor usbMon = new UsbSafetyMonitor();
    private final File modDir;

    private SwitcherStateStore.State state;
    private volatile boolean running = true;

    private long bootGraceDeadlineMs;
    private boolean bootAutoChecked;
    private long healthConfirmDeadlineMs;
    private long deadmanDeadlineMs;
    private UsbSafetyMonitor.Signal lastUsbSignal = UsbSafetyMonitor.Signal.NONE;
    private String lastError = "";
    private LocalServerSocket serverSocket;

    public static void main(String[] args) {
        String dataPath = System.getenv("HMGAPI_DATA");
        if (dataPath == null || dataPath.trim().isEmpty()) {
            dataPath = "/data/gen5w_launcher";
        }
        String modDirPath = System.getenv("HMGAPI_MOD_DIR");
        File modDir = modDirPath == null ? null : new File(modDirPath);
        HomeSwitcher svc = new HomeSwitcher(new File(dataPath), modDir);
        svc.run();
    }

    private HomeSwitcher(File dataDir, File modDir) {
        this.store = new SwitcherStateStore(dataDir);
        this.modDir = modDir;
        this.state = store.load();
    }

    private void run() {
        long startMs = System.currentTimeMillis();
        bootGraceDeadlineMs = startMs + BOOT_GRACE_MS;

        System.out.println("[HomeSwitcher] started; homeEnabled=" + state.homeEnabled
                + " strikes=" + state.strikes + " tripped=" + state.trippedReason);

        registerShutdownHook();
        startIpcServer();
        syncModApkIfNeededAsync();
        startVehiclePoller();

        try {
            while (running) {
                long now = System.currentTimeMillis();
                tick(now);
                Thread.sleep(LOOP_TICK_MS);
            }
        } catch (InterruptedException ignored) {
            // Expected during shutdown
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            System.err.println("[HomeSwitcher] loop error: " + t);
            t.printStackTrace(System.err);
        } finally {
            closeSocketQuietly();
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread("home-switcher-shutdown") {
            @Override
            public void run() {
                running = false;
                closeSocketQuietly();
                boolean disabled = isModDisabled();
                synchronized (HomeSwitcher.this) {
                    // Always leave stock Launcher3 preferred on disk during shutdown/disable so next
                    // cold boot begins cleanly in stock HOME for the 10-second grace window.
                    pmHelper.setPreferredHome(PreferredHomeHelper.STOCK_HOME);
                    if (disabled) {
                        state.homeEnabled = false;
                        state.strikes = 0;
                        state.trippedReason = "";
                        state.lastEvent = "mod_disabled_restored_stock";
                        store.save(state);
                        if (isBootCompleted()) {
                            pmHelper.bringHomeToFront(PreferredHomeHelper.STOCK_HOME);
                        }
                    } else {
                        System.out.println("[HomeSwitcher] shutdown: restored stock HOME on disk while preserving homeEnabled=" + state.homeEnabled);
                    }
                }
            }
        });
    }

    private synchronized void tick(long now) {
        UsbSafetyMonitor.Signal usb = usbMon.scan();
        lastUsbSignal = usb;
        if (usb == UsbSafetyMonitor.Signal.REVERT) {
            if (isCustomHomePreferred() || state.homeEnabled) {
                revertLocked("usb_kill_switch", true, "Gen5W Launcher reverted by USB kill-switch");
            }
            bootAutoChecked = true;
            return;
        }
        if (usb == UsbSafetyMonitor.Signal.SAFE_MODE) {
            if (isCustomHomePreferred()) {
                revertLocked("usb_safe_mode", false, "Gen5W Launcher safe mode: stock HOME active");
            }
            bootAutoChecked = true;
            return;
        }
        if ("usb_safe_mode".equals(state.trippedReason) && usb == UsbSafetyMonitor.Signal.NONE) {
            state.trippedReason = "";
        }

        if (isModDisabled()) {
            if (isCustomHomePreferred()) {
                revertLocked("mod_disabled", true, "Gen5W Launcher mod disabled: stock HOME restored");
            }
            return;
        }

        // Layer 5: Deadman timer enforcement
        if (deadmanDeadlineMs > 0L && now >= deadmanDeadlineMs) {
            revertLocked("deadman_timeout", true, "Gen5W Home timed out without confirmation; restored stock Home");
            return;
        }

        // Layer 3: Cold-boot grace window
        if (!bootAutoChecked && now >= bootGraceDeadlineMs) {
            bootAutoChecked = true;
            maybeActivateAfterGrace(now);
        }

        // Layer 2: Clear boot strikes once custom Home stays healthy past HEALTH_CONFIRM_MS
        if (healthConfirmDeadlineMs > 0L && now >= healthConfirmDeadlineMs) {
            healthConfirmDeadlineMs = 0L;
            if (isCustomHomePreferred() && state.strikes > 0) {
                state.strikes = 0;
                state.lastEvent = "boot_health_verified";
                store.save(state);
                System.out.println("[HomeSwitcher] custom HOME healthy after grace verification; strikes reset to 0");
            }
        }

        // Layer 4: 60s crash watchdog
        boolean customActive = isCustomHomePreferred();
        if (procWatch.sampleAndCheckCrashLoop(now, customActive)) {
            revertLocked("watchdog_crashes", true, "Gen5W Home crashed repeatedly; restored stock Home");
        }
    }

    private void maybeActivateAfterGrace(long now) {
        if (!state.homeEnabled) {
            return;
        }
        if (!isAutoActivateAllowedByModSetting()) {
            System.out.println("[HomeSwitcher] auto_activate_on_boot=false in HMG-API settings; staying on stock HOME");
            return;
        }
        if (!state.trippedReason.isEmpty()) {
            System.out.println("[HomeSwitcher] auto-activation blocked by trippedReason=" + state.trippedReason);
            return;
        }
        if (state.strikes >= 2) {
            state.trippedReason = "boot_strikes";
            state.homeEnabled = false;
            state.lastEvent = "tripped_boot_strikes";
            store.save(state);
            pmHelper.setPreferredHome(PreferredHomeHelper.STOCK_HOME);
            pmHelper.showToast("Gen5W Home failsafe: reverted to stock after repeated resets");
            return;
        }

        // Persist strike BEFORE switching preferred HOME so hard reset trips breaker on reboot #2.
        state.strikes += 1;
        state.lastEvent = "boot_activating_strike_" + state.strikes;
        store.save(state);

        if (pmHelper.setPreferredHome(PreferredHomeHelper.CUSTOM_HOME)) {
            procWatch.resetWatch();
            healthConfirmDeadlineMs = now + HEALTH_CONFIRM_MS;
            pmHelper.bringHomeToFront(PreferredHomeHelper.CUSTOM_HOME);
        } else {
            lastError = "replacePreferredActivity failed on boot activation";
            pmHelper.setPreferredHome(PreferredHomeHelper.STOCK_HOME);
        }
    }

    private void revertLocked(String reason, boolean persistDisable, String toastMsg) {
        deadmanDeadlineMs = 0L;
        healthConfirmDeadlineMs = 0L;
        if (persistDisable) {
            state.homeEnabled = false;
            state.strikes = 0;
        }
        state.trippedReason = reason;
        state.lastEvent = "reverted:" + reason;
        store.save(state);
        pmHelper.setPreferredHome(PreferredHomeHelper.STOCK_HOME);
        pmHelper.bringHomeToFront(PreferredHomeHelper.STOCK_HOME);
        pmHelper.showToast(toastMsg);
        System.out.println("[HomeSwitcher] reverted to stock HOME: reason=" + reason);
    }

    private synchronized JSONObject handleCommand(String line) throws Exception {
        String cmd = line == null ? "" : line.trim();
        long now = System.currentTimeMillis();
        if ("ACTIVATE_TEST".equals(cmd)) {
            UsbSafetyMonitor.Signal usb = usbMon.scan();
            if (usb != UsbSafetyMonitor.Signal.NONE) {
                lastError = "USB safety file (" + usb.name() + ") prevents activation";
                return buildStatusJson(now, false, lastError);
            }
            if (!pmHelper.isPackageInstalled(CUSTOM_PKG)) {
                lastError = "com.gen5w.launcher is not installed";
                return buildStatusJson(now, false, lastError);
            }
            state.trippedReason = "";
            state.strikes = 0;
            state.lastEvent = "deadman_test_started";
            store.save(state);
            procWatch.resetWatch();
            if (!pmHelper.setPreferredHome(PreferredHomeHelper.CUSTOM_HOME)) {
                lastError = "IPackageManager.replacePreferredActivity failed";
                return buildStatusJson(now, false, lastError);
            }
            deadmanDeadlineMs = now + DEADMAN_TIMEOUT_MS;
            lastError = "";
            return buildStatusJson(now, true, "Test active (45s): tap Confirm to keep");
        } else if ("CONFIRM".equals(cmd)) {
            deadmanDeadlineMs = 0L;
            state.homeEnabled = true;
            state.strikes = 0;
            state.trippedReason = "";
            state.lastEvent = "confirmed_default_home";
            store.save(state);
            lastError = "";
            return buildStatusJson(now, true, "Custom HOME confirmed and saved");
        } else if ("REVERT".equals(cmd)) {
            revertLocked("manual_revert", true, "Restored stock Ioniq 5 Launcher");
            return buildStatusJson(now, true, "Reverted to stock Launcher3");
        } else if ("CLEAR_STRIKES".equals(cmd)) {
            state.strikes = 0;
            state.trippedReason = "";
            state.lastEvent = "strikes_cleared";
            store.save(state);
            lastError = "";
            return buildStatusJson(now, true, "Failsafe state cleared");
        } else if ("LAUNCH_STOCK".equals(cmd)) {
            boolean ok = pmHelper.bringHomeToFront(PreferredHomeHelper.STOCK_HOME);
            return buildStatusJson(now, ok, ok ? "Launched stock Launcher3" : "Failed to launch Launcher3");
        } else {
            // STATUS or unknown defaults to non-mutating status read
            return buildStatusJson(now, true, "ok");
        }
    }

    private JSONObject buildStatusJson(long now, boolean ok, String message) throws Exception {
        ComponentName active = pmHelper.resolveCurrentHome();
        JSONObject j = new JSONObject();
        j.put("ok", ok);
        j.put("message", message);
        j.put("activeHome", active == null ? "none" : active.flattenToShortString());
        j.put("isCustomHome", PreferredHomeHelper.CUSTOM_HOME.equals(active));
        j.put("homeEnabled", state.homeEnabled);
        j.put("strikes", state.strikes);
        j.put("trippedReason", state.trippedReason);
        j.put("lastEvent", state.lastEvent);
        j.put("lastError", lastError);
        j.put("usbSignal", lastUsbSignal.name());
        j.put("graceRemainingSec", Math.max(0L, (bootGraceDeadlineMs - now + 999L) / 1000L));
        j.put("deadmanRemainingSec", deadmanDeadlineMs > 0L ? Math.max(0L, (deadmanDeadlineMs - now + 999L) / 1000L) : 0L);
        j.put("healthConfirmRemainingSec", healthConfirmDeadlineMs > 0L ? Math.max(0L, (healthConfirmDeadlineMs - now + 999L) / 1000L) : 0L);
        j.put("appPid", procWatch.findProcessPid(CUSTOM_PKG));
        j.put("crashesIn60s", procWatch.getCrashCountInWindow(now));
        j.put("appVersionCode", pmHelper.getPackageVersionCode(CUSTOM_PKG));
        j.put("appVersionName", pmHelper.getPackageVersionName(CUSTOM_PKG));
        return j;
    }

    private JSONObject dispatchCommand(String line) throws Exception {
        String cmd = line == null ? "" : line.trim();
        if ("VEHICLE".equals(cmd)) {
            return buildVehicleResponse();
        }
        if ("HMGAPI_MODS".equals(cmd)) {
            return handleListHmgApiLauncherEntries();
        }
        if (cmd.startsWith("SET_KEYS|")) {
            return handleSetVehicleKeysCommand(cmd.substring("SET_KEYS|".length()));
        }
        if (cmd.startsWith("ROOT_AM|")) {
            return handleRootAmCommand(cmd);
        }
        return handleCommand(cmd);
    }

    private JSONObject handleSetVehicleKeysCommand(String raw) throws Exception {
        Set<String> updated = new LinkedHashSet<String>();
        if (raw != null && !raw.trim().isEmpty()) {
            String[] parts = raw.split(",");
            for (String p : parts) {
                String k = p.trim();
                if (VehicleQueryClient.isValidKey(k) && updated.size() < 64) {
                    updated.add(k);
                }
            }
        }
        boolean changed;
        synchronized (vehiclePollLock) {
            changed = !extraVehicleKeys.equals(updated);
            if (changed) {
                extraVehicleKeys.clear();
                extraVehicleKeys.addAll(updated);
                vehicleClient.clearQuarantine();
                if (lastVehicleDemandMs > 0L) {
                    vehiclePollLock.notifyAll();
                }
            }
        }
        JSONObject j = new JSONObject();
        j.put("ok", true);
        j.put("changed", changed);
        j.put("count", updated.size());
        return j;
    }

    private JSONObject buildVehicleResponse() throws Exception {
        long now = System.currentTimeMillis();
        synchronized (vehiclePollLock) {
            lastVehicleDemandMs = now;
            if (cachedVehicleJson == null || (now - lastVehiclePollMs) > 600L) {
                vehiclePollLock.notifyAll();
            }
        }
        JSONObject cached = cachedVehicleJson;
        if (cached != null) {
            return cached;
        }
        JSONObject j = new JSONObject();
        j.put("ok", false);
        j.put("code", -1);
        j.put("error", "vehicle cache warming up");
        j.put("values", new JSONObject());
        j.put("updatedMs", 0L);
        return j;
    }

    private JSONObject handleListHmgApiLauncherEntries() throws Exception {
        JSONObject resp = new JSONObject();
        JSONArray out = new JSONArray();
        File modsRoot = (modDir != null && modDir.getParentFile() != null)
                ? modDir.getParentFile()
                : new File("/data/hmgapi/mods");
        File[] children = modsRoot.listFiles();
        if (children != null) {
            Arrays.sort(children);
            for (File dir : children) {
                if (!dir.isDirectory()) {
                    continue;
                }
                if (new File(dir, ".disabled").exists()) {
                    continue;
                }
                File manifest = new File(dir, "mod.json");
                if (!manifest.isFile() || manifest.length() > 524288L) {
                    continue;
                }
                try {
                    String raw = readTextFileLimited(manifest, 524288);
                    if (raw == null || raw.isEmpty()) continue;
                    JSONObject modJson = new JSONObject(raw);
                    String modId = modJson.optString("id", dir.getName());
                    JSONObject provides = modJson.optJSONObject("provides");
                    JSONArray entries = provides == null ? null : provides.optJSONArray("launcher");
                    if (entries == null) continue;
                    String modRootCanon = dir.getCanonicalPath() + File.separator;
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject e = entries.optJSONObject(i);
                        if (e == null) continue;
                        String pkg = e.optString("package", "").trim();
                        String cls = e.optString("class", "").trim();
                        if (pkg.isEmpty() || cls.isEmpty()) continue;
                        if (cls.startsWith(".")) cls = pkg + cls;
                        String title = e.optString("title", "").trim();
                        String iconRel = e.optString("icon", "").trim();
                        String iconB64 = "";
                        if (!iconRel.isEmpty()) {
                            File iconFile = new File(dir, iconRel);
                            if (iconFile.isFile()
                                    && iconFile.getCanonicalPath().startsWith(modRootCanon)
                                    && iconFile.length() > 0L
                                    && iconFile.length() <= 262144L) {
                                byte[] png = readBytesLimited(iconFile, 262144);
                                if (png != null && png.length > 0) {
                                    iconB64 = Base64.encodeToString(png, Base64.NO_WRAP);
                                }
                            }
                        }
                        JSONObject item = new JSONObject();
                        item.put("mod", modId);
                        item.put("package", pkg);
                        item.put("class", cls);
                        item.put("title", title);
                        item.put("iconBase64", iconB64);
                        out.put(item);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        resp.put("ok", true);
        resp.put("entries", out);
        return resp;
    }

    private static String readTextFileLimited(File file, int maxBytes) throws IOException {
        byte[] raw = readBytesLimited(file, maxBytes);
        return raw == null ? null : new String(raw, "UTF-8");
    }

    private static byte[] readBytesLimited(File file, int maxBytes) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = fis.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private JSONObject handleRootAmCommand(String raw) throws Exception {
        JSONObject resp = new JSONObject();
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 8) {
            resp.put("ok", false);
            resp.put("message", "bad ROOT_AM syntax");
            return resp;
        }
        String mode = parts[1];
        String pkg = parts[2];
        String cls = parts[3];
        String action = parts[4];
        String extraKey = parts[5];
        String extraType = parts[6];
        String extraVal = parts[7];

        if (!isSafeIdentifier(pkg) || !isSafeIdentifier(cls) || !isSafeIdentifier(action)
                || !isSafeIdentifier(extraKey) || !isSafeValue(extraVal)) {
            resp.put("ok", false);
            resp.put("message", "invalid token in ROOT_AM");
            return resp;
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add("/system/bin/am");
        if ("start".equals(mode)) {
            cmd.add("start");
        } else if ("bcast".equals(mode)) {
            cmd.add("broadcast");
        } else if ("service".equals(mode)) {
            cmd.add("startservice");
        } else {
            resp.put("ok", false);
            resp.put("message", "unsupported ROOT_AM mode: " + mode);
            return resp;
        }

        if (!action.isEmpty()) {
            cmd.add("-a");
            cmd.add(action);
        }
        if (!pkg.isEmpty() && !cls.isEmpty()) {
            String fullCls = cls.startsWith(".") ? (pkg + cls) : cls;
            cmd.add("-n");
            cmd.add(pkg + "/" + fullCls);
        } else if (!pkg.isEmpty() && "bcast".equals(mode)) {
            cmd.add("-p");
            cmd.add(pkg);
        }

        if (!extraKey.isEmpty() && !extraType.isEmpty()) {
            if ("int".equals(extraType)) {
                cmd.add("--ei");
                cmd.add(extraKey);
                cmd.add(String.valueOf(Integer.parseInt(extraVal)));
            } else if ("bool".equals(extraType)) {
                cmd.add("--ez");
                cmd.add(extraKey);
                cmd.add("true".equalsIgnoreCase(extraVal) ? "true" : "false");
            } else if ("str".equals(extraType)) {
                cmd.add("-e");
                cmd.add(extraKey);
                cmd.add(extraVal);
            }
        }

        PreferredHomeHelper.CommandResult res = pmHelper.runRootCommand(cmd.toArray(new String[cmd.size()]));
        boolean ok = res.exitCode == 0 && !res.output.contains("Error:") && !res.output.contains("Exception");
        resp.put("ok", ok);
        resp.put("message", "rc=" + res.exitCode + " " + res.output);
        return resp;
    }

    private static boolean isSafeIdentifier(String s) {
        if (s == null || s.length() > 160) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '$';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isSafeValue(String s) {
        if (s == null || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private void syncModApkIfNeededAsync() {
        if (modDir == null) {
            return;
        }
        final File apk = new File(modDir, "app/Gen5WLauncher.apk");
        if (!apk.isFile()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int installed = pmHelper.getPackageVersionCode(CUSTOM_PKG);
                    if (installed >= 0 && installed < EXPECTED_APP_VERSION_CODE) {
                        boolean wasCustom = isCustomHomePreferred();
                        System.out.println("[HomeSwitcher] syncing Gen5WLauncher APK (" + installed + " -> " + EXPECTED_APP_VERSION_CODE + ")");
                        if (pmHelper.installApkReplace(apk) && wasCustom) {
                            pmHelper.bringHomeToFront(PreferredHomeHelper.CUSTOM_HOME);
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[HomeSwitcher] syncModApkIfNeeded error: " + t);
                }
            }
        }, "home-switcher-apksync");
        t.setDaemon(true);
        t.start();
    }

    private void startVehiclePoller() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    long checkNow = System.currentTimeMillis();
                    if (lastVehicleDemandMs <= 0L || (checkNow - lastVehicleDemandMs) > VEHICLE_DEMAND_WINDOW_MS) {
                        synchronized (vehiclePollLock) {
                            if (!running) {
                                break;
                            }
                            checkNow = System.currentTimeMillis();
                            if (lastVehicleDemandMs <= 0L || (checkNow - lastVehicleDemandMs) > VEHICLE_DEMAND_WINDOW_MS) {
                                try {
                                    vehiclePollLock.wait();
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                                continue;
                            }
                        }
                    }
                    long loopStartMs = System.currentTimeMillis();
                    try {
                        List<String> keys = new ArrayList<String>(Arrays.asList(VehicleQueryClient.CORE_KEYS));
                        synchronized (vehiclePollLock) {
                            for (String k : extraVehicleKeys) {
                                if (!keys.contains(k)) {
                                    keys.add(k);
                                }
                            }
                        }
                        VehicleQueryClient.BatchResult res = vehicleClient.query(keys);
                        JSONObject vals = new JSONObject();
                        for (Map.Entry<String, String> entry : res.values.entrySet()) {
                            vals.put(entry.getKey(), entry.getValue());
                        }
                        boolean valid = res.anySuccess && vals.length() > 0;
                        JSONObject snap = new JSONObject();
                        snap.put("ok", valid);
                        snap.put("code", valid ? 0 : -1);
                        snap.put("error", valid ? "" : ("hmgapid " + res.errorNote));
                        snap.put("values", vals);
                        snap.put("updatedMs", System.currentTimeMillis());
                        cachedVehicleJson = snap;
                    } catch (Throwable ex) {
                        try {
                            JSONObject err = new JSONObject();
                            err.put("ok", false);
                            err.put("code", -1);
                            err.put("error", String.valueOf(ex.getMessage()));
                            err.put("values", new JSONObject());
                            err.put("updatedMs", System.currentTimeMillis());
                            cachedVehicleJson = err;
                        } catch (Throwable ignored) {
                        }
                    }
                    long nowMs = System.currentTimeMillis();
                    lastVehiclePollMs = nowMs;
                    long intervalMs = (nowMs - lastVehicleDemandMs) <= VEHICLE_DEMAND_WINDOW_MS
                            ? FAST_VEHICLE_POLL_MS
                            : IDLE_VEHICLE_POLL_MS;
                    long elapsedMs = Math.max(0L, nowMs - loopStartMs);
                    long waitMs = Math.max(100L, intervalMs - elapsedMs);
                    synchronized (vehiclePollLock) {
                        if (!running) {
                            break;
                        }
                        try {
                            vehiclePollLock.wait(waitMs);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            }
        }, "home-switcher-telemetry");
        t.setDaemon(true);
        t.start();
    }

    private boolean isCustomHomePreferred() {
        return PreferredHomeHelper.CUSTOM_HOME.equals(pmHelper.resolveCurrentHome());
    }

    private boolean isModDisabled() {
        return modDir != null && new File(modDir, ".disabled").exists();
    }

    private boolean isBootCompleted() {
        return "1".equals(procWatch.getProp("sys.boot_completed", "0"));
    }

    private boolean isAutoActivateAllowedByModSetting() {
        try {
            JSONObject s = HmgApi.connect().settings();
            return s == null || s.optBoolean("auto_activate_on_boot", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void startIpcServer() {
        Thread ipcThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new LocalServerSocket(SOCKET_NAME);
                    System.out.println("[HomeSwitcher] IPC listening on @" + SOCKET_NAME);
                    while (running) {
                        LocalSocket client = serverSocket.accept();
                        handleClient(client);
                    }
                } catch (IOException e) {
                    if (running) {
                        lastError = "IPC socket error: " + e.getMessage();
                        System.err.println("[HomeSwitcher] IPC accept error: " + e);
                    }
                }
            }
        }, "home-switcher-ipc");
        ipcThread.setDaemon(true);
        ipcThread.start();
    }

    private void handleClient(LocalSocket client) {
        try {
            Credentials creds = client.getPeerCredentials();
            int peerUid = creds == null ? -1 : creds.getUid();
            int allowedAppUid = pmHelper.getPackageUid(CUSTOM_PKG);
            if (peerUid != 0 && (allowedAppUid < 0 || peerUid != allowedAppUid)) {
                System.err.println("[HomeSwitcher] rejected unauthorized IPC peer uid=" + peerUid);
                return;
            }
            client.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            JSONObject resp = dispatchCommand(line);
            OutputStream out = client.getOutputStream();
            out.write((resp.toString() + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            System.err.println("[HomeSwitcher] client error: " + t);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeSocketQuietly() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
