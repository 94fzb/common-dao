package com.hibegin.common.dao.entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class EntityMapping {
    private static final ClassValue<EntityMapping> CACHE = new ClassValue<EntityMapping>() {
        @Override
        protected EntityMapping computeValue(Class<?> type) { return new EntityMapping(type); }
    };

    final Class<?> type;
    final Constructor<?> constructor;
    final String table;
    final boolean generatedId;
    final List<Property> properties;
    final int id;
    final int createdAt;
    final int updatedAt;
    final int version;

    static EntityMapping of(Class<?> type) { return CACHE.get(type); }

    private EntityMapping(Class<?> type) {
        this.type = type;
        Entity entity = type.getDeclaredAnnotation(Entity.class);
        if (entity == null || !BaseEntity.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) {
            throw new IllegalArgumentException("A concrete @Entity extending BaseEntity is required: " + type.getName());
        }
        table = identifier(entity.table().isEmpty() ? snakeCase(type.getSimpleName()) : entity.table());
        generatedId = entity.generatedId();
        Class<?> idType = resolveIdType(type);
        if (idType != String.class && idType != Integer.class && idType != Long.class) {
            throw new IllegalArgumentException("Entity ID must be String, Integer or Long");
        }
        if (generatedId && idType == String.class) {
            throw new IllegalArgumentException("Generated IDs require Integer or Long");
        }
        try {
            constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Entity requires a no-argument constructor: " + type.getName(), e);
        }
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(current);
        }
        Collections.reverse(hierarchy);
        List<Property> fields = new ArrayList<>();
        Set<String> columns = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                if (Modifier.isFinal(modifiers)) {
                    throw new IllegalArgumentException("Persistent fields must be mutable: " + field);
                }
                Column column = field.getAnnotation(Column.class);
                boolean primaryKey = current == BaseEntity.class && field.getName().equals("id");
                String name = primaryKey ? entity.idColumn()
                        : column == null || column.value().isEmpty() ? field.getName() : column.value();
                TemporalStorage storage = column == null ? TemporalStorage.NATIVE : column.temporalStorage();
                if (current == BaseEntity.class && field.getName().equals("createdAt")) {
                    name = entity.createdAtColumn();
                    storage = entity.createdAtStorage();
                } else if (current == BaseEntity.class && field.getName().equals("updatedAt")) {
                    name = entity.updatedAtColumn();
                    storage = entity.updatedAtStorage();
                }
                Class<?> valueType = primaryKey ? idType : field.getType();
                if (!supported(valueType)) {
                    throw new IllegalArgumentException("Unsupported persistent field type: " + field
                            + "; encode structured values explicitly or mark them transient");
                }
                if (!columns.add(name.toLowerCase(Locale.ROOT)) || !names.add(field.getName())) {
                    throw new IllegalArgumentException("Duplicate persistent field/column: " + field);
                }
                field.setAccessible(true);
                if (storage == TemporalStorage.EPOCH_MILLIS && valueType != Date.class && valueType != Timestamp.class) {
                    throw new IllegalArgumentException("EPOCH_MILLIS requires java.util.Date or java.sql.Timestamp: " + field);
                }
                fields.add(new Property(field, identifier(name), valueType, storage));
            }
        }
        properties = Collections.unmodifiableList(fields);
        id = index("id");
        createdAt = index("createdAt");
        updatedAt = index("updatedAt");
        version = index("version");
    }

    int index(String name) {
        for (int i = 0; i < properties.size(); i++) {
            if (properties.get(i).field.getName().equals(name)) { return i; }
        }
        throw new IllegalArgumentException("Unknown entity field: " + name);
    }

    String columns(boolean webApi) {
        return properties.stream().map(p -> selectColumn(p, webApi)).collect(Collectors.joining(", "));
    }

    String selectColumn(Property property, boolean webApi) {
        String column = quote(property.column);
        // SQLite/D1 JavaScript bridges cannot represent every int64 as a JSON number.
        // Read large numeric values as text before they pass through JavaScript.
        boolean exactNumber = property.type == Long.class || property.type == long.class
                || property.type == BigInteger.class || property.type == BigDecimal.class
                || property.storage == TemporalStorage.EPOCH_MILLIS;
        return webApi && exactNumber ? "CAST(" + column + " AS TEXT) AS " + column : column;
    }

    Object[] snapshot(BaseEntity<?> entity) {
        if (entity.getClass() != type) {
            throw new IllegalArgumentException("Expected exact entity type " + type.getName());
        }
        return properties.stream().map(p -> p.read(entity)).toArray();
    }

    BaseEntity<?> hydrate(Map<String, Object> row) throws SQLException {
        try {
            BaseEntity<?> entity = (BaseEntity<?>) constructor.newInstance();
            Map<String, Object> normalized = new HashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (normalized.containsKey(key)) { throw new SQLException("Duplicate result column: " + key); }
                normalized.put(key, entry.getValue());
            }
            for (Property property : properties) {
                String key = property.column.toLowerCase(Locale.ROOT);
                if (!normalized.containsKey(key)) {
                    throw new SQLException("Incomplete entity result; missing column: " + property.column);
                }
                property.write(entity, normalized.get(key));
            }
            if (entity.getId() == null || entity.getVersion() == null || entity.getVersion() < 0) {
                throw new SQLException("Entity requires a non-null ID and non-negative version: " + table);
            }
            return entity;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new SQLException("Cannot map entity " + type.getName(), e);
        }
    }

    static String quote(String identifier) { return "`" + identifier(identifier) + "`"; }

    private static String identifier(String value) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + value);
        }
        return value;
    }

    private static String snakeCase(String value) {
        return value.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static Class<?> resolveIdType(Class<?> type) {
        Map<Type, Type> bindings = new HashMap<>();
        Type parent = type.getGenericSuperclass();
        while (parent != null) {
            Class<?> raw;
            if (parent instanceof ParameterizedType) {
                ParameterizedType parameterized = (ParameterizedType) parent;
                raw = (Class<?>) parameterized.getRawType();
                Type[] arguments = parameterized.getActualTypeArguments();
                Type[] variables = raw.getTypeParameters();
                for (int i = 0; i < variables.length; i++) {
                    bindings.put(variables[i], bindings.getOrDefault(arguments[i], arguments[i]));
                }
                if (raw == BaseEntity.class) {
                    Type idType = bindings.get(variables[0]);
                    if (idType instanceof Class) { return (Class<?>) idType; }
                    break;
                }
            } else {
                raw = (Class<?>) parent;
            }
            parent = raw.getGenericSuperclass();
        }
        throw new IllegalArgumentException("Entity must declare its concrete BaseEntity ID type");
    }

    private static boolean supported(Class<?> type) {
        return Arrays.asList(String.class, Boolean.class, boolean.class, Byte.class, byte.class,
                Short.class, short.class, Integer.class, int.class, Long.class, long.class,
                Float.class, float.class, Double.class, double.class, BigDecimal.class, BigInteger.class,
                Date.class, Timestamp.class, java.sql.Date.class, byte[].class).contains(type);
    }

    static Object convert(Object value, Class<?> type) {
        if (value == null) {
            if (type.isPrimitive()) { throw new IllegalArgumentException("NULL cannot map to " + type); }
            return null;
        }
        if (type == String.class && value instanceof String) { return value; }
        if (type == byte[].class && value instanceof byte[]) { return ((byte[]) value).clone(); }
        if (TemporalValues.supports(type)) { return TemporalValues.convert(value, type); }
        if (type == Boolean.class || type == boolean.class) {
            if (value instanceof Boolean) { return value; }
            BigDecimal number = decimal(value);
            if (number.compareTo(BigDecimal.ZERO) == 0) { return false; }
            if (number.compareTo(BigDecimal.ONE) == 0) { return true; }
            throw new IllegalArgumentException("Boolean value must be 0 or 1");
        }
        if (type != String.class && type != byte[].class) {
            BigDecimal number = decimal(value);
            if (type == Long.class || type == long.class) { return number.longValueExact(); }
            if (type == Integer.class || type == int.class) { return number.intValueExact(); }
            if (type == Short.class || type == short.class) { return number.shortValueExact(); }
            if (type == Byte.class || type == byte.class) { return number.byteValueExact(); }
            if (type == BigInteger.class) { return number.toBigIntegerExact(); }
            if (type == BigDecimal.class) { return number; }
            if (type == Double.class || type == double.class) {
                double result = number.doubleValue();
                if (Double.isFinite(result)) { return result; }
            }
            if (type == Float.class || type == float.class) {
                float result = number.floatValue();
                if (Float.isFinite(result)) { return result; }
            }
        }
        throw new IllegalArgumentException("Cannot map " + value.getClass().getName() + " to " + type.getName());
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw new IllegalArgumentException("Expected a numeric value");
        }
        return new BigDecimal(value.toString());
    }

    static Object databaseValue(Object value) {
        if (value instanceof byte[]) { return ((byte[]) value).clone(); }
        return TemporalValues.jdbcValue(value);
    }

    static final class Property {
        final Field field;
        final String column;
        final Class<?> type;
        final TemporalStorage storage;

        Property(Field field, String column, Class<?> type, TemporalStorage storage) {
            this.field = field;
            this.column = column;
            this.type = type;
            this.storage = storage;
        }

        Object parameter(Object value) {
            Object converted = convert(value, type);
            if (converted != null && storage == TemporalStorage.EPOCH_MILLIS) {
                if (converted instanceof Timestamp && ((Timestamp) converted).getNanos() % 1_000_000 != 0) {
                    throw new IllegalArgumentException("EPOCH_MILLIS cannot preserve sub-millisecond precision: " + field);
                }
                return ((Date) converted).getTime();
            }
            return databaseValue(converted);
        }

        Object read(BaseEntity<?> entity) {
            try {
                return parameter(field.get(entity));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        void write(BaseEntity<?> entity, Object value) {
            try {
                Object mapped = value;
                if (value != null && storage == TemporalStorage.EPOCH_MILLIS) {
                    mapped = new Timestamp(decimal(value).longValueExact());
                }
                field.set(entity, convert(mapped, type));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
