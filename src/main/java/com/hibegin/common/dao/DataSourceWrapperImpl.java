package com.hibegin.common.dao;

import com.hibegin.common.util.LoggerUtil;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DataSourceWrapperImpl extends HikariDataSource implements DataSourceWrapper {

    private static final Logger LOGGER = LoggerUtil.getLogger(DataSourceWrapperImpl.class);
    private final boolean dev;

    public DataSourceWrapperImpl(Properties properties, boolean dev) {
        setDataSourceProperties(properties);
        this.dev = dev;
    }

    @Override
    public boolean isWebApi() {
        String jdbcUrl = (String) getDataSourceProperties().get("jdbcUrl");
        if (Objects.isNull(jdbcUrl)) {
            return false;
        }
        return jdbcUrl.startsWith("jdbc:webapi:");
    }

    @Override
    public void testConnection() throws SQLException {
        if (isWebApi()) {
            new WebApiQueryRunner(getDataSourceProperties(), dev).testConnection();
            LOGGER.info("Webapi test connect success");
            return;
        }
        try (Connection connection = getConnection()) {
            LOGGER.info("Db test connect success " + connection.getCatalog());
        }
    }

    @Override
    public boolean isDev() {
        return dev;
    }

    @Override
    public String getDbInfo() {
        try {
            if (isWebApi()) {
                return "webapi/1.0.3";
            }
            return (String) new DAO(this).queryFirstObj("select version()");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "DB connect error ", e);
        }
        return "Unknown";
    }
}
