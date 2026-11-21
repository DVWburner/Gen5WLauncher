package com.gen5w.launcher.render;

import android.content.Context;

import com.gen5w.launcher.model.ThemePack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads active ThemePack v1 definitions safely from external pack locations
 * (internal files/packs/active or USB preview folders) with guaranteed fallback
 * to embedded assets/packs/default/layout.json.
 */
public final class ThemePackLoader {
    public static final String PACK_PREF_SOURCE = "theme_pack_source"; // auto | default | usb
    private static final String DEFAULT_LAYOUT_ASSET = "packs/default/layout.json";
    private static final String[] USB_PREVIEW_DIRS = {
            "/storage/usb0/Gen5WLauncher/pack",
            "/storage/usb1/Gen5WLauncher/pack",
            "/storage/usb2/Gen5WLauncher/pack"
    };

    private ThemePackLoader() {}

    public static ThemePack load(Context context, String sourceMode) {
        if ("usb".equals(sourceMode)) {
            ThemePack usbPack = tryLoadUsbPreview(context);
            if (usbPack != null) {
                return usbPack;
            }
        }
        if (!"default".equals(sourceMode)) {
            File activeDir = ThemePackManager.resolveActiveInternalDir(context);
            if (activeDir != null) {
                ThemePack localPack = tryLoadDirectory(activeDir, "installed:" + activeDir.getName());
                if (localPack != null) {
                    return localPack;
                }
            }
        }
        try {
            String json = readStreamText(context.getAssets().open(DEFAULT_LAYOUT_ASSET));
            return ThemePack.fromJson(json, "bundled:default", "");
        } catch (Throwable t) {
            return buildEmergencyPack();
        }
    }

    public static File findUsbPreviewDir(Context context) {
        if (context != null) {
            String custom = context.getSharedPreferences(ThemePackManager.PREF_NAME, Context.MODE_PRIVATE)
                    .getString(ThemePackManager.PREF_USB_PREVIEW_PATH, "");
            if (custom != null && custom.startsWith("/storage/usb") && !custom.contains("..")) {
                File customDir = new File(custom);
                if (customDir.isDirectory() && new File(customDir, "layout.json").isFile()) {
                    return customDir;
                }
            }
        }
        return findUsbPreviewDir();
    }

    public static File findUsbPreviewDir() {
        for (String p : USB_PREVIEW_DIRS) {
            File d = new File(p);
            if (d.isDirectory() && new File(d, "layout.json").isFile()) {
                return d;
            }
        }
        return null;
    }

    private static ThemePack tryLoadUsbPreview(Context context) {
        File dir = findUsbPreviewDir(context);
        return dir == null ? null : tryLoadDirectory(dir, "usb:" + dir.getAbsolutePath());
    }

    private static ThemePack tryLoadDirectory(File dir, String sourceLabel) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File layout = new File(dir, "layout.json");
        if (!layout.isFile()) {
            return null;
        }
        try {
            String json = readStreamText(new FileInputStream(layout));
            return ThemePack.fromJson(json, sourceLabel, dir.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[Gen5WLauncher] failed loading ThemePack from " + dir + ": " + t);
            return null;
        }
    }

    private static String readStreamText(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static ThemePack buildEmergencyPack() {
        ThemePack p = new ThemePack();
        p.sourceLabel = "emergency-fallback";
        p.root.layout = "flex_col";
        p.root.paddingPx = new int[] { 24, 24, 24, 24 };
        return p;
    }
}
