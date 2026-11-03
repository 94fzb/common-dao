package com.hibegin.common.dao.entity;

import java.sql.SQLException;

/** The original id/version or an additional write condition no longer matches. Reload before retrying. */
public class OptimisticLockException extends SQLException {
    public OptimisticLockException(String table) {
        super("Entity write did not match its original version or conditions: " + table, "40001");
    }
}
