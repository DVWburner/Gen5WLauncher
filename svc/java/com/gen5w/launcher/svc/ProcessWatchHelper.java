package com.gen5w.launcher.svc;

import android.app.ActivityManager;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Monitors system properties and running processes via IActivityManager to detect
 * rapid crash loops of com.gen5w.launcher without spurious process forks.
 */
final class ProcessWatchHelper {
    private static final long CRASH_WINDOW_MS = 60_000L;
    private static final int CRASH_THRESHOLD = 3;

    private Object am;
    private Method mGetRunningAppProcesses;
    private Method mPropGet;

    private int lastPid = -1;
    private final Deque<Long> crashTimesMs = new ArrayDeque<Long>();

    private synchronized Object ensureAm() throws Exception {
        if (am != null) {
            return am;
        }
        Class<?> amn = Class.forName("android.app.ActivityManagerNative");
        am = amn.getMethod("getDefault").invoke(null);
        Class<?> iam = Class.forName("android.app.IActivityManager");
        mGetRunningAppProcesses = iam.getMethod("getRunningAppProcesses");
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

    synchronized void resetWatch() {
        lastPid = findProcessPid("com.gen5w.launcher");
        crashTimesMs.clear();
    }

    /**
     * Records a sampling tick for com.gen5w.launcher while custom HOME is active.
     * Returns true if 3+ unexpected exits/restarts occurred within 60 seconds.
     */
    synchronized boolean sampleAndCheckCrashLoop(long nowMs, boolean customHomeActive) {
        pruneOld(nowMs);
        int pid = findProcessPid("com.gen5w.launcher");
        if (!customHomeActive) {
            lastPid = pid;
            return false;
        }
        if (lastPid > 0 && pid != lastPid) {
            // Process exited or restarted into a new PID while active.
            crashTimesMs.addLast(nowMs);
            System.out.println("[HomeSwitcher] launcher PID transition detected: "
                    + lastPid + " -> " + pid + " (events_60s=" + crashTimesMs.size() + ")");
        }
        lastPid = pid;
        return crashTimesMs.size() >= CRASH_THRESHOLD;
    }

    synchronized int getCurrentPid() {
        return lastPid;
    }

    synchronized int getCrashCountInWindow(long nowMs) {
        pruneOld(nowMs);
        return crashTimesMs.size();
    }

    private void pruneOld(long nowMs) {
        while (!crashTimesMs.isEmpty() && (nowMs - crashTimesMs.peekFirst()) > CRASH_WINDOW_MS) {
            crashTimesMs.removeFirst();
        }
    }
}
