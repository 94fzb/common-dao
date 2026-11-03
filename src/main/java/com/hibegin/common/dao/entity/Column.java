package com.hibegin.common.dao.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Overrides a field's column name or explicitly maps a date/time to an existing integer column. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    String value() default "";
    TemporalStorage temporalStorage() default TemporalStorage.NATIVE;
}
