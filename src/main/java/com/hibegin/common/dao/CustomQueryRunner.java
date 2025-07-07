package com.hibegin.common.dao;

import com.hibegin.common.util.LoggerUtil;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CustomQueryRunner extends QueryRunner {

    private static final Logger LOGGER = LoggerUtil.getLogger(CustomQueryRunner.class);

    private final boolean dev;

    public CustomQueryRunner(DataSourceWrapper dataSource, boolean b) {
        super(dataSource, b);
        if (Objects.nonNull(dataSource)) {
            this.dev = dataSource.isDev();
        } else {
            this.dev = false;
        }
    }

    @Override
    public int update(Connection conn, String sql) throws SQLException {
        return this.update(conn, sql, (Object[]) null);
    }

    @Override
    public int update(Connection conn, String sql, Object param) throws SQLException {
        return this.update(conn, sql, new Object[]{param});

    }

    public <T> T query(Connection conn, String sql, ResultSetHandler<T> rsh, Object... params) throws SQLException {
        long start = System.currentTimeMillis();
        try {
            return super.query(conn, sql, rsh, params);
        } finally {
            if (dev) {
                LOGGER.log(Level.INFO, sql + " took " + (System.currentTimeMillis() - start) + "ms");
            }
        }
    }

    @Override
    public int update(Connection conn, String sql, Object... params) throws SQLException {
        long start = System.currentTimeMillis();
        if (conn == null) {
            throw new SQLException("Null connection");
        }

        if (sql == null) {
            throw new SQLException("Null SQL statement");
        }
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        conn.setAutoCommit(false);
        try {
            int result = super.update(conn, sql, params);
            conn.commit();
            return result;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            if (dev) {
                LOGGER.log(Level.INFO, sql + " took " + (System.currentTimeMillis() - start) + "ms");
            }
        }
    }
}
