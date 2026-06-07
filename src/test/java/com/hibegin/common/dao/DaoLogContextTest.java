package com.hibegin.common.dao;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DaoLogContextTest {

    @Test
    void openRestoresPreviousLabel() {
        assertNull(DaoLogContext.currentLabel());
        try (DaoLogContext.Scope ignored = DaoLogContext.open("plugin-a")) {
            assertEquals("plugin-a", DaoLogContext.currentLabel());
            assertEquals("[plugin-a] select 1", DaoLogContext.format("select 1"));
            try (DaoLogContext.Scope nested = DaoLogContext.open("plugin-b")) {
                assertEquals("plugin-b", DaoLogContext.currentLabel());
                assertEquals("[plugin-b] select 2", DaoLogContext.format("select 2"));
            }
            assertEquals("plugin-a", DaoLogContext.currentLabel());
        }
        assertNull(DaoLogContext.currentLabel());
    }
}
