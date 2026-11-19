package com.gen5w.launcher.svc;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection bridge to KitKat's IPackageManager binder interface inside app_process.
 * Verified against NEEV.USA.S5W_M.V015.012.260507 framework.odex:
 *   - resolveIntent(Intent, String, int, int)
 *   - queryIntentActivities(Intent, String, int, int)
 *   - replacePreferredActivity(IntentFilter, int, ComponentName[], ComponentName)
 *   - getPackageInfo(String, int, int)
 *   - getPackageUid(String, int)
 */
final class PreferredHomeHelper {
    static final ComponentName STOCK_HOME =
            new ComponentName("com.android.launcher3", "com.android.launcher3.view.Launcher");
    static final ComponentName CUSTOM_HOME =
            new ComponentName("com.gen5w.launcher", "com.gen5w.launcher.MainActivity");

    private static final int USER_OWNER = 0;
    private static final int MATCH_CATEGORY_EMPTY = IntentFilter.MATCH_CATEGORY_EMPTY; // 0x00100000

    private Object pm;
    private Method mResolveIntent;
    private Method mQueryIntentActivities;
    private Method mReplacePreferredActivity;
    private Method mGetPackageInfo;
    private Method mGetPackageUid;

    private synchronized Object ensurePm() throws Exception {
        if (pm != null) {
            return pm;
        }
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "package");
        if (binder == null) {
            throw new IllegalStateException("ServiceManager.getService(\"package\") returned null");
        }
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        pm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

        Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
        mResolveIntent = ipm.getMethod("resolveIntent", Intent.class, String.class, int.class, int.class);
        mQueryIntentActivities = ipm.getMethod("queryIntentActivities", Intent.class, String.class, int.class, int.class);
        mReplacePreferredActivity = ipm.getMethod(
                "replacePreferredActivity",
                IntentFilter.class, int.class, ComponentName[].class, ComponentName.class);
        mGetPackageInfo = ipm.getMethod("getPackageInfo", String.class, int.class, int.class);
        mGetPackageUid = ipm.getMethod("getPackageUid", String.class, int.class);
        return pm;
    }

    static Intent createHomeIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.addCategory(Intent.CATEGORY_DEFAULT);
        return i;
    }

    ComponentName resolveCurrentHome() {
        try {
            Object service = ensurePm();
            Object riObj = mResolveIntent.invoke(service, createHomeIntent(), null,
                    PackageManager.MATCH_DEFAULT_ONLY, USER_OWNER);
            if (!(riObj instanceof ResolveInfo)) {
                return null;
            }
            ResolveInfo ri = (ResolveInfo) riObj;
            if (ri.activityInfo == null) {
                return null;
            }
            return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        } catch (Throwable t) {
            System.err.println("[HomeSwitcher] resolveCurrentHome failed: " + t);
            return null;
        }
    }

    boolean isPackageInstalled(String packageName) {
        try {
            Object service = ensurePm();
            return mGetPackageInfo.invoke(service, packageName, 0, USER_OWNER) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    PackageInfo getPackageInfo(String packageName) {
        try {
            Object service = ensurePm();
            Object obj = mGetPackageInfo.invoke(service, packageName, 0, USER_OWNER);
            return obj instanceof PackageInfo ? (PackageInfo) obj : null;
        } catch (Throwable t) {
            return null;
        }
    }

    int getPackageVersionCode(String packageName) {
        PackageInfo pi = getPackageInfo(packageName);
        return pi == null ? -1 : pi.versionCode;
    }

    String getPackageVersionName(String packageName) {
        PackageInfo pi = getPackageInfo(packageName);
        return (pi == null || pi.versionName == null) ? "" : pi.versionName;
    }

    boolean installApkReplace(File apkFile) {
        if (apkFile == null || !apkFile.isFile()) {
            return false;
        }
        CommandResult res = runCommandCapture(new String[] {
                "/system/bin/pm", "install", "-r", apkFile.getAbsolutePath()
        });
        boolean ok = res.exitCode == 0 && (res.output.contains("Success") || !res.output.contains("Failure"));
        System.out.println("[HomeSwitcher] installApkReplace(" + apkFile.getName()
                + ") rc=" + res.exitCode + " out=" + res.output);
        return ok;
    }

    static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    CommandResult runRootCommand(String[] cmd) {
        return runCommandCapture(cmd);
    }

    int getPackageUid(String packageName) {
        try {
            Object service = ensurePm();
            Object uid = mGetPackageUid.invoke(service, packageName, USER_OWNER);
            return uid instanceof Integer ? ((Integer) uid).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    ComponentName[] queryHomeSet(ComponentName mustInclude) {
        List<ComponentName> out = new ArrayList<ComponentName>();
        try {
            Object service = ensurePm();
            Object listObj = mQueryIntentActivities.invoke(service, createHomeIntent(), null,
                    PackageManager.MATCH_DEFAULT_ONLY, USER_OWNER);
            if (listObj instanceof List) {
                for (Object item : (List<Object>) listObj) {
                    if (item instanceof ResolveInfo) {
                        ActivityInfo ai = ((ResolveInfo) item).activityInfo;
                        if (ai != null) {
                            ComponentName cn = new ComponentName(ai.packageName, ai.name);
                            if (!out.contains(cn)) {
                                out.add(cn);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[HomeSwitcher] queryHomeSet failed: " + t);
        }
        if (mustInclude != null && !out.contains(mustInclude)) {
            out.add(mustInclude);
        }
        return out.toArray(new ComponentName[out.size()]);
    }

    boolean setPreferredHome(ComponentName target) {
        if (target == null) {
            return false;
        }
        try {
            Object service = ensurePm();
            ComponentName[] set = queryHomeSet(target);
            boolean targetInSet = false;
            for (ComponentName cn : set) {
                if (target.equals(cn)) {
                    targetInSet = true;
                    break;
                }
            }
            if (!targetInSet || !isPackageInstalled(target.getPackageName())) {
                System.err.println("[HomeSwitcher] target HOME not installed: " + target.flattenToShortString());
                return false;
            }

            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);

            mReplacePreferredActivity.invoke(service, filter, MATCH_CATEGORY_EMPTY, set, target);

            ComponentName verified = resolveCurrentHome();
            boolean ok = target.equals(verified);
            System.out.println("[HomeSwitcher] setPreferredHome(" + target.flattenToShortString()
                    + ") -> resolved=" + (verified == null ? "null" : verified.flattenToShortString())
                    + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            System.err.println("[HomeSwitcher] setPreferredHome(" + target.flattenToShortString() + ") error: " + t);
            t.printStackTrace(System.err);
            return false;
        }
    }

    boolean bringHomeToFront(ComponentName target) {
        if (target == null) {
            return false;
        }
        String[] cmd = new String[] {
                "/system/bin/am", "start",
                "-a", Intent.ACTION_MAIN,
                "-c", Intent.CATEGORY_HOME,
                "-n", target.flattenToShortString(),
                "--activity-clear-top"
        };
        return runCommand(cmd) == 0;
    }

    void showToast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        runCommand(new String[] {
                "/system/bin/am", "broadcast",
                "-a", "com.hkmc.intent.action.TOAST",
                "-e", "com.hkmc.intent.extra.TOAST_MESSAGE", message,
                "--ez", "com.hkmc.intent.extra.TOAST_POPUPSHOWING", "true"
        });
    }

    private static int runCommand(String[] cmd) {
        return runCommandCapture(cmd).exitCode;
    }

    private static CommandResult runCommandCapture(String[] cmd) {
        Process p = null;
        StringBuilder sb = new StringBuilder();
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() < 1500) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(line.trim());
                }
            }
            int rc = p.waitFor();
            return new CommandResult(rc, sb.toString());
        } catch (Throwable t) {
            System.err.println("[HomeSwitcher] cmd error: " + t);
            return new CommandResult(-1, String.valueOf(t.getMessage()));
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
