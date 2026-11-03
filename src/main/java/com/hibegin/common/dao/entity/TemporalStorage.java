package com.hibegin.common.dao.entity;

/** Storage for a Java date/time field. This setting never changes the database schema. */
public enum TemporalStorage {
    /** JDBC DATE/TIMESTAMP, or date/time text on SQLite and its WebAPI bridge. */
    NATIVE,
    /** Explicit mapping for an existing integer column containing epoch milliseconds. */
    EPOCH_MILLIS
}
