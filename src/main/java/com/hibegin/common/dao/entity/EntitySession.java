package com.hibegin.common.dao.entity;

import com.hibegin.common.dao.DataSourceWrapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/**
 * An explicit scope for repositories sharing a caller-owned JDBC transaction. The caller owns
 * commit, rollback and connection close. Closing this scope invalidates its tracked entities.
 */
public final class EntitySession implements AutoCloseable {
    final DataSourceWrapper source;
    final Connection connection;
    private boolean active = true;

    public EntitySession(DataSourceWrapper source, Connection connection) throws SQLException {
        this.source = Objects.requireNonNull(source, "source");
        this.connection = Objects.requireNonNull(connection, "connection");
        if (source.isWebApi()) throw new IllegalArgumentException("WebAPI has no JDBC transaction");
        if (connection.isClosed() || connection.getAutoCommit()) {
            throw new IllegalArgumentException("An open, caller-owned transaction is required");
        }
    }

    public <E extends BaseEntity<ID>, ID> EntityRepository<E, ID> repository(Class<E> type) {
        requireActive();
        return new EntityRepository<>(type, source, Clock.systemUTC(), this);
    }

    void requireActive() {
        if (!active) throw new IllegalStateException("Entity session is closed; reload in the current transaction");
    }

    @Override
    public void close() { active = false; }
}
