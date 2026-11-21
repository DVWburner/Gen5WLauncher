package com.gen5w.launcher.ipc;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IPC client talking to root HomeSwitcher service via abstract LocalSocket @gen5w_launcher_ctl.
 */
public final class SwitcherClient {
    public static final String SOCKET_NAME = "gen5w_launcher_ctl";

    public static final class Status {
        public boolean serviceReachable = false;
        public boolean ok = false;
        public String message = "Service unreachable";
        public String activeHome = "unknown";
        public boolean isCustomHome = false;
        public boolean homeEnabled = false;
        public int strikes = 0;
        public String trippedReason = "";
        public String lastEvent = "";
        public String lastError = "";
        public String usbSignal = "NONE";
        public long graceRemainingSec = 0;
        public long deadmanRemainingSec = 0;
        public long healthConfirmRemainingSec = 0;
        public int appPid = -1;
        public int crashesIn60s = 0;
        public int appVersionCode = -1;
        public String appVersionName = "";
        public String rawJson = "";
    }

    public static final class VehicleReply {
        public final boolean ok;
        public final int code;
        public final String error;
        public final Map<String, String> values;

        VehicleReply(boolean ok, int code, String error, Map<String, String> values) {
            this.ok = ok;
            this.code = code;
            this.error = error == null ? "" : error;
            this.values = values;
        }
    }

    public static final class ModLauncherItem {
        public final String modId;
        public final String packageName;
        public final String className;
        public final String title;
        public final byte[] iconBytes;

        ModLauncherItem(String modId, String packageName, String className, String title, byte[] iconBytes) {
            this.modId = modId == null ? "" : modId;
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
            this.title = title == null ? "" : title;
            this.iconBytes = iconBytes;
        }
    }

    public static List<ModLauncherItem> fetchHmgApiLauncherEntriesBlocking() {
        List<ModLauncherItem> list = new ArrayList<ModLauncherItem>();
        try {
            String line = sendRawCommandBlocking("HMGAPI_MODS");
            JSONObject j = new JSONObject(line);
            JSONArray entries = j.optJSONArray("entries");
            if (entries == null) {
                return list;
            }
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e == null) continue;
                String pkg = e.optString("package", "").trim();
                String cls = e.optString("class", "").trim();
                if (pkg.isEmpty() || cls.isEmpty()) continue;
                String b64 = e.optString("iconBase64", "");
                byte[] icon = null;
                if (!b64.isEmpty()) {
                    try {
                        icon = Base64.decode(b64, Base64.DEFAULT);
                    } catch (Throwable ignored) {
                    }
                }
                list.add(new ModLauncherItem(
                        e.optString("mod", ""),
                        pkg,
                        cls,
                        e.optString("title", ""),
                        icon));
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    public interface Callback {
        void onResult(Status status);
    }

    public interface SimpleCallback {
        void onResult(boolean ok, String message);
    }

    private static final int FAST_TIMEOUT_MS = 1200;
    private static final int CMD_TIMEOUT_MS = 2200;

    private interface RejectAwareTask extends Runnable {
        void onRejected();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean STATUS_IN_FLIGHT = new AtomicBoolean(false);
    private static final ExecutorService STATUS_EXECUTOR = Executors.newSingleThreadExecutor(
            new DaemonThreadFactory("switcher-status-"));
    private static final ExecutorService CMD_EXECUTOR = new ThreadPoolExecutor(
            1, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(48),
            new DaemonThreadFactory("switcher-cmd-"),
            new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    if (r instanceof RejectAwareTask) {
                        ((RejectAwareTask) r).onRejected();
                    }
                }
            });

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

    public static void sendAsync(final String command, final Callback callback) {
        final boolean isStatusPoll = command == null || "STATUS".equals(command);
        if (isStatusPoll && !STATUS_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        ExecutorService exec = isStatusPoll ? STATUS_EXECUTOR : CMD_EXECUTOR;
        exec.execute(new RejectAwareTask() {
            @Override
            public void run() {
                try {
                    final Status st = sendBlocking(command);
                    if (callback != null) {
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onResult(st);
                            }
                        });
                    }
                } finally {
                    if (isStatusPoll) {
                        STATUS_IN_FLIGHT.set(false);
                    }
                }
            }

            @Override
            public void onRejected() {
                if (isStatusPoll) {
                    STATUS_IN_FLIGHT.set(false);
                }
                if (callback != null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            Status st = new Status();
                            st.ok = false;
                            st.message = "Command queue busy";
                            callback.onResult(st);
                        }
                    });
                }
            }
        });
    }

    public static void runRootAmAsync(
            final String mode,
            final String pkg,
            final String cls,
            final String action,
            final String extraKey,
            final String extraType,
            final String extraVal,
            final SimpleCallback callback) {
        final String wire = "ROOT_AM|" + safe(mode) + "|" + safe(pkg) + "|" + safe(cls)
                + "|" + safe(action) + "|" + safe(extraKey) + "|" + safe(extraType) + "|" + safe(extraVal);
        sendSimpleWireAsync(wire, CMD_TIMEOUT_MS, callback);
    }

    public static void sendCarCommandAsync(
            final String cmd,
            final String arg,
            final SimpleCallback callback) {
        final String wire = "CAR_CMD|" + safe(cmd) + "|" + safe(arg);
        sendSimpleWireAsync(wire, CMD_TIMEOUT_MS, callback);
    }

    private static void sendSimpleWireAsync(
            final String wire,
            final int timeoutMs,
            final SimpleCallback callback) {
        CMD_EXECUTOR.execute(new RejectAwareTask() {
            @Override
            public void run() {
                boolean ok = false;
                String msg;
                try {
                    String line = sendRawCommandBlocking(wire, timeoutMs);
                    JSONObject j = new JSONObject(line);
                    ok = j.optBoolean("ok", false);
                    msg = j.optString("message", ok ? "ok" : "failed");
                } catch (Throwable ex) {
                    msg = "IPC error: " + ex.getMessage();
                }
                final boolean fOk = ok;
                final String fMsg = msg;
                if (callback != null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(fOk, fMsg);
                        }
                    });
                }
            }

            @Override
            public void onRejected() {
                if (callback != null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(false, "Busy: too many commands queued");
                        }
                    });
                }
            }
        });
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('|', '_').replace('\n', ' ').trim();
    }

    public static void setVehicleKeysAsync(final Collection<String> keys) {
        final String wire = buildSetKeysWire(keys);
        CMD_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                setVehicleKeysWireBlocking(wire);
            }
        });
    }

    public static boolean setVehicleKeysBlocking(Collection<String> keys) {
        return setVehicleKeysWireBlocking(buildSetKeysWire(keys));
    }

    private static String buildSetKeysWire(Collection<String> keys) {
        StringBuilder sb = new StringBuilder("SET_KEYS|");
        if (keys != null) {
            boolean first = true;
            for (String k : keys) {
                if (k == null) continue;
                String clean = k.trim();
                if (!VehicleQueryClient.isValidKey(clean)) continue;
                if (!first) sb.append(',');
                sb.append(clean);
                first = false;
            }
        }
        return sb.toString();
    }

    private static boolean setVehicleKeysWireBlocking(String wire) {
        try {
            String line = sendRawCommandBlocking(wire);
            return new JSONObject(line).optBoolean("ok", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static VehicleReply fetchVehicleBlocking() {
        Map<String, String> vals = new LinkedHashMap<String, String>();
        try {
            String line = sendRawCommandBlocking("VEHICLE");
            JSONObject j = new JSONObject(line);
            boolean ok = j.optBoolean("ok", false);
            int code = j.optInt("code", -1);
            String err = j.optString("error", "");
            JSONObject vObj = j.optJSONObject("values");
            if (vObj != null) {
                Iterator<String> keys = vObj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    vals.put(k, vObj.optString(k, "-"));
                }
            }
            return new VehicleReply(ok && !vals.isEmpty(), code, err, vals);
        } catch (Throwable t) {
            return new VehicleReply(false, -1, "svc: " + t.getMessage(), vals);
        }
    }

    public static Status sendBlocking(String command) {
        Status st = new Status();
        try {
            String line = sendRawCommandBlocking(command == null ? "STATUS" : command);
            st.rawJson = line;
            JSONObject j = new JSONObject(line);
            st.serviceReachable = true;
            st.ok = j.optBoolean("ok", false);
            st.message = j.optString("message", "");
            st.activeHome = j.optString("activeHome", "unknown");
            st.isCustomHome = j.optBoolean("isCustomHome", false);
            st.homeEnabled = j.optBoolean("homeEnabled", false);
            st.strikes = j.optInt("strikes", 0);
            st.trippedReason = j.optString("trippedReason", "");
            st.lastEvent = j.optString("lastEvent", "");
            st.lastError = j.optString("lastError", "");
            st.usbSignal = j.optString("usbSignal", "NONE");
            st.graceRemainingSec = j.optLong("graceRemainingSec", 0);
            st.deadmanRemainingSec = j.optLong("deadmanRemainingSec", 0);
            st.healthConfirmRemainingSec = j.optLong("healthConfirmRemainingSec", 0);
            st.appPid = j.optInt("appPid", -1);
            st.crashesIn60s = j.optInt("crashesIn60s", 0);
            st.appVersionCode = j.optInt("appVersionCode", -1);
            st.appVersionName = j.optString("appVersionName", "");
            return st;
        } catch (Throwable t) {
            st.serviceReachable = false;
            st.message = "home-switcher offline (" + t.getMessage() + ")";
            return st;
        }
    }

    private static String sendRawCommandBlocking(String command) throws IOException {
        return sendRawCommandBlocking(command, FAST_TIMEOUT_MS);
    }

    private static String sendRawCommandBlocking(String command, int timeoutMs) throws IOException {
        LocalSocket socket = new LocalSocket();
        OutputStream out = null;
        InputStream in = null;
        BufferedReader reader = null;
        try {
            socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(Math.max(500, timeoutMs));
            out = socket.getOutputStream();
            out.write((command + "\n").getBytes("UTF-8"));
            out.flush();

            in = socket.getInputStream();
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty()) {
                throw new IOException("Empty reply from home-switcher");
            }
            return line;
        } finally {
            closeQuietly(out);
            closeQuietly(reader);
            closeQuietly(in);
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
