package com.hibegin.common.dao.entity;

import com.hibegin.common.dao.DataSourceWrapper;
import com.hibegin.common.dao.WebApiQueryRunner;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.MapListHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Explicit, single-row persistence with snapshots and optimistic locking. No automatic DDL,
 * implicit flush, relation loading, retries, global data source or multi-operation transaction.
 *
 * @param <E> concrete mapped entity
 * @param <ID> primary key type
 */
public class EntityRepository<E extends BaseEntity<ID>, ID> {
    private final DataSourceWrapper source;
    private final QueryRunner runner;
    private final EntityMapping mapping;
    private final Clock clock;
    private final boolean textDates;

    public EntityRepository(Class<E> type, DataSourceWrapper source) {
        this(type, source, Clock.systemUTC());
    }

    public EntityRepository(Class<E> type, DataSourceWrapper source, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.runner = Objects.requireNonNull(source.getQueryRunner(), "queryRunner");
        this.mapping = EntityMapping.of(type);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.textDates = source.isWebApi()
                || source.getDataSourceProperties().getProperty("jdbcUrl", "").startsWith("jdbc:sqlite:");
        if (source.isWebApi()) {
            if (!(runner instanceof WebApiQueryRunner)) {
                throw new IllegalArgumentException("WebAPI entities require WebApiQueryRunner");
            }
            if (mapping.properties.stream().anyMatch(p -> p.type == byte[].class)) {
                throw new IllegalArgumentException("WebAPI entity binary fields require an explicit text encoding");
            }
        }
    }

    public Optional<E> findById(ID id) throws SQLException {
        Objects.requireNonNull(id, "id");
        List<E> result = findMatching(Collections.singletonMap("id", id), 1);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /** Equality conditions use Java field names. Every result is complete; limit must be 1..1000. */
    public List<E> findMatching(Map<String, ?> conditions, int limit) throws SQLException {
        if (limit < 1 || limit > 1000) { throw new IllegalArgumentException("limit must be 1..1000"); }
        List<Object> params = new ArrayList<>();
        String where = conditions(conditions, params);
        params.add(limit);
        String sql = "SELECT " + mapping.columns(source.isWebApi()) + " FROM " + table() + " WHERE 1=1" + where
                + " ORDER BY " + column(mapping.id) + " LIMIT ?";
        List<E> result = new ArrayList<>();
        for (Map<String, Object> row : rows(sql, params.toArray())) {
            @SuppressWarnings("unchecked") E entity = (E) mapping.hydrate(row);
            entity.persistenceState = new EntityState(source, mapping.snapshot(entity));
            result.add(entity);
        }
        return result;
    }

    /** Inserts a new entity, setting both times and version=0 only after a confirmed write. */
    public E insert(E entity) throws SQLException {
        Object[] values = mapping.snapshot(entity);
        if (entity.persistenceState != null || entity.getVersion() != null
                || entity.getCreatedAt() != null || entity.getUpdatedAt() != null) {
            throw new IllegalArgumentException("insert requires a new entity with no persisted metadata");
        }
        if (mapping.generatedId ? entity.getId() != null : entity.getId() == null) {
            throw new IllegalArgumentException(mapping.generatedId ? "Generated ID must be null" : "Assigned ID is required");
        }
        Timestamp now = new Timestamp(clock.millis());
        values[mapping.createdAt] = mapping.properties.get(mapping.createdAt).parameter(now);
        values[mapping.updatedAt] = mapping.properties.get(mapping.updatedAt).parameter(now);
        values[mapping.version] = 0L;
        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (i == mapping.id && mapping.generatedId) { continue; }
            columns.add(column(i));
            params.add(values[i]);
        }
        String sql = "INSERT INTO " + table() + " (" + String.join(", ", columns) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        try {
            if (mapping.generatedId) {
                values[mapping.id] = insertGenerated(sql, params.toArray());
            } else {
                requireOne(write(sql, params.toArray()));
            }
            accept(entity, values);
            return entity;
        } catch (SQLException | RuntimeException e) {
            invalidate(entity, values);
            throw e;
        }
    }

    public boolean update(E entity) throws SQLException {
        return update(entity, Collections.emptyMap());
    }

    /**
     * Writes changed fields plus updatedAt/version, using the loaded version and optional equality
     * conditions in the same statement. False means no changes and NO SQL (conditions not evaluated).
     */
    public boolean update(E entity, Map<String, ?> expectedValues) throws SQLException {
        Object[] old = tracked(entity);
        Object[] values = mapping.snapshot(entity);
        List<Object> conditionParams = new ArrayList<>();
        String extraConditions = conditions(expectedValues, conditionParams);
        List<String> assignments = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (managed(i) || Objects.deepEquals(values[i], old[i])) { continue; }
            assignments.add(column(i) + "=?");
            params.add(values[i]);
        }
        if (assignments.isEmpty()) { return false; }
        values[mapping.version] = Math.addExact((Long) old[mapping.version], 1L);
        values[mapping.updatedAt] = mapping.properties.get(mapping.updatedAt).parameter(new Timestamp(clock.millis()));
        assignments.add(column(mapping.updatedAt) + "=?");
        params.add(values[mapping.updatedAt]);
        assignments.add(column(mapping.version) + "=?");
        params.add(values[mapping.version]);
        params.add(old[mapping.id]);
        params.add(old[mapping.version]);
        params.addAll(conditionParams);
        String sql = "UPDATE " + table() + " SET " + String.join(", ", assignments)
                + " WHERE " + column(mapping.id) + "=? AND " + column(mapping.version) + "=?" + extraConditions;
        try {
            requireOne(write(sql, params.toArray()));
            accept(entity, values);
            return true;
        } catch (SQLException | RuntimeException e) {
            entity.persistenceState.valid = false;
            throw e;
        }
    }

    public void delete(E entity) throws SQLException {
        delete(entity, Collections.emptyMap());
    }

    /** Deletes only the loaded version, subject to any additional equality conditions. */
    public void delete(E entity, Map<String, ?> expectedValues) throws SQLException {
        Object[] old = tracked(entity);
        List<Object> params = new ArrayList<>();
        params.add(old[mapping.id]);
        params.add(old[mapping.version]);
        String extraConditions = conditions(expectedValues, params);
        try {
            requireOne(write("DELETE FROM " + table() + " WHERE " + column(mapping.id)
                    + "=? AND " + column(mapping.version) + "=?" + extraConditions, params.toArray()));
        } finally {
            entity.persistenceState.valid = false;
        }
    }

    private String conditions(Map<String, ?> conditions, List<Object> params) {
        Objects.requireNonNull(conditions, "conditions");
        StringBuilder sql = new StringBuilder();
        for (Map.Entry<String, ?> entry : conditions.entrySet()) {
            int index = mapping.index(entry.getKey());
            Object value = mapping.properties.get(index).parameter(entry.getValue());
            sql.append(" AND ").append(column(index));
            if (value == null) {
                sql.append(" IS NULL");
            } else {
                sql.append("=?");
                params.add(value);
            }
        }
        return sql.toString();
    }

    private Object[] tracked(E entity) {
        Object[] current = mapping.snapshot(entity);
        EntityState state = entity.persistenceState;
        if (state == null || !state.valid || state.source != source) {
            throw new IllegalStateException("Entity must be loaded from this data source; reload after a failed write");
        }
        for (int i = 0; i < current.length; i++) {
            if (managed(i) && !Objects.deepEquals(current[i], state.values[i])) {
                throw new IllegalStateException("Persisted ID/timestamps/version cannot be changed by callers");
            }
        }
        return state.values;
    }

    private boolean managed(int i) {
        return i == mapping.id || i == mapping.createdAt || i == mapping.updatedAt || i == mapping.version;
    }

    private void accept(E entity, Object[] values) {
        for (int i = 0; i < values.length; i++) {
            if (managed(i)) { mapping.properties.get(i).write(entity, values[i]); }
        }
        entity.persistenceState = new EntityState(source, values);
    }

    private void invalidate(E entity, Object[] values) {
        entity.persistenceState = new EntityState(source, values);
        entity.persistenceState.valid = false;
    }

    private void requireOne(int changes) throws SQLException {
        if (changes == 0) { throw new OptimisticLockException(mapping.table); }
        if (changes != 1) { throw new SQLException("Entity write affected an unexpected number of rows: " + changes); }
    }

    private String table() { return EntityMapping.quote(mapping.table); }
    private String column(int index) { return EntityMapping.quote(mapping.properties.get(index).column); }

    private int write(String sql, Object[] params) throws SQLException {
        params = parameters(params);
        return source.isWebApi() ? ((WebApiQueryRunner) runner).updateEntity(sql, params) : runner.update(sql, params);
    }

    private List<Map<String, Object>> rows(String sql, Object[] params) throws SQLException {
        params = parameters(params);
        return source.isWebApi() ? ((WebApiQueryRunner) runner).queryEntityRows(sql, params)
                : runner.query(sql, new MapListHandler(), params);
    }

    private Object insertGenerated(String sql, Object[] params) throws SQLException {
        Object[] bound = parameters(params);
        EntityMapping.Property key = mapping.properties.get(mapping.id);
        if (source.isWebApi()) {
            List<Map<String, Object>> result = rows(sql + " RETURNING " + mapping.selectColumn(key, true), bound);
            if (result.size() != 1) { throw new SQLException("Generated insert requires exactly one returned key"); }
            return generatedKey(result.get(0).get(key.column), key.type);
        }
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Object id = runner.insert(connection, sql, rs -> {
                    if (!rs.next()) { throw new SQLException("JDBC did not return a generated key"); }
                    Object result = generatedKey(rs.getObject(1), key.type);
                    if (rs.next()) { throw new SQLException("JDBC returned multiple generated keys"); }
                    return result;
                }, bound);
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    private Object[] parameters(Object[] values) {
        if (!textDates) { return values; }
        Object[] bound = values.clone();
        for (int i = 0; i < bound.length; i++) { bound[i] = TemporalValues.textValue(bound[i]); }
        return bound;
    }

    private Object generatedKey(Object value, Class<?> type) throws SQLException {
        if (value == null) { throw new SQLException("Generated insert returned a null key"); }
        try {
            return EntityMapping.convert(value, type);
        } catch (RuntimeException e) {
            throw new SQLException("Generated key cannot be represented by the entity ID type", e);
        }
    }
}
