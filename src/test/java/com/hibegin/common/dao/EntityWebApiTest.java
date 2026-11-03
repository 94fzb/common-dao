package com.hibegin.common.dao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hibegin.common.dao.entity.BaseEntity;
import com.hibegin.common.dao.entity.Column;
import com.hibegin.common.dao.entity.TemporalStorage;
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
import java.sql.Timestamp;
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
                if (mode.equals("missingKey")) { result.put("results", List.of(Map.of())); }
                if (mode.equals("multipleKeys")) { result.put("results", List.of(Map.of("entryId", "1"), Map.of("entryId", "2"))); }
                if (mode.equals("missingResults")) { result.remove("results"); }
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
                    + " createdAt DATETIME, updatedAt DATETIME, version INTEGER NOT NULL, title TEXT, body TEXT, visitedAt INTEGER)");
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
        assertEquals(new Timestamp(1234), entry.getCreatedAt());
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

    @Test
    void dateColumnsUseDateTimeTextAndPreservePrecisionThroughTheWorker() throws Exception {
        try (java.sql.Statement statement = sqlite.createStatement()) {
            statement.execute("CREATE TABLE calendar_entry (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " createdAt DATETIME, updatedAt DATETIME, version INTEGER NOT NULL, calendarDay DATE, publishedAt DATETIME)");
            statement.execute("INSERT INTO calendar_entry(id,version,publishedAt) VALUES"
                    + " (1,7,'2026-11-03 11:22:33.123456789 +0800')");
        }
        EntityRepository<CalendarEntry, Long> dates = new EntityRepository<>(CalendarEntry.class, source,
                Clock.fixed(Instant.parse("2026-11-03T03:04:05.678Z"), ZoneOffset.UTC));
        CalendarEntry legacy = dates.findById(1L).orElseThrow();
        assertEquals(Instant.parse("2026-11-03T03:22:33.123456789Z"), legacy.publishedAt.toInstant());
        assertNull(legacy.getCreatedAt());
        CalendarEntry entry = new CalendarEntry();
        entry.calendarDay = java.sql.Date.valueOf("2024-02-29");
        entry.publishedAt = Timestamp.valueOf("2026-11-03 11:22:33.123456789");
        statements.clear();
        dates.insert(entry);
        assertEquals(1, statements.size());
        CalendarEntry loaded = dates.findById(entry.getId()).orElseThrow();
        assertEquals(entry.getCreatedAt(), loaded.getCreatedAt());
        assertEquals(entry.calendarDay, loaded.calendarDay);
        assertEquals(entry.publishedAt, loaded.publishedAt);
        Object legacyValue = source.getQueryRunner().query("SELECT publishedAt FROM calendar_entry WHERE id=?",
                new org.apache.commons.dbutils.handlers.ScalarHandler<>(), entry.getId());
        assertEquals(entry.publishedAt.getTime(), ResultValueConvertUtils.parseDate(legacyValue));
        assertEquals(1, dates.findMatching(Map.of("publishedAt", entry.publishedAt, "calendarDay", entry.calendarDay), 10).size());
        try (java.sql.Statement statement = sqlite.createStatement();
             ResultSet row = statement.executeQuery("SELECT typeof(createdAt),strftime('%Y',calendarDay),publishedAt"
                     + " FROM calendar_entry WHERE id=" + entry.getId())) {
            assertTrue(row.next());
            assertEquals("text", row.getString(1));
            assertEquals("2024", row.getString(2));
            assertEquals("2026-11-03 11:22:33.123456789", row.getString(3));
        }
        loaded.publishedAt.setNanos(987654321);
        statements.clear();
        assertTrue(dates.update(loaded));
        assertEquals(1, statements.size());
        assertEquals(987654321, dates.findById(entry.getId()).orElseThrow().publishedAt.getNanos());
        statements.clear();
        assertFalse(dates.update(loaded));
        assertTrue(statements.isEmpty());
    }

    @Test
    void explicitlyMappedIntegerMetadataKeepsItsExactHistoricalValue() throws Exception {
        long large = 9007199254740993L;
        try (java.sql.Statement statement = sqlite.createStatement()) {
            statement.execute("CREATE TABLE legacy_entry (id TEXT PRIMARY KEY, created_at INTEGER,"
                    + " updated_at INTEGER, version INTEGER NOT NULL, title TEXT)");
            statement.execute("INSERT INTO legacy_entry VALUES ('legacy'," + large + ",NULL,42,'before')");
        }
        EntityRepository<LegacyEntry, String> legacy = new EntityRepository<>(LegacyEntry.class, source,
                Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC));
        LegacyEntry entry = legacy.findById("legacy").orElseThrow();
        assertEquals(large, entry.getCreatedAt().getTime());
        assertEquals(1, legacy.findMatching(Map.of("createdAt", entry.getCreatedAt()), 1).size());
        entry.title = "after";
        legacy.update(entry);
        LegacyEntry actual = legacy.findById("legacy").orElseThrow();
        assertEquals(large, actual.getCreatedAt().getTime());
        assertEquals(1234, actual.getUpdatedAt().getTime());
        assertEquals(43L, actual.getVersion());
        LegacyEntry fresh = new LegacyEntry();
        fresh.setId("new");
        legacy.insert(fresh);
        assertEquals(1234, legacy.findById("new").orElseThrow().getCreatedAt().getTime());
    }

    @Test
    void conditionalWritesUseOneWebApiRequestAndPreserveFieldsAndVersion() throws Exception {
        Entry denied = entry();
        assertFalse(repository.insertWhen(denied, "?=?", 1, 2));
        assertNull(denied.getId());
        assertNull(denied.getVersion());
        assertEquals(1, statements.size());
        statements.clear();
        assertTrue(repository.insertWhen(denied, "?=?", 1, 1));
        assertEquals(1, statements.size());
        Entry patch = new Entry();
        statements.clear();
        assertEquals(0, repository.updateWhere(patch, List.of("body"), "entryId=? AND title=?", denied.getId(), "stale"));
        assertEquals(1, repository.updateWhere(patch, List.of("body"), "entryId=? AND title=?", denied.getId(), "initial"));
        assertEquals(2, statements.size());
        Entry actual = repository.findById(denied.getId()).orElseThrow();
        assertNull(actual.body);
        assertEquals("initial", actual.title);
        assertEquals(1L, actual.getVersion());
        statements.clear();
        assertEquals(0, repository.deleteWhere("entryId=? AND version=?", denied.getId(), 0));
        assertEquals(1, repository.deleteWhere("entryId=? AND version=?", denied.getId(), 1));
        assertEquals(2, statements.size());
    }

    @Test
    void conditionalWritesFailClosedOnUncertainReceipts() throws Exception {
        for (String mode : List.of("missingKey", "multipleKeys", "missingResults", "invalid")) {
            Entry fresh = entry();
            responseMode.set(mode);
            statements.clear();
            assertThrows(SQLException.class, () -> repository.insertWhen(fresh, "?=?", 1, 1));
            assertNull(fresh.getId());
            assertNull(fresh.getVersion());
            assertThrows(IllegalArgumentException.class, () -> repository.insertWhen(fresh, "?=?", 1, 1));
            assertEquals(1, statements.size());
        }
        Entry stored = repository.insert(entry());
        for (String mode : List.of("missingCount", "fractionalCount", "invalid")) {
            Entry patch = new Entry();
            patch.title = mode;
            responseMode.set(mode);
            statements.clear();
            assertThrows(SQLException.class, () -> repository.updateWhere(patch, List.of("title"), "entryId=?", stored.getId()));
            assertThrows(IllegalArgumentException.class, () -> repository.updateWhere(patch, List.of("title"), "entryId=?", stored.getId()));
            assertEquals(1, statements.size());
            assertEquals(mode, repository.findById(stored.getId()).orElseThrow().title);
        }
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
        @Column(temporalStorage = TemporalStorage.EPOCH_MILLIS)
        private Date visitedAt;
    }

    @Entity(generatedId = true)
    static class CalendarEntry extends BaseEntity<Long> {
        private java.sql.Date calendarDay;
        private Timestamp publishedAt;
    }

    @Entity(createdAtColumn = "created_at", updatedAtColumn = "updated_at",
            createdAtStorage = TemporalStorage.EPOCH_MILLIS, updatedAtStorage = TemporalStorage.EPOCH_MILLIS)
    static class LegacyEntry extends BaseEntity<String> {
        private String title;
    }
}
