package com.hibegin.common.dao;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class ResultValueConvertUtils {

    public static String formatDate(Object date, String format) {
        if (Objects.isNull(date)) {
            return null;
        }
        if (date instanceof String) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss" + (((String) date).contains("+") ? " Z" : ""), Locale.ENGLISH);
            SimpleDateFormat realSdf = new SimpleDateFormat(format, Locale.ENGLISH);
            return realSdf.format(new Date(LocalDateTime.from(formatter.parse((String) date)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        } else if (date instanceof LocalDateTime) {
            return ((LocalDateTime) date).format(DateTimeFormatter.ofPattern(format));
        } else if (date instanceof Timestamp) {
            date = new SimpleDateFormat(format).format(new Date(((Timestamp) date).getTime()));
        }
        return date.toString();
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
