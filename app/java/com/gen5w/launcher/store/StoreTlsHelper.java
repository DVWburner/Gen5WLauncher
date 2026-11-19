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
        Certificate x1 = cf.generateCertificate(new ByteArrayInputStream(ISRG_ROOT_X1_PEM.getBytes("US-ASCII")));
        Certificate x2 = cf.generateCertificate(new ByteArrayInputStream(ISRG_ROOT_X2_PEM.getBytes("US-ASCII")));
        extraKs.setCertificateEntry("isrg_root_x1", x1);
        extraKs.setCertificateEntry("isrg_root_x2", x2);

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
                        throw primary;
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
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return configureSocket(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return configureSocket(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return configureSocket(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return configureSocket(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return configureSocket(delegate.createSocket(address, port, localAddress, localPort));
        }

        private static Socket configureSocket(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
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
