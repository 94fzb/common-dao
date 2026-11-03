package com.hibegin.common.dao.entity;

import com.google.gson.annotations.SerializedName;
import com.google.gson.annotations.JsonAdapter;
import com.hibegin.common.dao.ResultBeanUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Read-only DTO mapping. Partial projections can never be used as tracked entities. */
public final class RowProjection {
    private RowProjection() { }

    public static <T> T map(Map<String, Object> row, Class<T> type) throws SQLException {
        if (BaseEntity.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("A concrete projection DTO is required");
        }
        if (row == null) return null;
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            T value = constructor.newInstance();
            Map<String, Object> columns = new HashMap<>();
            row.forEach((key, cell) -> columns.put(key.toLowerCase(Locale.ROOT), cell));
            for (Class<?> parent = type; parent != Object.class; parent = parent.getSuperclass()) {
                for (Field field : parent.getDeclaredFields()) {
                    if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    Column column = field.getAnnotation(Column.class);
                    SerializedName json = field.getAnnotation(SerializedName.class);
                    String name = column != null && !column.value().isEmpty() ? column.value()
                            : json == null ? field.getName() : json.value();
                    name = name.toLowerCase(Locale.ROOT);
                    if (!columns.containsKey(name)) continue;
                    if (Modifier.isFinal(field.getModifiers())) throw new IllegalArgumentException("Projection field must be mutable: " + field);
                    Object cell = columns.get(name);
                    if (cell == null && field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object mapped;
                    if (field.isAnnotationPresent(JsonAdapter.class)
                            || java.util.Collection.class.isAssignableFrom(field.getType())
                            || Map.class.isAssignableFrom(field.getType())) {
                        // Keep existing DTO field adapters for explicitly structured/JSON columns.
                        mapped = field.get(ResultBeanUtils.convert(java.util.Collections.singletonMap(
                                json == null ? field.getName() : json.value(), cell), type));
                    } else {
                        mapped = cell != null && field.getType() == String.class ? cell.toString()
                                : EntityMapping.convert(cell, field.getType());
                    }
                    field.set(value, mapped);
                }
            }
            return value;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new SQLException("Cannot map projection " + type.getName(), error);
        }
    }
}
