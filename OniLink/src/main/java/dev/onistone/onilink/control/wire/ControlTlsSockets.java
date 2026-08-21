package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.config.OniControlConfig.ControlBackendConfig;
import dev.onistone.onilink.config.OniControlConfig.TlsConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class ControlTlsSockets {
    private ControlTlsSockets() {
    }

    static SSLSocket connect(ControlBackendConfig backend) throws Exception {
        TlsConfig tls = backend.tls();
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (tls.caFile().toString().isBlank()) {
            trust.init((KeyStore) null);
        } else {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (InputStream input = Files.newInputStream(tls.caFile())) {
                int index = 0;
                for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                    store.setCertificateEntry("control-ca-" + index++, certificate);
                }
                if (index == 0) throw new IOException("control TLS CA file contains no certificates");
            }
            trust.init(store);
        }

        KeyManagerFactory keys = null;
        if (!tls.clientCertificate().toString().isBlank()) {
            List<X509Certificate> chain = certificates(tls.clientCertificate());
            PrivateKey key = privateKey(tls.clientPrivateKey());
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setKeyEntry("onilink-control", key, new char[0], chain.toArray(Certificate[]::new));
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, new char[0]);
        }

        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        javax.net.ssl.SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new javax.net.ssl.SNIHostName(tls.serverName())));
        socket.setSSLParameters(parameters);
        socket.connect(new java.net.InetSocketAddress(backend.connectHost(), backend.connectPort()),
                backend.connectTimeoutMillis());
        socket.startHandshake();
        verifyPin(socket, tls.pinnedCertificateSha256());
        return socket;
    }

    private static void verifyPin(SSLSocket socket, String expectedHex) throws Exception {
        if (expectedHex.isBlank()) return;
        byte[] actual = MessageDigest.getInstance("SHA-256")
                .digest(socket.getSession().getPeerCertificates()[0].getEncoded());
        byte[] expected = java.util.HexFormat.of().parseHex(expectedHex);
        if (!MessageDigest.isEqual(actual, expected)) {
            socket.close();
            throw new IOException("OniControl TLS certificate pin mismatch");
        }
    }

    private static List<X509Certificate> certificates(java.nio.file.Path path) throws Exception {
        List<X509Certificate> result = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path)) {
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                result.add((X509Certificate) certificate);
            }
        }
        if (result.isEmpty()) throw new IOException("control TLS client certificate file is empty");
        return result;
    }

    private static PrivateKey privateKey(java.nio.file.Path path) throws Exception {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(body);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (java.security.GeneralSecurityException ignored) {
                // Try the other standard key type.
            }
        }
        throw new IOException("control TLS private key must be unencrypted PKCS#8 RSA or EC PEM");
    }
}
