package com.hibegin.common.dao.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Explicit, runtime-only mapping. This annotation never creates or alters a table. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Entity {
    String table() default "";
    String idColumn() default "id";
    boolean generatedId() default false;
}
