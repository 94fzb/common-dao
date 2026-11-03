package com.hibegin.common.dao.entity;


final class EntityState {
    final Object source;
    final Object[] values;
    boolean valid = true;

    EntityState(Object source, Object[] values) {
        this.source = source;
        this.values = values;
    }
}
