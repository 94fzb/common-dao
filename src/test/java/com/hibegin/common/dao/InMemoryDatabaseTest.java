package com.hibegin.common.dao;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDatabaseTest {

    @Test
    void shouldOpenH2DatabaseAndRestoreDefaultDataSource() throws Exception {
        DataSourceWrapper previous = DAO.getDefaultDataSource();

        try (InMemoryDatabase database = InMemoryDatabase.openH2("common_dao_test")) {
            assertSame(database.dataSource(), DAO.getDefaultDataSource());

            database.update("create table sample(id int primary key, name varchar(32))");
            database.update("insert into sample(id, name) values(?, ?)", 1, "alpha");

            assertEquals("alpha", database.queryOne("select name from sample where id=?", 1).get("NAME"));
        }

        assertSame(previous, DAO.getDefaultDataSource());
    }

    @Test
    void shouldLoadMySQLSchemaIntoH2() throws Exception {
        String sql = "# comment\n"
                + "DROP TABLE IF EXISTS `sample`, `other`;\n"
                + "CREATE TABLE `sample` (\n"
                + "  `id` int(11) NOT NULL AUTO_INCREMENT,\n"
                + "  `name` varchar(32) DEFAULT NULL COMMENT 'display name',\n"
                + "  `enabled` bit(1) DEFAULT b'1',\n"
                + "  PRIMARY KEY (`id`),\n"
                + "  UNIQUE KEY `name_idx` (`name`),\n"
                + "  KEY `enabled_idx` (`enabled`)\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8;\n";

        try (InMemoryDatabase database = InMemoryDatabase.openH2("common_dao_schema")) {
            database.loadMySQLSchema(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
            database.update("insert into sample(name, enabled) values(?, ?)", "alpha", true);

            assertEquals(1L, ((Number) database.scalar("select count(*) from sample")).longValue());
            assertTrue((Boolean) database.scalar("select enabled from sample where name=?", "alpha"));
        }
    }
}
