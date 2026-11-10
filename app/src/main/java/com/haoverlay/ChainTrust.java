package com.haoverlay;

import android.net.Uri;
import android.net.http.X509TrustManagerExtensions;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * The system's normal certificate checking, plus one thing browsers do and Android apps don't: when a
 * server forgets to send its intermediate certificate, download it from the address named inside the
 * server's own certificate and check again. Nothing is trusted that the system wouldn't trust; the
 * downloaded certificate only fills the gap between the server and a root the device already has.
 * (A Home Assistant server set up with only its own certificate works in a browser but fails here otherwise.)
 */
final class ChainTrust implements X509TrustManager {

    private static final String TAG = "ChainTrust";
    /** DER encoding of the "CA Issuers" access method, 1.3.6.1.5.5.7.48.2. */
    private static final byte[] CA_ISSUERS = {0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02};
    private static final Map<String, List<X509Certificate>> FETCHED = new ConcurrentHashMap<>();

    private final X509TrustManager system;
    private final X509TrustManagerExtensions extensions;

    private ChainTrust(X509TrustManager system) {
        this.system = system;
        this.extensions = new X509TrustManagerExtensions(system);
    }

    /** Makes the client use this checking. If anything about setting it up fails, the client is left as it was. */
    static OkHttpClient.Builder apply(OkHttpClient.Builder builder) {
        try {
            TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            f.init((KeyStore) null);
            for (TrustManager tm : f.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    ChainTrust trust = new ChainTrust((X509TrustManager) tm);
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{trust}, null);
                    return builder.sslSocketFactory(ctx.getSocketFactory(), trust);
                }
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "Using the default certificate checking", e);
        }
        return builder;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        system.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType);
        } catch (CertificateException first) {
            X509Certificate[] longer = completed(chain);
            if (longer == null) throw first;
            system.checkServerTrusted(longer, authType);
        }
    }

    /** Android calls this form (found by name) so the check also knows which host the certificate is for. */
    @SuppressWarnings("unused")
    public List<X509Certificate> checkServerTrusted(X509Certificate[] chain, String authType, String host)
            throws CertificateException {
        try {
            return extensions.checkServerTrusted(chain, authType, host);
        } catch (CertificateException first) {
            X509Certificate[] longer = completed(chain);
            if (longer == null) throw first;
            return extensions.checkServerTrusted(longer, authType, host);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return system.getAcceptedIssuers();
    }

    /** The chain with the missing issuer certificate(s) added, or {@code null} if none could be found. */
    private static X509Certificate[] completed(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) return null;
        List<X509Certificate> out = new ArrayList<>(Arrays.asList(chain));
        X509Certificate last = chain[chain.length - 1];
        for (int hops = 0; hops < 3; hops++) {
            if (last.getSubjectX500Principal().equals(last.getIssuerX500Principal())) break;   // reached a root
            String url = issuerUrl(last);
            if (url == null) break;
            List<X509Certificate> got = FETCHED.get(url);
            if (got == null) {
                got = download(url);
                if (got.isEmpty()) break;
                FETCHED.put(url, got);
            }
            out.addAll(got);
            last = got.get(got.size() - 1);
        }
        return out.size() > chain.length ? out.toArray(new X509Certificate[0]) : null;
    }

    /** The http address a certificate gives for its issuer's certificate, or {@code null}. */
    private static String issuerUrl(X509Certificate cert) {
        byte[] ext = cert.getExtensionValue("1.3.6.1.5.5.7.1.1");
        if (ext == null) return null;
        for (int i = 0; i + CA_ISSUERS.length + 2 < ext.length; i++) {
            boolean match = true;
            for (int j = 0; j < CA_ISSUERS.length && match; j++) match = ext[i + j] == CA_ISSUERS[j];
            if (!match) continue;
            int p = i + CA_ISSUERS.length;
            int len = ext[p + 1] & 0xFF;
            if ((ext[p] & 0xFF) != 0x86 || len >= 0x80 || p + 2 + len > ext.length) continue;   // a plain URI entry
            String url = new String(ext, p + 2, len, StandardCharsets.US_ASCII);
            if (url.startsWith("http://")) return url;
        }
        return null;
    }

    /**
     * Fetches the certificate over plain http, as the certificate itself directs (these addresses are
     * always http). That is safe: whatever comes back still has to chain to a root the system trusts.
     */
    private static List<X509Certificate> download(String url) {
        List<X509Certificate> out = new ArrayList<>();
        Uri u = Uri.parse(url);
        String host = u.getHost();
        if (host == null) return out;
        String path = u.getEncodedPath() == null || u.getEncodedPath().isEmpty() ? "/" : u.getEncodedPath();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, u.getPort() > 0 ? u.getPort() : 80), 8000);
            s.setSoTimeout(8000);
            OutputStream o = s.getOutputStream();
            o.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nAccept: */*\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 100_000) buf.write(chunk, 0, n);
            byte[] all = buf.toByteArray();
            int body = -1;
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                    body = i + 4;
                    break;
                }
            }
            if (body < 0 || !new String(all, 0, Math.min(15, all.length), StandardCharsets.US_ASCII).contains(" 200")) return out;
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (Certificate c : cf.generateCertificates(new ByteArrayInputStream(all, body, all.length - body))) {
                if (c instanceof X509Certificate) out.add((X509Certificate) c);
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't fetch the issuer certificate from " + host + ": " + e.getMessage());
        }
        return out;
    }
}
