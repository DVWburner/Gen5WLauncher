package com.gen5w.launcher.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Enables TLS 1.2 + modern Let's Encrypt ISRG Root X1/X2 PKIX verification on
 * Android 4.4.2 KitKat (API 19), where TLSv1.2 is off by default and ISRG roots
 * are absent from the 2014 system CA store. Never bypasses cert or hostname checks.
 */
public final class StoreTlsHelper {
    private static final String ISRG_ROOT_X1_PEM =
            "-----BEGIN CERTIFICATE-----\n"
            + "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n"
            + "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n"
            + "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n"
            + "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n"
            + "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n"
            + "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n"
            + "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF\n"
            + "+0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6\n"
            + "UA5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+s\n"
            + "WT8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qy\n"
            + "HB5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+U\n"
            + "CB5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHz\n"
            + "UvKBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahm\n"
            + "bWnOlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3Sz\n"
            + "ynTnjh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEb\n"
            + "wrbwqHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y\n"
            + "53CIrU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAP\n"
            + "BgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjAN\n"
            + "BgkqhkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V\n"
            + "9lZLubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPb\n"
            + "k6ZGQ3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisR\n"
            + "cOj/KKNFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzk\n"
            + "tHCgKQ5ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnv\n"
            + "zqvHu7UrTkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wd\n"
            + "eRlN8NwdCjNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscd\n"
            + "Cb+ZAJzVcoyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4\n"
            + "kqKOJ2qxq4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2\n"
            + "LN9d11TPAmRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1Sg\n"
            + "EEzwxA57demyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX\n"
            + "5iItreGCc=\n"
            + "-----END CERTIFICATE-----\n";

    private static final String ISRG_ROOT_X2_PEM =
            "-----BEGIN CERTIFICATE-----\n"
            + "MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw\n"
            + "CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg\n"
            + "R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00\n"
            + "MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT\n"
            + "ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw\n"
            + "EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW\n"
            + "+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9\n"
            + "ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T\n"
            + "AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI\n"
            + "zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW\n"
            + "tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1\n"
            + "/q4AaOeMSQ+2b1tbFfLn\n"
            + "-----END CERTIFICATE-----\n";

    private static final String GTS_ROOT_R1_PEM =
            "-----BEGIN CERTIFICATE-----\n"
            + "MIIFVzCCAz+gAwIBAgINAgPlk28xsBNJiGuiFzANBgkqhkiG9w0BAQwFADBHMQsw\n"
            + "CQYDVQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZpY2VzIExMQzEU\n"
            + "MBIGA1UEAxMLR1RTIFJvb3QgUjEwHhcNMTYwNjIyMDAwMDAwWhcNMzYwNjIyMDAw\n"
            + "MDAwWjBHMQswCQYDVQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZp\n"
            + "Y2VzIExMQzEUMBIGA1UEAxMLR1RTIFJvb3QgUjEwggIiMA0GCSqGSIb3DQEBAQUA\n"
            + "A4ICDwAwggIKAoICAQC2EQKLHuOhd5s73L+UPreVp0A8of2C+X0yBoJx9vaMf/vo\n"
            + "27xqLpeXo4xL+Sv2sfnOhB2x+cWX3u+58qPpvBKJXqeqUqv4IyfLpLGcY9vXmX7w\n"
            + "Cl7raKb0xlpHDU0QM+NOsROjyBhsS+z8CZDfnWQpJSMHobTSPS5g4M/SCYe7zUjw\n"
            + "TcLCeoiKu7rPWRnWr4+wB7CeMfGCwcDfLqZtbBkOtdh+JhpFAz2weaSUKK0Pfybl\n"
            + "qAj+lug8aJRT7oM6iCsVlgmy4HqMLnXWnOunVmSPlk9orj2XwoSPwLxAwAtcvfaH\n"
            + "szVsrBhQf4TgTM2S0yDpM7xSma8ytSmzJSq0SPly4cpk9+aCEI3oncKKiPo4Zor8\n"
            + "Y/kB+Xj9e1x3+naH+uzfsQ55lVe0vSbv1gHR6xYKu44LtcXFilWr06zqkUspzBmk\n"
            + "MiVOKvFlRNACzqrOSbTqn3yDsEB750Orp2yjj32JgfpMpf/VjsPOS+C12LOORc92\n"
            + "wO1AK/1TD7Cn1TsNsYqiA94xrcx36m97PtbfkSIS5r762DL8EGMUUXLeXdYWk70p\n"
            + "aDPvOmbsB4om3xPXV2V4J95eSRQAogB/mqghtqmxlbCluQ0WEdrHbEg8QOB+DVrN\n"
            + "VjzRlwW5y0vtOUucxD/SVRNuJLDWcfr0wbrM7Rv1/oFB2ACYPTrIrnqYNxgFlQID\n"
            + "AQABo0IwQDAOBgNVHQ8BAf8EBAMCAYYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4E\n"
            + "FgQU5K8rJnEaK0gnhS9SZizv8IkTcT4wDQYJKoZIhvcNAQEMBQADggIBAJ+qQibb\n"
            + "C5u+/x6Wki4+omVKapi6Ist9wTrYggoGxval3sBOh2Z5ofmmWJyq+bXmYOfg6LEe\n"
            + "QkEzCzc9zolwFcq1JKjPa7XSQCGYzyI0zzvFIoTgxQ6KfF2I5DUkzps+GlQebtuy\n"
            + "h6f88/qBVRRiClmpIgUxPoLW7ttXNLwzldMXG+gnoot7TiYaelpkttGsN/H9oPM4\n"
            + "7HLwEXWdyzRSjeZ2axfG34arJ45JK3VmgRAhpuo+9K4l/3wV3s6MJT/KYnAK9y8J\n"
            + "ZgfIPxz88NtFMN9iiMG1D53Dn0reWVlHxYciNuaCp+0KueIHoI17eko8cdLiA6Ef\n"
            + "MgfdG+RCzgwARWGAtQsgWSl4vflVy2PFPEz0tv/bal8xa5meLMFrUKTX5hgUvYU/\n"
            + "Z6tGn6D/Qqc6f1zLXbBwHSs09dR2CQzreExZBfMzQsNhFRAbd03OIozUhfJFfbdT\n"
            + "6u9AWpQKXCBfTkBdYiJ23//OYb2MI3jSNwLgjt7RETeJ9r/tSQdirpLsQBqvFAnZ\n"
            + "0E6yove+7u7Y/9waLd64NnHi/Hm3lCXRSHNboTXns5lndcEZOitHTtNCjv0xyBZm\n"
            + "2tIMPNuzjsmhDYAPexZ3FL//2wmUspO8IFgV6dtxQ/PeEMMA3KgqlbbC1j+Qa3bb\n"
            + "bP6MvPJwNQzcmRk13NfIRmPVNnGuV/u3gm3c\n"
            + "-----END CERTIFICATE-----\n";

    private static final String GTS_ROOT_R4_PEM =
            "-----BEGIN CERTIFICATE-----\n"
            + "MIICCTCCAY6gAwIBAgINAgPlwGjvYxqccpBQUjAKBggqhkjOPQQDAzBHMQswCQYD\n"
            + "VQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZpY2VzIExMQzEUMBIG\n"
            + "A1UEAxMLR1RTIFJvb3QgUjQwHhcNMTYwNjIyMDAwMDAwWhcNMzYwNjIyMDAwMDAw\n"
            + "WjBHMQswCQYDVQQGEwJVUzEiMCAGA1UEChMZR29vZ2xlIFRydXN0IFNlcnZpY2Vz\n"
            + "IExMQzEUMBIGA1UEAxMLR1RTIFJvb3QgUjQwdjAQBgcqhkjOPQIBBgUrgQQAIgNi\n"
            + "AATzdHOnaItgrkO4NcWBMHtLSZ37wWHO5t5GvWvVYRg1rkDdc/eJkTBa6zzuhXyi\n"
            + "QHY7qca4R9gq55KRanPpsXI5nymfopjTX15YhmUPoYRlBtHci8nHc8iMai/lxKvR\n"
            + "HYqjQjBAMA4GA1UdDwEB/wQEAwIBhjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQW\n"
            + "BBSATNbrdP9JNqPV2Py1PsVq8JQdjDAKBggqhkjOPQQDAwNpADBmAjEA6ED/g94D\n"
            + "9J+uHXqnLrmvT/aDHQ4thQEd0dlq7A/Cr8deVl5c1RxYIigL9zC2L7F8AjEA8GE8\n"
            + "p/SgguMh1YQdc4acLa/KNJvxn7kjNuK8YAOdgLOaVsjh4rsUecrNIdSUtUlD\n"
            + "-----END CERTIFICATE-----\n";

    private static final String DIGICERT_GLOBAL_ROOT_G2_PEM =
            "-----BEGIN CERTIFICATE-----\n"
            + "MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBhMQswCQYDVQQG\n"
            + "EwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3d3cuZGlnaWNlcnQuY29tMSAw\n"
            + "HgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBHMjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUx\n"
            + "MjAwMDBaMGExCzAJBgNVBAYTAlVTMRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3\n"
            + "dy5kaWdpY2VydC5jb20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkq\n"
            + "hkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI2/Ou8jqJ\n"
            + "kTx65qsGGmvPrC3oXgkkRLpimn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx1x7e/dfgy5SDN67sH0NO\n"
            + "3Xss0r0upS/kqbitOtSZpLYl6ZtrAGCSYP9PIUkY92eQq2EGnI/yuum06ZIya7XzV+hdG82MHauV\n"
            + "BJVJ8zUtluNJbd134/tJS7SsVQepj5WztCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyM\n"
            + "UNGPHgm+F6HmIcr9g+UQvIOlCsRnKPZzFBQ9RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQAB\n"
            + "o0IwQDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV5uNu\n"
            + "5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBnKJRvDkhj6zHd6mcY1Yl9PMWLSn/pvtsr\n"
            + "F9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4NeF22d+mQrvHRAiGfzZ0JFrabA0U\n"
            + "WTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NGFdtom/DzMNU+MeKNhJ7jitralj41E6Vf8PlwUHBH\n"
            + "QRFXGU7Aj64GxJUTFy8bJZ918rGOmaFvE7FBcf6IKshPECBV1/MUReXgRPTqh5Uykw7+U0b6LJ3/\n"
            + "iyK5S9kJRaTepLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyNxF1WW6LZZm6zNTfl\n"
            + "MrY=\n"
            + "-----END CERTIFICATE-----\n";

    private static volatile SSLSocketFactory cachedFactory;
    private static volatile X509TrustManager cachedTrustManager;

    private StoreTlsHelper() {}

    public static void configure(HttpURLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(getSocketFactory());
        }
    }

    public static synchronized SSLSocketFactory getSocketFactory() {
        if (cachedFactory != null) {
            return cachedFactory;
        }
        try {
            X509TrustManager tm = getCompositeTrustManager();
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, new TrustManager[] { tm }, new SecureRandom());
            cachedFactory = new Tls12SocketFactory(ctx.getSocketFactory());
            return cachedFactory;
        } catch (Throwable t) {
            try {
                X509TrustManager tm = getCompositeTrustManager();
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] { tm }, new SecureRandom());
                cachedFactory = new Tls12SocketFactory(ctx.getSocketFactory());
                return cachedFactory;
            } catch (Exception ex) {
                throw new IllegalStateException("Failed initializing TLS 1.2 SSLContext", ex);
            }
        }
    }

    public static synchronized X509TrustManager getCompositeTrustManager() throws Exception {
        if (cachedTrustManager != null) {
            return cachedTrustManager;
        }
        String alg = TrustManagerFactory.getDefaultAlgorithm();
        TrustManagerFactory sysTmf = TrustManagerFactory.getInstance(alg);
        sysTmf.init((KeyStore) null);
        X509TrustManager sysTm = firstX509(sysTmf.getTrustManagers());

        KeyStore extraKs = KeyStore.getInstance(KeyStore.getDefaultType());
        extraKs.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        extraKs.setCertificateEntry("isrg_root_x1", cf.generateCertificate(new ByteArrayInputStream(ISRG_ROOT_X1_PEM.getBytes("US-ASCII"))));
        extraKs.setCertificateEntry("isrg_root_x2", cf.generateCertificate(new ByteArrayInputStream(ISRG_ROOT_X2_PEM.getBytes("US-ASCII"))));
        extraKs.setCertificateEntry("gts_root_r1", cf.generateCertificate(new ByteArrayInputStream(GTS_ROOT_R1_PEM.getBytes("US-ASCII"))));
        extraKs.setCertificateEntry("gts_root_r4", cf.generateCertificate(new ByteArrayInputStream(GTS_ROOT_R4_PEM.getBytes("US-ASCII"))));
        extraKs.setCertificateEntry("digicert_global_root_g2", cf.generateCertificate(new ByteArrayInputStream(DIGICERT_GLOBAL_ROOT_G2_PEM.getBytes("US-ASCII"))));

        TrustManagerFactory extraTmf = TrustManagerFactory.getInstance(alg);
        extraTmf.init(extraKs);
        X509TrustManager extraTm = firstX509(extraTmf.getTrustManagers());

        cachedTrustManager = new CompositeX509TrustManager(sysTm, extraTm);
        return cachedTrustManager;
    }

    private static X509TrustManager firstX509(TrustManager[] list) {
        if (list != null) {
            for (TrustManager tm : list) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        }
        return null;
    }

    private static final class CompositeX509TrustManager implements X509TrustManager {
        private final X509TrustManager systemTm;
        private final X509TrustManager extraTm;
        private final X509Certificate[] mergedIssuers;

        CompositeX509TrustManager(X509TrustManager systemTm, X509TrustManager extraTm) {
            this.systemTm = systemTm;
            this.extraTm = extraTm;
            List<X509Certificate> issuers = new ArrayList<X509Certificate>();
            if (systemTm != null && systemTm.getAcceptedIssuers() != null) {
                issuers.addAll(Arrays.asList(systemTm.getAcceptedIssuers()));
            }
            if (extraTm != null && extraTm.getAcceptedIssuers() != null) {
                issuers.addAll(Arrays.asList(extraTm.getAcceptedIssuers()));
            }
            this.mergedIssuers = issuers.toArray(new X509Certificate[issuers.size()]);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (systemTm != null) {
                systemTm.checkClientTrusted(chain, authType);
            } else if (extraTm != null) {
                extraTm.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException primary = null;
            if (systemTm != null) {
                try {
                    systemTm.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    primary = ex;
                }
            }
            if (extraTm != null) {
                try {
                    extraTm.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    if (primary != null) {
                        throw new CertificateException(
                                "TLS verification failed (system: " + primary.getMessage()
                                + "; embedded CAs: " + ex.getMessage() + ")", ex);
                    }
                    throw ex;
                }
            }
            if (primary != null) {
                throw primary;
            }
            throw new CertificateException("No X509TrustManager available");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return mergedIssuers;
        }
    }

    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return configureSocket(delegate.createSocket(), null);
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return configureSocket(delegate.createSocket(s, host, port, autoClose), host);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return configureSocket(delegate.createSocket(host, port), host);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return configureSocket(delegate.createSocket(host, port, localHost, localPort), host);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return configureSocket(delegate.createSocket(host, port), host != null ? host.getHostName() : null);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return configureSocket(delegate.createSocket(address, port, localAddress, localPort), address != null ? address.getHostName() : null);
        }

        private static Socket configureSocket(Socket s, String sniHost) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                if (sniHost != null && !sniHost.isEmpty()) {
                    try {
                        java.lang.reflect.Method setHostname = ssl.getClass().getMethod("setHostname", String.class);
                        setHostname.invoke(ssl, sniHost);
                    } catch (Throwable ignored) {
                    }
                }
                String[] supportedProtos = ssl.getSupportedProtocols();
                if (supportedProtos != null) {
                    Set<String> want = new LinkedHashSet<String>();
                    List<String> sup = Arrays.asList(supportedProtos);
                    if (sup.contains("TLSv1.3")) want.add("TLSv1.3");
                    if (sup.contains("TLSv1.2")) want.add("TLSv1.2");
                    if (sup.contains("TLSv1.1")) want.add("TLSv1.1");
                    if (sup.contains("TLSv1")) want.add("TLSv1");
                    if (!want.isEmpty()) {
                        ssl.setEnabledProtocols(want.toArray(new String[want.size()]));
                    }
                }
                String[] supportedCiphers = ssl.getSupportedCipherSuites();
                String[] enabledCiphers = ssl.getEnabledCipherSuites();
                if (supportedCiphers != null && enabledCiphers != null) {
                    Set<String> merged = new LinkedHashSet<String>(Arrays.asList(enabledCiphers));
                    for (String c : supportedCiphers) {
                        if ((c.startsWith("TLS_ECDHE_") || c.startsWith("TLS_AES_"))
                                && !c.contains("_NULL_")
                                && !c.contains("_anon_")
                                && !c.contains("_EXPORT_")
                                && !c.contains("_RC4_")
                                && !c.contains("_DES_")) {
                            merged.add(c);
                        }
                    }
                    ssl.setEnabledCipherSuites(merged.toArray(new String[merged.size()]));
                }
            }
            return s;
        }
    }
}
