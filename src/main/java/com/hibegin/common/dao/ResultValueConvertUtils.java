package com.hibegin.common.dao;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class ResultValueConvertUtils {

    private static final DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendPattern(" XXX")      // +08:00 格式时区
            .optionalEnd()
            .optionalStart()
            .appendPattern(" XX")       // +0800 格式时区
            .optionalEnd()
            .optionalStart()
            .appendPattern(" X")        // Z 或 +08 格式时区
            .optionalEnd()
            .optionalStart()
            .appendLiteral('Z')         // 纯 Z 时区
            .optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)
            .toFormatter(Locale.getDefault());

    private static OffsetDateTime parse(String dateStr) {
        return OffsetDateTime.parse(dateStr, formatter);
    }

    public static Long parseDate(Object date) {
        if (Objects.isNull(date)) {
            return null;
        }
        if (date instanceof String) {
            if (((String) date).trim().isEmpty()) {
                return null;
            }
            String dateStr = (String) date;
            if (dateStr.contains("T")) {
                // ISO 8601 格式：支持 T 分隔的 LocalDateTime
                LocalDateTime dt = LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } else if (dateStr.matches(".*([+-]\\d{2}(:?\\d{2})?|Z)$") && dateStr.contains(":")) {
                // 带时区信息，直接用 OffsetDateTime 或 ZonedDateTime 解析
                OffsetDateTime offsetDateTime = parse((String) date);
                return offsetDateTime.atZoneSameInstant(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } else {
                if (dateStr.contains(":")) {
                    TemporalAccessor parsed = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").parse(dateStr);
                    // 包含时间部分
                    LocalDateTime dt = LocalDateTime.from(parsed);
                    return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                }
                try {
                    Date parse = new SimpleDateFormat("yyyy-MM-dd").parse(dateStr);
                    return parse.getTime();
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            }
        } else if (date instanceof LocalDateTime) {
            return ((LocalDateTime) date).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } else if (date instanceof Date) {
            return ((Date) date).getTime();
        }
        return null;
    }

    public static String formatDate(Object date, String format) {
        Long time = parseDate(date);
        if (Objects.isNull(time)) {
            if (Objects.isNull(date)) {
                return "";
            }
            return date.toString();
        }
        OffsetDateTime offsetDateTime = new Date(time).toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        return offsetDateTime.format(DateTimeFormatter.ofPattern(format));
    }

    public static void main(String[] args) {
        String[] samples = {
                "2024-12-31 16:00:00 +08:00",
                "2024-12-31 16:00:00 +0800",
                "2024-12-31 16:00:00 +08",
                "2024-12-31 16:00:00Z",
                "2024-12-31 16:00:00",
                "2024-12-31",
                "2024-12-31T16:00:00+08:00",
        };
        for (String sample : samples) {
            Long a = parseDate(sample);
            if (a != null) {
                System.out.println("parseDate(" + sample + ") = " + new Date(a));
            } else {
                System.out.println("parseDate(" + sample + ") error");
            }
            System.out.println("formatDate(" + sample + ") = " + formatDate(sample, "yyyy_MM_dd HH:mm:ss"));
        }
    }

    public static boolean toBoolean(Object value) {
        if (Objects.equals(value + "", 1 + "")) {
            return true;
        } else if (Objects.equals(value + "", "1.0")) {
            return true;
        } else if (Objects.equals(value + "", "on")) {
            return true;
        } else if (Objects.equals(value + "", "true")) {
            return true;
        }
        return Objects.equals(value, true);
    }
}
