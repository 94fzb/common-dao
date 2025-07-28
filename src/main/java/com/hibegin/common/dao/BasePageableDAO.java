package com.hibegin.common.dao;

import com.hibegin.common.dao.dto.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class BasePageableDAO extends DAO {

    protected static <T> List<T> doConvertList(List<Map<String, Object>> results, Class<T> clazz) {
        return results.stream().map(e -> {
            if (clazz.isAssignableFrom(Map.class)) {
                return (T) e;
            }
            return ResultBeanUtils.convert(e, clazz);
        }).collect(Collectors.toList());
    }

    public <T> PageData<T> queryPageData(String sql, PageRequest pageRequest, Object[] obj, Class<T> clazz) {
        PageData<T> data = new PageData<>();
        ExecutorService executors = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            tasks.add(CompletableFuture.runAsync(() -> {
                //无需查询具体数据
                if (pageRequest.getSize() <= 0) {
                    data.setRows(new ArrayList<>());
                    return;
                }
                try {
                    List<Object> params = new ArrayList<>(Arrays.asList(obj));
                    //需要分页对象
                    if (pageRequest instanceof PageRequestImpl) {
                        params.add(pageRequest.getOffset());
                        params.add(pageRequest.getSize());
                        data.setRows(doConvertList(this.queryListWithParams(sql + " limit  ?,?", params.toArray()), clazz));
                    } else {
                        data.setRows(doConvertList(this.queryListWithParams(sql, params.toArray()), clazz));
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, executors));
            tasks.add(CompletableFuture.runAsync(() -> {
                try {
                    data.setTotalElements((long) Double.parseDouble(this.queryFirstObj("select count(1) cnt from (" + sql + ") as subquery", obj) + ""));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, executors));
            try {
                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
        } finally {
            executors.shutdown();
        }
        if (pageRequest.getSorts() != null) {
            data.setSort(new ArrayList<>(pageRequest.getSorts().stream().map(OrderBy::toParamString).collect(Collectors.toList())));
        } else {
            data.setSort(new ArrayList<>());
        }
        data.setPage(pageRequest.getPage());
        if (pageRequest instanceof UnPageRequestImpl) {
            data.setSize(Math.max(data.getRows().size(), 1000000L));
        } else {
            data.setSize(pageRequest.getSize());
        }
        return data;
    }

    public PageData<Map<String, Object>> queryPageData(String sql, PageRequest pageRequest, Object[] obj) {
        return (PageData) queryPageData(sql, pageRequest, obj, Map.class);
    }
}
