package com.hibegin.common.dao;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebApiConcurrencyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(45)
    void webApiRequestsUseThePoolCapacityAndQueueOverflow() throws Exception {
        SSLContext previousContext = SSLContext.getDefault();
        SSLContext testContext = localTlsContext();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        ExecutorService callers = Executors.newCachedThreadPool();
        CountDownLatch releaseResponses = new CountDownLatch(1);
        try {
            SSLContext.setDefault(testContext);
            server.setHttpsConfigurator(new HttpsConfigurator(testContext));
            server.setExecutor(serverExecutor);
            Properties properties = new Properties();
            properties.setProperty("jdbcUrl", "jdbc:webapi://127.0.0.1:" + server.getAddress().getPort() + "/test");
            properties.setProperty("user", "test");
            properties.setProperty("password", "test");

            try (DataSourceWrapperImpl dataSource = new DataSourceWrapperImpl(properties, false)) {
                int capacity = dataSource.getMaximumPoolSize();
                assertTrue(capacity > 1, "This regression test requires a pool with multiple connections");
                CountDownLatch fullBatch = new CountDownLatch(capacity);
                CountDownLatch overflowReceived = new CountDownLatch(1);
                CountDownLatch callersStarted = new CountDownLatch(capacity + 1);
                AtomicInteger received = new AtomicInteger();
                server.createContext("/test", exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes();
                        if (received.incrementAndGet() > capacity) {
                            overflowReceived.countDown();
                        }
                        fullBatch.countDown();
                        if (!releaseResponses.await(20, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting to release responses");
                        }
                        byte[] body = "{\"success\":true,\"results\":[{\"value\":1}]}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
                server.start();
                List<Future<Number>> results = new ArrayList<>();
                for (int i = 0; i <= capacity; i++) {
                    results.add(callers.submit(() -> {
                        callersStarted.countDown();
                        return dataSource.getQueryRunner().query("select 1", new ScalarHandler<Number>());
                    }));
                }

                assertTrue(callersStarted.await(10, TimeUnit.SECONDS), "All callers should start");
                assertTrue(fullBatch.await(10, TimeUnit.SECONDS), "WebAPI requests should fill the pool concurrently");
                assertEquals(capacity, dataSource.getDatabaseConnectPoolInfo().getConnectActiveSize());
                assertFalse(overflowReceived.await(300, TimeUnit.MILLISECONDS), "Overflow must wait for a free permit");

                releaseResponses.countDown();
                for (Future<Number> result : results) {
                    assertEquals(1, result.get(10, TimeUnit.SECONDS).intValue());
                }
                assertEquals(capacity + 1, received.get());
                assertEquals(0, dataSource.getDatabaseConnectPoolInfo().getConnectActiveSize());
            }
        } finally {
            releaseResponses.countDown();
            server.stop(0);
            callers.shutdownNow();
            serverExecutor.shutdownNow();
            SSLContext.setDefault(previousContext);
        }
    }

    private SSLContext localTlsContext() throws Exception {
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
