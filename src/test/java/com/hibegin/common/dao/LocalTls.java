package com.hibegin.common.dao;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalTls {
    static SSLContext create(Path temporaryDirectory) throws Exception {
        Path keyStorePath = temporaryDirectory.resolve("localhost.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keyStorePath.toString(), "-storepass", "local-test",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).start();
        try {
            assertTrue(keytool.waitFor(10, TimeUnit.SECONDS), "Local test certificate generation should finish");
            assertEquals(0, keytool.exitValue(), new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            keytool.destroyForcibly();
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = java.nio.file.Files.newInputStream(keyStorePath)) {
            keyStore.load(input, "local-test".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "local-test".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }
}
