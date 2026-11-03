package com.hibegin.common.dao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hibegin.common.dao.entity.BaseEntity;
import com.hibegin.common.dao.entity.Entity;
import com.hibegin.common.dao.entity.EntityRepository;
import com.hibegin.common.dao.entity.OptimisticLockException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTPS protocol plus SQLite persistence; no credentials or remote service required. */
@Timeout(15)
class EntityWebApiTest {
    @TempDir static Path directory;
    private static SSLContext previousTls;
    private static HttpsServer server;
    private static ExecutorService executor;
    private static Connection sqlite;
    private static DataSourceWrapper source;
    private static final List<String> statements = new CopyOnWriteArrayList<>();
    private static final AtomicReference<String> responseMode = new AtomicReference<>("normal");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private EntityRepository<Entry, Long> repository;

    @BeforeAll
    static void start() throws Exception {
        previousTls = SSLContext.getDefault();
        SSLContext tls = LocalTls.create(directory);
        SSLContext.setDefault(tls);
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/sql", exchange -> {
            try {
                JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                String sql = request.get("sql").getAsString();
                statements.add(sql);
                Map<String, Object> result = new LinkedHashMap<>();
                try (PreparedStatement statement = sqlite.prepareStatement(sql)) {
                    int index = 1;
                    for (JsonElement param : request.getAsJsonArray("params")) {
                        Object value = null;
                        if (!param.isJsonNull()) {
                            if (param.getAsJsonPrimitive().isBoolean()) { value = param.getAsBoolean(); }
                            // Model a JavaScript Worker: numeric JSON bindings pass through IEEE-754.
                            else if (param.getAsJsonPrimitive().isNumber()) { value = (long) param.getAsDouble(); }
                            else { value = param.getAsString(); }
                        }
                        statement.setObject(index++, value);
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    boolean hasRows = statement.execute();
                    if (hasRows) {
                        try (ResultSet rs = statement.getResultSet()) {
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                                    Object value = rs.getObject(i);
                                    if (value instanceof Long) { value = ((Long) value).doubleValue(); }
                                    row.put(rs.getMetaData().getColumnLabel(i), value);
                                }
                                rows.add(row);
                            }
                        }
                    }
                    result.put("success", true);
                    result.put("results", rows);
                    result.put("meta", Map.of("changes", hasRows ? 0 : statement.getUpdateCount()));
                }
                String mode = responseMode.getAndSet("normal");
                if (mode.equals("missingCount")) { result.remove("meta"); }
                if (mode.equals("fractionalCount")) { result.put("changes", "1.5"); }
                if (mode.equals("missingColumn")) {
                    @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("results");
                    rows.get(0).remove("body");
                }
                byte[] body = (mode.equals("invalid") ? "invalid response" : JSON.toJson(result)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception e) {
                byte[] body = "{\"success\":false}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        Properties properties = new Properties();
        properties.setProperty("jdbcUrl", "jdbc:webapi://127.0.0.1:" + server.getAddress().getPort() + "/sql");
        properties.setProperty("user", "local-test");
        properties.setProperty("password", "local-test");
        source = new DataSourceWrapperImpl(properties, false);
    }

    @AfterAll
    static void stop() throws Exception {
        if (source != null) { source.close(); }
        if (server != null) { server.stop(0); }
        if (executor != null) { executor.shutdownNow(); }
        if (sqlite != null) { sqlite.close(); }
        if (previousTls != null) { SSLContext.setDefault(previousTls); }
    }

    @BeforeEach
    void reset() throws Exception {
        try (java.sql.Statement statement = sqlite.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS entry");
            statement.execute("CREATE TABLE entry (entryId INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " createdAt INTEGER, updatedAt INTEGER, version INTEGER NOT NULL, title TEXT, body TEXT, visitedAt INTEGER)");
        }
        statements.clear();
        responseMode.set("normal");
        repository = new EntityRepository<>(Entry.class, source, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC));
    }

    @Test
    void sqliteGeneratedIdAndUpdatesUseOneRequestEachAndNoOpUsesNone() throws Exception {
        Entry entry = entry();
        repository.insert(entry);
        assertEquals(1L, entry.getId());
        assertEquals(1234L, entry.getCreatedAt());
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).endsWith("RETURNING CAST(`entryId` AS TEXT) AS `entryId`"));
        Entry loaded = repository.findById(entry.getId()).orElseThrow();
        assertEquals("initial", loaded.title);
        loaded.body = null;
        statements.clear();
        repository.update(loaded);
        assertEquals(1, statements.size());
        assertEquals(1L, loaded.getVersion());
        assertNull(repository.findById(entry.getId()).orElseThrow().body);
        statements.clear();
        assertFalse(repository.update(loaded));
        assertTrue(statements.isEmpty());
        repository.delete(loaded);
        assertTrue(repository.findById(entry.getId()).isEmpty());
    }

    @Test
    void exactLongKeysAndVersionsSurviveJsonWithoutDoubleRounding() throws Exception {
        long large = 9007199254740993L;
        try (PreparedStatement statement = sqlite.prepareStatement("INSERT INTO entry(entryId,version,title) VALUES (?,?,?)")) {
            statement.setLong(1, large);
            statement.setLong(2, large);
            statement.setString(3, "before");
            statement.executeUpdate();
        }
        Entry entry = repository.findById(large).orElseThrow();
        assertEquals(large, entry.getId());
        assertEquals(large, entry.getVersion());
        entry.title = "after";
        entry.visitedAt = new Date(large);
        repository.update(entry);
        Entry persisted = repository.findById(large).orElseThrow();
        assertEquals(large + 1, persisted.getVersion());
        assertEquals("after", persisted.title);
        assertEquals(large, persisted.visitedAt.getTime());
        Entry generated = repository.insert(entry());
        assertEquals(large + 1, generated.getId());
    }

    @Test
    void webApiZeroChangesIsAnOptimisticConflict() throws Exception {
        Entry winner = repository.insert(entry());
        Entry stale = repository.findById(winner.getId()).orElseThrow();
        winner.title = "winner";
        repository.update(winner);
        stale.title = "stale";
        assertThrows(OptimisticLockException.class, () -> repository.update(stale));
        assertEquals(0L, stale.getVersion());
        assertEquals("winner", repository.findById(winner.getId()).orElseThrow().title);
    }

    @Test
    void uncertainUpdateIsNotReplayedAndMustBeReconciledByReading() throws Exception {
        for (String mode : List.of("missingCount", "fractionalCount", "invalid")) {
            Entry entry = repository.insert(entry());
            entry.title = mode;
            responseMode.set(mode);
            statements.clear();
            assertThrows(SQLException.class, () -> repository.update(entry));
            assertEquals(1, statements.size());
            assertEquals(0L, entry.getVersion(), "An unconfirmed write cannot advance the in-memory baseline");
            assertThrows(IllegalStateException.class, () -> repository.update(entry));
            assertEquals(1, statements.size(), "Retrying the same uncertain object must issue no request");
            Entry actual = repository.findById(entry.getId()).orElseThrow();
            assertEquals(mode, actual.title, "The server may have committed even when acknowledgement was unusable");
            assertEquals(1L, actual.getVersion());
        }
    }

    @Test
    void uncertainGeneratedInsertCannotBeBlindlyRepeated() throws Exception {
        Entry entry = entry();
        responseMode.set("invalid");
        assertThrows(SQLException.class, () -> repository.insert(entry));
        assertNull(entry.getId());
        assertNull(entry.getVersion());
        assertThrows(IllegalArgumentException.class, () -> repository.insert(entry));
        assertEquals(1, statements.size());
        assertEquals("initial", repository.findById(1L).orElseThrow().title);
    }

    @Test
    void partialWebApiResultsCannotBecomeUpdatableEntities() throws Exception {
        Entry entry = repository.insert(entry());
        responseMode.set("missingColumn");
        SQLException failure = assertThrows(SQLException.class, () -> repository.findById(entry.getId()));
        assertTrue(failure.getMessage().contains("missing column"));
        assertEquals("body", repository.findById(entry.getId()).orElseThrow().body);
    }

    private Entry entry() {
        Entry entry = new Entry();
        entry.title = "initial";
        entry.body = "body";
        return entry;
    }

    @Entity(idColumn = "entryId", generatedId = true)
    static class Entry extends BaseEntity<Long> {
        private String title;
        private String body;
        private Date visitedAt;
    }
}
