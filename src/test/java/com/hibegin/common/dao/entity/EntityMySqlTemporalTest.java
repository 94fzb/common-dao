package com.hibegin.common.dao.entity;

import com.hibegin.common.dao.InMemoryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Uses only the disposable MySQL service explicitly provided by CI. */
@EnabledIfEnvironmentVariable(named = "COMMON_DAO_MYSQL_TEST_URL", matches = ".+")
class EntityMySqlTemporalTest {
    @Test
    void nativeDateDatetimeAndTimestampColumnsRoundTripWithoutChangingIntegerColumns() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("driverClass", "com.mysql.cj.jdbc.Driver");
        properties.setProperty("jdbcUrl", System.getenv("COMMON_DAO_MYSQL_TEST_URL"));
        properties.setProperty("user", System.getenv("COMMON_DAO_MYSQL_TEST_USER"));
        properties.setProperty("password", System.getenv("COMMON_DAO_MYSQL_TEST_PASSWORD"));
        try (InMemoryDatabase db = InMemoryDatabase.open(properties, false)) {
            db.execute("CREATE TABLE event_mysql (id bigint auto_increment primary key, createdAt datetime(3),"
                    + " updatedAt datetime(3), version bigint not null, calendarDay date, startsAt datetime(6),"
                    + " sqlTimestamp timestamp(6) NULL, expiresAt bigint)");
            try {
                Clock clock = Clock.fixed(Instant.parse("2026-11-03T03:04:05.678Z"), ZoneOffset.UTC);
                EntityRepository<Event, Long> repository = new EntityRepository<>(Event.class, db.dataSource(), clock);
                Event event = new Event();
                event.calendarDay = java.sql.Date.valueOf("2024-02-29");
                event.startsAt = Timestamp.valueOf("2026-11-03 11:22:33.123456");
                event.sqlTimestamp = Timestamp.from(Instant.parse("2026-11-03T03:22:33.123456Z"));
                event.expiresAt = new Timestamp(9007199254740993L);
                repository.insert(event);
                Event loaded = repository.findById(event.getId()).orElseThrow();
                assertEquals(event.calendarDay, loaded.calendarDay);
                assertEquals(event.startsAt, loaded.startsAt);
                assertEquals(event.sqlTimestamp, loaded.sqlTimestamp);
                assertEquals(event.expiresAt, loaded.expiresAt);
                assertEquals(Timestamp.from(clock.instant()), loaded.getCreatedAt());
                assertEquals(1, repository.findMatching(Map.of("startsAt", event.startsAt, "calendarDay", event.calendarDay), 1).size());
                assertEquals("2026-11-03 11:22:33.123456", db.scalar("SELECT CAST(startsAt AS CHAR) FROM event_mysql"));
                assertEquals("2024-02-29", db.scalar("SELECT CAST(calendarDay AS CHAR) FROM event_mysql"));
                assertEquals(9007199254740993L, ((Number) db.scalar("SELECT expiresAt FROM event_mysql")).longValue());
                assertFalse(repository.update(loaded));
                loaded.startsAt.setNanos(987654000);
                loaded.calendarDay = null;
                assertTrue(repository.update(loaded));
                Event actual = repository.findById(event.getId()).orElseThrow();
                assertEquals(Timestamp.valueOf("2026-11-03 11:22:33.987654"), actual.startsAt);
                assertNull(actual.calendarDay);
                assertEquals(1L, actual.getVersion());
            } finally {
                db.execute("DROP TABLE event_mysql");
            }
        }
    }

    @Test
    void conditionalWritesRetainGeneratedKeysPermissionsMetadataAndCallerRollback() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("driverClass", "com.mysql.cj.jdbc.Driver");
        properties.setProperty("jdbcUrl", System.getenv("COMMON_DAO_MYSQL_TEST_URL"));
        properties.setProperty("user", System.getenv("COMMON_DAO_MYSQL_TEST_USER"));
        properties.setProperty("password", System.getenv("COMMON_DAO_MYSQL_TEST_PASSWORD"));
        try (InMemoryDatabase db = InMemoryDatabase.open(properties, false)) {
            db.execute("CREATE TABLE conditional_mysql (siteId bigint auto_increment primary key,"
                    + " createdAt datetime(3), updatedAt datetime(3), version bigint not null,"
                    + " name varchar(100) unique, value text, remark varchar(100))");
            try {
                EntityRepository<Setting, Long> repository = new EntityRepository<>(Setting.class, db.dataSource());
                Setting row = new Setting();
                row.name = "task"; row.value = "payload"; row.remark = "revision-1";
                assertFalse(repository.insertWhen(row, "?=?", 1, 2));
                assertNull(row.getId());
                assertTrue(repository.insertWhen(row, "NOT EXISTS (SELECT 1 FROM conditional_mysql WHERE name=?)", row.name));
                assertNotNull(row.getId());
                Setting duplicate = new Setting(); duplicate.name = row.name;
                assertFalse(repository.insertWhen(duplicate, "NOT EXISTS (SELECT 1 FROM conditional_mysql WHERE name=?)", row.name));
                assertNull(duplicate.getId());
                Setting patch = new Setting(); patch.remark = "revision-2";
                assertEquals(0, repository.updateWhere(patch, java.util.List.of("remark"), "name=? AND remark=?", row.name, "stale"));
                assertEquals(1, repository.updateWhere(patch, java.util.List.of("remark"), "name=? AND remark=?"
                        + " AND (SELECT reference_count FROM (SELECT count(*) AS reference_count FROM conditional_mysql WHERE name<>?) AS refs)=0",
                        row.name, row.remark, row.name));
                Setting actual = repository.findById(row.getId()).orElseThrow();
                assertEquals("payload", actual.value);
                assertEquals("revision-2", actual.remark);
                assertEquals(row.getCreatedAt(), actual.getCreatedAt());
                assertEquals(1L, actual.getVersion());
                try (var connection = db.dataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    try (var session = new EntitySession(db.dataSource(), connection)) {
                        EntityRepository<Setting, Long> bound = session.repository(Setting.class);
                        Setting temporary = new Setting(); temporary.name = "temporary";
                        assertTrue(bound.insertWhen(temporary, "EXISTS (SELECT 1 FROM conditional_mysql WHERE name=?)", row.name));
                        assertEquals(1, bound.deleteWhere("name=?", row.name));
                        assertEquals(1, bound.updateWhere(patch, java.util.List.of("remark"), "name=?", temporary.name));
                        connection.rollback();
                    }
                }
                assertEquals(1, repository.select("ORDER BY siteId").size());
                assertEquals(1L, repository.findById(row.getId()).orElseThrow().getVersion());
                assertEquals(1, repository.deleteWhere("name=? AND remark=?", row.name, "revision-2"));
            } finally {
                db.execute("DROP TABLE conditional_mysql");
            }
        }
    }

    @Entity(table = "conditional_mysql", idColumn = "siteId", generatedId = true)
    static class Setting extends BaseEntity<Long> {
        String name;
        String value;
        String remark;
    }

    @Entity(table = "event_mysql", generatedId = true)
    static class Event extends BaseEntity<Long> {
        java.sql.Date calendarDay;
        Timestamp startsAt;
        Timestamp sqlTimestamp;
        @Column(temporalStorage = TemporalStorage.EPOCH_MILLIS) Timestamp expiresAt;
    }
}
