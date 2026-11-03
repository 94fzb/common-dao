package com.hibegin.common.dao.entity;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Date;
import java.util.Locale;

/** Date/time conversion only; SQL values without an offset keep their existing local-time meaning. */
final class TemporalValues {
    private static final DateTimeFormatter SQL_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter LEGACY_OFFSET_TIMESTAMP = new DateTimeFormatterBuilder()
            .append(SQL_TIMESTAMP).appendLiteral(' ').appendOffset("+HHMM", "+0000")
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private TemporalValues() { }

    static boolean supports(Class<?> type) {
        return type == Date.class || type == Timestamp.class || type == java.sql.Date.class;
    }

    static Object convert(Object value, Class<?> type) {
        if (type == java.sql.Date.class) {
            if (value instanceof java.sql.Date) { return java.sql.Date.valueOf(((java.sql.Date) value).toLocalDate()); }
            if (value instanceof LocalDate) { return java.sql.Date.valueOf((LocalDate) value); }
            if (value instanceof String) { return java.sql.Date.valueOf(LocalDate.parse((String) value)); }
            throw new IllegalArgumentException("SQL DATE requires a calendar date, not a timestamp or number");
        }
        Timestamp timestamp = timestamp(value);
        return type == Timestamp.class ? timestamp : new Date(timestamp.getTime());
    }

    private static Timestamp timestamp(Object value) {
        if (value instanceof Timestamp) { return (Timestamp) ((Timestamp) value).clone(); }
        if (value instanceof Date) { return new Timestamp(((Date) value).getTime()); }
        if (value instanceof LocalDateTime) { return Timestamp.valueOf((LocalDateTime) value); }
        if (value instanceof OffsetDateTime) { return Timestamp.from(((OffsetDateTime) value).toInstant()); }
        if (value instanceof String) {
            String text = ((String) value).trim();
            // Legacy WebAPI rows contain a space and numeric offset; preserve their instant.
            try { return Timestamp.from(OffsetDateTime.parse(text, LEGACY_OFFSET_TIMESTAMP).toInstant()); }
            catch (DateTimeParseException ignored) { }
            try { return Timestamp.from(OffsetDateTime.parse(text.replace(' ', 'T')).toInstant()); }
            catch (DateTimeParseException ignored) { }
            return Timestamp.valueOf(LocalDateTime.parse(text.replace('T', ' '), SQL_TIMESTAMP));
        }
        throw new IllegalArgumentException("SQL date/time requires a temporal value; use EPOCH_MILLIS for integer storage");
    }

    static Object jdbcValue(Object value) {
        if (value instanceof Timestamp) { return ((Timestamp) value).clone(); }
        if (value instanceof java.sql.Date) { return java.sql.Date.valueOf(((java.sql.Date) value).toLocalDate()); }
        if (value instanceof Date) { return new Timestamp(((Date) value).getTime()); }
        return value;
    }

    static Object textValue(Object value) {
        if (value instanceof java.sql.Date) { return value.toString(); }
        if (value instanceof Date) { return SQL_TIMESTAMP.format(timestamp(value).toLocalDateTime()); }
        return value;
    }
}
