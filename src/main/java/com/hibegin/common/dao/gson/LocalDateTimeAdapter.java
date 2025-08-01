package com.hibegin.common.dao.gson;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.hibegin.common.dao.ResultBeanUtils;
import com.hibegin.common.dao.ResultValueConvertUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class LocalDateTimeAdapter extends TypeAdapter<LocalDateTime> {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public void write(JsonWriter jsonWriter, LocalDateTime localDateTime) throws IOException {
        jsonWriter.value(localDateTime.atZone(ZoneOffset.ofHours(0)).format(formatter));
    }

    @Override
    public LocalDateTime read(JsonReader jsonReader) throws IOException {
        Long time = ResultValueConvertUtils.parseDate(jsonReader.nextString());
        if (time == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault());
    }
}