package com.hibegin.common.dao;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.apache.commons.dbutils.QueryRunner;

import java.lang.reflect.Method;
import java.net.ProxySelector;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DataSourceWrapperImpl extends HikariDataSource implements DataSourceWrapper {

    private static final Logger LOGGER = DaoLoggerUtil.getLogger(DataSourceWrapperImpl.class);
    private static final String PROJECT_VERSION_RESOURCE = "META-INF/maven/com.hibegin/common-dao/pom.properties";
    private final boolean dev;
    private final QueryRunner queryRunner;

    public DataSourceWrapperImpl(Properties properties, boolean dev) {
        this(properties, dev, null);
    }

    /**
     * Creates a data source with an optional proxy for WebAPI requests.
     *
     * @param properties data source properties
     * @param dev whether to log SQL timings
     * @param proxySelector WebAPI proxy selector, or {@code null} to use the JDK default; ignored for JDBC
     */
    public DataSourceWrapperImpl(Properties properties, boolean dev, ProxySelector proxySelector) {
        setDataSourceProperties(properties);
        this.dev = dev;
        if (this.isWebApi()) {
            queryRunner = new WebApiQueryRunner(this.getDataSourceProperties(), this.isDev(), Math.max(getMaximumPoolSize(), 1), proxySelector);
        } else {
            queryRunner = new CustomQueryRunner(this, true);
        }
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
            ((WebApiQueryRunner) queryRunner).testConnection();
            LOGGER.info("Webapi test connect success");
            return;
        }
        try (Connection connection = getConnection()) {
            String catalog = connection.getCatalog();
            LOGGER.info("Db test connect success" + (catalog == null || catalog.isBlank() ? "" : " " + catalog));
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
                return "webapi/" + getProjectVersion();
            }
            return (String) new DAO(this).queryFirstObj(getDatabaseVersionSql());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "DB connect error ", e);
        }
        return "Unknown";
    }

    private String getDatabaseVersionSql() {
        if (isH2()) {
            return "select h2version()";
        }
        return "select version()";
    }

    private boolean isH2() {
        String jdbcUrl = getJdbcUrl();
        if (jdbcUrl == null) {
            jdbcUrl = (String) getDataSourceProperties().get("jdbcUrl");
        }
        if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:h2:")) {
            return true;
        }
        String driverClassName = getDriverClassName();
        if (driverClassName == null) {
            driverClassName = getDataSourceProperties().getProperty("driverClass");
        }
        return "org.h2.Driver".equals(driverClassName);
    }

    private static String getProjectVersion() {
        try (var inputStream = DataSourceWrapperImpl.class.getClassLoader().getResourceAsStream(PROJECT_VERSION_RESOURCE)) {
            if (inputStream == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties.getProperty("version", "unknown");
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Read project version error", e);
            return "unknown";
        }
    }

    @Override
    public QueryRunner getQueryRunner() {
        return queryRunner;
    }

    @Override
    public DatabaseConnectPoolInfo getDatabaseConnectPoolInfo() {
        if (queryRunner instanceof GetConnectPoolInfo) {
            return new DatabaseConnectPoolInfo(((GetConnectPoolInfo) queryRunner).getConnectActiveSize(), ((GetConnectPoolInfo) queryRunner).getConnectTotalSize());
        }
        HikariPoolMXBean hikariPoolMXBean = this.getHikariPoolMXBean();
        if (Objects.isNull(hikariPoolMXBean)) {
            return new DatabaseConnectPoolInfo(0, 0);
        }
        return new DatabaseConnectPoolInfo(hikariPoolMXBean.getActiveConnections(), hikariPoolMXBean.getTotalConnections());
    }

    @Override
    public void close() {
        super.close();
        if (isWebApi()) {
            return;
        }
        String jdbcUrl = (String) getDataSourceProperties().get("jdbcUrl");
        if (Objects.nonNull(jdbcUrl) && jdbcUrl.contains("mysql")) {
            try {
                Class<?> clazz = Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread");
                // 调用静态方法 checkedShutdown()
                Method method = clazz.getMethod("checkedShutdown");
                method.invoke(null);
            } catch (Exception e) {
                LOGGER.info("DataSource close error " + e.getMessage());
            }
        }
    }
}
