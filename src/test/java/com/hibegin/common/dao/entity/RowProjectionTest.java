package com.hibegin.common.dao.entity;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RowProjectionTest {
    static class Summary {
        String title;
        String dateText;
        Timestamp happenedAt;
        long total;
        @JsonAdapter(Uppercase.class) String adapted;
    }

    static class Uppercase extends TypeAdapter<String> {
        public void write(JsonWriter out, String value) throws IOException { out.value(value); }
        public String read(JsonReader in) throws IOException { return in.nextString().toUpperCase(java.util.Locale.ROOT); }
    }

    @Test
    void mapsDateValuesAndCaseInsensitiveAliasesWithoutJsonRoundTrip() throws Exception {
        Timestamp time = Timestamp.valueOf("2026-11-03 12:30:45.123456");
        Map<String, Object> row = new HashMap<>();
        row.put("TITLE", "note"); row.put("dateText", time); row.put("happenedAt", time);
        row.put("total", null); row.put("adapted", "mixed");
        Summary result = RowProjection.map(row, Summary.class);
        assertEquals("note", result.title);
        assertEquals("2026-11-03 12:30:45.123456", result.dateText);
        assertEquals(time, result.happenedAt);
        assertNotSame(time, result.happenedAt);
        assertEquals(0, result.total);
        assertEquals("MIXED", result.adapted);
    }

    @Test
    void rejectsPartialEntitiesAndInexactNumbers() {
        assertThrows(IllegalArgumentException.class, () -> RowProjection.map(Map.of(), EntityRepositoryTest.Note.class));
        assertThrows(IllegalArgumentException.class, () -> RowProjection.map(Map.of(), Map.class));
        assertThrows(SQLException.class, () -> RowProjection.map(Map.of("total", "1.1"), Summary.class));
    }
}
