package com.gen5w.launcher.store;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure Java HTTP client for https://market.ioniqthemes.com/ compatible with
 * Android 4.4.2 KitKat (TLS 1.2 + embedded ISRG Root X1/X2) and host JVM tests.
 */
public final class ThemeStoreClient {
    public static final String DEFAULT_BASE_URL = "https://market.ioniqthemes.com";
    public static final int MAX_CATALOG_BYTES = 4 * 1024 * 1024;
    public static final int MAX_THUMBNAIL_BYTES = 6 * 1024 * 1024;
    public static final long MAX_PACKAGE_BYTES = 150L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static final int MAX_REDIRECTS = 4;

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public interface CancelFlag {
        boolean isCancelled();
    }

    private final String baseUrl;
    private final boolean enforceHttps;

    public ThemeStoreClient() {
        this(DEFAULT_BASE_URL);
    }

    public ThemeStoreClient(String baseUrl) {
        String clean = baseUrl == null || baseUrl.trim().isEmpty()
                ? DEFAULT_BASE_URL
                : baseUrl.trim().replaceAll("/+$", "");
        this.baseUrl = clean;
        this.enforceHttps = !isLoopbackHttp(clean);
        validateUrlAllowed(clean, enforceHttps);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public StoreThemeListing.CatalogResult fetchCatalog(String category, String query) {
        try {
            String qs = buildQueryString(category, query);
            HttpURLConnection conn = openConnectionWithRedirects(baseUrl + "/api/v1/themes" + qs);
            int code = conn.getResponseCode();
            if (code == 404) {
                conn.disconnect();
                conn = openConnectionWithRedirects(baseUrl + "/" + qs);
                code = conn.getResponseCode();
            }
            if (code < 200 || code >= 300) {
                String errBody = readBoundedText(conn.getErrorStream(), 2048);
                conn.disconnect();
                return new StoreThemeListing.CatalogResult(
                        false,
                        "Store server returned HTTP " + code + (errBody.isEmpty() ? "" : ": " + errBody),
                        "",
                        null,
                        null);
            }
            String jsonText;
            try {
                jsonText = readBoundedText(conn.getInputStream(), MAX_CATALOG_BYTES);
            } finally {
                conn.disconnect();
            }
            return parseCatalogResponse(jsonText, category, query);
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.trim().isEmpty()) {
                msg = t.getClass().getSimpleName();
            }
            return new StoreThemeListing.CatalogResult(false, msg, "", null, null);
        }
    }

    public StoreThemeListing.CatalogResult parseCatalogResponse(String jsonText, String filterCategory, String filterQuery)
            throws Exception {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            return new StoreThemeListing.CatalogResult(false, "Store returned an empty catalog response.", "", null, null);
        }
        Object parsed = new JSONTokener(jsonText).nextValue();
        JSONObject rootObj = parsed instanceof JSONObject ? (JSONObject) parsed : null;
        JSONArray themeArr = null;
        List<String> serverCategories = new ArrayList<String>();
        String generatedAt = "";

        if (rootObj != null) {
            generatedAt = rootObj.optString("generatedAt", "");
            JSONArray cats = rootObj.optJSONArray("categories");
            if (cats != null) {
                Set<String> seen = new LinkedHashSet<String>();
                for (int i = 0; i < cats.length(); i++) {
                    String c = cats.optString(i, "").trim();
                    if (!c.isEmpty() && seen.add(c)) {
                        serverCategories.add(c);
                    }
                }
            }
            if (rootObj.has("themes")) themeArr = rootObj.optJSONArray("themes");
            else if (rootObj.has("items")) themeArr = rootObj.optJSONArray("items");
            else if (rootObj.has("data")) themeArr = rootObj.optJSONArray("data");
        } else if (parsed instanceof JSONArray) {
            themeArr = (JSONArray) parsed;
        }

        List<StoreThemeListing> list = new ArrayList<StoreThemeListing>();
        if (themeArr != null) {
            for (int i = 0; i < themeArr.length(); i++) {
                JSONObject o = themeArr.optJSONObject(i);
                if (o == null) continue;
                StoreThemeListing item = parseThemeItem(o);
                if (item != null && matchesFilter(item, filterCategory, filterQuery)) {
                    list.add(item);
                }
            }
        }

        Collections.sort(list, new Comparator<StoreThemeListing>() {
            @Override
            public int compare(StoreThemeListing a, StoreThemeListing b) {
                long au = a.updatedAtMs > 0L ? a.updatedAtMs : a.createdAtMs;
                long bu = b.updatedAtMs > 0L ? b.updatedAtMs : b.createdAtMs;
                if (au != bu) {
                    return bu > au ? 1 : -1;
                }
                if (a.createdAtMs != b.createdAtMs) {
                    return b.createdAtMs > a.createdAtMs ? 1 : -1;
                }
                if (a.revision != b.revision) {
                    return b.revision - a.revision;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        return new StoreThemeListing.CatalogResult(true, "", generatedAt, serverCategories, list);
    }

    public byte[] downloadThumbnailBytes(String thumbnailUrl, int maxBytes) throws IOException {
        String abs = resolveStoreUrl(thumbnailUrl);
        if (abs.isEmpty()) {
            return null;
        }
        int cap = maxBytes > 0 ? maxBytes : MAX_THUMBNAIL_BYTES;
        HttpURLConnection conn = openConnectionWithRedirects(abs);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Thumbnail HTTP " + code);
            }
            return readBoundedBytes(conn.getInputStream(), cap);
        } finally {
            conn.disconnect();
        }
    }

    public void downloadPackageStreamed(
            String downloadUrl,
            File destFile,
            String expectedSha256,
            ProgressListener listener,
            CancelFlag cancel) throws Exception {
        String abs = resolveStoreUrl(downloadUrl);
        if (abs.isEmpty()) {
            throw new IllegalArgumentException("Missing theme download URL.");
        }
        if (destFile == null) {
            throw new IllegalArgumentException("Missing destination file.");
        }
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create download cache folder.");
        }
        if (destFile.exists()) {
            destFile.delete();
        }

        boolean completed = false;
        HttpURLConnection conn = openConnectionWithRedirects(abs);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Download failed with HTTP " + code);
            }
            long contentLength = conn.getContentLength();
            if (contentLength > MAX_PACKAGE_BYTES) {
                throw new IOException("Theme package exceeds 150 MB safety cap.");
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(destFile);
            try {
                byte[] buf = new byte[32768];
                long total = 0L;
                int n;
                if (listener != null) {
                    listener.onProgress(0L, contentLength);
                }
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.isCancelled()) {
                        throw new IOException("Download cancelled.");
                    }
                    total += n;
                    if (total > MAX_PACKAGE_BYTES) {
                        throw new IOException("Downloaded archive exceeds 150 MB safety limit.");
                    }
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                    if (listener != null) {
                        listener.onProgress(total, contentLength);
                    }
                }
                out.flush();
            } finally {
                try { in.close(); } catch (IOException ignored) {}
                try { out.close(); } catch (IOException ignored) {}
            }

            if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                String actualHex = toHexLower(sha.digest());
                if (!actualHex.equalsIgnoreCase(expectedSha256.trim())) {
                    throw new IOException("Downloaded file failed SHA-256 checksum verification.");
                }
            }
            completed = true;
        } finally {
            conn.disconnect();
            if (!completed && destFile.exists()) {
                destFile.delete();
            }
        }
    }

    private StoreThemeListing parseThemeItem(JSONObject o) {
        StoreThemeListing item = new StoreThemeListing();
        item.id = o.optString("id", "").trim();
        item.name = o.optString("name", item.id).trim();
        if (item.name.isEmpty()) item.name = "Untitled Theme";
        item.author = o.optString("author", "Community").trim();
        if (item.author.isEmpty()) item.author = "Community";
        item.version = o.optString("version", "1.0.0").trim();
        item.revision = Math.max(1, o.optInt("revision", 1));
        item.category = o.optString("category", "").trim();
        item.description = o.optString("description", "").trim();
        item.minAppVersion = o.optString("minAppVersion", "").trim();
        item.formatVersion = Math.max(1, o.optInt("formatVersion", 2));
        item.showSystemBar = o.optBoolean("showSystemBar", true);
        item.createdAt = o.optString("createdAt", "").trim();
        item.updatedAt = o.optString("updatedAt", "").trim();
        item.createdAtMs = StoreThemeListing.parseIsoUtcMillis(item.createdAt);
        item.updatedAtMs = StoreThemeListing.parseIsoUtcMillis(item.updatedAt);
        item.downloadUrl = resolveStoreUrl(o.optString("downloadUrl", ""));
        item.thumbnailUrl = resolveStoreUrl(o.optString("thumbnailUrl", ""));
        item.fileSizeBytes = Math.max(0L, o.optLong("fileSizeBytes", 0L));
        item.sha256 = o.optString("sha256", "").trim();
        item.downloads = Math.max(0L, o.optLong("downloads", 0L));

        JSONArray comp = o.optJSONArray("compatibleVersions");
        if (comp != null) {
            for (int i = 0; i < comp.length(); i++) {
                String c = comp.optString(i, "").trim();
                if (!c.isEmpty()) item.compatibleVersions.add(c);
            }
        }
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                String t = tags.optString(i, "").trim();
                if (!t.isEmpty()) item.tags.add(t);
            }
        }
        if (item.id.isEmpty() && item.downloadUrl.isEmpty()) {
            return null;
        }
        return item;
    }

    public String resolveStoreUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String r = raw.trim();
        try {
            URI u = URI.create(r);
            String resolved = u.isAbsolute() ? u.toString() : URI.create(baseUrl + "/").resolve(u).toString();
            validateUrlAllowed(resolved, enforceHttps);
            return resolved;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean matchesFilter(StoreThemeListing item, String category, String query) {
        if (category != null && !category.trim().isEmpty() && !"All Categories".equalsIgnoreCase(category.trim())) {
            if (!category.trim().equalsIgnoreCase(item.category == null ? "" : item.category.trim())) {
                return false;
            }
        }
        if (query != null && !query.trim().isEmpty()) {
            String q = query.trim().toLowerCase(Locale.US);
            if (containsIgnoreCase(item.name, q)
                    || containsIgnoreCase(item.author, q)
                    || containsIgnoreCase(item.id, q)
                    || containsIgnoreCase(item.category, q)
                    || containsIgnoreCase(item.description, q)) {
                return true;
            }
            for (String tag : item.tags) {
                if (containsIgnoreCase(tag, q)) return true;
            }
            for (String cv : item.compatibleVersions) {
                if (containsIgnoreCase(cv, q)) return true;
            }
            return false;
        }
        return true;
    }

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.US).contains(needleLower);
    }

    private static String buildQueryString(String category, String query) throws IOException {
        StringBuilder sb = new StringBuilder("?sort=newest");
        if (category != null && !category.trim().isEmpty() && !"All Categories".equalsIgnoreCase(category.trim())) {
            sb.append("&category=").append(URLEncoder.encode(category.trim(), "UTF-8"));
        }
        if (query != null && !query.trim().isEmpty()) {
            sb.append("&q=").append(URLEncoder.encode(query.trim(), "UTF-8"));
        }
        return sb.toString();
    }

    private HttpURLConnection openConnectionWithRedirects(String initialUrl) throws IOException {
        String current = initialUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateUrlAllowed(current, enforceHttps);
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json, */*");
            conn.setRequestProperty("User-Agent", "Gen5WLauncher/0.4.3 (Android 4.4.2; Gen5W)");
            StoreTlsHelper.configure(conn);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) {
                    throw new IOException("Redirect without Location header");
                }
                current = URI.create(current).resolve(loc.trim()).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many HTTP redirects");
    }

    private static void validateUrlAllowed(String url, boolean requireHttps) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if ("https".equals(scheme)) {
            return;
        }
        if (!requireHttps && "http".equals(scheme) && isLoopbackHttp(url)) {
            return;
        }
        throw new IllegalArgumentException("Blocked non-HTTPS store URL: " + url);
    }

    private static boolean isLoopbackHttp(String url) {
        try {
            URI u = URI.create(url);
            if (!"http".equalsIgnoreCase(u.getScheme())) {
                return false;
            }
            String h = u.getHost();
            return "127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readBoundedText(InputStream in, int maxBytes) throws IOException {
        if (in == null) {
            return "";
        }
        return new String(readBoundedBytes(in, maxBytes), "UTF-8");
    }

    private static byte[] readBoundedBytes(InputStream in, int maxBytes) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("Response exceeds " + maxBytes + " byte safety limit.");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String toHexLower(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = alphabet[v >>> 4];
            hex[i * 2 + 1] = alphabet[v & 0x0F];
        }
        return new String(hex);
    }
}
