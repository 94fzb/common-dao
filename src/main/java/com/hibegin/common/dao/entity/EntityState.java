package com.hibegin.common.dao.entity;

import com.hibegin.common.dao.DataSourceWrapper;

final class EntityState {
    final DataSourceWrapper source;
    final Object[] values;
    boolean valid = true;

    EntityState(DataSourceWrapper source, Object[] values) {
        this.source = source;
        this.values = values;
    }
}
