package com.hibegin.common.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultValueConvertUtilsTest {
    @Test
    void legacyFormattingReadsFractionalEntityValuesWithoutChangingSecondOnlyTimes() {
        for (String fraction : new String[]{"", ".1", ".123", ".123456", ".123456789"}) {
            String local = "2026-11-03 11:22:33" + fraction;
            assertEquals(Timestamp.valueOf(local).getTime(), ResultValueConvertUtils.parseDate(local));
            assertEquals("2026-11-03 11:22:33", ResultValueConvertUtils.formatDate(local, "yyyy-MM-dd HH:mm:ss"));
            long instant = Instant.parse("2026-11-03T03:22:33" + fraction + "Z").toEpochMilli();
            assertEquals(instant, ResultValueConvertUtils.parseDate(local + " +0800"));
            assertEquals(instant, ResultValueConvertUtils.parseDate(local + " +08:00"));
            assertEquals(instant, ResultValueConvertUtils.parseDate("2026-11-03 03:22:33" + fraction + "Z"));
        }
    }


    @Test
    void preservesExistingSeconds() {
        assertEquals("2026-10-30 01:33:47",
                ResultValueConvertUtils.formatDate("2026-10-30 01:33:47", "yyyy-MM-dd HH:mm:ss"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-30 01:33", "2026-10-30T01:33",
            "2026-10-30 01:33:00", "2026-10-30T01:33:00"})
    void parsesLocalTimeWithOptionalSeconds(String value) {
        long expected = LocalDateTime.of(2026, 10, 30, 1, 33)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, ResultValueConvertUtils.parseDate(value));
        assertEquals("2026_10", ResultValueConvertUtils.formatDate(value, "yyyy_MM"));
        assertEquals("2026-10-30 01:33:00",
                ResultValueConvertUtils.formatDate(value, "yyyy-MM-dd HH:mm:ss"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-30 01:33 +08:00", "2026-10-30 01:33 +0800",
            "2026-10-30 01:33 +08", "2026-10-30 01:33:00 +08:00"})
    void preservesExplicitOffsetWithOptionalSeconds(String value) {
        long expected = LocalDateTime.of(2026, 10, 30, 1, 33)
                .toInstant(ZoneOffset.ofHours(8)).toEpochMilli();
        assertEquals(expected, ResultValueConvertUtils.parseDate(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-30 01:33Z", "2026-10-30T01:33Z",
            "2026-10-30 01:33:00Z"})
    void parsesUtcWithOptionalSeconds(String value) {
        long expected = LocalDateTime.of(2026, 10, 30, 1, 33)
                .toInstant(ZoneOffset.UTC).toEpochMilli();
        assertEquals(expected, ResultValueConvertUtils.parseDate(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-30 01:33:", "2026-10-30 01:33junk",
            "2026-10-30 01:60", "2026-10-30 01:33.123"})
    void rejectsMalformedTime(String value) {
        assertThrows(DateTimeParseException.class, () -> ResultValueConvertUtils.parseDate(value));
    }
}
