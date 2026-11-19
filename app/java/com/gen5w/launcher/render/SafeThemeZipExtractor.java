package com.gen5w.launcher.render;

import com.gen5w.launcher.model.ThemePack;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Strict, streaming single-theme ZIP verifier and atomic extractor for Android API 19+.
 * Mirrors desktop Studio security checks (ThemePackageService.cs) without loading
 * multi-megabyte archives into RAM:
 *   - Enforces single root layout.json (rejects nested multi-theme archives)
 *   - Blocks symlinks (via ZIP central directory external attributes) & ZIP-slip paths
 *   - Enforces <=2500 entries, <=25 MB/entry, <=150 MB total uncompressed bytes
 *   - Whitelists dirs (icons, img, media, anim, fonts) & extensions (+ anim/* /frames.json)
 *   - Checks file magic headers & blocks executable signatures (MZ, ELF, #!, PK, CAFEBABE)
 *   - Parses & sanitizes SVGs safely (no ENTITY/DTD subset/script/foreignObject/on* /javascript:)
 *   - Parses layout.json before promoting staged pack into internal storage
 */
public final class SafeThemeZipExtractor {
    public static final int MAX_ENTRY_COUNT = 2500;
    public static final long MAX_SINGLE_FILE_BYTES = 25L * 1024L * 1024L;
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 150L * 1024L * 1024L;
    private static final int MAX_TEXT_ENTRY_BYTES = 8 * 1024 * 1024;

    private static final Set<String> ALLOWED_SUBDIRS = new HashSet<String>(
            Arrays.asList("icons", "img", "media", "anim", "fonts"));
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<String>(
            Arrays.asList(".svg", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ttf", ".otf"));
    private static final Set<String> BLOCKED_EXTENSIONS = new HashSet<String>(Arrays.asList(
            ".exe", ".dll", ".bat", ".cmd", ".ps1", ".psm1", ".sh", ".bash", ".zsh",
            ".apk", ".jar", ".dex", ".odex", ".so", ".sys", ".msi", ".vbs", ".vbe",
            ".js", ".mjs", ".cjs", ".ts", ".py", ".rb", ".pl", ".php", ".html", ".htm",
            ".hta", ".scr", ".com", ".pif", ".lnk", ".reg", ".zip", ".7z", ".rar", ".tar", ".gz"
    ));
    private static final Pattern SIMPLE_DOCTYPE_PATTERN =
            Pattern.compile("<!DOCTYPE\\b[^>\\[]*>", Pattern.CASE_INSENSITIVE);

    public static final class ExtractResult {
        public final ThemePack pack;
        public final int fileCount;
        public final long totalBytes;

        ExtractResult(ThemePack pack, int fileCount, long totalBytes) {
            this.pack = pack;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
    }

    public static final class ZipSummary {
        public String id = "";
        public String name = "";
        public String author = "";
        public String version = "";
        public String category = "";
        public String description = "";
        public int revision = 1;
        public int formatVersion = 2;
        public byte[] thumbnailBytes;
        public String error = "";
    }

    private SafeThemeZipExtractor() {}

    public static ZipSummary inspectSummary(File zipFile) {
        ZipSummary s = new ZipSummary();
        if (zipFile == null || !zipFile.isFile()) {
            s.error = "ZIP file not found";
            return s;
        }
        s.name = zipFile.getName();
        ZipFile zf = null;
        try {
            rejectSymlinksFromCentralDirectory(zipFile);
            zf = new ZipFile(zipFile);
            int entryCount = 0;
            int layoutCount = 0;
            ZipEntry layoutEntry = null;
            ZipEntry manifestEntry = null;
            ZipEntry thumbEntry = null;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String rawName = normalizeEntryName(e.getName());
                if (e.isDirectory() || rawName.endsWith("/") || shouldIgnoreOsJunk(rawName)) {
                    continue;
                }
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    s.error = "Exceeds max " + MAX_ENTRY_COUNT + " files";
                    return s;
                }
                if (isUnsafePath(rawName)) {
                    s.error = "Unsafe path: " + rawName;
                    return s;
                }
                if ("layout.json".equalsIgnoreCase(rawName)) {
                    layoutCount++;
                    layoutEntry = e;
                } else if (rawName.toLowerCase(Locale.US).endsWith("/layout.json")) {
                    s.error = "Contains multiple/nested themes (" + rawName + ")";
                    return s;
                } else if ("manifest.json".equalsIgnoreCase(rawName)) {
                    manifestEntry = e;
                } else if ("thumbnail.png".equalsIgnoreCase(rawName) || "thumbnail.jpg".equalsIgnoreCase(rawName)) {
                    thumbEntry = e;
                }
            }
            if (layoutCount != 1 || layoutEntry == null) {
                s.error = "Missing root layout.json";
                return s;
            }
            byte[] layoutBytes = readEntryBounded(zf.getInputStream(layoutEntry), MAX_TEXT_ENTRY_BYTES);
            ThemePack p = ThemePack.fromJson(new String(layoutBytes, "UTF-8"), "zip:" + zipFile.getName(), "");
            s.id = p.id;
            s.name = p.name;
            s.author = p.author;
            s.version = p.version;
            s.description = p.description;
            s.formatVersion = p.formatVersion;

            if (manifestEntry != null) {
                try {
                    byte[] mBytes = readEntryBounded(zf.getInputStream(manifestEntry), 1024 * 1024);
                    JSONObject mObj = new JSONObject(new String(mBytes, "UTF-8")).optJSONObject("theme");
                    if (mObj != null) {
                        if (!mObj.optString("id", "").isEmpty()) s.id = mObj.optString("id");
                        if (!mObj.optString("name", "").isEmpty()) s.name = mObj.optString("name");
                        if (!mObj.optString("author", "").isEmpty()) s.author = mObj.optString("author");
                        if (!mObj.optString("version", "").isEmpty()) s.version = mObj.optString("version");
                        if (!mObj.optString("category", "").isEmpty()) s.category = mObj.optString("category");
                        if (!mObj.optString("description", "").isEmpty()) s.description = mObj.optString("description");
                        s.revision = Math.max(1, mObj.optInt("revision", 1));
                    }
                } catch (Throwable ignored) {
                }
            }
            if (thumbEntry != null) {
                try {
                    byte[] tb = readEntryBounded(zf.getInputStream(thumbEntry), 4 * 1024 * 1024);
                    if (hasPngMagic(tb) || hasJpegMagic(tb)) {
                        s.thumbnailBytes = tb;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            s.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (IOException ignored) {}
            }
        }
        return s;
    }

    public static ExtractResult extractSafely(File zipFile, File destinationDir) throws Exception {
        if (zipFile == null || !zipFile.isFile()) {
            throw new IllegalArgumentException("Archive file does not exist.");
        }
        if (destinationDir == null) {
            throw new IllegalArgumentException("Destination directory is required.");
        }

        rejectSymlinksFromCentralDirectory(zipFile);

        File parent = destinationDir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create packs directory: " + parent);
        }
        File stageDir = new File(parent, destinationDir.getName() + ".staging_" + System.currentTimeMillis());
        deleteTree(stageDir);
        if (!stageDir.mkdirs()) {
            throw new IOException("Cannot create staging directory: " + stageDir);
        }

        String stageCanonical = stageDir.getCanonicalPath();
        if (!stageCanonical.endsWith(File.separator)) {
            stageCanonical += File.separator;
        }

        ZipFile zf = null;
        boolean promoted = false;
        try {
            zf = new ZipFile(zipFile);
            int entryCount = 0;
            int layoutCount = 0;
            long totalUncompressed = 0L;
            String layoutJsonText = null;
            Set<String> seenLower = new HashSet<String>();

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String rawName = normalizeEntryName(e.getName());
                if (e.isDirectory() || rawName.endsWith("/") || shouldIgnoreOsJunk(rawName)) {
                    continue;
                }
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw new IllegalStateException("Archive exceeds max " + MAX_ENTRY_COUNT + " file entries.");
                }
                if (isUnsafePath(rawName)) {
                    throw new SecurityException("Blocked unsafe archive path: " + rawName);
                }
                if (!seenLower.add(rawName.toLowerCase(Locale.US))) {
                    throw new SecurityException("Duplicate archive entry: " + rawName);
                }

                if ("layout.json".equalsIgnoreCase(rawName)) {
                    layoutCount++;
                } else if (rawName.toLowerCase(Locale.US).endsWith("/layout.json")) {
                    throw new SecurityException("Archive contains multiple themes (" + rawName + "); only one theme is allowed.");
                }

                validatePathAllowed(rawName);

                File outFile = new File(stageDir, rawName.replace('/', File.separatorChar));
                String outCanon = outFile.getCanonicalPath();
                if (!outCanon.startsWith(stageCanonical)) {
                    throw new SecurityException("Blocked path traversal outside staging dir: " + rawName);
                }
                File outParent = outFile.getParentFile();
                if (outParent != null && !outParent.exists() && !outParent.mkdirs()) {
                    throw new IOException("Cannot create subdirectory for " + rawName);
                }

                long declaredSize = e.getSize();
                if (declaredSize > MAX_SINGLE_FILE_BYTES) {
                    throw new SecurityException(rawName + " exceeds 25 MB per-file safety cap.");
                }

                long written;
                if (isTextVerifiedEntry(rawName)) {
                    byte[] bytes = readEntryBounded(zf.getInputStream(e), MAX_TEXT_ENTRY_BYTES);
                    written = bytes.length;
                    totalUncompressed += written;
                    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw new SecurityException("Archive exceeds 150 MB uncompressed safety limit.");
                    }
                    validateTextOrSvgEntry(rawName, bytes);
                    if ("layout.json".equalsIgnoreCase(rawName)) {
                        layoutJsonText = new String(bytes, "UTF-8");
                    }
                    FileOutputStream fos = new FileOutputStream(outFile);
                    try {
                        fos.write(bytes);
                    } finally {
                        fos.close();
                    }
                } else {
                    written = streamAndVerifyBinaryEntry(zf.getInputStream(e), rawName, outFile, totalUncompressed);
                    totalUncompressed += written;
                    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw new SecurityException("Archive exceeds 150 MB uncompressed safety limit.");
                    }
                }
            }

            if (layoutCount != 1 || layoutJsonText == null) {
                throw new SecurityException("Archive must contain exactly one root layout.json.");
            }
            ThemePack parsedPack = ThemePack.fromJson(layoutJsonText, "zip:" + zipFile.getName(), destinationDir.getAbsolutePath());

            zf.close();
            zf = null;

            File backupOld = null;
            if (destinationDir.exists()) {
                backupOld = new File(parent, destinationDir.getName() + ".backup_" + System.currentTimeMillis());
                deleteTree(backupOld);
                if (!destinationDir.renameTo(backupOld)) {
                    deleteTree(destinationDir);
                    backupOld = null;
                }
            }
            if (!stageDir.renameTo(destinationDir)) {
                if (backupOld != null && backupOld.exists()) {
                    backupOld.renameTo(destinationDir);
                }
                throw new IOException("Failed promoting validated theme into " + destinationDir);
            }
            if (backupOld != null && backupOld.exists()) {
                deleteTree(backupOld);
            }
            promoted = true;
            return new ExtractResult(parsedPack, entryCount, totalUncompressed);
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (IOException ignored) {}
            }
            if (!promoted) {
                deleteTree(stageDir);
            }
        }
    }

    private static boolean isTextVerifiedEntry(String rawName) {
        String low = rawName.toLowerCase(Locale.US);
        return "layout.json".equals(low) || "manifest.json".equals(low)
                || isAllowedAnimFramesJson(low) || low.endsWith(".svg");
    }

    private static void validateTextOrSvgEntry(String rawName, byte[] bytes) throws Exception {
        if (bytes.length == 0) {
            throw new SecurityException(rawName + " is empty.");
        }
        if (hasBlockedBinarySignature(bytes)) {
            throw new SecurityException(rawName + " has blocked binary header.");
        }
        String low = rawName.toLowerCase(Locale.US);
        if (low.endsWith(".svg")) {
            validateSvgSafety(rawName, bytes);
            return;
        }
        String text = new String(bytes, "UTF-8");
        Object parsed = new JSONTokener(text).nextValue();
        if ("layout.json".equals(low) && !(parsed instanceof JSONObject)) {
            throw new SecurityException("layout.json must be a JSON object.");
        }
    }

    private static long streamAndVerifyBinaryEntry(InputStream in, String rawName, File outFile, long priorTotal)
            throws Exception {
        byte[] header = new byte[16];
        int headerLen = 0;
        byte[] buf = new byte[32768];
        long written = 0L;
        FileOutputStream fos = new FileOutputStream(outFile);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (headerLen < header.length) {
                    int take = Math.min(header.length - headerLen, n);
                    System.arraycopy(buf, 0, header, headerLen, take);
                    headerLen += take;
                }
                written += n;
                if (written > MAX_SINGLE_FILE_BYTES) {
                    throw new SecurityException(rawName + " exceeds 25 MB per-file safety cap.");
                }
                if (priorTotal + written > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new SecurityException("Archive exceeds 150 MB uncompressed safety limit.");
                }
                fos.write(buf, 0, n);
            }
            fos.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            try { fos.close(); } catch (IOException ignored) {}
        }

        if (written == 0L) {
            throw new SecurityException(rawName + " is empty.");
        }
        byte[] hdr = headerLen < header.length ? Arrays.copyOf(header, headerLen) : header;
        if (hasBlockedBinarySignature(hdr)) {
            throw new SecurityException(rawName + " contains blocked executable/archive signature.");
        }
        verifyExpectedFileMagic(rawName, hdr);
        return written;
    }

    private static void verifyExpectedFileMagic(String relPath, byte[] hdr) {
        String low = relPath.toLowerCase(Locale.US);
        if (low.endsWith(".png")) {
            if (!hasPngMagic(hdr)) throw new SecurityException(relPath + ": invalid PNG header");
        } else if (low.endsWith(".jpg") || low.endsWith(".jpeg")) {
            if (!hasJpegMagic(hdr)) throw new SecurityException(relPath + ": invalid JPEG header");
        } else if (low.endsWith(".gif")) {
            if (!hasAsciiPrefix(hdr, "GIF87a") && !hasAsciiPrefix(hdr, "GIF89a")) {
                throw new SecurityException(relPath + ": invalid GIF header");
            }
        } else if (low.endsWith(".webp")) {
            if (hdr.length < 12 || !hasAsciiPrefix(hdr, "RIFF")
                    || hdr[8] != 'W' || hdr[9] != 'E' || hdr[10] != 'B' || hdr[11] != 'P') {
                throw new SecurityException(relPath + ": invalid WebP header");
            }
        } else if (low.endsWith(".ttf") || low.endsWith(".otf")) {
            boolean ok = hasSignature(hdr, 0x00, 0x01, 0x00, 0x00)
                    || hasAsciiPrefix(hdr, "OTTO")
                    || hasAsciiPrefix(hdr, "true")
                    || hasAsciiPrefix(hdr, "typ1");
            if (!ok) {
                throw new SecurityException(relPath + ": invalid font header");
            }
        }
    }

    static void validateSvgSafety(String relPath, byte[] bytes) throws Exception {
        String xml = new String(bytes, "UTF-8");
        String lower = xml.toLowerCase(Locale.US);
        if (lower.contains("javascript:") || lower.contains("vbscript:")) {
            throw new SecurityException(relPath + ": SVG contains disallowed script URI scheme");
        }
        if (lower.contains("<!entity")) {
            throw new SecurityException(relPath + ": SVG contains disallowed <!ENTITY declaration");
        }
        String sanitized = SIMPLE_DOCTYPE_PATTERN.matcher(xml).replaceAll("");
        String sanitizedLower = sanitized.toLowerCase(Locale.US);
        if (sanitizedLower.contains("<!doctype")) {
            throw new SecurityException(relPath + ": SVG contains disallowed internal DTD subset");
        }
        // Strip leading UTF-8 BOM & whitespace cleanly
        int start = 0;
        while (start < sanitized.length() && (sanitized.charAt(start) == '\uFEFF' || Character.isWhitespace(sanitized.charAt(start)))) {
            start++;
        }
        sanitized = sanitized.substring(start);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setExpandEntityReferences(false);
        setFeatureQuietly(dbf, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureQuietly(dbf, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureQuietly(dbf, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuietly(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureQuietly(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(sanitized.getBytes("UTF-8")));
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new SecurityException(relPath + ": empty SVG document");
        }
        String rootName = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
        if (!"svg".equalsIgnoreCase(rootName)) {
            throw new SecurityException(relPath + ": SVG root element must be <svg>");
        }
        inspectSvgElementRecursive(relPath, root);
    }

    private static void inspectSvgElementRecursive(String relPath, Element el) {
        String tag = (el.getLocalName() != null ? el.getLocalName() : el.getTagName()).toLowerCase(Locale.US);
        if ("script".equals(tag) || "foreignobject".equals(tag) || "iframe".equals(tag)
                || "object".equals(tag) || "embed".equals(tag)) {
            throw new SecurityException(relPath + ": SVG contains disallowed <" + tag + "> element");
        }
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; attrs != null && i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String an = (a.getLocalName() != null ? a.getLocalName() : a.getNodeName()).toLowerCase(Locale.US);
            if (an.startsWith("on")) {
                throw new SecurityException(relPath + ": SVG contains event handler attribute " + an);
            }
            String val = a.getNodeValue() == null ? "" : a.getNodeValue().toLowerCase(Locale.US);
            if (val.contains("javascript:") || val.contains("vbscript:") || val.contains("data:text/html")) {
                throw new SecurityException(relPath + ": SVG attribute " + an + " contains unsafe URI");
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; children != null && i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element) {
                inspectSvgElementRecursive(relPath, (Element) c);
            }
        }
    }

    private static void setFeatureQuietly(DocumentBuilderFactory dbf, String feature, boolean value) {
        try {
            dbf.setFeature(feature, value);
        } catch (Throwable ignored) {
        }
    }

    private static void validatePathAllowed(String relPath) {
        String low = relPath.toLowerCase(Locale.US);
        if ("layout.json".equals(low) || "manifest.json".equals(low)
                || "thumbnail.png".equals(low) || "thumbnail.jpg".equals(low)) {
            return;
        }
        int slash = relPath.indexOf('/');
        if (slash <= 0 || slash == relPath.length() - 1) {
            throw new SecurityException("Disallowed root file '" + relPath + "'");
        }
        String topDir = relPath.substring(0, slash).toLowerCase(Locale.US);
        if (!ALLOWED_SUBDIRS.contains(topDir)) {
            throw new SecurityException("Folder '" + topDir + "/' is not in whitelist (icons, img, media, anim, fonts)");
        }
        if (isAllowedAnimFramesJson(low)) {
            return;
        }
        int dot = low.lastIndexOf('.');
        String ext = dot >= 0 ? low.substring(dot) : "";
        if (ext.isEmpty()) {
            throw new SecurityException("File without extension not allowed: " + relPath);
        }
        if (BLOCKED_EXTENSIONS.contains(ext) || !ALLOWED_EXTENSIONS.contains(ext)) {
            throw new SecurityException("Disallowed file type '" + ext + "' in " + relPath);
        }
        boolean isFont = ".ttf".equals(ext) || ".otf".equals(ext);
        if ("fonts".equals(topDir) && !isFont) {
            throw new SecurityException("Only .ttf/.otf files allowed in fonts/: " + relPath);
        }
        if (!"fonts".equals(topDir) && isFont) {
            throw new SecurityException("Font files must be inside fonts/: " + relPath);
        }
    }

    private static boolean isAllowedAnimFramesJson(String lowerRelPath) {
        String[] segs = lowerRelPath.split("/");
        return segs.length >= 2 && "anim".equals(segs[0]) && "frames.json".equals(segs[segs.length - 1]);
    }

    private static boolean isUnsafePath(String rawName) {
        return rawName == null || rawName.isEmpty() || rawName.startsWith("/")
                || rawName.contains("..") || rawName.indexOf(':') >= 0 || rawName.indexOf('\0') >= 0;
    }

    private static String normalizeEntryName(String raw) {
        return raw == null ? "" : raw.replace('\\', '/').trim();
    }

    private static boolean shouldIgnoreOsJunk(String relPath) {
        String low = relPath.toLowerCase(Locale.US);
        int slash = low.lastIndexOf('/');
        String base = slash >= 0 ? low.substring(slash + 1) : low;
        return low.startsWith("__macosx/") || ".ds_store".equals(base)
                || "thumbs.db".equals(base) || "desktop.ini".equals(base);
    }

    private static void rejectSymlinksFromCentralDirectory(File zipFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(zipFile, "r");
        try {
            long len = raf.length();
            if (len < 22L) {
                return;
            }
            int searchLen = (int) Math.min(len, 65557L);
            long startOff = len - searchLen;
            raf.seek(startOff);
            byte[] tail = new byte[searchLen];
            raf.readFully(tail);

            int eocdIdx = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4B && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                    int commentLen = u16le(tail, i + 20);
                    if (i + 22 + commentLen == tail.length) {
                        eocdIdx = i;
                        break;
                    }
                }
            }
            if (eocdIdx < 0) {
                return;
            }
            int cdEntries = u16le(tail, eocdIdx + 10);
            long cdSize = u32le(tail, eocdIdx + 12);
            long cdOffset = u32le(tail, eocdIdx + 16);
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || cdSize > 32L * 1024L * 1024L || cdOffset + cdSize > len) {
                return;
            }
            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOffset);
            raf.readFully(cd);

            int pos = 0;
            for (int i = 0; i < cdEntries && pos + 46 <= cd.length; i++) {
                if (cd[pos] != 0x50 || cd[pos + 1] != 0x4B || cd[pos + 2] != 0x01 || cd[pos + 3] != 0x02) {
                    break;
                }
                int nameLen = u16le(cd, pos + 28);
                int extraLen = u16le(cd, pos + 30);
                int commentLen = u16le(cd, pos + 32);
                long extAttr = u32le(cd, pos + 38);
                long unixMode = (extAttr >>> 16) & 0xF000L;
                if (unixMode == 0xA000L) {
                    int safeNameLen = Math.min(nameLen, Math.max(0, cd.length - (pos + 46)));
                    String entryName = new String(cd, pos + 46, safeNameLen, "UTF-8");
                    throw new SecurityException("Archive contains blocked symbolic link: " + entryName);
                }
                pos += 46 + nameLen + extraLen + commentLen;
            }
        } finally {
            raf.close();
        }
    }

    private static int u16le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static byte[] readEntryBounded(InputStream in, int maxBytes) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new SecurityException("Entry exceeds size cap (" + maxBytes + " bytes)");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static boolean hasBlockedBinarySignature(byte[] b) {
        return hasSignature(b, 0x4D, 0x5A) // MZ
                || hasSignature(b, 0x7F, 0x45, 0x4C, 0x46) // ELF
                || hasSignature(b, 0x23, 0x21) // #!
                || hasSignature(b, 0x50, 0x4B, 0x03, 0x04) // PK ZIP/APK/JAR
                || hasSignature(b, 0xCA, 0xFE, 0xBA, 0xBE); // Java class / Mach-O
    }

    private static boolean hasPngMagic(byte[] b) {
        return hasSignature(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
    }

    private static boolean hasJpegMagic(byte[] b) {
        return hasSignature(b, 0xFF, 0xD8, 0xFF);
    }

    private static boolean hasSignature(byte[] data, int... sig) {
        if (data == null || data.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if ((data[i] & 0xFF) != sig[i]) return false;
        }
        return true;
    }

    private static boolean hasAsciiPrefix(byte[] data, String ascii) {
        if (data == null || data.length < ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (data[i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    private static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File c : kids) deleteTree(c);
            }
        }
        return f.delete();
    }
}
