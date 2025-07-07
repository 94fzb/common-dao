package com.hibegin.common.dao;

import com.google.gson.Gson;
import com.hibegin.common.util.LoggerUtil;
import com.hibegin.common.util.ObjectUtil;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.MapHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebApiQueryRunner extends QueryRunner {

    private static final Logger LOGGER = LoggerUtil.getLogger(WebApiQueryRunner.class);
    private final Properties dbProperties;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final boolean dev;
    private final URI webApiUrl;

    public WebApiQueryRunner(Properties dataSourceProperties, boolean dev) {
        this.dbProperties = dataSourceProperties;
        this.dev = dev;
        URI uri = URI.create(dbProperties.getProperty("jdbcUrl").replaceAll("jdbc:", ""));
        boolean http2 = ObjectUtil.requireNonNullElse(uri.getQuery(), "").contains("supportHttp2=true");
        this.webApiUrl = URI.create("https://" + uri.getHost() + ":" + uri.getPort() + uri.getPath());
        this.httpClient = HttpClient.newBuilder().version(http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1).build();
    }

    private HttpRequest buildHttpRequest(String sql, Object... params) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sql", sql);
        if (Objects.nonNull(params)) {
            List<Object> objects = new ArrayList<>(params.length);
            for (Object param : params) {
                if (param instanceof Date) {
                    objects.add(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format((Date) param));
                } else {
                    objects.add(param);
                }
            }
            map.put("params", objects);

        } else {
            map.put("params", new ArrayList<>());
        }
        String body = gson.toJson(map);
        return HttpRequest.newBuilder().uri(webApiUrl).POST(
                        HttpRequest.BodyPublishers.ofString(body)
                )
                .header("Authorization", "Bearer " + dbProperties.getProperty("user") + ":" + dbProperties.getProperty("password"))
                .build();
    }

    public <T> T query(final String sql, final ResultSetHandler<T> rsh) throws SQLException {
        return webApiQuery(sql, rsh, (Object[]) null);
    }

    public <T> T query(final String sql, final ResultSetHandler<T> rsh, final Object... params) throws SQLException {
        return webApiQuery(sql, rsh, params);
    }

    private <T> T webApiQuery(String sql, ResultSetHandler<T> rsh, Object... params) throws SQLException {
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> send = httpClient.send(buildHttpRequest(sql, params), HttpResponse.BodyHandlers.ofString());
            if (send.statusCode() == 200) {
                Map<String, Object> map = gson.fromJson(send.body(), Map.class);
                if (Objects.equals(map.get("success"), true)) {
                    List results = (List) map.get("results");
                    if (rsh instanceof ScalarHandler) {
                        if (results.isEmpty()) {
                            return null;
                        }
                        ArrayList<Object> objects = new ArrayList<>(((Map<String, Object>) results.get(0)).values());
                        if (objects.isEmpty()) {
                            return null;
                        }
                        return (T) objects.get(0);
                    }
                    if (rsh instanceof MapHandler) {
                        if (results.isEmpty()) {
                            return null;
                        }
                        return (T) results.get(0);
                    }
                    if (results.isEmpty()) {
                        return (T) new ArrayList<>();
                    }
                    return (T) results;
                }
            }
            throw new SQLException(send.body());
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e);
        } finally {
            if (dev) {
                LOGGER.log(Level.INFO, sql + " took " + (System.currentTimeMillis() - start) + "ms");
            }
        }
    }

    @Override
    public <T> T query(Connection conn, String sql, ResultSetHandler<T> rsh, Object... params) throws SQLException {
        return webApiQuery(sql, rsh, params);
    }

    @Override
    public int update(Connection conn, String sql) throws SQLException {
        return this.webApiUpdate(sql, (Object[]) null);
    }

    @Override
    public int update(Connection conn, String sql, Object param) throws SQLException {
        return webApiUpdate(sql, param);
    }

    public int update(final String sql, final Object... params) throws SQLException {
        return this.webApiUpdate(sql, params);
    }

    private int webApiUpdate(String sql, Object... params) throws SQLException {
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> send = httpClient.send(buildHttpRequest(sql, params), HttpResponse.BodyHandlers.ofString());
            if (send.statusCode() == 200) {
                Map<String, Object> map = gson.fromJson(send.body(), Map.class);
                if (Objects.equals(map.get("success"), true)) {
                    return Math.min(1, Objects.requireNonNullElse((Integer) map.get("changes"), 1));
                }
            }
            throw new SQLException(send.body());
        } catch (IOException | InterruptedException e) {
            throw new SQLException(e);
        } finally {
            if (dev) {
                LOGGER.log(Level.INFO, sql + " took " + (System.currentTimeMillis() - start) + "ms");
            }
        }
    }

    @Override
    public int update(Connection conn, String sql, Object... params) throws SQLException {
        return webApiUpdate(sql, params);
    }

    public void testConnection() throws SQLException {
        query("select 1 = 1", new MapHandler());
    }
}
