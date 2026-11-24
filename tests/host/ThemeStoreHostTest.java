import com.gen5w.launcher.ipc.TemperatureNormalizer;
import com.gen5w.launcher.render.SafeThemeZipExtractor;
import com.gen5w.launcher.store.StoreThemeListing;
import com.gen5w.launcher.store.StoreTlsHelper;
import com.gen5w.launcher.store.ThemeStoreClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.net.ssl.X509TrustManager;

/**
 * Host JVM verification suite covering KitKat TLS trust roots, Store HTTP/JSON client,
 * compatibility checks, streaming SHA-256 download, and SafeThemeZipExtractor security guards.
 */
public final class ThemeStoreHostTest {
    private static final byte[] PNG_HDR = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };
    private static final byte[] JPG_HDR = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46
    };
    private static final byte[] TTF_HDR = new byte[] {
            0x00, 0x01, 0x00, 0x00, 0x00, 0x0F, 0x00, (byte) 0x80
    };

    private static final String VALID_LAYOUT = "{\n"
            + "  \"formatVersion\": 2,\n"
            + "  \"id\": \"autumn-ronin\",\n"
            + "  \"name\": \"Autumn Ronin\",\n"
            + "  \"author\": \"Tapple\",\n"
            + "  \"version\": \"1.2.0\",\n"
            + "  \"root\": {\n"
            + "    \"id\": \"r\", \"type\": \"panel\", \"layout\": \"flex_row\",\n"
            + "    \"children\": [{\"id\": \"b\", \"kind\": \"tile\", \"title\": \"EV\", \"action\": {\"type\": \"root_start\", \"package\": \"com.example\", \"class\": \".Main\"}}]\n"
            + "  }\n"
            + "}\n";

    private static final String VALID_SVGREPO = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n"
            + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M2 2h20v20z\"/></svg>\n";

    public static void main(String[] args) throws Exception {
        boolean runLive = args != null && Arrays.asList(args).contains("--live");

        File tmpRoot = java.nio.file.Files.createTempDirectory("gen5w-store-test-").toFile();
        try {
            testTlsRootsEmbedded();
            testCompatibilityAndFormatting();
            testBundledDefaultPackContainsThemeStore();
            testTemperatureNormalizerCrossUnit();
            testStoreHttpMockClient(tmpRoot);
            testSafeZipExtractorPassAndSecurity(tmpRoot);
            testLegacyThemeBackwardsCompatibility(tmpRoot);
            if (runLive) {
                testLiveStoreEndpoint(tmpRoot);
            }
            System.out.println("ALL HOST STORE & SECURITY TESTS PASSED" + (runLive ? " (INCLUDING LIVE HTTPS)" : ""));
        } finally {
            deleteTree(tmpRoot);
        }
    }

    private static void testTlsRootsEmbedded() throws Exception {
        X509TrustManager tm = StoreTlsHelper.getCompositeTrustManager();
        X509Certificate[] issuers = tm.getAcceptedIssuers();
        boolean hasX1 = false;
        boolean hasX2 = false;
        boolean hasGtsR1 = false;
        boolean hasGtsR4 = false;
        boolean hasDigiG2 = false;
        for (X509Certificate c : issuers) {
            String dn = c.getSubjectX500Principal().getName();
            if (dn.contains("CN=ISRG Root X1")) hasX1 = true;
            if (dn.contains("CN=ISRG Root X2")) hasX2 = true;
            if (dn.contains("CN=GTS Root R1")) hasGtsR1 = true;
            if (dn.contains("CN=GTS Root R4")) hasGtsR4 = true;
            if (dn.contains("CN=DigiCert Global Root G2")) hasDigiG2 = true;
        }
        check(hasX1, "ISRG Root X1 missing from composite TrustManager");
        check(hasX2, "ISRG Root X2 missing from composite TrustManager");
        check(hasGtsR1, "GTS Root R1 missing from composite TrustManager");
        check(hasGtsR4, "GTS Root R4 missing from composite TrustManager");
        check(hasDigiG2, "DigiCert Global Root G2 missing from composite TrustManager");
        check(StoreTlsHelper.getSocketFactory() != null, "TLSv1.2 SSLSocketFactory null");
        System.out.println("ok  StoreTlsHelper: ISRG X1/X2 + GTS R1/R4 + DigiCert G2 loaded & Tls12SocketFactory ready");
    }

    private static void testBundledDefaultPackContainsThemeStore() throws Exception {
        File defLayout = new File("app/assets/packs/default/layout.json");
        if (!defLayout.isFile()) {
            defLayout = new File("../app/assets/packs/default/layout.json");
        }
        check(defLayout.isFile(), "Default layout.json not found");
        String json = new String(readAll(defLayout), StandardCharsets.UTF_8);
        com.gen5w.launcher.model.ThemePack pack = com.gen5w.launcher.model.ThemePack.fromJson(json, "bundled:default", "");
        check(pack != null && pack.root != null, "Default layout failed to parse");
        check(json.contains("\"open_theme_store\""), "Default layout must contain front-and-center open_theme_store tile");
        check(new File(defLayout.getParentFile(), "icons/store.svg").isFile(), "icons/store.svg missing from default pack");
        System.out.println("ok  Bundled default layout.json parses cleanly with front-and-center Theme Store tile");
    }

    private static void testCompatibilityAndFormatting() {
        StoreThemeListing l = new StoreThemeListing();
        l.minAppVersion = "0.3.4";
        l.compatibleVersions.add("0.3.4+");
        check(l.isCompatibleWith("0.3.5"), "0.3.4+ should match 0.3.5");
        check(l.isCompatibleWith("0.5.0"), "0.3.4+ should match 0.5.0");
        check(!l.isCompatibleWith("0.3.3"), "0.3.4+ must not match 0.3.3");

        StoreThemeListing newer = new StoreThemeListing();
        newer.minAppVersion = "0.4.0";
        newer.compatibleVersions.add("0.4.0+");
        check(!newer.isCompatibleWith("0.3.5"), "0.4.0+ must warn on 0.3.5");
        check(newer.isCompatibleWith("0.5.0"), "0.4.0+ should match 0.5.0");

        long ms = StoreThemeListing.parseIsoUtcMillis("2026-11-19T00:45:21Z");
        check(ms > 0L, "ISO timestamp parsing failed");
        check("1.0 MB".equals(StoreThemeListing.formatByteSize(1024L * 1024L)), "byte formatting mismatch");
        System.out.println("ok  StoreThemeListing: semantic compatibility & UTC parsing verified");
    }

    private static void testStoreHttpMockClient(File tmpRoot) throws Exception {
        final File pkgZip = new File(tmpRoot, "sample_store.zip");
        Map<String, byte[]> files = validThemeFiles();
        writeZip(pkgZip, files, -1);
        final byte[] pkgBytes = readAll(pkgZip);
        final String pkgSha = sha256Hex(pkgBytes);

        final boolean[] useFallbackRoot = { false };
        final boolean[] fail500 = { false };

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String path = ex.getRequestURI().getPath();
                if (fail500[0]) {
                    byte[] err = "internal error".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(500, err.length);
                    ex.getResponseBody().write(err);
                    ex.close();
                    return;
                }
                if ("/api/v1/themes".equals(path) && useFallbackRoot[0]) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                if ("/api/v1/themes".equals(path) || "/".equals(path)) {
                    String json = "{\n"
                            + "  \"apiVersion\": 1,\n"
                            + "  \"generatedAt\": \"2026-11-19T01:00:00Z\",\n"
                            + "  \"categories\": [\"AnimeOnly\", \"Nature\"],\n"
                            + "  \"themes\": [\n"
                            + "    {\"id\": \"older-one\", \"name\": \"Older\", \"author\": \"A\", \"version\": \"1.0.0\", \"revision\": 1, \"category\": \"Nature\", \"updatedAt\": \"2026-11-01T10:00:00Z\", \"downloadUrl\": \"/downloads/sample.zip\", \"thumbnailUrl\": \"/thumbs/t.png\", \"sha256\": \"" + pkgSha + "\", \"fileSizeBytes\": " + pkgBytes.length + "},\n"
                            + "    {\"id\": \"autumn-ronin\", \"name\": \"Autumn Ronin\", \"author\": \"Tapple\", \"version\": \"1.2.0\", \"revision\": 2, \"category\": \"AnimeOnly\", \"updatedAt\": \"2026-11-18T12:00:00Z\", \"downloadUrl\": \"/downloads/sample.zip\", \"thumbnailUrl\": \"/thumbs/t.png\", \"sha256\": \"" + pkgSha + "\", \"fileSizeBytes\": " + pkgBytes.length + "}\n"
                            + "  ]\n"
                            + "}\n";
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                    return;
                }
                if ("/downloads/sample.zip".equals(path)) {
                    ex.getResponseHeaders().set("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, pkgBytes.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(pkgBytes);
                    }
                    return;
                }
                if ("/thumbs/t.png".equals(path)) {
                    ex.sendResponseHeaders(200, PNG_HDR.length);
                    ex.getResponseBody().write(PNG_HDR);
                    ex.close();
                    return;
                }
                ex.sendResponseHeaders(404, -1);
                ex.close();
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ThemeStoreClient client = new ThemeStoreClient(base);

            // 1. Server categories respected & newest-first enforced
            StoreThemeListing.CatalogResult cat = client.fetchCatalog("", "");
            check(cat.ok, "Expected ok catalog: " + cat.errorMessage);
            check(cat.categories.equals(Arrays.asList("AnimeOnly", "Nature")),
                    "Categories mismatch (must match server strictly): " + cat.categories);
            check(cat.themes.size() == 2, "Expected 2 themes");
            check("autumn-ronin".equals(cat.themes.get(0).id), "Expected newest theme first");

            // 2. Fallback to GET / on 404
            useFallbackRoot[0] = true;
            StoreThemeListing.CatalogResult fb = client.fetchCatalog("AnimeOnly", "ronin");
            check(fb.ok && fb.themes.size() == 1, "Expected 404 fallback + filter match");

            // 3. No demo fallback when offline / HTTP 500
            fail500[0] = true;
            StoreThemeListing.CatalogResult err = client.fetchCatalog("", "");
            check(!err.ok && err.themes.isEmpty(), "Error must fail explicitly without demo fallback");
            fail500[0] = false;

            // 4. Streaming package download + SHA-256 pass + mismatch fail cleanup
            File dstGood = new File(tmpRoot, "dl_good.zip");
            final long[] lastProg = { 0L };
            client.downloadPackageStreamed(cat.themes.get(0).downloadUrl, dstGood, pkgSha,
                    new ThemeStoreClient.ProgressListener() {
                        @Override
                        public void onProgress(long bytesRead, long totalBytes) {
                            lastProg[0] = bytesRead;
                        }
                    }, null);
            check(dstGood.isFile() && lastProg[0] == pkgBytes.length, "Streamed download size mismatch");

            File dstBadSha = new File(tmpRoot, "dl_badsha.zip");
            boolean shaRejected = false;
            try {
                client.downloadPackageStreamed(cat.themes.get(0).downloadUrl, dstBadSha,
                        "0000000000000000000000000000000000000000000000000000000000000000", null, null);
            } catch (Exception expected) {
                shaRejected = expected.getMessage().contains("SHA-256");
            }
            check(shaRejected && !dstBadSha.exists(), "SHA-256 mismatch must fail and remove staged file");

            // 5. Production HTTPS client must block plain http:// URLs
            ThemeStoreClient prodClient = new ThemeStoreClient(ThemeStoreClient.DEFAULT_BASE_URL);
            check("".equals(prodClient.resolveStoreUrl("http://insecure.example.com/a.zip")),
                    "Production client must reject non-HTTPS asset URLs");

            System.out.println("ok  ThemeStoreClient: catalog, categories, newest sort, 404 fallback, offline error, streamed SHA-256 verified");
        } finally {
            server.stop(0);
        }
    }

    private static void testSafeZipExtractorPassAndSecurity(File tmpRoot) throws Exception {
        // Valid package extraction
        File validZip = new File(tmpRoot, "valid.zip");
        writeZip(validZip, validThemeFiles(), -1);
        SafeThemeZipExtractor.ZipSummary sum = SafeThemeZipExtractor.inspectSummary(validZip);
        check(sum.error.isEmpty() && "autumn-ronin".equals(sum.id) && "Anime".equals(sum.category) && sum.thumbnailBytes != null,
                "inspectSummary failed: " + sum.error);

        File outDir = new File(tmpRoot, "installed/autumn-ronin");
        SafeThemeZipExtractor.ExtractResult res = SafeThemeZipExtractor.extractSafely(validZip, outDir);
        check("Autumn Ronin".equals(res.pack.name), "Extracted pack mismatch");
        check(new File(outDir, "anim/wave/frames.json").isFile(), "Missing anim frames.json");
        check(new File(outDir, "fonts/ui.ttf").isFile(), "Missing font");

        // Ensure malicious zips fail AND preserve existing outDir intact!
        expectZipBlocked(tmpRoot, "multi_theme.zip", withAdded(validThemeFiles(), "packs/extra/layout.json", VALID_LAYOUT.getBytes(StandardCharsets.UTF_8)), outDir, "multiple");
        expectZipBlocked(tmpRoot, "zip_slip.zip", withAdded(validThemeFiles(), "icons/../../evil.png", PNG_HDR), outDir, "unsafe");
        expectZipBlocked(tmpRoot, "disallowed_dir.zip", withAdded(validThemeFiles(), "scripts/evil.png", PNG_HDR), outDir, "whitelist");
        expectZipBlocked(tmpRoot, "disallowed_ext.zip", withAdded(validThemeFiles(), "img/page.html", "<html>".getBytes(StandardCharsets.UTF_8)), outDir, "Disallowed file type");
        expectZipBlocked(tmpRoot, "mz_spoof.zip", withAdded(validThemeFiles(), "img/bad.png", new byte[] { 0x4D, 0x5A, 0x00, 0x01 }), outDir, "executable");
        expectZipBlocked(tmpRoot, "svg_script.zip", withAdded(validThemeFiles(), "icons/xss.svg", "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8)), outDir, "script");
        expectZipBlocked(tmpRoot, "svg_entity.zip", withAdded(validThemeFiles(), "icons/xxe.svg", "<!DOCTYPE svg [<!ENTITY x 'y'>]><svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)), outDir, "ENTITY");
        expectZipBlocked(tmpRoot, "svg_onload.zip", withAdded(validThemeFiles(), "icons/ev.svg", "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8)), outDir, "event handler");
        expectZipBlocked(tmpRoot, "svg_jsuri.zip", withAdded(validThemeFiles(), "icons/uri.svg", "<svg xmlns='http://www.w3.org/2000/svg'><a href='javascript:alert(1)'/></svg>".getBytes(StandardCharsets.UTF_8)), outDir, "script URI");

        // Symlink entry crafted via UNIX external attribute 0120777 (0xA1FF0000)
        File symlinkZip = new File(tmpRoot, "symlink.zip");
        writeZip(symlinkZip, validThemeFiles(), 0xA1FF0000);
        boolean symBlocked = false;
        try {
            SafeThemeZipExtractor.extractSafely(symlinkZip, outDir);
        } catch (Exception ex) {
            symBlocked = ex.getMessage().toLowerCase().contains("symbolic link");
        }
        check(symBlocked, "Symlink ZIP entry must be blocked");
        check(new File(outDir, "layout.json").isFile(), "Existing valid theme must survive failed extraction");

        System.out.println("ok  SafeThemeZipExtractor: valid extraction + 10 attack vector rejections verified");
    }

    private static void testLiveStoreEndpoint(File tmpRoot) throws Exception {
        ThemeStoreClient live = new ThemeStoreClient(ThemeStoreClient.DEFAULT_BASE_URL);
        StoreThemeListing.CatalogResult res = live.fetchCatalog("", "");
        check(res.ok, "Live market.ioniqthemes.com fetch failed: " + res.errorMessage);
        check(!res.categories.isEmpty(), "Live server returned empty categories");
        System.out.println("ok  LIVE HTTPS: market.ioniqthemes.com returned "
                + res.themes.size() + " themes across " + res.categories.size() + " server categories");
        if (!res.themes.isEmpty()) {
            StoreThemeListing first = res.themes.get(0);
            if (!first.thumbnailUrl.isEmpty()) {
                byte[] thumb = live.downloadThumbnailBytes(first.thumbnailUrl, ThemeStoreClient.MAX_THUMBNAIL_BYTES);
                check(thumb != null && thumb.length > 8, "Live thumbnail download failed");
                System.out.println("ok  LIVE HTTPS thumbnail verified (" + thumb.length + " bytes)");
            }
            if (!first.downloadUrl.isEmpty()) {
                File dlZip = new File(tmpRoot, "live_pkg.zip");
                live.downloadPackageStreamed(first.downloadUrl, dlZip, first.sha256, null, null);
                File liveOut = new File(tmpRoot, "live_extracted");
                SafeThemeZipExtractor.ExtractResult exRes = SafeThemeZipExtractor.extractSafely(dlZip, liveOut);
                check(exRes.pack != null && exRes.fileCount > 0, "Live package extract returned empty");
                System.out.println("ok  LIVE HTTPS package verified & extracted ("
                        + exRes.fileCount + " files, " + (exRes.totalBytes / 1024L) + " KB, pack=" + exRes.pack.name + ")");
            }
        }
    }

    private static void expectZipBlocked(File tmpRoot, String name, Map<String, byte[]> files, File preservedDir, String token)
            throws Exception {
        File z = new File(tmpRoot, name);
        writeZip(z, files, -1);
        boolean blocked = false;
        String msg = "";
        try {
            SafeThemeZipExtractor.extractSafely(z, preservedDir);
        } catch (Exception ex) {
            blocked = true;
            msg = String.valueOf(ex.getMessage());
        }
        check(blocked, name + " should have been blocked!");
        check(msg.toLowerCase().contains(token.toLowerCase()), name + " error unexpected: " + msg);
        check(new File(preservedDir, "layout.json").isFile(), preservedDir + " vanished after failed extract of " + name);
    }

    private static Map<String, byte[]> validThemeFiles() {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("layout.json", VALID_LAYOUT.getBytes(StandardCharsets.UTF_8));
        m.put("manifest.json", "{\"manifestVersion\":1,\"theme\":{\"id\":\"autumn-ronin\",\"name\":\"Autumn Ronin\",\"category\":\"Anime\",\"revision\":2}}".getBytes(StandardCharsets.UTF_8));
        m.put("thumbnail.png", PNG_HDR);
        m.put("icons/home.svg", VALID_SVGREPO.getBytes(StandardCharsets.UTF_8));
        m.put("img/wall.jpg", JPG_HDR);
        m.put("anim/wave/frames.json", "{\"fps\":16,\"frames\":[\"f_0001.jpg\"]}".getBytes(StandardCharsets.UTF_8));
        m.put("anim/wave/f_0001.jpg", JPG_HDR);
        m.put("fonts/ui.ttf", TTF_HDR);
        return m;
    }

    private static Map<String, byte[]> withAdded(Map<String, byte[]> base, String k, byte[] v) {
        Map<String, byte[]> copy = new LinkedHashMap<String, byte[]>(base);
        copy.put(k, v);
        return copy;
    }

    private static void writeZip(File outZip, Map<String, byte[]> entries, int externalAttrForLast) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip))) {
            int idx = 0;
            int total = entries.size();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                idx++;
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
                if (idx == total && externalAttrForLast != -1) {
                    // Nothing via ZipOutputStream; handled right below on raw bytes if symlink requested
                }
            }
        }
        if (externalAttrForLast != -1) {
            patchLastCentralDirExternalAttr(outZip, externalAttrForLast);
        }
    }

    private static void patchLastCentralDirExternalAttr(File zipFile, int externalAttr) throws IOException {
        byte[] data = readAll(zipFile);
        int lastCd = -1;
        for (int i = 0; i <= data.length - 46; i++) {
            if (data[i] == 0x50 && data[i + 1] == 0x4B && data[i + 2] == 0x01 && data[i + 3] == 0x02) {
                lastCd = i;
            }
        }
        check(lastCd >= 0, "CD header not found");
        data[lastCd + 38] = (byte) (externalAttr & 0xFF);
        data[lastCd + 39] = (byte) ((externalAttr >>> 8) & 0xFF);
        data[lastCd + 40] = (byte) ((externalAttr >>> 16) & 0xFF);
        data[lastCd + 41] = (byte) ((externalAttr >>> 24) & 0xFF);
        try (FileOutputStream fos = new FileOutputStream(zipFile)) {
            fos.write(data);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static String sha256Hex(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static void testTemperatureNormalizerCrossUnit() {
        // 1. Exact user-reported bug: Celsius car at 14.0°C where unpopulated Fahrenheit CAN byte decodes to -40.0°F
        Map<String, String> celsiusCar = new LinkedHashMap<String, String>();
        celsiusCar.put("outside_temp", "14.0");
        celsiusCar.put("outside_temp_f", "-40.0");
        TemperatureNormalizer.normalizeVehicleMap(celsiusCar);
        check("14".equals(celsiusCar.get("outside_temp")), "Expected outside_temp=14, got " + celsiusCar.get("outside_temp"));
        check("57".equals(celsiusCar.get("outside_temp_f")), "Expected outside_temp_f=57 for 14C, got " + celsiusCar.get("outside_temp_f"));

        // 2. Cold winter Celsius car at -38.0°C (climate.temp_unit=C) with unpopulated -40.0°F channel
        Map<String, String> winterCelsiusCar = new LinkedHashMap<String, String>();
        winterCelsiusCar.put("climate.temp_unit", "C");
        winterCelsiusCar.put("outside_temp", "-38.0");
        winterCelsiusCar.put("outside_temp_f", "-40.0");
        TemperatureNormalizer.normalizeVehicleMap(winterCelsiusCar);
        check("-38".equals(winterCelsiusCar.get("outside_temp")), "Expected -38C, got " + winterCelsiusCar.get("outside_temp"));
        check("-36".equals(winterCelsiusCar.get("outside_temp_f")), "Expected -36F for -38C, got " + winterCelsiusCar.get("outside_temp_f"));

        // 3. Genuine -40°C / -40°F extreme cold stays -40 in both
        Map<String, String> realMinus40 = new LinkedHashMap<String, String>();
        realMinus40.put("outside_temp", "-40.0");
        realMinus40.put("outside_temp_f", "-40.0");
        TemperatureNormalizer.normalizeVehicleMap(realMinus40);
        check("-40".equals(realMinus40.get("outside_temp")), "Expected -40C");
        check("-40".equals(realMinus40.get("outside_temp_f")), "Expected -40F");

        // 4. US Fahrenheit car at 73°F where Celsius state=255 or -40.0°C
        Map<String, String> usCar = new LinkedHashMap<String, String>();
        TemperatureNormalizer.resolveAmbientPair(-40.0f, 255, 73.0f, 113, "F", usCar);
        check("73".equals(usCar.get("outside_temp_f")), "Expected 73F, got " + usCar.get("outside_temp_f"));
        check("23".equals(usCar.get("outside_temp")), "Expected 23C for 73F, got " + usCar.get("outside_temp"));

        // 5. Cabin HVAC target temperature conversion (°C car <-> °F theme and vice versa)
        check(Math.round(TemperatureNormalizer.convertClimateTemp(21.5f, "C", "\u00B0F")) == 71,
                "Expected 21.5C -> 71F");
        check(Math.abs(TemperatureNormalizer.convertClimateTemp(72f, "F", "\u00B0C") - 22.0f) < 0.01f,
                "Expected 72F -> 22.0C");
        check(Math.abs(TemperatureNormalizer.convertClimateTemp(21.5f, "C", "") - 21.5f) < 0.01f,
                "Empty unit override must preserve car native unit");

        System.out.println("ok  TemperatureNormalizer: 14C/-40F bug -> 57F, US 73F -> 23C, real -40, & HVAC C<->F verified");
    }

    private static void testLegacyThemeBackwardsCompatibility(File tmpRoot) throws Exception {
        String legacyLayout = "{\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"id\": \"legacy-v03-theme\",\n"
                + "  \"name\": \"Legacy v0.3 Theme\",\n"
                + "  \"author\": \"Pioneer\",\n"
                + "  \"version\": \"1.0.0\",\n"
                + "  \"background\": { \"type\": \"bitmap\", \"posterAsset\": \"media/old_bg.jpg\" },\n"
                + "  \"pages\": [\n"
                + "    {\n"
                + "      \"id\": \"pg1\",\n"
                + "      \"title\": \"Main\",\n"
                + "      \"layout\": \"grid\",\n"
                + "      \"paddingPx\": 20,\n"
                + "      \"children\": [\n"
                + "        { \"id\": \"w_clock\", \"type\": \"clock\" },\n"
                + "        {\n"
                + "          \"id\": \"w_ring\", \"kind\": \"ring_gauge\", \"telemetryKey\": \"hv_soc\",\n"
                + "          \"marginPx\": [12, 8],\n"
                + "          \"style\": { \"gradientEndColor\": \"#FF112233\", \"fontSizePx\": 28, \"animAsset\": \"anim/wave\", \"animFps\": 12 },\n"
                + "          \"action\": { \"type\": \"preset\", \"preset\": \"ev\" }\n"
                + "        },\n"
                + "        {\n"
                + "          \"id\": \"w_app\", \"kind\": \"tile\", \"title\": \"Nav\",\n"
                + "          \"action\": { \"type\": \"broadcast\", \"pkg\": \"com.example\", \"cls\": \".Main\", \"action\": \"com.example.ACT\", \"extraIntKey\": \"mode\", \"extraIntValue\": 2 }\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";

        Map<String, byte[]> legacyFiles = new LinkedHashMap<String, byte[]>();
        legacyFiles.put("layout.json", legacyLayout.getBytes(StandardCharsets.UTF_8));
        legacyFiles.put("manifest.json", "{\"manifestVersion\":1,\"theme\":{\"id\":\"legacy-v03-theme\",\"name\":\"Legacy v0.3 Theme\",\"studioVersion\":\"0.3.9\",\"revision\":1}}".getBytes(StandardCharsets.UTF_8));
        legacyFiles.put("thumbnail.jpg", JPG_HDR);
        legacyFiles.put("media/old_bg.jpg", JPG_HDR);
        legacyFiles.put("media/unused_old_wallpaper.jpg", JPG_HDR);
        legacyFiles.put("icons/music.svg", VALID_SVGREPO.getBytes(StandardCharsets.UTF_8));
        legacyFiles.put("anim/wave/frames.json", "{\"fps\":12,\"frames\":[\"f_0001.jpg\"]}".getBytes(StandardCharsets.UTF_8));
        legacyFiles.put("anim/wave/f_0001.jpg", JPG_HDR);

        File legacyZip = new File(tmpRoot, "legacy_v039.zip");
        writeZip(legacyZip, legacyFiles, -1);

        SafeThemeZipExtractor.ZipSummary sum = SafeThemeZipExtractor.inspectSummary(legacyZip, true);
        check(sum.error.isEmpty(), "Legacy ZIP summary failed: " + sum.error);
        check("legacy-v03-theme".equals(sum.id) && sum.thumbnailBytes != null, "Legacy ZIP summary metadata mismatch");

        File outDir = new File(tmpRoot, "legacy_extracted");
        SafeThemeZipExtractor.ExtractResult res = SafeThemeZipExtractor.extractSafely(legacyZip, outDir);
        com.gen5w.launcher.model.ThemePack pack = res.pack;
        check(pack != null && pack.formatVersion == 1, "Legacy formatVersion mismatch");
        check("media/old_bg.jpg".equals(pack.background.bitmapAsset), "Legacy posterAsset alias failed");
        check("pager".equals(pack.root.layout) && pack.root.children.size() == 1, "Legacy top-level pages conversion failed");
        com.gen5w.launcher.model.PanelSpec pg = (com.gen5w.launcher.model.PanelSpec) pack.root.children.get(0);
        check("Main".equals(pg.pageTitle) && pg.paddingPx[0] == 20 && pg.paddingPx[3] == 20, "Legacy panel padding/title failed");
        com.gen5w.launcher.model.WidgetSpec clock = (com.gen5w.launcher.model.WidgetSpec) pg.children.get(0);
        com.gen5w.launcher.model.WidgetSpec ring = (com.gen5w.launcher.model.WidgetSpec) pg.children.get(1);
        com.gen5w.launcher.model.WidgetSpec app = (com.gen5w.launcher.model.WidgetSpec) pg.children.get(2);
        check("clock".equals(clock.kind), "Legacy widget type->kind fallback failed");
        check("ring_gauge".equals(ring.kind) && "hv_soc".equals(ring.telemetryKey), "Legacy ring_gauge/hv_soc failed");
        check(ring.marginPx[0] == 12 && ring.marginPx[1] == 8 && ring.style.bgEndColor == 0xFF112233
                && Math.round(ring.style.titleSizePx) == 28 && "anim/wave".equals(ring.style.bgAnimAsset)
                && ring.style.bgAnimFps == 12 && "ev".equals(ring.action.cmd), "Legacy style/preset aliases failed");
        check("com.example".equals(app.action.pkg) && ".Main".equals(app.action.cls)
                && "com.example.ACT".equals(app.action.actionName)
                && "mode".equals(app.action.extraKey) && "int".equals(app.action.extraType)
                && "2".equals(app.action.extraVal), "Legacy action aliases failed");
        check(new File(outDir, "media/unused_old_wallpaper.jpg").isFile()
                && new File(outDir, "icons/music.svg").isFile(), "Extra assets in legacy ZIP should still extract safely");
        System.out.println("ok  Backwards compatibility verified for legacy v0.1-v0.4.x ThemePack JSON & pre-v0.5.0 ZIPs");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("TEST FAILED: " + msg);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
