package com.hibegin.common.dao;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
