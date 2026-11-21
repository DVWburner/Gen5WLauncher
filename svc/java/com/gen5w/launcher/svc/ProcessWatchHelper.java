package com.gen5w.launcher.svc;

import android.app.ActivityManager;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Monitors system properties and running processes via IActivityManager to detect
 * rapid crash loops of com.gen5w.launcher (including instant <150ms onCreate crashes)
 * using monotonic elapsedRealtimeMs timestamps.
 */
final class ProcessWatchHelper {
    private static final long CRASH_WINDOW_MS = 60_000L;
    private static final int CRASH_THRESHOLD = 3;

    private Object am;
    private Method mGetRunningAppProcesses;
    private Method mGetProcessesInErrorState;
    private Method mPropGet;

    private int lastPid = -1;
    private int lastErrorPid = -1;
    private final Deque<Long> crashTimesMs = new ArrayDeque<Long>();

    private synchronized Object ensureAm() throws Exception {
        if (am != null) {
            return am;
        }
        Class<?> amn = Class.forName("android.app.ActivityManagerNative");
        am = amn.getMethod("getDefault").invoke(null);
        Class<?> iam = Class.forName("android.app.IActivityManager");
        mGetRunningAppProcesses = iam.getMethod("getRunningAppProcesses");
        try {
            mGetProcessesInErrorState = iam.getMethod("getProcessesInErrorState");
        } catch (Throwable ignored) {
        }
        return am;
    }

    String getProp(String key, String fallback) {
        try {
            if (mPropGet == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                mPropGet = sp.getMethod("get", String.class, String.class);
            }
            Object v = mPropGet.invoke(null, key, fallback);
            return v == null ? fallback : String.valueOf(v);
        } catch (Throwable t) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    int findProcessPid(String processName) {
        try {
            Object service = ensureAm();
            Object listObj = mGetRunningAppProcesses.invoke(service);
            if (!(listObj instanceof List)) {
                return -1;
            }
            for (Object o : (List<Object>) listObj) {
                if (o instanceof ActivityManager.RunningAppProcessInfo) {
                    ActivityManager.RunningAppProcessInfo info = (ActivityManager.RunningAppProcessInfo) o;
                    if (processName.equals(info.processName)) {
                        return info.pid;
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[HomeSwitcher] getRunningAppProcesses error: " + t);
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private boolean isProcessInErrorState(String processName) {
        try {
            Object service = ensureAm();
            if (mGetProcessesInErrorState == null) {
                return false;
            }
            Object listObj = mGetProcessesInErrorState.invoke(service);
            if (!(listObj instanceof List)) {
                return false;
            }
            for (Object o : (List<Object>) listObj) {
                if (o instanceof ActivityManager.ProcessErrorStateInfo) {
                    ActivityManager.ProcessErrorStateInfo err = (ActivityManager.ProcessErrorStateInfo) o;
                    if (processName.equals(err.processName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    synchronized void resetWatch() {
        lastPid = findProcessPid("com.gen5w.launcher");
        lastErrorPid = -1;
        crashTimesMs.clear();
    }

    /**
     * Records a sampling tick for com.gen5w.launcher while custom HOME is active.
     * Uses monotonic elapsedMs so wall-clock NTP/GPS updates never skew the 60s window.
     * Note: pid <= 0 while another app (e.g. CarPlay, Navigation, NEMESIS) is in the foreground
     * is normal Android LMK / lifecycle behavior and must NOT be treated as a crash loop.
     */
    synchronized boolean sampleAndCheckCrashLoop(long elapsedMs, boolean customHomeActive) {
        pruneOld(elapsedMs);
        int pid = findProcessPid("com.gen5w.launcher");
        if (!customHomeActive) {
            lastPid = pid;
            lastErrorPid = -1;
            return false;
        }
        if (pid > 0 && pid != lastErrorPid && isProcessInErrorState("com.gen5w.launcher")) {
            lastErrorPid = pid;
            crashTimesMs.addLast(Long.valueOf(elapsedMs));
            System.out.println("[HomeSwitcher] launcher in error/crash state pid=" + pid
                    + " (events_60s=" + crashTimesMs.size() + ")");
        } else if (lastPid > 0 && pid != lastPid) {
            crashTimesMs.addLast(Long.valueOf(elapsedMs));
            System.out.println("[HomeSwitcher] launcher PID transition detected: "
                    + lastPid + " -> " + pid + " (events_60s=" + crashTimesMs.size() + ")");
        }
        if (pid <= 0) {
            lastErrorPid = -1;
        }
        lastPid = pid;
        return crashTimesMs.size() >= CRASH_THRESHOLD;
    }

    synchronized int getCurrentPid() {
        return lastPid;
    }

    synchronized int getCrashCountInWindow(long elapsedMs) {
        pruneOld(elapsedMs);
        return crashTimesMs.size();
    }

    private void pruneOld(long elapsedMs) {
        while (!crashTimesMs.isEmpty()) {
            long first = crashTimesMs.peekFirst().longValue();
            if ((elapsedMs - first) > CRASH_WINDOW_MS || elapsedMs < first) {
                crashTimesMs.removeFirst();
            } else {
                break;
            }
        }
    }
}
