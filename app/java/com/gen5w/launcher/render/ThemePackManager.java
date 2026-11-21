package com.gen5w.launcher.render;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import com.gen5w.launcher.model.ThemePack;
import com.gen5w.launcher.store.StoreThemeListing;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages persisted internal ThemePacks (downloaded from the Theme Store or imported
 * from USB folders / USB .zip bundles), discovers local & USB packs, and tracks
 * pack activation changes cleanly for MainActivity.
 */
public final class ThemePackManager {
    public static final String PREF_NAME = "gen5w_launcher_prefs";
    public static final String PREF_ACTIVE_PACK_ID = "active_pack_id";
    public static final String PREF_PACK_CHANGE_SEQ = "pack_change_seq";
    public static final String PREF_BG_OVERRIDE = "bg_override";
    public static final String PREF_USB_PREVIEW_PATH = "usb_preview_path";
    public static final String BUNDLED_ID = "bundled:default";
    public static final String STORE_META_FILE = ".store_meta.json";

    private static final String[] USB_ROOTS = {
            "/storage/usb0/Gen5WLauncher",
            "/storage/usb1/Gen5WLauncher",
            "/storage/usb2/Gen5WLauncher"
    };

    public enum SourceType {
        BUNDLED,
        INSTALLED,
        USB,
        USB_ZIP
    }

    public static final class PackEntry {
        public final String id;
        public final String storeId;
        public final String name;
        public final String author;
        public final String version;
        public final int revision;
        public final String category;
        public final String description;
        public final int formatVersion;
        public final SourceType sourceType;
        public final File dir;
        public final File zipFile;
        public final File thumbnailFile;
        public final byte[] zipThumbnailBytes;
        public final boolean isActive;
        public final String error;

        PackEntry(
                String id,
                String storeId,
                String name,
                String author,
                String version,
                int revision,
                String category,
                String description,
                int formatVersion,
                SourceType sourceType,
                File dir,
                File zipFile,
                File thumbnailFile,
                byte[] zipThumbnailBytes,
                boolean isActive,
                String error) {
            this.id = id;
            this.storeId = storeId == null ? "" : storeId;
            this.name = name;
            this.author = author;
            this.version = version;
            this.revision = Math.max(1, revision);
            this.category = category == null ? "" : category;
            this.description = description == null ? "" : description;
            this.formatVersion = formatVersion;
            this.sourceType = sourceType;
            this.dir = dir;
            this.zipFile = zipFile;
            this.thumbnailFile = thumbnailFile;
            this.zipThumbnailBytes = zipThumbnailBytes;
            this.isActive = isActive;
            this.error = error == null ? "" : error;
        }
    }

    private ThemePackManager() {}

    public static File getInternalPacksRoot(Context context) {
        return new File(context.getFilesDir(), "packs");
    }

    public static String getActivePackId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_ACTIVE_PACK_ID, BUNDLED_ID);
    }

    public static int getPackChangeSeq(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(PREF_PACK_CHANGE_SEQ, 0);
    }

    public static void setActiveUsbPreviewDir(Context context, File usbDir) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int seq = prefs.getInt(PREF_PACK_CHANGE_SEQ, 0) + 1;
        prefs.edit()
                .putString(PREF_ACTIVE_PACK_ID, BUNDLED_ID)
                .putString(ThemePackLoader.PACK_PREF_SOURCE, "usb")
                .putString(PREF_USB_PREVIEW_PATH, usbDir == null ? "" : usbDir.getAbsolutePath())
                .remove(PREF_BG_OVERRIDE)
                .putInt(PREF_PACK_CHANGE_SEQ, seq)
                .apply();
    }

    public static void setActivePack(Context context, String packId, String sourceMode) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int seq = prefs.getInt(PREF_PACK_CHANGE_SEQ, 0) + 1;
        SharedPreferences.Editor ed = prefs.edit()
                .putString(PREF_ACTIVE_PACK_ID, packId == null || packId.isEmpty() ? BUNDLED_ID : packId)
                .putString(ThemePackLoader.PACK_PREF_SOURCE, sourceMode == null ? "auto" : sourceMode)
                .remove(PREF_BG_OVERRIDE)
                .putInt(PREF_PACK_CHANGE_SEQ, seq);
        if (!"usb".equals(sourceMode)) {
            ed.remove(PREF_USB_PREVIEW_PATH);
        }
        ed.apply();
    }

    public static File resolveActiveInternalDir(Context context) {
        String id = getActivePackId(context);
        if (BUNDLED_ID.equals(id) || id.trim().isEmpty()) {
            return null;
        }
        File candidate = new File(getInternalPacksRoot(context), sanitizeId(id));
        if (new File(candidate, "layout.json").isFile()) {
            return candidate;
        }
        File legacyActive = new File(getInternalPacksRoot(context), "active");
        return new File(legacyActive, "layout.json").isFile() ? legacyActive : null;
    }

    public static List<PackEntry> discoverInstalledOnly(Context context) {
        List<PackEntry> out = new ArrayList<PackEntry>();
        String activeId = getActivePackId(context);
        String sourceMode = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");

        // 1. Bundled default
        try {
            String json = readStreamText(context.getAssets().open("packs/default/layout.json"));
            ThemePack def = ThemePack.fromJson(json, "bundled:default", "");
            boolean defActive = "default".equals(sourceMode) || (!"usb".equals(sourceMode) && BUNDLED_ID.equals(activeId));
            out.add(new PackEntry(BUNDLED_ID, "default", def.name, def.author, def.version, 1,
                    "Built-in", def.description, def.formatVersion, SourceType.BUNDLED,
                    null, null, null, null, defActive, ""));
        } catch (Throwable t) {
            out.add(new PackEntry(BUNDLED_ID, "default", "Default Built-in", "Gen5WLauncher", "-", 1,
                    "Built-in", "", 2, SourceType.BUNDLED,
                    null, null, null, null, true, t.getMessage()));
        }

        // 2. Installed internal packs
        File root = getInternalPacksRoot(context);
        if (root.isDirectory()) {
            File[] dirs = root.listFiles();
            if (dirs != null) {
                Arrays.sort(dirs);
                for (File d : dirs) {
                    if (d.isDirectory() && new File(d, "layout.json").isFile()) {
                        boolean active = !"default".equals(sourceMode)
                                && !"usb".equals(sourceMode)
                                && d.getName().equals(sanitizeId(activeId));
                        PackEntry e = inspectDirectory(d, SourceType.INSTALLED, active);
                        if (e != null) out.add(e);
                    }
                }
            }
        }
        return out;
    }

    public static List<PackEntry> discoverAllPacks(Context context) {
        List<PackEntry> out = discoverInstalledOnly(context);
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String sourceMode = prefs.getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");
        File activeUsbDir = "usb".equals(sourceMode) ? ThemePackLoader.findUsbPreviewDir(context) : null;

        // 3. Discovered USB folders & USB .zip theme bundles
        for (String usbRoot : USB_ROOTS) {
            File base = new File(usbRoot);
            if (!base.isDirectory()) continue;

            File single = new File(base, "pack");
            if (single.isDirectory() && new File(single, "layout.json").isFile()) {
                boolean isUsbActive = activeUsbDir != null && single.getAbsolutePath().equals(activeUsbDir.getAbsolutePath());
                PackEntry e = inspectDirectory(single, SourceType.USB, isUsbActive);
                if (e != null) out.add(e);
            }

            scanZipFilesInDir(base, out);

            File multi = new File(base, "packs");
            if (multi.isDirectory()) {
                File[] children = multi.listFiles();
                if (children != null) {
                    Arrays.sort(children);
                    for (File child : children) {
                        if (child.isDirectory() && new File(child, "layout.json").isFile()) {
                            boolean isUsbActive = activeUsbDir != null && child.getAbsolutePath().equals(activeUsbDir.getAbsolutePath());
                            PackEntry e = inspectDirectory(child, SourceType.USB, isUsbActive);
                            if (e != null) out.add(e);
                        } else if (isThemeZipFile(child)) {
                            out.add(inspectZipFile(child));
                        }
                    }
                }
            }
        }
        return out;
    }

    public static Map<String, PackEntry> mapInstalledByStoreOrPackId(Context context) {
        Map<String, PackEntry> map = new HashMap<String, PackEntry>();
        for (PackEntry e : discoverInstalledOnly(context)) {
            if (!e.storeId.isEmpty()) {
                map.put(e.storeId.toLowerCase(Locale.US), e);
            }
            if (!e.id.isEmpty()) {
                map.put(e.id.toLowerCase(Locale.US), e);
            }
        }
        return map;
    }

    public static long getAvailableInternalBytes(Context context) {
        try {
            File target = context.getFilesDir();
            if (!target.exists()) target.mkdirs();
            StatFs sf = new StatFs(target.getAbsolutePath());
            long availBlocks = (long) sf.getAvailableBlocks();
            long blockSize = (long) sf.getBlockSize();
            return availBlocks * blockSize;
        } catch (Throwable ignored) {
            return Long.MAX_VALUE;
        }
    }

    public static void ensureSpaceForDownload(Context context, long expectedZipBytes) throws IOException {
        long needed = Math.max(32L * 1024L * 1024L, expectedZipBytes * 2L + 24L * 1024L * 1024L);
        long free = getAvailableInternalBytes(context);
        if (free < needed) {
            throw new IOException("Not enough internal storage on unit (" + (free / (1024 * 1024))
                    + " MB free, need ~" + (needed / (1024 * 1024)) + " MB). Delete unused themes first.");
        }
    }

    public static PackEntry installDownloadedZip(
            Context context,
            File stagedZipFile,
            StoreThemeListing listing,
            boolean activateNow) throws Exception {
        String rawId = (listing != null && listing.id != null && !listing.id.trim().isEmpty())
                ? listing.id.trim()
                : SafeThemeZipExtractor.inspectSummary(stagedZipFile).id;
        String safeId = sanitizeOrHashId(rawId, listing != null ? listing.name : stagedZipFile.getName());
        File dstDir = new File(getInternalPacksRoot(context), safeId);

        SafeThemeZipExtractor.ExtractResult res = SafeThemeZipExtractor.extractSafely(stagedZipFile, dstDir);
        if (listing != null) {
            writeStoreMeta(dstDir, listing);
        }
        if (activateNow) {
            setActivePack(context, safeId, "auto");
        } else {
            bumpChangeSeq(context);
        }
        boolean isActive = safeId.equals(sanitizeId(getActivePackId(context)));
        return inspectDirectory(dstDir, SourceType.INSTALLED, isActive);
    }

    public static ThemePack importFromUsbZipAndActivate(Context context, File usbZipFile) throws Exception {
        if (usbZipFile == null || !usbZipFile.isFile()) {
            throw new IllegalArgumentException("USB theme .zip file not found");
        }
        SafeThemeZipExtractor.ZipSummary summary = SafeThemeZipExtractor.inspectSummary(usbZipFile);
        if (!summary.error.isEmpty()) {
            throw new IllegalArgumentException(summary.error);
        }
        ensureSpaceForDownload(context, usbZipFile.length());
        String rawId = !summary.id.isEmpty() ? summary.id : usbZipFile.getName().replaceAll("\\.zip$", "");
        String safeId = sanitizeOrHashId(rawId, usbZipFile.getName());
        File dst = new File(getInternalPacksRoot(context), safeId);
        SafeThemeZipExtractor.extractSafely(usbZipFile, dst);
        setActivePack(context, safeId, "auto");
        return ThemePackLoader.load(context, "auto");
    }

    public static ThemePack importFromUsbAndActivate(Context context, File usbDir) throws Exception {
        if (usbDir == null || !usbDir.isDirectory()) {
            throw new IllegalArgumentException("USB pack folder not found");
        }
        File layoutFile = new File(usbDir, "layout.json");
        if (!layoutFile.isFile()) {
            throw new IllegalArgumentException("Missing layout.json in " + usbDir.getName());
        }
        String json = readStreamText(new FileInputStream(layoutFile));
        ThemePack parsed = ThemePack.fromJson(json, "usb:" + usbDir.getName(), usbDir.getAbsolutePath());

        String rawId = parsed.id != null && !parsed.id.trim().isEmpty() ? parsed.id : usbDir.getName();
        String safeId = sanitizeOrHashId(rawId, usbDir.getName());

        File dst = new File(getInternalPacksRoot(context), safeId);
        File tmp = new File(getInternalPacksRoot(context), safeId + ".staging");
        deleteTree(tmp);
        long[] copyStats = new long[] { 0L, 0L }; // [fileCount, totalBytes]
        copyDirectorySafe(usbDir, tmp, "", 0, copyStats);
        deleteTree(dst);
        if (!tmp.renameTo(dst)) {
            copyDirectorySafe(tmp, dst, "", 0, new long[] { 0L, 0L });
            deleteTree(tmp);
        }
        setActivePack(context, safeId, "auto");
        return ThemePackLoader.load(context, "auto");
    }

    public static boolean deleteInstalledPack(Context context, String packId) {
        if (packId == null || BUNDLED_ID.equals(packId) || packId.trim().isEmpty()) {
            return false;
        }
        String safeId = sanitizeId(packId);
        File dir = new File(getInternalPacksRoot(context), safeId);
        if (!dir.isDirectory()) {
            return false;
        }
        boolean wasActive = safeId.equals(sanitizeId(getActivePackId(context))) || packId.equals(getActivePackId(context));
        boolean deleted = deleteTree(dir);
        if (wasActive) {
            setActivePack(context, BUNDLED_ID, "auto");
        } else if (deleted) {
            bumpChangeSeq(context);
        }
        return deleted;
    }

    private static void bumpChangeSeq(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int seq = prefs.getInt(PREF_PACK_CHANGE_SEQ, 0) + 1;
        prefs.edit().putInt(PREF_PACK_CHANGE_SEQ, seq).apply();
    }

    private static void scanZipFilesInDir(File base, List<PackEntry> out) {
        File[] files = base.listFiles();
        if (files == null) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : files) {
            if (isThemeZipFile(f)) {
                out.add(inspectZipFile(f));
            }
        }
    }

    private static boolean isThemeZipFile(File f) {
        return f != null && f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".zip")
                && !f.getName().startsWith(".");
    }

    private static PackEntry inspectZipFile(File zipFile) {
        SafeThemeZipExtractor.ZipSummary s = SafeThemeZipExtractor.inspectSummary(zipFile, false);
        String id = !s.id.isEmpty() ? s.id : zipFile.getName();
        return new PackEntry(id, s.id, s.name, s.author, s.version, s.revision,
                s.category, s.description, s.formatVersion, SourceType.USB_ZIP,
                null, zipFile, null, null, false, s.error);
    }

    private static PackEntry inspectDirectory(File dir, SourceType srcType, boolean active) {
        try {
            String json = readStreamText(new FileInputStream(new File(dir, "layout.json")));
            ThemePack p = ThemePack.fromJson(json, srcType.name(), dir.getAbsolutePath());
            String id = srcType == SourceType.INSTALLED ? dir.getName() : (p.id.isEmpty() ? dir.getName() : p.id);
            String storeId = p.id == null ? "" : p.id;
            String name = p.name;
            String author = p.author;
            String version = p.version;
            int revision = 1;
            String category = "";
            String description = p.description == null ? "" : p.description;

            File manifestFile = new File(dir, "manifest.json");
            if (manifestFile.isFile()) {
                try {
                    JSONObject mObj = new JSONObject(readStreamText(new FileInputStream(manifestFile))).optJSONObject("theme");
                    if (mObj != null) {
                        if (!mObj.optString("id", "").isEmpty()) storeId = mObj.optString("id");
                        if (!mObj.optString("name", "").isEmpty()) name = mObj.optString("name");
                        if (!mObj.optString("author", "").isEmpty()) author = mObj.optString("author");
                        if (!mObj.optString("version", "").isEmpty()) version = mObj.optString("version");
                        if (!mObj.optString("category", "").isEmpty()) category = mObj.optString("category");
                        if (!mObj.optString("description", "").isEmpty()) description = mObj.optString("description");
                        revision = Math.max(revision, mObj.optInt("revision", 1));
                    }
                } catch (Throwable ignored) {
                }
            }
            File metaFile = new File(dir, STORE_META_FILE);
            if (metaFile.isFile()) {
                try {
                    JSONObject sm = new JSONObject(readStreamText(new FileInputStream(metaFile)));
                    if (!sm.optString("storeId", "").isEmpty()) storeId = sm.optString("storeId");
                    if (!sm.optString("category", "").isEmpty()) category = sm.optString("category");
                    if (!sm.optString("description", "").isEmpty()) description = sm.optString("description");
                    revision = Math.max(revision, sm.optInt("revision", revision));
                } catch (Throwable ignored) {
                }
            }

            File thumb = new File(dir, "thumbnail.png");
            if (!thumb.isFile()) {
                File jpg = new File(dir, "thumbnail.jpg");
                thumb = jpg.isFile() ? jpg : null;
            }
            return new PackEntry(id, storeId, name, author, version, revision,
                    category, description, p.formatVersion, srcType,
                    dir, null, thumb, null, active, "");
        } catch (Throwable t) {
            return new PackEntry(dir.getName(), "", dir.getName() + " (invalid)", "-", "-", 1,
                    "", "", 1, srcType, dir, null, null, null, active, t.getMessage());
        }
    }

    private static void writeStoreMeta(File packDir, StoreThemeListing listing) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("storeId", listing.id);
            obj.put("name", listing.name);
            obj.put("author", listing.author);
            obj.put("version", listing.version);
            obj.put("revision", listing.revision);
            obj.put("category", listing.category);
            obj.put("description", listing.description);
            obj.put("minAppVersion", listing.minAppVersion);
            obj.put("sha256", listing.sha256);
            obj.put("updatedAt", listing.updatedAt);
            FileOutputStream fos = new FileOutputStream(new File(packDir, STORE_META_FILE));
            try {
                fos.write(obj.toString(2).getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String sanitizeId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]", "_");
        while (s.startsWith("_")) s = s.substring(1);
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    private static String sanitizeOrHashId(String rawId, String fallbackName) {
        String safeId = sanitizeId(rawId);
        if (safeId.isEmpty() || "default".equals(safeId)) {
            String basis = (rawId == null || rawId.isEmpty()) ? (fallbackName == null ? "theme" : fallbackName) : rawId;
            safeId = "custom_" + Math.abs(basis.hashCode());
        }
        return safeId;
    }

    private static void copyDirectorySafe(File src, File dst, String relPrefix, int depth, long[] stats) throws Exception {
        if (depth > 8 || src == null || !src.isDirectory()) {
            return;
        }
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("Cannot create dir: " + dst);
        }
        File[] children = src.listFiles();
        if (children == null) {
            return;
        }
        byte[] buf = new byte[16384];
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".") || name.contains("..")) {
                continue;
            }
            if (!child.getAbsolutePath().equals(child.getCanonicalPath())) {
                continue;
            }
            String relPath = relPrefix.isEmpty() ? name : (relPrefix + "/" + name);
            if (SafeThemeZipExtractor.shouldSkipJunkFile(relPath)) {
                continue;
            }
            File target = new File(dst, name);
            if (child.isDirectory()) {
                copyDirectorySafe(child, target, relPath, depth + 1, stats);
            } else if (child.isFile()) {
                stats[0]++;
                if (stats[0] > SafeThemeZipExtractor.MAX_ENTRY_COUNT) {
                    throw new SecurityException("USB theme folder exceeds max " + SafeThemeZipExtractor.MAX_ENTRY_COUNT + " files.");
                }
                long len = child.length();
                stats[1] += len;
                if (stats[1] > SafeThemeZipExtractor.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new SecurityException("USB theme folder exceeds 150 MB total safety cap.");
                }
                SafeThemeZipExtractor.validateFolderFileAllowed(relPath, new FileInputStream(child), len);
                FileInputStream in = new FileInputStream(child);
                FileOutputStream out = new FileOutputStream(target);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                    out.close();
                }
            }
        }
    }

    private static boolean deleteTree(File f) {
        if (f == null || !f.exists()) {
            return false;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteTree(c);
                }
            }
        }
        return f.delete();
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
}
