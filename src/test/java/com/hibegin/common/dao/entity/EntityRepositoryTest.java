package com.hibegin.common.dao.entity;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.hibegin.common.dao.CustomQueryRunner;
import com.hibegin.common.dao.DataSourceWrapper;
import com.hibegin.common.dao.InMemoryDatabase;
import org.apache.commons.dbutils.ResultSetHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityRepositoryTest {
    private InMemoryDatabase database;
    private DataSourceWrapper source;
    private CountingRunner runner;
    private EntityRepository<Note, String> repository;
    private static final Clock INSERT_TIME = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    private static final Clock UPDATE_TIME = Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC);

    @BeforeEach
    void open() throws Exception {
        database = InMemoryDatabase.open(InMemoryDatabase.h2Properties(UUID.randomUUID().toString()), false);
        database.execute("CREATE TABLE note (id varchar(100) primary key, createdAt bigint, updatedAt bigint,"
                + " version bigint not null, title varchar(100), body varchar(500), ownerId int,"
                + " visitedAt bigint, payload varbinary(100), enabled boolean, amount decimal(30,4))");
        runner = new CountingRunner(database.dataSource());
        source = (DataSourceWrapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSourceWrapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getQueryRunner")) { return runner; }
                    try { return method.invoke(database.dataSource(), args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        repository = new EntityRepository<>(Note.class, source, INSERT_TIME);
    }

    @AfterEach
    void close() { database.close(); }

    @Test
    void insertAndUpdatePersistNullsAndManageTimesWithoutExtraQueries() throws Exception {
        Note note = note("one");
        note.body = "will be cleared";
        note.amount = new BigDecimal("1234567890123456.1234");
        repository.insert(note);
        assertEquals(0L, note.getVersion());
        assertEquals(1000L, note.getCreatedAt());
        assertEquals(1000L, note.getUpdatedAt());
        assertEquals(1, runner.statements.size());

        Note loaded = repository.findById("one").orElseThrow();
        assertEquals(note.amount, loaded.amount);
        loaded.body = null;
        loaded.title = "changed";
        runner.statements.clear();
        assertTrue(new EntityRepository<>(Note.class, source, UPDATE_TIME).update(loaded));
        assertEquals(1, runner.statements.size());
        assertEquals(1000L, loaded.getCreatedAt());
        assertEquals(2000L, loaded.getUpdatedAt());
        assertEquals(1L, loaded.getVersion());
        Map<String, Object> persisted = database.queryOne("SELECT * FROM note WHERE id=?", "one");
        assertNull(persisted.get("body"));
        assertEquals("changed", persisted.get("title"));
        assertEquals(1L, ((Number) persisted.get("version")).longValue());

        runner.statements.clear();
        assertFalse(repository.update(loaded));
        assertTrue(runner.statements.isEmpty(), "No-op must not read or write the database");
        assertEquals(2000L, loaded.getUpdatedAt());
        assertEquals(1L, loaded.getVersion());
    }

    @Test
    void staleUpdateAndDeleteCannotOverwriteAnotherWriter() throws Exception {
        repository.insert(note("one"));
        Note winner = repository.findById("one").orElseThrow();
        Note stale = repository.findById("one").orElseThrow();
        Note staleDelete = repository.findById("one").orElseThrow();
        winner.title = "winner";
        repository.update(winner);
        stale.title = "loser";
        assertThrows(OptimisticLockException.class, () -> repository.update(stale));
        assertEquals(0L, stale.getVersion());
        assertEquals(1000L, stale.getUpdatedAt());
        assertThrows(IllegalStateException.class, () -> repository.update(stale));
        assertThrows(OptimisticLockException.class, () -> repository.delete(staleDelete));
        assertEquals("winner", database.scalar("SELECT title FROM note WHERE id='one'"));
        repository.delete(winner);
        assertTrue(repository.findById("one").isEmpty());
        assertThrows(IllegalStateException.class, () -> repository.delete(winner));
    }

    @Test
    void onlyDirtyFieldsAreWrittenAndHistoricalUnknownTimesStayNull() throws Exception {
        database.execute("INSERT INTO note(id,version,title,body) VALUES ('old',42,'old title','old body')");
        Note note = repository.findById("old").orElseThrow();
        assertNull(note.getCreatedAt());
        assertNull(note.getUpdatedAt());
        // An unmigrated path can still change a different column. Do not write its old value back.
        database.execute("UPDATE note SET body='external body' WHERE id='old'");
        note.title = "new title";
        runner.statements.clear();
        repository.update(note);
        assertEquals(1, runner.statements.size());
        assertFalse(runner.statements.get(0).contains("`body`="));
        assertEquals("external body", database.scalar("SELECT body FROM note WHERE id='old'"));
        assertNull(database.scalar("SELECT createdAt FROM note WHERE id='old'"));
        assertEquals(43L, note.getVersion());
        assertEquals(1000L, note.getUpdatedAt());
    }

    @Test
    void inPlaceMutableChangesAreComparedAgainstIndependentSnapshots() throws Exception {
        Note note = note("mutable");
        note.visitedAt = new Date(123);
        note.payload = new byte[]{1, 2};
        repository.insert(note);
        note.visitedAt.setTime(456);
        note.payload[1] = 9;
        assertTrue(repository.update(note));
        Note loaded = repository.findById(note.getId()).orElseThrow();
        assertEquals(456, loaded.visitedAt.getTime());
        assertArrayEquals(new byte[]{1, 9}, loaded.payload);
        assertFalse(repository.update(loaded));
        loaded.payload[0] = 8;
        assertTrue(repository.update(loaded));
        assertArrayEquals(new byte[]{8, 9}, repository.findById(note.getId()).orElseThrow().payload);
    }

    @Test
    void rejectsDetachedPartialAndCrossSourceObjectsBeforeIssuingSql() throws Exception {
        Note stored = repository.insert(note("one"));
        Note partial = note("one");
        runner.statements.clear();
        assertThrows(IllegalStateException.class, () -> repository.update(partial));
        String json = new Gson().toJson(stored);
        assertFalse(json.contains("persistenceState"));
        assertFalse(json.contains("source"));
        Note detached = new Gson().fromJson(json, Note.class);
        assertThrows(IllegalStateException.class, () -> repository.update(detached));
        stored.setId("someone-else");
        assertThrows(IllegalStateException.class, () -> repository.update(stored));
        assertTrue(runner.statements.isEmpty());
        stored.setId("one");
        try (InMemoryDatabase other = InMemoryDatabase.open(InMemoryDatabase.h2Properties(UUID.randomUUID().toString()), false)) {
            assertThrows(IllegalStateException.class, () -> new EntityRepository<>(Note.class, other.dataSource()).update(stored));
        }
        assertEquals("initial", database.scalar("SELECT title FROM note WHERE id='one'"));
    }

    @Test
    void explicitOwnerConditionsAreCheckedAtomicallyWithTheVersion() throws Exception {
        Note note = note("one");
        note.ownerId = 7;
        repository.insert(note);
        note.title = "not authorized";
        runner.statements.clear();
        assertThrows(OptimisticLockException.class, () -> repository.update(note, Map.of("ownerId", 8)));
        assertEquals(1, runner.statements.size());
        assertEquals("initial", database.scalar("SELECT title FROM note WHERE id='one'"));
        Note reloaded = repository.findById("one").orElseThrow();
        reloaded.title = "authorized";
        repository.update(reloaded, Map.of("ownerId", 7));
        assertEquals("authorized", database.scalar("SELECT title FROM note WHERE id='one'"));
        assertThrows(OptimisticLockException.class, () -> repository.delete(reloaded, Map.of("ownerId", 8)));
        assertTrue(repository.findById("one").isPresent());
    }

    @Test
    void generatedLegacyPrimaryKeyIsReturnedByTheInsertAndGenericInheritanceWorks() throws Exception {
        database.execute("CREATE TABLE log (logId int auto_increment primary key, createdAt bigint, updatedAt bigint,"
                + " version bigint not null, title varchar(100), plain_content varchar(100))");
        EntityRepository<Log, Integer> logs = new EntityRepository<>(Log.class, source, INSERT_TIME);
        Log log = new Log();
        log.title = "generated";
        log.plainContent = "mapped name";
        runner.statements.clear();
        logs.insert(log);
        assertEquals(1, log.getId());
        assertEquals(1, runner.statements.size(), "Generated key retrieval must not issue a separate SELECT");
        assertEquals("mapped name", database.scalar("SELECT plain_content FROM log WHERE logId=?", log.getId()));
        Log loaded = logs.findById(log.getId()).orElseThrow();
        loaded.title = "after";
        logs.update(loaded);
        assertEquals("after", database.scalar("SELECT title FROM log WHERE logId=?", log.getId()));
        assertEquals(1L, loaded.getVersion());
    }

    @Test
    void failedInsertAndUpdateDoNotAdvanceMetadataOrPermitBlindRetry() throws Exception {
        Note existing = repository.insert(note("one"));
        Note duplicate = note("one");
        assertThrows(SQLException.class, () -> repository.insert(duplicate));
        assertNull(duplicate.getVersion());
        assertNull(duplicate.getCreatedAt());
        assertThrows(IllegalArgumentException.class, () -> repository.insert(duplicate));
        existing.title = "x".repeat(101);
        assertThrows(SQLException.class, () -> repository.update(existing));
        assertEquals(0L, existing.getVersion());
        assertEquals("initial", database.scalar("SELECT title FROM note WHERE id='one'"));
        existing.title = "short";
        assertThrows(IllegalStateException.class, () -> repository.update(existing));
    }

    @Test
    void listReadsAreBoundedAndNullConditionsAreNotLost() throws Exception {
        repository.insert(note("a"));
        Note owned = note("b");
        owned.ownerId = 7;
        repository.insert(owned);
        assertEquals("a", repository.findMatching(Collections.singletonMap("ownerId", null), 1).get(0).getId());
        assertEquals("b", repository.findMatching(Map.of("ownerId", 7), 10).get(0).getId());
        assertEquals(1, repository.findMatching(Collections.emptyMap(), 1).size());
        assertThrows(IllegalArgumentException.class, () -> repository.findMatching(Collections.emptyMap(), 0));
        assertThrows(IllegalArgumentException.class, () -> repository.findMatching(Map.of("missing", 1), 10));
    }

    @Test
    void badMappingsAndMissingSchemaFailWithoutAttemptingDdl() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new EntityRepository<>(Duplicate.class, source));
        assertThrows(IllegalArgumentException.class, () -> new EntityRepository<>(InvalidTable.class, source));
        assertThrows(IllegalArgumentException.class, () -> new EntityRepository<>(Structured.class, source));
        EntityRepository<Missing, Long> missing = new EntityRepository<>(Missing.class, source);
        runner.statements.clear();
        assertThrows(SQLException.class, () -> missing.findById(1L));
        assertEquals(1, runner.statements.size());
        assertTrue(runner.statements.get(0).startsWith("SELECT"));
        database.execute("CREATE TABLE missing (id bigint primary key, createdAt bigint, updatedAt bigint)");
        assertThrows(SQLException.class, () -> missing.findById(1L));
        database.execute("ALTER TABLE missing ADD version bigint");
        database.execute("INSERT INTO missing(id) VALUES (1)");
        assertThrows(SQLException.class, () -> missing.findById(1L), "NULL versions need an explicit migration");
    }

    @Test
    void versionOverflowDoesNotWriteOrWrap() throws Exception {
        database.execute("INSERT INTO note(id,version,title) VALUES ('max',?,'before')", Long.MAX_VALUE);
        Note max = repository.findById("max").orElseThrow();
        max.title = "after";
        runner.statements.clear();
        assertThrows(ArithmeticException.class, () -> repository.update(max));
        assertTrue(runner.statements.isEmpty());
        assertEquals("before", database.scalar("SELECT title FROM note WHERE id='max'"));
        assertEquals(Long.MAX_VALUE, max.getVersion());
    }

    @Test
    void nativeConfigurationIncludesPrivateAndInheritedFieldsButNotTracking() {
        String config = EntityReflection.configuration(Log.class, Note.class);
        assertEquals(4, JsonParser.parseString(config).getAsJsonArray().size());
        assertTrue(config.contains(BaseEntity.class.getName()));
        assertTrue(config.contains("plainContent"));
        assertTrue(config.contains("<init>") || config.contains("\\u003cinit\\u003e"));
        assertFalse(config.contains("persistenceState"));
        assertFalse(config.contains("uiOnly"));
    }

    private Note note(String id) {
        Note note = new Note();
        note.setId(id);
        note.title = "initial";
        return note;
    }

    @Entity
    static class Note extends BaseEntity<String> {
        private String title;
        private String body;
        private Integer ownerId;
        private Date visitedAt;
        private byte[] payload;
        private Boolean enabled;
        private BigDecimal amount;
        private transient String uiOnly;
    }

    abstract static class Content<ID> extends BaseEntity<ID> { String title; }

    @Entity(idColumn = "logId", generatedId = true)
    static class Log extends Content<Integer> { @Column("plain_content") String plainContent; }

    @Entity
    static class Duplicate extends BaseEntity<String> { @Column("ID") String duplicate; }
    @Entity(table = "note;drop")
    static class InvalidTable extends BaseEntity<String> { }
    @Entity
    static class Structured extends BaseEntity<String> { Map<String, String> data; }
    @Entity
    static class Missing extends BaseEntity<Long> { }

    private static class CountingRunner extends CustomQueryRunner {
        final List<String> statements = new ArrayList<>();
        CountingRunner(DataSourceWrapper source) { super(source, false); }

        @Override
        public int update(Connection connection, String sql, Object... params) throws SQLException {
            statements.add(sql);
            return super.update(connection, sql, params);
        }

        @Override
        public <T> T query(Connection connection, String sql, ResultSetHandler<T> handler, Object... params) throws SQLException {
            statements.add(sql);
            return super.query(connection, sql, handler, params);
        }

        @Override
        public <T> T insert(Connection connection, String sql, ResultSetHandler<T> handler, Object... params) throws SQLException {
            statements.add(sql);
            return super.insert(connection, sql, handler, params);
        }
    }
}
