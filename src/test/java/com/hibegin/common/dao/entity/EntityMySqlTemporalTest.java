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

    @Entity(table = "event_mysql", generatedId = true)
    static class Event extends BaseEntity<Long> {
        java.sql.Date calendarDay;
        Timestamp startsAt;
        Timestamp sqlTimestamp;
        @Column(temporalStorage = TemporalStorage.EPOCH_MILLIS) Timestamp expiresAt;
    }
}
