package com.hibegin.common.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hibegin.common.dao.dto.PageRequestImpl;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class BasePageableDAOTest {
    @Test
    void explicitSourceWorksWithoutDefaultAndSurvivesAnotherDefault() throws Exception {
        DataSourceWrapper previous = DAO.getDefaultDataSource();
        try (InMemoryDatabase first =
                        InMemoryDatabase.open(
                                InMemoryDatabase.h2Properties("first_" + UUID.randomUUID()),
                                false);
                InMemoryDatabase second =
                        InMemoryDatabase.open(
                                InMemoryDatabase.h2Properties("second_" + UUID.randomUUID()),
                                false)) {
            first.update("create table sample(id int primary key, name varchar(32))");
            second.update("create table sample(id int primary key, name varchar(32))");
            first.update("insert into sample values(1, 'first')");
            second.update("insert into sample values(1, 'second')");
            DAO.setDs(null);
            BasePageableDAO dao = new BasePageableDAO(first.dataSource());
            DAO.setDs(second.dataSource());

            var page =
                    dao.queryPageData(
                            "select name from sample order by id",
                            new PageRequestImpl(1L, 10L),
                            new Object[0]);
            assertEquals(1L, page.getTotalElements());
            assertEquals("first", page.getRows().get(0).get("name"));
            dao.execute("update sample set name=? where id=?", "updated", 1);
            assertEquals("updated", first.scalar("select name from sample where id=1"));
            assertEquals("second", second.scalar("select name from sample where id=1"));
        } finally {
            DAO.setDs(previous);
        }
    }
}
