package com.hibegin.common.dao;

import org.apache.commons.dbutils.QueryRunner;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

public interface DataSourceWrapper extends AutoCloseable, DataSource {

    boolean isWebApi();

    Properties getDataSourceProperties();

    void testConnection() throws SQLException;

    boolean isDev();

    String getDbInfo();

    QueryRunner getQueryRunner();

    DatabaseConnectPoolInfo getDatabaseConnectPoolInfo();
}
