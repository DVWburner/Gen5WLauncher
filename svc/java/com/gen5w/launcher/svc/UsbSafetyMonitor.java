package com.gen5w.launcher.svc;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Layer 1 Failsafe: scans attached USB drives for REVERT_LAUNCHER* or SAFE_MODE*
 * marker files both in the USB root and in <usb>/Gen5WLauncher/.
 * Matches prefixes case-insensitively to tolerate accidental double extensions
 * such as REVERT_LAUNCHER.txt.txt.
 */
final class UsbSafetyMonitor {
    enum Signal {
        NONE,
        SAFE_MODE,
        REVERT
    }

    private static final String[] FIXED_USB_ROOTS = {
            "/storage/usb0",
            "/storage/usb1",
            "/storage/usb2",
            "/mnt/media_rw/usb0",
            "/mnt/media_rw/usb1",
            "/mnt/media_rw/usb2",
            "/mnt/usb_storage",
            "/storage/usbdisk"
    };

    Signal scan() {
        Set<File> dirs = new LinkedHashSet<File>();
        for (String rootPath : FIXED_USB_ROOTS) {
            File root = new File(rootPath);
            if (root.isDirectory()) {
                dirs.add(root);
                File modSub = new File(root, "Gen5WLauncher");
                if (modSub.isDirectory()) {
                    dirs.add(modSub);
                }
            }
        }

        boolean safeModeSeen = false;
        for (File dir : dirs) {
            File[] entries = dir.listFiles();
            if (entries == null) {
                continue;
            }
            for (File entry : entries) {
                if (!entry.isFile()) {
                    continue;
                }
                String name = entry.getName().toLowerCase(Locale.US);
                if (name.startsWith("revert_launcher")) {
                    return Signal.REVERT;
                }
                if (name.startsWith("safe_mode")) {
                    safeModeSeen = true;
                }
            }
        }
        return safeModeSeen ? Signal.SAFE_MODE : Signal.NONE;
    }
}
