package com.gen5w.launcher.svc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Atomic file-backed persistence for the 5-layer failsafe state.
 * Stores state under /data/gen5w_launcher/run/home-switcher (GEN5W_DATA_DIR)
 * with fsync + rename so power loss or pinhole resets never corrupt boot strikes.
 */
final class SwitcherStateStore {
    static final class State {
        int schemaVersion = 1;
        boolean homeEnabled;
        boolean userChoseStock;
        boolean autoActivateOnBoot = true;
        int strikes;
        String trippedReason = "";
        String lastEvent = "initialized";
    }

    private final File stateFile;
    private final File tmpFile;

    SwitcherStateStore(File dataDir) {
        if (!dataDir.isDirectory()) {
            dataDir.mkdirs();
        }
        this.stateFile = new File(dataDir, "switcher-state.properties");
        this.tmpFile = new File(dataDir, "switcher-state.properties.tmp");
    }

    synchronized State load() {
        State s = new State();
        if (!stateFile.isFile()) {
            s.schemaVersion = 3;
            return s;
        }
        Properties p = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(stateFile);
            p.load(in);
            try {
                s.schemaVersion = Integer.parseInt(p.getProperty("schemaVersion", "1").trim());
            } catch (NumberFormatException ignored) {
                s.schemaVersion = 1;
            }
            s.homeEnabled = "true".equalsIgnoreCase(p.getProperty("homeEnabled", "false"));
            s.userChoseStock = "true".equalsIgnoreCase(p.getProperty("userChoseStock", "false"));
            s.autoActivateOnBoot = !"false".equalsIgnoreCase(p.getProperty("autoActivateOnBoot", "true"));
            try {
                s.strikes = Math.max(0, Integer.parseInt(p.getProperty("strikes", "0").trim()));
            } catch (NumberFormatException ignored) {
                s.strikes = 0;
            }
            s.trippedReason = nonNull(p.getProperty("trippedReason", ""));
            s.lastEvent = nonNull(p.getProperty("lastEvent", "loaded"));
        } catch (IOException e) {
            s.lastEvent = "load_error:" + e.getMessage();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return s;
    }

    synchronized void save(State s) {
        Properties p = new Properties();
        p.setProperty("schemaVersion", Integer.toString(Math.max(3, s.schemaVersion)));
        p.setProperty("homeEnabled", Boolean.toString(s.homeEnabled));
        p.setProperty("userChoseStock", Boolean.toString(s.userChoseStock));
        p.setProperty("autoActivateOnBoot", Boolean.toString(s.autoActivateOnBoot));
        p.setProperty("strikes", Integer.toString(Math.max(0, s.strikes)));
        p.setProperty("trippedReason", nonNull(s.trippedReason));
        p.setProperty("lastEvent", nonNull(s.lastEvent));

        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmpFile);
            p.store(out, "Gen5WLauncher HomeSwitcher Failsafe State");
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmpFile.renameTo(stateFile)) {
                if (stateFile.exists()) {
                    stateFile.delete();
                }
                tmpFile.renameTo(stateFile);
            }
        } catch (IOException e) {
            System.err.println("[HomeSwitcher] failed to save state: " + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String nonNull(String v) {
        return v == null ? "" : v;
    }
}
