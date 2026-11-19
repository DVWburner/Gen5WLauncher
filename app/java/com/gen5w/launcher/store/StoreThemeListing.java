package com.gen5w.launcher.store;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Store metadata model for a single theme published on https://market.ioniqthemes.com/.
 */
public final class StoreThemeListing {
    public String id = "";
    public String name = "";
    public String author = "";
    public String version = "1.0.0";
    public int revision = 1;
    public String category = "";
    public String description = "";
    public String minAppVersion = "";
    public final List<String> compatibleVersions = new ArrayList<String>();
    public int formatVersion = 2;
    public boolean showSystemBar = true;
    public String createdAt = "";
    public String updatedAt = "";
    public String downloadUrl = "";
    public String thumbnailUrl = "";
    public long fileSizeBytes = 0L;
    public String sha256 = "";
    public long downloads = 0L;
    public final List<String> tags = new ArrayList<String>();

    public long createdAtMs = 0L;
    public long updatedAtMs = 0L;

    public static final class CatalogResult {
        public final boolean ok;
        public final String errorMessage;
        public final String generatedAt;
        public final List<String> categories;
        public final List<StoreThemeListing> themes;

        public CatalogResult(boolean ok, String errorMessage, String generatedAt,
                List<String> categories, List<StoreThemeListing> themes) {
            this.ok = ok;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
            this.generatedAt = generatedAt == null ? "" : generatedAt;
            this.categories = categories == null ? Collections.<String>emptyList() : categories;
            this.themes = themes == null ? Collections.<StoreThemeListing>emptyList() : themes;
        }
    }

    public boolean isCompatibleWith(String appVersion) {
        if (appVersion == null || appVersion.trim().isEmpty()) {
            return true;
        }
        String min = minAppVersion == null ? "" : minAppVersion.trim();
        if (!min.isEmpty() && compareVersions(appVersion, min) < 0) {
            return false;
        }
        if (compatibleVersions.isEmpty()) {
            return true;
        }
        for (String rule : compatibleVersions) {
            if (rule == null) continue;
            String r = rule.trim();
            if (r.isEmpty()) continue;
            if (r.endsWith("+")) {
                String floor = r.substring(0, r.length() - 1).trim();
                if (compareVersions(appVersion, floor) >= 0) {
                    return true;
                }
            } else if (compareVersions(appVersion, r) == 0) {
                return true;
            }
        }
        return false;
    }

    public static int compareVersions(String a, String b) {
        int[] pa = parseVersionParts(a);
        int[] pb = parseVersionParts(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] parseVersionParts(String v) {
        if (v == null || v.trim().isEmpty()) {
            return new int[] { 0 };
        }
        String clean = v.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        int dash = clean.indexOf('-');
        if (dash >= 0) {
            clean = clean.substring(0, dash);
        }
        String[] segs = clean.split("\\.");
        int[] out = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            String digits = segs[i].replaceAll("[^0-9]", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }

    public static long parseIsoUtcMillis(String iso) {
        if (iso == null) {
            return 0L;
        }
        String s = iso.trim();
        if (s.isEmpty()) {
            return 0L;
        }
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1);
        }
        int plus = s.indexOf('+');
        if (plus > 10) {
            s = s.substring(0, plus);
        }
        String[] patterns = new String[] {
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        TimeZone utc = TimeZone.getTimeZone("UTC");
        for (String p : patterns) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat(p, Locale.US);
                fmt.setTimeZone(utc);
                Date d = fmt.parse(s);
                if (d != null) {
                    return d.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return 0L;
    }

    public static String formatShortDate(long millis, String fallbackIso) {
        if (millis > 0L) {
            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy", Locale.US);
            return fmt.format(new Date(millis));
        }
        if (fallbackIso != null && fallbackIso.length() >= 10) {
            return fallbackIso.substring(0, 10);
        }
        return "-";
    }

    public static String formatByteSize(long bytes) {
        if (bytes <= 0L) {
            return "Unknown";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
