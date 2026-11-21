package com.gen5w.launcher.svc;

import android.content.ComponentName;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.util.Base64;

import com.gen5w.launcher.ipc.TemperatureNormalizer;
import com.gen5w.launcher.ipc.VehicleQueryClient;

import hmgapi.client.HmgApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int EXPECTED_APP_VERSION_CODE = 25;
    private static final int MAX_IPC_LINE_BYTES = 16384;
    private static final long LOOP_TICK_MS = 2_000L;
    private static final long BOOT_GRACE_MS = 10_000L;
    private static final long HEALTH_CONFIRM_MS = 25_000L;
    private static final long DEADMAN_TIMEOUT_MS = 45_000L;
    private static final long FAST_VEHICLE_POLL_MS = 100L;
    private static final long EXTENDED_VEHICLE_POLL_MS = 500L;
    private static final long HMG_VEHICLE_POLL_MS = 1200L;
    private static final long IDLE_VEHICLE_POLL_MS = 2000L;
    private static final long VEHICLE_DEMAND_WINDOW_MS = 5000L;
    private static final long CAR_CMD_TIMEOUT_MS = 1800L;

    private final Object vehiclePollLock = new Object();
    private final Object liveVehicleLock = new Object();
    private final Set<String> extraVehicleKeys = new LinkedHashSet<String>();
    private final Set<String> inFlightCarCmds = Collections.synchronizedSet(new HashSet<String>());
    private final ExecutorService ipcClientPool = new ThreadPoolExecutor(
            2, 6, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(32),
            new DaemonThreadFactory("home-switcher-client-"),
            new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    if (r instanceof ClientTask) {
                        ((ClientTask) r).closeQuietly();
                    }
                }
            });
    private volatile ThreadPoolExecutor carCmdPool = createCarCmdPool();
    private final AtomicInteger carCmdPoolReplacements = new AtomicInteger(0);

    private static ThreadPoolExecutor createCarCmdPool() {
        return new ThreadPoolExecutor(
                1, 6, 30L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new DaemonThreadFactory("home-switcher-carcmd-"));
    }

    private final VehicleQueryClient vehicleClient = new VehicleQueryClient();
    private final DirectCarServiceBridge directCar = new DirectCarServiceBridge();
    private final Map<String, String> hmgCachedValues = new LinkedHashMap<String, String>();
    private final Map<String, String> directFastValues = new LinkedHashMap<String, String>();
    private final Map<String, String> directExtValues = new LinkedHashMap<String, String>();
    private final Map<String, String> mergedVehicleValues = new LinkedHashMap<String, String>();
    private volatile JSONObject cachedVehicleJson;
    private volatile long lastVehicleDemandMs = 0L;
    private volatile long lastVehiclePollMs = 0L;
    private volatile String lastHmgErr = "";

    private final SwitcherStateStore store;
    private final PreferredHomeHelper pmHelper = new PreferredHomeHelper();
    private final ProcessWatchHelper procWatch = new ProcessWatchHelper();
    private final UsbSafetyMonitor usbMon = new UsbSafetyMonitor();
    private final File modDir;

    private SwitcherStateStore.State state;
    private volatile boolean running = true;

    private volatile long bootGraceDeadlineMs;
    private boolean bootAutoChecked;
    private volatile long healthConfirmDeadlineMs;
    private volatile long deadmanDeadlineMs;
    private volatile UsbSafetyMonitor.Signal lastUsbSignal = UsbSafetyMonitor.Signal.NONE;
    private volatile String lastError = "";
    private volatile LocalServerSocket serverSocket;
    private volatile boolean apkSyncInProgress = false;
    private long lastPreferredReassertMs = 0L;
    private boolean hotRestartPendingBringFront = false;
    private boolean startedAsColdBoot = true;
    private boolean coldBootStrikeRecorded = false;
    private boolean chooserArmed = false;
    private boolean needsInitialChooserReset = false;

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
        if (this.state.schemaVersion < 3) {
            // One-time v0.4.7+ migration: earlier builds called setPreferredHome(STOCK_HOME) on shutdown/boot,
            // locking Launcher3 as "Always" in /data/system/packages.xml and suppressing Android's native
            // "Select a Home app" chooser when pressing the physical HOME button.
            boolean currentlyCustom = isCustomHomePreferred();
            if (currentlyCustom) {
                this.state.homeEnabled = true;
                this.state.userChoseStock = false;
                this.state.strikes = 0;
                this.state.trippedReason = "";
                this.state.lastEvent = "migrated_v047_preserved_custom";
            } else if (!"usb_kill_switch".equals(this.state.trippedReason)) {
                this.state.homeEnabled = false;
                this.state.userChoseStock = false;
                this.state.strikes = 0;
                this.state.trippedReason = "";
                this.state.lastEvent = "migrated_v047_native_chooser";
                this.needsInitialChooserReset = true;
            }
            this.state.schemaVersion = 3;
            this.store.save(this.state);
        }
        if (!this.state.homeEnabled && !this.state.userChoseStock && this.state.trippedReason.isEmpty()) {
            this.needsInitialChooserReset = true;
        }
    }

    private void run() {
        long startElapsedMs = SystemClock.elapsedRealtime();
        boolean isColdBoot = startElapsedMs < 90_000L;
        startedAsColdBoot = isColdBoot;
        bootGraceDeadlineMs = startElapsedMs + (isColdBoot ? BOOT_GRACE_MS : 2_500L);
        hotRestartPendingBringFront = !isColdBoot;

        String bootMsg = "started (isColdBoot=" + isColdBoot + " elapsed=" + startElapsedMs
                + "ms); homeEnabled=" + state.homeEnabled + " userChoseStock=" + state.userChoseStock
                + " strikes=" + state.strikes + " tripped=" + state.trippedReason;
        System.out.println("[HomeSwitcher] " + bootMsg);

        // Layer 2 on cold boot: NEVER blindly overwrite packages.xml with STOCK_HOME (doing so suppresses
        // Android's native Home chooser and erases the user's native "Always" selection). Instead, track
        // cold-boot strikes when Custom Home is active and only revert if 2+ consecutive unconfirmed resets occur.
        if (isColdBoot) {
            synchronized (this) {
                boolean customPreferred = isCustomHomePreferred();
                if (!isAutoActivateAllowedByModSetting()) {
                    if (customPreferred) {
                        System.out.println("[HomeSwitcher] auto_activate_on_boot=false in HMG-API settings; restoring STOCK_HOME");
                        pmHelper.setPreferredHome(PreferredHomeHelper.STOCK_HOME);
                        pmHelper.bringHomeToFront(PreferredHomeHelper.STOCK_HOME);
                    }
                } else if (customPreferred || (state.homeEnabled && !state.userChoseStock && state.trippedReason.isEmpty())) {
                    if (state.strikes >= 2) {
                        revertLocked("boot_strikes", true, "Gen5W Home failsafe: reverted to stock after repeated resets");
                    } else {
                        coldBootStrikeRecorded = true;
                        state.strikes += 1;
                        state.lastEvent = "cold_boot_strike_" + state.strikes;
                        store.save(state);
                        healthConfirmDeadlineMs = startElapsedMs + HEALTH_CONFIRM_MS;
                        procWatch.resetWatch();
                    }
                }
            }
        }

        registerShutdownHook();
        startIpcServer();
        syncModApkIfNeededAsync();
        startVehiclePoller();

        try {
            while (running) {
                long nowElapsed = SystemClock.elapsedRealtime();
                tick(nowElapsed);
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
                    if (disabled) {
                        // Only restore STOCK_HOME when the mod is disabled/uninstalled; NEVER lock STOCK_HOME
                        // on normal power-off or live NEMESIS updates so Android's native Home choice/chooser stays intact.
                        pmHelper.setPreferredHome(PreferredHomeHelper.STOCK_HOME);
                        state.homeEnabled = false;
                        state.userChoseStock = false;
                        state.strikes = 0;
                        state.trippedReason = "";
                        state.lastEvent = "mod_disabled_restored_stock";
                        store.save(state);
                        if (isBootCompleted()) {
                            pmHelper.bringHomeToFront(PreferredHomeHelper.STOCK_HOME);
                        }
                    } else {
                        System.out.println("[HomeSwitcher] shutdown: preserving PackageManager HOME preference (homeEnabled="
                                + state.homeEnabled + ", userChoseStock=" + state.userChoseStock + ")");
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

        // Wait for any in-flight APK replacement (pm install -r) to finish before switching preferred HOME
        // or sampling crash loops, since Android clears PreferredActivity and kills the old PID during install.
        if (apkSyncInProgress) {
            return;
        }

        // Arm Android's native "Select a Home app" chooser if neither launcher has been chosen by the user yet.
        if (needsInitialChooserReset) {
            needsInitialChooserReset = false;
            ComponentName cur = pmHelper.resolveCurrentHome();
            if (PreferredHomeHelper.CUSTOM_HOME.equals(cur)) {
                state.homeEnabled = true;
                state.userChoseStock = false;
                state.lastEvent = "detected_existing_custom_home";
                store.save(state);
            } else if (!state.homeEnabled && !state.userChoseStock && state.trippedReason.isEmpty()) {
                if (!pmHelper.isResolverComponent(cur)) {
                    pmHelper.clearHomePreferredActivities();
                }
                chooserArmed = true;
                System.out.println("[HomeSwitcher] Native Android Home Chooser armed; next physical HOME press will prompt user");
            }
        }

        // Track the user's choice in Android's native Home Picker ("Select a Home app" -> Always).
        ComponentName currentHome = pmHelper.resolveCurrentHome();
        if (PreferredHomeHelper.CUSTOM_HOME.equals(currentHome) && deadmanDeadlineMs == 0L) {
            chooserArmed = false;
            if (!state.homeEnabled || state.userChoseStock || !state.trippedReason.isEmpty()) {
                state.homeEnabled = true;
                state.userChoseStock = false;
                state.strikes = 0;
                state.trippedReason = "";
                state.lastEvent = "user_selected_custom_home";
                store.save(state);
                procWatch.resetWatch();
                System.out.println("[HomeSwitcher] User selected Gen5W Home as default launcher; persisted homeEnabled=true");
            }
        } else if (!state.homeEnabled && pmHelper.isResolverComponent(currentHome)) {
            chooserArmed = true;
        } else if (chooserArmed && PreferredHomeHelper.STOCK_HOME.equals(currentHome)) {
            chooserArmed = false;
            state.userChoseStock = true;
            state.homeEnabled = false;
            state.lastEvent = "user_selected_stock_home";
            store.save(state);
            System.out.println("[HomeSwitcher] User selected Stock Launcher in Android chooser; respecting choice");
        }

        // Layer 3: Cold-boot / service-start grace window
        if (!bootAutoChecked && now >= bootGraceDeadlineMs) {
            bootAutoChecked = true;
            maybeActivateAfterGrace(now);
            currentHome = pmHelper.resolveCurrentHome();
        }

        // Self-heal PreferredActivity if Android PackageManager cleared CATEGORY_HOME preference
        // (e.g. when NEMESIS/hmgapid updated Gen5WLauncher.apk or another launcher mod at runtime)
        // ONLY after the user has already chosen Gen5W Home (state.homeEnabled == true).
        if (bootAutoChecked
                && deadmanDeadlineMs == 0L
                && state.homeEnabled
                && !state.userChoseStock
                && !chooserArmed
                && state.trippedReason.isEmpty()
                && state.strikes < 2
                && (now - lastPreferredReassertMs) >= 6_000L
                && !PreferredHomeHelper.CUSTOM_HOME.equals(currentHome)
                && isAutoActivateAllowedByModSetting()) {
            lastPreferredReassertMs = now;
            if (pmHelper.setPreferredHome(PreferredHomeHelper.CUSTOM_HOME)) {
                procWatch.resetWatch();
                state.lastEvent = "reasserted_custom_home";
                store.save(state);
                currentHome = PreferredHomeHelper.CUSTOM_HOME;
                if (hotRestartPendingBringFront) {
                    hotRestartPendingBringFront = false;
                    pmHelper.bringHomeToFront(PreferredHomeHelper.CUSTOM_HOME);
                }
            }
        }

        // Layer 2: Clear boot strikes once custom Home stays healthy past HEALTH_CONFIRM_MS
        if (healthConfirmDeadlineMs > 0L && now >= healthConfirmDeadlineMs) {
            healthConfirmDeadlineMs = 0L;
            if (PreferredHomeHelper.CUSTOM_HOME.equals(currentHome) && state.strikes > 0) {
                state.strikes = 0;
                state.lastEvent = "boot_health_verified";
                store.save(state);
                System.out.println("[HomeSwitcher] custom HOME healthy after grace verification; strikes reset to 0");
            }
        }

        // Layer 4: 60s crash watchdog
        boolean customActive = PreferredHomeHelper.CUSTOM_HOME.equals(currentHome);
        if (procWatch.sampleAndCheckCrashLoop(now, customActive)) {
            revertLocked("watchdog_crashes", true, "Gen5W Home crashed repeatedly; restored stock Home");
        }
    }

    private void maybeActivateAfterGrace(long now) {
        if (!state.homeEnabled || state.userChoseStock) {
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
        if (isCustomHomePreferred()) {
            hotRestartPendingBringFront = false;
            if (healthConfirmDeadlineMs == 0L && state.strikes > 0) {
                healthConfirmDeadlineMs = now + HEALTH_CONFIRM_MS;
            }
            return;
        }
        if (startedAsColdBoot && !coldBootStrikeRecorded) {
            if (state.strikes >= 2) {
                revertLocked("boot_strikes", true, "Gen5W Home failsafe: reverted to stock after repeated resets");
                return;
            }
            coldBootStrikeRecorded = true;
            state.strikes += 1;
            state.lastEvent = "boot_activating_strike_" + state.strikes;
            store.save(state);
        } else if (!startedAsColdBoot) {
            state.lastEvent = "hot_restart_activating";
            store.save(state);
        }

        if (pmHelper.setPreferredHome(PreferredHomeHelper.CUSTOM_HOME)) {
            procWatch.resetWatch();
            chooserArmed = false;
            hotRestartPendingBringFront = false;
            lastPreferredReassertMs = now;
            healthConfirmDeadlineMs = now + HEALTH_CONFIRM_MS;
            pmHelper.bringHomeToFront(PreferredHomeHelper.CUSTOM_HOME);
        } else {
            lastError = "replacePreferredActivity failed on boot activation";
        }
    }

    private void revertLocked(String reason, boolean persistDisable, String toastMsg) {
        deadmanDeadlineMs = 0L;
        healthConfirmDeadlineMs = 0L;
        chooserArmed = false;
        needsInitialChooserReset = false;
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
        long now = SystemClock.elapsedRealtime();
        if ("PROMPT_CHOOSER".equals(cmd)) {
            UsbSafetyMonitor.Signal usb = usbMon.scan();
            if (usb != UsbSafetyMonitor.Signal.NONE) {
                lastError = "USB safety file (" + usb.name() + ") prevents activation";
                return buildStatusJson(now, false, lastError);
            }
            deadmanDeadlineMs = 0L;
            healthConfirmDeadlineMs = 0L;
            state.homeEnabled = false;
            state.userChoseStock = false;
            state.trippedReason = "";
            state.strikes = 0;
            state.lastEvent = "manual_prompt_chooser";
            store.save(state);
            chooserArmed = true;
            needsInitialChooserReset = false;
            lastError = "";
            boolean ok = pmHelper.promptHomeChooserNow();
            return buildStatusJson(now, true,
                    ok ? "Select your preferred Home app" : "Cleared default Home — press the HOME button");
        } else if ("ACTIVATE_TEST".equals(cmd)) {
            UsbSafetyMonitor.Signal usb = usbMon.scan();
            if (usb != UsbSafetyMonitor.Signal.NONE) {
                lastError = "USB safety file (" + usb.name() + ") prevents activation";
                return buildStatusJson(now, false, lastError);
            }
            if (!pmHelper.isPackageInstalled(CUSTOM_PKG)) {
                lastError = "com.gen5w.launcher is not installed";
                return buildStatusJson(now, false, lastError);
            }
            chooserArmed = false;
            needsInitialChooserReset = false;
            state.userChoseStock = false;
            state.trippedReason = "";
            state.strikes = 0;
            state.lastEvent = "deadman_test_started";
            store.save(state);
            procWatch.resetWatch();
            if (!pmHelper.setPreferredHome(PreferredHomeHelper.CUSTOM_HOME)) {
                lastError = "IPackageManager.replacePreferredActivity failed";
                return buildStatusJson(now, false, lastError);
            }
            procWatch.resetWatch();
            deadmanDeadlineMs = now + DEADMAN_TIMEOUT_MS;
            lastPreferredReassertMs = now;
            lastError = "";
            pmHelper.bringHomeToFront(PreferredHomeHelper.CUSTOM_HOME);
            return buildStatusJson(now, true, "Test active (45s): tap Confirm to keep");
        } else if ("CONFIRM".equals(cmd)) {
            UsbSafetyMonitor.Signal usb = usbMon.scan();
            if (usb != UsbSafetyMonitor.Signal.NONE) {
                lastError = "USB safety file (" + usb.name() + ") prevents activation";
                return buildStatusJson(now, false, lastError);
            }
            chooserArmed = false;
            needsInitialChooserReset = false;
            if (!isCustomHomePreferred()) {
                if (!pmHelper.setPreferredHome(PreferredHomeHelper.CUSTOM_HOME)) {
                    lastError = "IPackageManager.replacePreferredActivity failed";
                    return buildStatusJson(now, false, lastError);
                }
                procWatch.resetWatch();
                pmHelper.bringHomeToFront(PreferredHomeHelper.CUSTOM_HOME);
            }
            deadmanDeadlineMs = 0L;
            lastPreferredReassertMs = now;
            state.homeEnabled = true;
            state.userChoseStock = false;
            state.strikes = 0;
            state.trippedReason = "";
            state.lastEvent = "confirmed_default_home";
            store.save(state);
            lastError = "";
            return buildStatusJson(now, true, "Custom HOME confirmed and saved");
        } else if ("REVERT".equals(cmd)) {
            state.userChoseStock = true;
            revertLocked("manual_revert", true, "Restored stock Ioniq 5 Launcher");
            return buildStatusJson(now, true, "Reverted to stock Launcher3");
        } else if ("CLEAR_STRIKES".equals(cmd)) {
            state.strikes = 0;
            state.trippedReason = "";
            state.userChoseStock = false;
            state.lastEvent = "strikes_cleared";
            store.save(state);
            if (!state.homeEnabled) {
                pmHelper.clearHomePreferredActivities();
                chooserArmed = true;
            }
            lastError = "";
            return buildStatusJson(now, true,
                    state.homeEnabled ? "Failsafe state cleared" : "Failsafe cleared — press HOME to pick launcher");
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
        if (cmd.startsWith("CAR_CMD|")) {
            return handleCarCmdCommand(cmd);
        }
        return handleCommand(cmd);
    }

    private JSONObject handleCarCmdCommand(String raw) throws Exception {
        String[] parts = raw.split("\\|", 3);
        final String carCmd = parts.length > 1 ? parts[1].trim() : "";
        final String carArg = parts.length > 2 ? parts[2].trim() : "";
        ThreadPoolExecutor pool = carCmdPool;
        if (carCmd.isEmpty()) {
            return buildCarCmdJson(false, "Empty car command");
        }
        if (!inFlightCarCmds.add(carCmd)) {
            return buildCarCmdJson(false, "Busy / timed out on this vehicle: " + carCmd);
        }
        final Map<String, String> snap;
        synchronized (liveVehicleLock) {
            snap = new LinkedHashMap<String, String>(mergedVehicleValues);
        }
        Callable<DirectCarServiceBridge.CommandReply> task = new Callable<DirectCarServiceBridge.CommandReply>() {
            @Override
            public DirectCarServiceBridge.CommandReply call() {
                try {
                    return directCar.executeCommand(carCmd, carArg, snap);
                } finally {
                    inFlightCarCmds.remove(carCmd);
                }
            }
        };
        Future<DirectCarServiceBridge.CommandReply> future;
        try {
            future = pool.submit(task);
        } catch (RejectedExecutionException rej) {
            if (carCmdPoolReplacements.get() < 2 && carCmdPoolReplacements.incrementAndGet() <= 2) {
                ThreadPoolExecutor fresh = createCarCmdPool();
                carCmdPool = fresh;
                try {
                    future = fresh.submit(task);
                } catch (RejectedExecutionException rej2) {
                    inFlightCarCmds.remove(carCmd);
                    return buildCarCmdJson(false, "Car control workers busy; previous commands still blocked");
                }
            } else {
                inFlightCarCmds.remove(carCmd);
                return buildCarCmdJson(false, "Car control workers busy; previous commands still blocked");
            }
        }

        DirectCarServiceBridge.CommandReply reply;
        try {
            reply = future.get(CAR_CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            System.err.println("[HomeSwitcher] CAR_CMD timed out after " + CAR_CMD_TIMEOUT_MS + "ms: " + carCmd);
            return buildCarCmdJson(false, "Timed out (1.8s): " + carCmd + " did not respond on this vehicle");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            String errMsg = cause == null ? ee.getMessage() : cause.getMessage();
            return buildCarCmdJson(false, "Error executing " + carCmd + ": " + errMsg);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return buildCarCmdJson(false, "Interrupted: " + carCmd);
        }

        synchronized (vehiclePollLock) {
            lastVehicleDemandMs = SystemClock.elapsedRealtime();
            vehiclePollLock.notifyAll();
        }
        return buildCarCmdJson(reply.ok, reply.message);
    }

    private JSONObject buildCarCmdJson(boolean ok, String message) throws Exception {
        JSONObject j = new JSONObject();
        j.put("ok", ok);
        j.put("message", message == null ? "" : message);
        JSONObject cached = cachedVehicleJson;
        if (cached != null && cached.optJSONObject("values") != null) {
            j.put("values", cached.optJSONObject("values"));
        }
        return j;
    }

    private JSONObject handleSetVehicleKeysCommand(String raw) throws Exception {
        Set<String> hmgFiltered = new LinkedHashSet<String>();
        int validInputCount = 0;
        if (raw != null && !raw.trim().isEmpty()) {
            String[] parts = raw.split(",");
            for (String p : parts) {
                String k = p.trim();
                if (!VehicleQueryClient.isValidKey(k)) {
                    continue;
                }
                validInputCount++;
                if (VehicleQueryClient.isHmgApiDeclaredKey(k) && hmgFiltered.size() < 64) {
                    hmgFiltered.add(k);
                }
            }
        }
        boolean changed;
        synchronized (vehiclePollLock) {
            changed = !extraVehicleKeys.equals(hmgFiltered);
            if (changed) {
                extraVehicleKeys.clear();
                extraVehicleKeys.addAll(hmgFiltered);
                vehicleClient.clearQuarantine();
                if (lastVehicleDemandMs > 0L) {
                    vehiclePollLock.notifyAll();
                }
            }
        }
        JSONObject j = new JSONObject();
        j.put("ok", true);
        j.put("changed", changed);
        j.put("count", validInputCount);
        j.put("hmgCount", hmgFiltered.size());
        return j;
    }

    private JSONObject buildVehicleResponse() throws Exception {
        long now = SystemClock.elapsedRealtime();
        synchronized (vehiclePollLock) {
            lastVehicleDemandMs = now;
            if (cachedVehicleJson == null || (now - lastVehiclePollMs) > 250L) {
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

    private void publishMergedVehicleLocked() throws Exception {
        mergedVehicleValues.clear();
        for (Map.Entry<String, String> e : hmgCachedValues.entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isEmpty() && !"-".equals(v)) {
                mergedVehicleValues.put(e.getKey(), v);
            }
        }
        for (Map.Entry<String, String> e : directExtValues.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null || v.isEmpty() || "-".equals(v)) {
                continue;
            }
            if ("hv.soc".equals(k) || "odometer".equals(k)) {
                String existing = mergedVehicleValues.get(k);
                if (existing != null && !existing.isEmpty() && !"-".equals(existing)) {
                    continue;
                }
            }
            mergedVehicleValues.put(k, v);
        }
        for (Map.Entry<String, String> e : directFastValues.entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isEmpty() && !"-".equals(v)) {
                mergedVehicleValues.put(e.getKey(), v);
            }
        }
        TemperatureNormalizer.normalizeVehicleMap(mergedVehicleValues);

        JSONObject vals = new JSONObject();
        for (Map.Entry<String, String> entry : mergedVehicleValues.entrySet()) {
            vals.put(entry.getKey(), entry.getValue());
        }
        boolean valid = vals.length() > 0;
        JSONObject snap = new JSONObject();
        snap.put("ok", valid);
        snap.put("code", valid ? 0 : -1);
        snap.put("error", valid ? "" : (lastHmgErr == null || lastHmgErr.isEmpty() ? "no readings yet" : "hmgapid " + lastHmgErr));
        snap.put("values", vals);
        snap.put("updatedMs", System.currentTimeMillis());
        cachedVehicleJson = snap;
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
        int currentInstalled = pmHelper.getPackageVersionCode(CUSTOM_PKG);
        if (currentInstalled >= EXPECTED_APP_VERSION_CODE) {
            return;
        }
        apkSyncInProgress = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int installed = pmHelper.getPackageVersionCode(CUSTOM_PKG);
                    if (installed < EXPECTED_APP_VERSION_CODE) {
                        boolean wasCustom = isCustomHomePreferred();
                        System.out.println("[HomeSwitcher] syncing Gen5WLauncher APK (" + installed + " -> " + EXPECTED_APP_VERSION_CODE + ")");
                        if (pmHelper.installApkReplace(apk)) {
                            boolean shouldRestoreCustom;
                            boolean shouldArmChooser;
                            synchronized (HomeSwitcher.this) {
                                procWatch.resetWatch();
                                shouldRestoreCustom = wasCustom
                                        || (state.homeEnabled && !state.userChoseStock && state.trippedReason.isEmpty()
                                        && isAutoActivateAllowedByModSetting());
                                shouldArmChooser = !shouldRestoreCustom
                                        && !state.userChoseStock
                                        && state.trippedReason.isEmpty();
                            }
                            if (shouldRestoreCustom) {
                                if (pmHelper.setPreferredHome(PreferredHomeHelper.CUSTOM_HOME)) {
                                    synchronized (HomeSwitcher.this) {
                                        procWatch.resetWatch();
                                        chooserArmed = false;
                                        needsInitialChooserReset = false;
                                    }
                                    pmHelper.bringHomeToFront(PreferredHomeHelper.CUSTOM_HOME);
                                }
                            } else if (shouldArmChooser) {
                                pmHelper.clearHomePreferredActivities();
                                synchronized (HomeSwitcher.this) {
                                    chooserArmed = true;
                                    needsInitialChooserReset = false;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[HomeSwitcher] syncModApkIfNeeded error: " + t);
                } finally {
                    apkSyncInProgress = false;
                }
            }
        }, "home-switcher-apksync");
        t.setDaemon(true);
        t.start();
    }

    private void startVehiclePoller() {
        // Dedicated slower HMG-API helper thread so callback-heavy or blocking readings (like battery temp)
        // never stall the 100ms direct Binder fast-sensor loop.
        Thread hmgThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    long now = SystemClock.elapsedRealtime();
                    if (lastVehicleDemandMs <= 0L || (now - lastVehicleDemandMs) > VEHICLE_DEMAND_WINDOW_MS) {
                        synchronized (vehiclePollLock) {
                            if (!running) break;
                            now = SystemClock.elapsedRealtime();
                            if (lastVehicleDemandMs <= 0L || (now - lastVehicleDemandMs) > VEHICLE_DEMAND_WINDOW_MS) {
                                try {
                                    vehiclePollLock.wait(IDLE_VEHICLE_POLL_MS);
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                                continue;
                            }
                        }
                    }
                    long startMs = SystemClock.elapsedRealtime();
                    try {
                        List<String> keys = new ArrayList<String>();
                        for (String ck : VehicleQueryClient.CORE_KEYS) {
                            if (VehicleQueryClient.isHmgApiDeclaredKey(ck) && !keys.contains(ck)) {
                                keys.add(ck);
                            }
                        }
                        for (String extra : new String[] {
                                "hv.ready", "charging", "hv.soc", "outside_temp_f", "outside_temp",
                                "wheel_heat", "defogger", "hv.conditioning_active", "driving.restricted", "odometer"
                        }) {
                            if (!keys.contains(extra)) keys.add(extra);
                        }
                        synchronized (vehiclePollLock) {
                            for (String k : extraVehicleKeys) {
                                if (VehicleQueryClient.isHmgApiDeclaredKey(k) && !keys.contains(k)) {
                                    keys.add(k);
                                }
                            }
                        }
                        VehicleQueryClient.BatchResult res = vehicleClient.query(keys);
                        lastHmgErr = res.errorNote == null ? "" : res.errorNote;
                        synchronized (liveVehicleLock) {
                            if (res.anySuccess && !res.values.isEmpty()) {
                                hmgCachedValues.putAll(res.values);
                            }
                            publishMergedVehicleLocked();
                        }
                    } catch (Throwable ex) {
                        lastHmgErr = String.valueOf(ex.getMessage());
                    }
                    long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - startMs);
                    long sleep = Math.max(250L, HMG_VEHICLE_POLL_MS - elapsed);
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }, "home-switcher-hmgapi");
        hmgThread.setDaemon(true);
        hmgThread.start();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastExtMs = -EXTENDED_VEHICLE_POLL_MS;
                while (running) {
                    long checkNow = SystemClock.elapsedRealtime();
                    if (lastVehicleDemandMs <= 0L || (checkNow - lastVehicleDemandMs) > VEHICLE_DEMAND_WINDOW_MS) {
                        synchronized (vehiclePollLock) {
                            if (!running) {
                                break;
                            }
                            checkNow = SystemClock.elapsedRealtime();
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
                    long loopStartMs = SystemClock.elapsedRealtime();
                    try {
                        Map<String, String> fastFresh = new LinkedHashMap<String, String>();
                        directCar.pollFast(fastFresh);
                        Map<String, String> extFresh = null;
                        if ((loopStartMs - lastExtMs) >= EXTENDED_VEHICLE_POLL_MS) {
                            lastExtMs = loopStartMs;
                            extFresh = new LinkedHashMap<String, String>();
                            directCar.pollExtended(extFresh);
                        }
                        synchronized (liveVehicleLock) {
                            if (!fastFresh.isEmpty()) {
                                directFastValues.putAll(fastFresh);
                            }
                            if (extFresh != null && !extFresh.isEmpty()) {
                                directExtValues.putAll(extFresh);
                            }
                            publishMergedVehicleLocked();
                        }
                    } catch (Throwable ex) {
                        System.err.println("[HomeSwitcher] direct telemetry error: " + ex);
                    }
                    long nowMs = SystemClock.elapsedRealtime();
                    lastVehiclePollMs = nowMs;
                    long intervalMs = (nowMs - lastVehicleDemandMs) <= VEHICLE_DEMAND_WINDOW_MS
                            ? FAST_VEHICLE_POLL_MS
                            : IDLE_VEHICLE_POLL_MS;
                    long elapsedMs = Math.max(0L, nowMs - loopStartMs);
                    long waitMs = Math.max(25L, intervalMs - elapsedMs);
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
            JSONObject s = HmgApi.connect().timeout(600).settings();
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
                } catch (IOException bindErr) {
                    lastError = "IPC socket bind error: " + bindErr.getMessage();
                    System.err.println("[HomeSwitcher] IPC bind error: " + bindErr);
                    return;
                }
                while (running) {
                    LocalSocket client = null;
                    try {
                        client = serverSocket.accept();
                        ipcClientPool.execute(new ClientTask(client));
                        client = null; // ownership transferred to ClientTask / RejectedExecutionHandler
                    } catch (IOException acceptErr) {
                        closeSocketQuietly(client);
                        if (!running) {
                            break;
                        }
                        lastError = "IPC accept error: " + acceptErr.getMessage();
                        System.err.println("[HomeSwitcher] IPC accept error: " + acceptErr);
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    } catch (Throwable t) {
                        closeSocketQuietly(client);
                        if (!running) {
                            break;
                        }
                        System.err.println("[HomeSwitcher] IPC loop error: " + t);
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            }
        }, "home-switcher-ipc");
        ipcThread.setDaemon(true);
        ipcThread.start();
    }

    private final class ClientTask implements Runnable {
        private final LocalSocket client;

        ClientTask(LocalSocket client) {
            this.client = client;
        }

        @Override
        public void run() {
            handleClient(client);
        }

        void closeQuietly() {
            closeSocketQuietly(client);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(1);

        DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
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
            client.setSoTimeout(2500);
            InputStream in = client.getInputStream();
            String line = readBoundedUtf8Line(in, MAX_IPC_LINE_BYTES);
            JSONObject resp = dispatchCommand(line);
            OutputStream out = client.getOutputStream();
            out.write((resp.toString() + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            System.err.println("[HomeSwitcher] client error: " + t);
        } finally {
            closeSocketQuietly(client);
        }
    }

    private static String readBoundedUtf8Line(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int b;
        boolean sawAny = false;
        while ((b = in.read()) != -1) {
            sawAny = true;
            if (b == '\n') {
                break;
            }
            if (b == '\r') {
                continue;
            }
            if (bos.size() >= maxBytes) {
                throw new IOException("IPC request line exceeded " + maxBytes + " bytes");
            }
            bos.write(b);
        }
        if (!sawAny && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static final Object LIBCORE_OS;
    private static final Method LIBCORE_OS_CLOSE;
    static {
        Object osObj = null;
        Method closeMethod = null;
        try {
            Class<?> libcoreCls = Class.forName("libcore.io.Libcore");
            osObj = libcoreCls.getField("os").get(null);
            if (osObj != null) {
                closeMethod = osObj.getClass().getMethod("close", FileDescriptor.class);
            }
        } catch (Throwable ignored) {
        }
        LIBCORE_OS = osObj;
        LIBCORE_OS_CLOSE = closeMethod;
    }

    /**
     * Closes an accepted LocalSocket without leaking its underlying OS file descriptor on Android 4.4 KitKat.
     * On Gen5W KitKat, LocalServerSocket.accept() never sets ownsFd=true on LocalSocketImpl, so s.close()
     * drops the Java reference while leaving the Unix domain socket FD open until EMFILE kills IPC!
     */
    private static void closeSocketQuietly(LocalSocket s) {
        if (s == null) {
            return;
        }
        try {
            FileDescriptor fd = s.getFileDescriptor();
            if (fd != null && fd.valid() && LIBCORE_OS != null && LIBCORE_OS_CLOSE != null) {
                LIBCORE_OS_CLOSE.invoke(LIBCORE_OS, fd);
            }
        } catch (Throwable ignored) {
        }
        try {
            s.close();
        } catch (Throwable ignored) {
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
