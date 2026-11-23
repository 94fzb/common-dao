package com.hibegin.common.dao.entity;

import com.hibegin.common.dao.BasePageableDAO;
import com.hibegin.common.dao.DataSourceWrapper;
import com.hibegin.common.dao.dto.PageData;
import com.hibegin.common.dao.dto.PageRequest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed row writes and explicit SQL projections, without mutable query/row state. */
public abstract class BaseRepository<E extends BaseEntity<ID>, ID> extends EntityRepository<E, ID> {
    protected final String tableName;
    protected final String pk;
    private final BasePageableDAO queries;

    protected BaseRepository(Class<E> entityClass, DataSourceWrapper source) {
        super(entityClass, source);
        EntityMapping mapping = EntityMapping.of(entityClass);
        tableName = mapping.table;
        pk = mapping.properties.get(mapping.id).column;
        queries = new BasePageableDAO(source);
    }

    private static void requireProjection(Class<?> type) {
        if (BaseEntity.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                    "Use tracked entity reads or a concrete projection type");
        }
    }

    private static <T> List<T> doConvertList(List<Map<String, Object>> rows, Class<T> type)
            throws SQLException {
        List<T> values = new ArrayList<>();
        for (Map<String, Object> row : rows) values.add(RowProjection.map(row, type));
        return values;
    }

    public boolean execute(String sql, Object... params) throws SQLException {
        return queries.execute(sql, params);
    }

    public Object queryFirstObj(String sql, Object... params) throws SQLException {
        return queries.queryFirstObj(sql, params);
    }

    /** A complete, tracked entity; null means that the row does not exist. */
    public E loadById(ID id) throws SQLException {
        return findById(id).orElse(null);
    }

    /** Read-only projection. Entity reads must use the tracked EntityRepository APIs. */
    public <T> List<T> queryList(String sql, Class<T> type, Object... params) throws SQLException {
        requireProjection(type);
        return doConvertList(queries.queryListWithParams(sql, params), type);
    }

    public <T> T queryOne(String sql, Class<T> type, Object... params) throws SQLException {
        requireProjection(type);
        Map<String, Object> row = queries.queryFirstWithParams(sql, params);
        return RowProjection.map(row, type);
    }

    public <T> PageData<T> queryPageData(
            String sql, PageRequest page, Object[] params, Class<T> type) {
        requireProjection(type);
        return queries.queryPageData(sql, page, params, type);
    }
}
