package com.hibegin.common.dao.entity;

import java.sql.Timestamp;

/**
 * Common persisted metadata. Times map to SQL date/time columns; historical times may be null.
 * Instances are not thread safe. Use a separate DTO at application boundaries.
 *
 * @param <ID> String, Integer or Long primary key type
 */
public abstract class BaseEntity<ID> {
    private ID id;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Long version;

    // Never part of persistence or JSON. No global entity cache or implicit unit of work.
    transient EntityState persistenceState;

    public ID getId() { return id; }
    public void setId(ID id) { this.id = id; }
    public Timestamp getCreatedAt() { return copy(createdAt); }
    public Timestamp getUpdatedAt() { return copy(updatedAt); }
    public Long getVersion() { return version; }

    private static Timestamp copy(Timestamp value) {
        return value == null ? null : (Timestamp) value.clone();
    }
}
