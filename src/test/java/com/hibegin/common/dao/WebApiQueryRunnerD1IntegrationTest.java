package com.hibegin.common.dao;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebApiQueryRunnerD1IntegrationTest {

    @Test
    void updateUsesCloudflareD1ReturnedChangeCount() throws Exception {
        Properties properties = loadDbProperties();
        String tableName = "common_dao_update_test_" + UUID.randomUUID().toString().replace("-", "");

        try (DataSourceWrapper dataSource = new DataSourceWrapperImpl(properties, false)) {
            QueryRunner queryRunner = dataSource.getQueryRunner();
            try {
                queryRunner.update("create table if not exists " + tableName + " (id integer primary key autoincrement, name text)");
                queryRunner.update("delete from " + tableName);
                assertEquals(1, queryRunner.update("insert into " + tableName + " (name) values (?)", "before"));
                assertEquals(0, queryRunner.update("update " + tableName + " set name=? where name=?", "after", "missing"));

                TestDAO dao = new TestDAO(dataSource, tableName);
                assertFalse(dao.set("name", "after_by_dao").update(Map.of("name", "missing")));
                assertTrue(dao.update(Map.of("name", "before")));

                assertEquals("after_by_dao", queryRunner.query("select name from " + tableName, new ScalarHandler<>(1)));
            } finally {
                dropTable(queryRunner, tableName);
            }
        }
    }

    private static Properties loadDbProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = WebApiQueryRunnerD1IntegrationTest.class.getClassLoader().getResourceAsStream("db.properties")) {
            assertNotNull(inputStream, "src/test/resources/db.properties is required for the D1 integration test");
            properties.load(inputStream);
        }
        return properties;
    }

    private static void dropTable(QueryRunner queryRunner, String tableName) throws Exception {
        queryRunner.update("drop table if exists " + tableName);
    }

    private static class TestDAO extends DAO {

        private TestDAO(DataSourceWrapper dataSource, String tableName) {
            super(dataSource);
            this.tableName = tableName;
        }
    }
}
