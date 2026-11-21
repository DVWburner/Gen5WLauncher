package com.gen5w.launcher.ipc;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls HMG-API live vehicle telemetry cleanly off the UI thread and delivers
 * immutable snapshots to the active UI when the launcher is visible.
 */
public final class TelemetryModel {
    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(false, null, "");

        public final boolean connected;
        public final String hvSoc;
        public final String aux12vSoc;
        public final String charging;
        public final String hvReady;
        public final String gearPark;
        public final String speed;
        public final String outsideTempF;
        public final String outsideTempC;
        public final String drivingRestricted;
        public final String odometer;
        public final String error;
        public final Map<String, String> allValues;

        Snapshot(boolean connected, Map<String, String> m, String error) {
            this.connected = connected;
            this.allValues = m == null ? Collections.<String, String>emptyMap() : m;
            this.hvSoc = pick(allValues, "hv.soc");
            this.aux12vSoc = pick(allValues, "battery12v.soc");
            this.charging = pick(allValues, "charging");
            this.hvReady = pick(allValues, "hv.ready");
            this.gearPark = pick(allValues, "gear.park");
            this.speed = pick(allValues, "speed");
            this.outsideTempF = pick(allValues, "outside_temp_f");
            this.outsideTempC = pick(allValues, "outside_temp");
            this.drivingRestricted = pick(allValues, "driving.restricted");
            this.odometer = pick(allValues, "odometer");
            this.error = error == null ? "" : error;
        }

        public boolean isChargingActive() {
            return !"0".equals(charging) && !"--".equals(charging) && !charging.isEmpty() && !"off".equalsIgnoreCase(charging);
        }

        public boolean isInPark() {
            return "1".equals(gearPark) || "true".equalsIgnoreCase(gearPark);
        }

        public boolean isReady() {
            return !"0".equals(hvReady) && !"--".equals(hvReady) && !hvReady.isEmpty();
        }

        public float parseHvSocPercent(float fallback) {
            try {
                if ("--".equals(hvSoc) || hvSoc.isEmpty()) {
                    return fallback;
                }
                return Math.max(0f, Math.min(100f, Float.parseFloat(hvSoc)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        public String getRaw(String key) {
            if (key == null || key.isEmpty()) return "--";
            return pick(allValues, canonicalKey(key));
        }

        private static String pick(Map<String, String> m, String key) {
            String v = m.get(key);
            if (v == null) {
                return "--";
            }
            String trimmed = v.trim();
            return (trimmed.isEmpty() || "-".equals(trimmed)) ? "--" : trimmed;
        }
    }

    public static final long POLL_INTERVAL_MS = 100L;
    private static final long SLOW_POLL_INTERVAL_MS = 1500L;
    private static final long HEAVY_POLL_INTERVAL_MS = 5000L;
    private static final long MIN_POLL_SLEEP_MS = 30L;
    private static final long UI_HEARTBEAT_MS = 1500L;
    private static final int MAX_SWITCHER_TRANSIENT_MISSES = 5;

    public interface Listener {
        void onTelemetryUpdated(Snapshot snapshot);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object wakeLock = new Object();
    private final Object stateLock = new Object();
    private final Set<String> extraKeys = new LinkedHashSet<String>();
    private final VehicleQueryClient directClient = new VehicleQueryClient();
    private final Map<String, String> mergedValues = new LinkedHashMap<String, String>();
    private final AtomicReference<Snapshot> pendingUiSnapshot = new AtomicReference<Snapshot>();
    private final AtomicBoolean uiPostPending = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile boolean polling = false;
    private volatile AtomicBoolean activeRunToken;
    private volatile boolean needsFullSync = true;
    private volatile Snapshot latest = new Snapshot(false, null, "not polled");
    private Map<String, String> lastDeliveredValues = Collections.emptyMap();
    private boolean lastDeliveredConnected = false;
    private String lastDeliveredError = "";
    private long lastDeliveredAtMs = 0L;
    private boolean switcherEverSucceeded = false;
    private int switcherConsecutiveMisses = 0;
    private Thread worker;

    private final Runnable uiDeliverRunnable = new Runnable() {
        @Override
        public void run() {
            uiPostPending.set(false);
            Snapshot snap = pendingUiSnapshot.getAndSet(null);
            Listener target = listener;
            if (polling && snap != null && target != null) {
                target.onTelemetryUpdated(snap);
            }
        }
    };

    public static String canonicalKey(String key) {
        if (key == null) return "";
        String k = key.trim();
        if ("hv_soc".equals(k)) return "hv.soc";
        if ("hv_range".equals(k) || "hv.range_mi".equals(k) || "hv.range_km".equals(k)) return "hv.range";
        if ("climate_temp_driver".equals(k)) return "climate.driver_temp";
        if ("climate_temp_passenger".equals(k)) return "climate.passenger_temp";
        if ("climate_fan".equals(k)) return "climate.fan_speed";
        if ("volume".equals(k)) return "audio.volume";
        if ("aux12v_soc".equals(k)) return "battery12v.soc";
        if ("outside_temp_c".equals(k)) return "outside_temp";
        if ("hv_ready".equals(k)) return "hv.ready";
        if ("gear_park".equals(k)) return "gear.park";
        if ("driving_restricted".equals(k)) return "driving.restricted";
        if ("hv.battery_temp_min_f".equals(k) || "hv.battery_temp_min_c".equals(k)) return "hv.battery_temp_min";
        if ("speed_mph".equals(k) || "speed_kmh".equals(k)) return "speed";
        if ("odometer_mi".equals(k) || "odometer_km".equals(k)) return "odometer";
        return k;
    }

    public synchronized void setExtraKeys(Collection<String> keys) {
        extraKeys.clear();
        if (keys != null) {
            for (String raw : keys) {
                String k = canonicalKey(raw);
                if (!"gear_speed".equals(k) && VehicleQueryClient.isHmgApiDeclaredKey(k)) {
                    extraKeys.add(k);
                }
            }
        }
        directClient.clearQuarantine();
        needsFullSync = true;
        if (polling) {
            SwitcherClient.setVehicleKeysAsync(new ArrayList<String>(extraKeys));
        }
    }

    public void requestImmediateRefresh() {
        needsFullSync = true;
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    private synchronized List<String> snapshotExtraKeys() {
        return new ArrayList<String>(extraKeys);
    }

    private synchronized void buildTieredKeys(List<String> fastOut, List<String> slowOut, List<String> heavyOut) {
        Set<String> extraSnap = new LinkedHashSet<String>(extraKeys);

        // Fast tier: <= 14 live driving sensors (100-500ms HMG-API freshness, single IPC call)
        fastOut.add("speed");
        fastOut.add("gear.park");
        for (String fk : new String[] {
                "pedal.accel", "brake.pressure", "steering.angle",
                "moving", "wheel_heat", "defogger", "driving.restricted"
        }) {
            if (extraSnap.contains(fk) && !fastOut.contains(fk)) {
                fastOut.add(fk);
            }
        }

        // Slow tier: 1-5s non-blocking vehicle status sensors
        Set<String> slowSet = new LinkedHashSet<String>(Arrays.asList(
                "hv.ready", "charging", "hv.soc", "outside_temp_f", "outside_temp"));
        // Heavy/blocking tier: 5-60s sensors (including hv.battery_temp_min which may sleep up to 500ms)
        Set<String> heavySet = new LinkedHashSet<String>(Arrays.asList(
                "battery12v.soc", "odometer"));

        for (String k : extraSnap) {
            if (fastOut.contains(k)) continue;
            if (isHeavyOrVerySlowKey(k)) {
                heavySet.add(k);
            } else {
                slowSet.add(k);
            }
        }
        slowOut.addAll(slowSet);
        heavyOut.addAll(heavySet);
    }

    private static boolean isHeavyOrVerySlowKey(String k) {
        return "hv.battery_temp_min".equals(k)
                || "hv.health".equals(k)
                || "hv.conditioning".equals(k)
                || "hv.conditioning_failure".equals(k)
                || "odometer".equals(k)
                || k.startsWith("battery12v.");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Snapshot getLatest() {
        return latest;
    }

    public synchronized void start() {
        if (polling) {
            return;
        }
        polling = true;
        needsFullSync = true;
        synchronized (stateLock) {
            lastDeliveredAtMs = 0L;
            switcherConsecutiveMisses = 0;
        }
        final AtomicBoolean runToken = new AtomicBoolean(true);
        activeRunToken = runToken;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                SwitcherClient.setVehicleKeysBlocking(snapshotExtraKeys());
                long lastSlowMs = -SLOW_POLL_INTERVAL_MS;
                long lastHeavyMs = -HEAVY_POLL_INTERVAL_MS;
                while (runToken.get() && polling) {
                    long tickStartMs = SystemClock.elapsedRealtime();
                    boolean forceAll = needsFullSync;
                    if (forceAll) {
                        needsFullSync = false;
                    }
                    boolean dueSlow = forceAll || (tickStartMs - lastSlowMs) >= SLOW_POLL_INTERVAL_MS;
                    boolean dueHeavy = forceAll || (tickStartMs - lastHeavyMs) >= HEAVY_POLL_INTERVAL_MS;
                    if (dueSlow) lastSlowMs = tickStartMs;
                    if (dueHeavy) lastHeavyMs = tickStartMs;

                    Snapshot snap = fetchTiered(dueSlow, dueHeavy, runToken);
                    if (!runToken.get() || !polling) {
                        break;
                    }
                    long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - tickStartMs);
                    if (snap != null) {
                        deliverSnapshot(snap);
                    }
                    long sleepMs = Math.max(MIN_POLL_SLEEP_MS, POLL_INTERVAL_MS - elapsedMs);
                    synchronized (wakeLock) {
                        if (!runToken.get() || !polling) {
                            break;
                        }
                        if (!needsFullSync) {
                            try {
                                wakeLock.wait(sleepMs);
                            } catch (InterruptedException ignored) {
                                break;
                            }
                        }
                    }
                }
            }
        }, "gen5w-telemetry");
        worker.setDaemon(true);
        worker.start();
    }

    private void deliverSnapshot(final Snapshot snap) {
        latest = snap;
        final Listener target = listener;
        if (target != null) {
            pendingUiSnapshot.set(snap);
            if (uiPostPending.compareAndSet(false, true)) {
                mainHandler.post(uiDeliverRunnable);
            }
        }
    }

    public synchronized void stop() {
        polling = false;
        AtomicBoolean token = activeRunToken;
        if (token != null) {
            token.set(false);
            activeRunToken = null;
        }
        mainHandler.removeCallbacks(uiDeliverRunnable);
        uiPostPending.set(false);
        pendingUiSnapshot.set(null);
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private static void mergeValidReadingsLocked(Map<String, String> dst, Map<String, String> src) {
        if (src == null) {
            return;
        }
        for (Map.Entry<String, String> e : src.entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isEmpty() && !"-".equals(v)) {
                dst.put(e.getKey(), v);
            }
        }
    }

    private Snapshot maybeBuildChangedSnapshotLocked(boolean connected, String err) {
        long nowMs = SystemClock.elapsedRealtime();
        boolean sameState = (connected == lastDeliveredConnected)
                && err.equals(lastDeliveredError)
                && mergedValues.equals(lastDeliveredValues);
        if (sameState && lastDeliveredAtMs > 0L && (nowMs - lastDeliveredAtMs) < UI_HEARTBEAT_MS) {
            return null;
        }
        Map<String, String> copy = new LinkedHashMap<String, String>(mergedValues);
        lastDeliveredValues = copy;
        lastDeliveredConnected = connected;
        lastDeliveredError = err;
        lastDeliveredAtMs = nowMs;
        return new Snapshot(connected, copy, err);
    }

    private Snapshot fetchTiered(boolean dueSlow, boolean dueHeavy, AtomicBoolean runToken) {
        if (!runToken.get()) {
            return null;
        }
        // Primary path: read unified 100ms Direct-Binder + HMG-API cache from root HomeSwitcher.
        // Gives instant steering/pedal/speed/climate/seat/audio updates with zero Binder stall on the launcher.
        SwitcherClient.VehicleReply cached = SwitcherClient.fetchVehicleBlocking();
        if (!runToken.get()) {
            return null;
        }
        synchronized (stateLock) {
            if (cached.ok && !cached.values.isEmpty()) {
                switcherEverSucceeded = true;
                switcherConsecutiveMisses = 0;
                mergeValidReadingsLocked(mergedValues, cached.values);
                return maybeBuildChangedSnapshotLocked(true, "");
            }

            // Tolerate brief transient IPC misses once connected to HomeSwitcher without triggering
            // fallback multi-threaded directClient queries.
            if (switcherEverSucceeded && switcherConsecutiveMisses < MAX_SWITCHER_TRANSIENT_MISSES) {
                switcherConsecutiveMisses++;
                return null;
            }
        }

        // Fallback path: if HomeSwitcher daemon is unavailable or warming up, query hmgapid directly.
        List<String> fastKeys = new ArrayList<String>();
        List<String> slowKeys = new ArrayList<String>();
        List<String> heavyKeys = new ArrayList<String>();
        buildTieredKeys(fastKeys, slowKeys, heavyKeys);

        StringBuilder errNote = new StringBuilder();
        if (!cached.error.isEmpty()) {
            errNote.append("svc=").append(cached.error);
        }

        VehicleQueryClient.BatchResult fastRes = directClient.query(fastKeys);
        VehicleQueryClient.BatchResult slowRes = (dueSlow && !slowKeys.isEmpty() && runToken.get())
                ? directClient.query(slowKeys)
                : null;
        VehicleQueryClient.BatchResult heavyRes = (dueHeavy && !heavyKeys.isEmpty() && runToken.get())
                ? directClient.query(heavyKeys)
                : null;

        if (!runToken.get()) {
            return null;
        }

        synchronized (stateLock) {
            if (fastRes.anySuccess && !fastRes.values.isEmpty()) {
                mergeValidReadingsLocked(mergedValues, fastRes.values);
            } else if (!fastRes.errorNote.isEmpty()) {
                if (errNote.length() > 0) errNote.append("; ");
                errNote.append("fast ").append(fastRes.errorNote);
            }

            if (slowRes != null) {
                if (slowRes.anySuccess && !slowRes.values.isEmpty()) {
                    mergeValidReadingsLocked(mergedValues, slowRes.values);
                } else if (!slowRes.errorNote.isEmpty() && errNote.length() < 120) {
                    if (errNote.length() > 0) errNote.append("; ");
                    errNote.append("slow ").append(slowRes.errorNote);
                }
            }

            if (heavyRes != null) {
                if (heavyRes.anySuccess && !heavyRes.values.isEmpty()) {
                    mergeValidReadingsLocked(mergedValues, heavyRes.values);
                } else if (!heavyRes.errorNote.isEmpty() && errNote.length() < 120) {
                    if (errNote.length() > 0) errNote.append("; ");
                    errNote.append("heavy ").append(heavyRes.errorNote);
                }
            }

            if (!mergedValues.isEmpty()) {
                return maybeBuildChangedSnapshotLocked(true, "");
            }
            return maybeBuildChangedSnapshotLocked(false, errNote.toString());
        }
    }
}
