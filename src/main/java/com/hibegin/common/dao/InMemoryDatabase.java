package com.hibegin.common.dao;

import org.apache.commons.dbutils.handlers.MapHandler;
import org.apache.commons.dbutils.handlers.MapListHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

public class InMemoryDatabase implements AutoCloseable {

    public static final String H2_DRIVER_CLASS = "org.h2.Driver";

    private final DataSourceWrapper dataSource;
    private final DataSourceWrapper previousDataSource;
    private final boolean restoreDefaultDataSource;
    private boolean closed;

    private InMemoryDatabase(DataSourceWrapper dataSource, boolean setAsDefaultDataSource) {
        this.dataSource = dataSource;
        this.restoreDefaultDataSource = setAsDefaultDataSource;
        this.previousDataSource = setAsDefaultDataSource ? DAO.getDefaultDataSource() : null;
        if (setAsDefaultDataSource) {
            DAO.setDs(dataSource);
        }
    }

    public static InMemoryDatabase openH2(String databaseName) {
        return open(h2Properties(databaseName), true);
    }

    public static InMemoryDatabase open(DataSourceWrapper dataSource, boolean setAsDefaultDataSource) {
        return new InMemoryDatabase(dataSource, setAsDefaultDataSource);
    }

    public static InMemoryDatabase open(Properties properties, boolean setAsDefaultDataSource) {
        return new InMemoryDatabase(buildDataSource(properties), setAsDefaultDataSource);
    }

    public static Properties h2Properties(String databaseName) {
        Properties properties = new Properties();
        properties.setProperty("driverClass", H2_DRIVER_CLASS);
        properties.setProperty("jdbcUrl", h2JdbcUrl(databaseName));
        properties.setProperty("user", "sa");
        properties.setProperty("password", "");
        return properties;
    }

    public static String h2JdbcUrl(String databaseName) {
        return "jdbc:h2:mem:" + cleanDatabaseName(databaseName)
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                + ";NON_KEYWORDS=USER,VALUE,COMMENT,TYPE;DB_CLOSE_DELAY=-1";
    }

    private static String cleanDatabaseName(String databaseName) {
        String fallbackName = "in_memory_" + UUID.randomUUID();
        String name = Objects.requireNonNullElse(databaseName, fallbackName).trim();
        if (name.isEmpty()) {
            name = fallbackName;
        }
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static DataSourceWrapperImpl buildDataSource(Properties properties) {
        DataSourceWrapperImpl dataSource = new DataSourceWrapperImpl(properties, false);
        if (!dataSource.isWebApi()) {
            String driverClass = properties.getProperty("driverClass");
            if (driverClass == null || driverClass.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing JDBC driverClass");
            }
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Missing JDBC driver " + driverClass, e);
            }
            dataSource.setDriverClassName(driverClass);
            dataSource.setJdbcUrl(properties.getProperty("jdbcUrl"));
        }
        dataSource.setUsername(properties.getProperty("user"));
        dataSource.setPassword(properties.getProperty("password"));
        return dataSource;
    }

    public DataSourceWrapper dataSource() {
        return dataSource;
    }

    public DAO dao() {
        return new DAO(dataSource);
    }

    public void loadMySQLSchema(InputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("Missing SQL schema input");
        }
        try {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            executeStatements(SqlConvertUtils.doMySQLToH2BySqlText(sql));
        } catch (Exception e) {
            throw new IllegalStateException("Load MySQL schema into H2 failed", e);
        }
    }

    public void executeStatements(Collection<String> statements) throws SQLException {
        for (String statement : statements) {
            String trimmed = statement == null ? "" : statement.trim();
            if (!trimmed.isEmpty()) {
                update(trimmed);
            }
        }
    }

    public int update(String sql, Object... params) throws SQLException {
        return dataSource.getQueryRunner().update(sql, params);
    }

    public boolean execute(String sql, Object... params) throws SQLException {
        return update(sql, params) > 0;
    }

    public Object scalar(String sql, Object... params) throws SQLException {
        return dataSource.getQueryRunner().query(sql, new ScalarHandler<>(1), params);
    }

    public Map<String, Object> queryOne(String sql, Object... params) throws SQLException {
        return dataSource.getQueryRunner().query(sql, new MapHandler(), params);
    }

    public List<Map<String, Object>> queryList(String sql, Object... params) throws SQLException {
        return dataSource.getQueryRunner().query(sql, new MapListHandler(), params);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        Exception closeError = null;
        try {
            dataSource.close();
        } catch (Exception e) {
            closeError = e;
        } finally {
            if (restoreDefaultDataSource) {
                DAO.setDs(previousDataSource);
            }
            closed = true;
        }
        if (closeError != null) {
            throw new IllegalStateException("Close in-memory database failed", closeError);
        }
    }
}
