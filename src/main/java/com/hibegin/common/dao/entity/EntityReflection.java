package com.hibegin.common.dao.entity;

import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Build-time helper for the application's Native Image reflect-config.json. No GraalVM dependency. */
public final class EntityReflection {
    private EntityReflection() { }

    /**
     * Returns reflection configuration for constructors and persisted fields, including inherited
     * metadata. Merge into the application's configuration at build time, before native-image runs.
     * This configures persistence only; API DTO serialization is the application's responsibility.
     */
    public static String configuration(Class<?>... entityTypes) {
        Map<Class<?>, Set<String>> fields = new LinkedHashMap<>();
        Set<Class<?>> constructors = new LinkedHashSet<>();
        for (Class<?> type : entityTypes) {
            EntityMapping mapping = EntityMapping.of(type);
            constructors.add(type);
            for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
                fields.computeIfAbsent(current, key -> new LinkedHashSet<>());
            }
            for (EntityMapping.Property property : mapping.properties) {
                fields.computeIfAbsent(property.field.getDeclaringClass(), key -> new LinkedHashSet<>())
                        .add(property.field.getName());
            }
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        fields.forEach((type, names) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.getName());
            List<Map<String, String>> mappedFields = new ArrayList<>();
            names.forEach(name -> mappedFields.add(Map.of("name", name)));
            entry.put("fields", mappedFields);
            if (constructors.contains(type)) {
                entry.put("methods", List.of(Map.of("name", "<init>", "parameterTypes", List.of())));
            }
            entries.add(entry);
        });
        return new GsonBuilder().setPrettyPrinting().create().toJson(entries);
    }
}
