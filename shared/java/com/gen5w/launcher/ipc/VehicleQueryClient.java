package com.gen5w.launcher.ipc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared vehicle telemetry key grammar and parsing helpers.
 * All telemetry reads and commands are served directly via HomeSwitcher's Binder bridge
 * with zero external runtime dependencies.
 */
public final class VehicleQueryClient {
    public static final String[] CORE_KEYS = new String[] {
            "hv.soc", "battery12v.soc", "charging", "hv.ready",
            "gear.park", "speed", "outside_temp_f", "outside_temp",
            "driving.restricted", "odometer"
    };

    private VehicleQueryClient() {}

    public static boolean isDeclaredTelemetryKey(String key) {
        return key != null && isValidKey(key.trim());
    }

    /** Validates canonical telemetry key grammar ([a-z0-9_.]{1,48}). */
    public static boolean isValidKey(String key) {
        if (key == null) return false;
        String t = key.trim();
        int len = t.length();
        if (len == 0 || len > 48) return false;
        for (int i = 0; i < len; i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public static Map<String, String> parseVehicleLines(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        parseVehicleLines(text, out);
        return out;
    }

    public static void parseVehicleLines(String text, Map<String, String> out) {
        if (text == null || text.isEmpty()) return;
        String[] lines = text.split("\n");
        for (String line : lines) {
            int sp = line.indexOf(' ');
            if (sp > 0) {
                String k = line.substring(0, sp).trim();
                String v = line.substring(sp + 1).trim();
                if (isValidKey(k) && !v.isEmpty() && !"-".equals(v)) {
                    out.put(k, v);
                }
            }
        }
    }
}
