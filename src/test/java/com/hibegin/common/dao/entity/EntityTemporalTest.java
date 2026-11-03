package com.hibegin.common.dao.entity;

import com.hibegin.common.dao.InMemoryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityTemporalTest {
    @TempDir Path directory;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T03:04:05.678Z"), ZoneOffset.UTC);

    private InMemoryDatabase open(boolean sqlite) {
        Properties properties = InMemoryDatabase.h2Properties(UUID.randomUUID().toString());
        if (sqlite) {
            properties.setProperty("driverClass", "org.sqlite.JDBC");
            // Default SQLite JDBC normally binds Timestamp as an integer. Entity must use date/time text.
            properties.setProperty("jdbcUrl", "jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + ".db"));
        }
        return InMemoryDatabase.open(properties, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sqlDateAndDateTimeRetainTheirDatabaseMeaningAndMutablePrecision(boolean sqlite) throws Exception {
        try (InMemoryDatabase db = open(sqlite)) {
            db.execute("CREATE TABLE event (id varchar(20) primary key, createdAt datetime(9), updatedAt datetime(9),"
                    + " version bigint not null, calendarDay date, startsAt datetime(9), visitedAt datetime(3))");
            db.execute("INSERT INTO event(id,version,calendarDay,startsAt,visitedAt) VALUES"
                    + " ('legacy',42,'2020-02-29','2026-11-03 11:22:33.123456789','2026-11-03 11:22:33.123')");
            EntityRepository<Event, String> repository = new EntityRepository<>(Event.class, db.dataSource(), CLOCK);
            Event event = repository.findById("legacy").orElseThrow();
            assertEquals(java.sql.Date.valueOf("2020-02-29"), event.calendarDay);
            assertEquals(Timestamp.valueOf("2026-11-03 11:22:33.123456789"), event.startsAt);
            assertEquals(Timestamp.valueOf("2026-11-03 11:22:33.123").getTime(), event.visitedAt.getTime());
            assertNull(event.getCreatedAt());
            assertFalse(repository.update(event));
            assertEquals(1, repository.findMatching(Map.of("startsAt", event.startsAt, "calendarDay", event.calendarDay), 5).size());

            event.startsAt.setNanos(987654321);
            event.visitedAt.setTime(event.visitedAt.getTime() + 1);
            event.calendarDay.setTime(java.sql.Date.valueOf("2024-02-29").getTime());
            assertTrue(repository.update(event));
            Event reloaded = repository.findById("legacy").orElseThrow();
            assertEquals(987654321, reloaded.startsAt.getNanos());
            assertEquals(event.visitedAt, reloaded.visitedAt);
            assertEquals(java.sql.Date.valueOf("2024-02-29"), reloaded.calendarDay);
            assertEquals(43L, reloaded.getVersion());
            assertNull(reloaded.getCreatedAt());
            assertEquals(Timestamp.from(CLOCK.instant()), reloaded.getUpdatedAt());
            assertEquals("2024", db.scalar(sqlite ? "SELECT strftime('%Y', calendarDay) FROM event"
                    : "SELECT CAST(YEAR(calendarDay) AS varchar) FROM event"));
            if (sqlite) {
                assertEquals("text", db.scalar("SELECT typeof(updatedAt) FROM event"));
                assertEquals("2026-11-03 11:22:33.987654321", db.scalar("SELECT startsAt FROM event"));
            } else {
                assertInstanceOf(Timestamp.class, db.scalar("SELECT updatedAt FROM event"));
                assertInstanceOf(java.sql.Date.class, db.scalar("SELECT calendarDay FROM event"));
            }

            reloaded.getUpdatedAt().setTime(0);
            assertEquals(Timestamp.from(CLOCK.instant()), reloaded.getUpdatedAt(), "Metadata getters return copies");
            assertFalse(repository.update(reloaded));
            reloaded.startsAt = null;
            reloaded.calendarDay = null;
            assertTrue(repository.update(reloaded));
            Event cleared = repository.findById("legacy").orElseThrow();
            assertNull(cleared.startsAt);
            assertNull(cleared.calendarDay);

            Event inserted = new Event();
            inserted.setId("new");
            inserted.calendarDay = java.sql.Date.valueOf("2026-11-03");
            inserted.startsAt = Timestamp.valueOf("2026-11-03 12:13:14.123456789");
            repository.insert(inserted);
            Event actual = repository.findById("new").orElseThrow();
            assertEquals(inserted.startsAt, actual.startsAt);
            assertEquals(Timestamp.from(CLOCK.instant()), actual.getCreatedAt());
            assertEquals(1, repository.findMatching(Map.of("createdAt", actual.getCreatedAt()), 5).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitIntegerColumnsCoexistWithNativeDateTimes(boolean sqlite) throws Exception {
        try (InMemoryDatabase db = open(sqlite)) {
            db.execute("CREATE TABLE token (id varchar(20) primary key, created_at bigint, updated_at datetime(3),"
                    + " version bigint not null, expiresAt bigint, note varchar(20))");
            EntityRepository<Token, String> repository = new EntityRepository<>(Token.class, db.dataSource(), CLOCK);
            Token token = new Token();
            token.setId("token");
            token.expiresAt = new Timestamp(9007199254740993L);
            repository.insert(token);
            Token loaded = repository.findById("token").orElseThrow();
            assertEquals(CLOCK.millis(), ((Number) db.scalar("SELECT created_at FROM token")).longValue());
            assertEquals(Timestamp.from(CLOCK.instant()), loaded.getCreatedAt());
            assertEquals(token.expiresAt, loaded.expiresAt);
            assertEquals(1, repository.findMatching(Map.of("expiresAt", token.expiresAt), 1).size());
            assertFalse(repository.update(loaded));
            loaded.note = "changed";
            assertTrue(repository.update(loaded));
            assertEquals(1L, loaded.getVersion());
            assertEquals(Timestamp.from(CLOCK.instant()), repository.findById("token").orElseThrow().getUpdatedAt());
            loaded.expiresAt.setNanos(1);
            assertThrows(IllegalArgumentException.class, () -> repository.update(loaded), "No silent precision loss");
        }
    }

    @Test
    void legacyOffsetTextPreservesItsInstantAndNativeNumbersRequireAnExplicitMapping() throws Exception {
        try (InMemoryDatabase db = open(true)) {
            db.execute("CREATE TABLE event (id text primary key, createdAt datetime, updatedAt datetime,"
                    + " version integer not null, calendarDay date, startsAt datetime, visitedAt datetime)");
            db.execute("INSERT INTO event(id,version,startsAt,visitedAt) VALUES ('offset',0,"
                    + " '2026-11-03 11:22:33.123456789 +0800','2026-11-03T03:22:33.123Z')");
            EntityRepository<Event, String> repository = new EntityRepository<>(Event.class, db.dataSource(), CLOCK);
            Event event = repository.findById("offset").orElseThrow();
            assertEquals(Instant.parse("2026-11-03T03:22:33.123456789Z"), event.startsAt.toInstant());
            assertEquals(Instant.parse("2026-11-03T03:22:33.123Z"), event.visitedAt.toInstant());
            db.execute("UPDATE event SET startsAt=12345 WHERE id='offset'");
            assertThrows(SQLException.class, () -> repository.findById("offset"));
            db.execute("UPDATE event SET startsAt='2026-02-30 12:00:00' WHERE id='offset'");
            assertThrows(SQLException.class, () -> repository.findById("offset"));
        }
    }

    @Test
    void driverJavaTimeValuesAndInvalidMappingsAreHandledExplicitly() throws Exception {
        assertEquals(Timestamp.valueOf("2026-11-03 11:22:33.123456789"),
                EntityMapping.convert(LocalDateTime.parse("2026-11-03T11:22:33.123456789"), Timestamp.class));
        assertEquals(java.sql.Date.valueOf("2024-02-29"), EntityMapping.convert(LocalDate.of(2024, 2, 29), java.sql.Date.class));
        assertThrows(IllegalArgumentException.class, () -> EntityMapping.convert(123L, Timestamp.class));
        assertThrows(RuntimeException.class, () -> EntityMapping.convert("2026-02-30", java.sql.Date.class));
        assertThrows(RuntimeException.class, () -> EntityMapping.convert("2026-11-03 11:22:33", java.sql.Date.class));
        assertThrows(IllegalArgumentException.class, () -> EntityMapping.of(InvalidStorage.class));
    }

    @Entity
    static class Event extends BaseEntity<String> {
        java.sql.Date calendarDay;
        Timestamp startsAt;
        Date visitedAt;
    }

    @Entity(createdAtColumn = "created_at", updatedAtColumn = "updated_at", createdAtStorage = TemporalStorage.EPOCH_MILLIS)
    static class Token extends BaseEntity<String> {
        @Column(temporalStorage = TemporalStorage.EPOCH_MILLIS) Timestamp expiresAt;
        String note;
    }

    @Entity
    static class InvalidStorage extends BaseEntity<String> {
        @Column(temporalStorage = TemporalStorage.EPOCH_MILLIS) java.sql.Date calendarDay;
    }
}
