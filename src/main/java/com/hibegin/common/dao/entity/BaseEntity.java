package com.hibegin.common.dao.entity;

/**
 * Common persisted metadata. Timestamps are UTC epoch milliseconds; historical times may be null.
 * Instances are not thread safe. Use a separate DTO at application boundaries.
 *
 * @param <ID> String, Integer or Long primary key type
 */
public abstract class BaseEntity<ID> {
    private ID id;
    private Long createdAt;
    private Long updatedAt;
    private Long version;

    // Never part of persistence or JSON. No global entity cache or implicit unit of work.
    transient EntityState persistenceState;

    public ID getId() { return id; }
    public void setId(ID id) { this.id = id; }
    public Long getCreatedAt() { return createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
