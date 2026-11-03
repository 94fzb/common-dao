package com.hibegin.common.dao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.MapHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebApiQueryRunner extends QueryRunner implements GetConnectPoolInfo {

    private static final Logger LOGGER = DaoLoggerUtil.getLogger(WebApiQueryRunner.class);
    private final Properties dbProperties;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final Gson entityGson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    private final boolean dev;
    private final URI webApiUrl;
    private final AtomicLong connectSize = new AtomicLong(0);
    private final Semaphore semaphore;
    private final int maximumPoolSize;


    public WebApiQueryRunner(Properties dataSourceProperties, boolean dev, int maximumPoolSize) {
        this(dataSourceProperties, dev, maximumPoolSize, null);
    }

    /**
     * Creates a runner with an optional proxy for WebAPI requests.
     *
     * @param dataSourceProperties WebAPI data source properties
     * @param dev whether to log SQL timings
     * @param maximumPoolSize maximum number of concurrent requests
     * @param proxySelector proxy selector, or {@code null} to use the JDK default
     */
    public WebApiQueryRunner(Properties dataSourceProperties, boolean dev, int maximumPoolSize, ProxySelector proxySelector) {
        this.dbProperties = dataSourceProperties;
        this.dev = dev;
        URI uri = URI.create(dbProperties.getProperty("jdbcUrl").replaceAll("jdbc:", ""));
        String query = uri.getQuery() == null ? "" : uri.getQuery();
        boolean http2 = query.contains("supportHttp2=true");
        this.webApiUrl = URI.create("https://" + uri.getHost() + ":" + uri.getPort() + uri.getPath());
        this.semaphore = new Semaphore(maximumPoolSize);
        this.maximumPoolSize = maximumPoolSize;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(maximumPoolSize))
                .version(http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.httpClient = builder.build();
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

    private HttpResponse<String> doRequest(String sql, Object... params) throws IOException, InterruptedException {
        semaphore.acquire();
        connectSize.incrementAndGet();
        try {
            return httpClient.send(buildHttpRequest(sql, params), HttpResponse.BodyHandlers.ofString());
        } finally {
            connectSize.decrementAndGet();
            semaphore.release();
        }
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
            HttpResponse<String> send = doRequest(sql, params);
            if (send.statusCode() == 200) {
                Map<String, Object> map = gson.fromJson(send.body(), Map.class);
                if (Objects.equals(map.get("success"), true)) {
                    List<?> results = (List<?>) map.get("results");
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
                LOGGER.log(Level.INFO, DaoLogContext.format(sql + " took " + (System.currentTimeMillis() - start) + "ms"));
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

    @Override
    public int update(String sql) throws SQLException {
        return this.webApiUpdate(sql, (Object[]) null);
    }

    @Override
    public int update(String sql, Object param) throws SQLException {
        return this.webApiUpdate(sql, param);
    }

    public int update(final String sql, final Object... params) throws SQLException {
        return this.webApiUpdate(sql, params);
    }

    private int webApiUpdate(String sql, Object... params) throws SQLException {
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> send = doRequest(sql, params);
            if (send.statusCode() == 200) {
                Map<String, Object> map = gson.fromJson(send.body(), Map.class);
                if (Objects.equals(map.get("success"), true)) {
                    return extractUpdateCount(map);
                }
            }
            throw new SQLException(send.body());
        } catch (IOException | InterruptedException e) {
            throw new SQLException(e);
        } finally {
            if (dev) {
                LOGGER.log(Level.INFO, DaoLogContext.format(sql + " took " + (System.currentTimeMillis() - start) + "ms"));
            }
        }
    }

    private int extractUpdateCount(Map<String, Object> response) throws SQLException {
        Object changes = response.get("changes");
        Object meta = response.get("meta");
        if (changes == null && meta instanceof Map) {
            changes = ((Map<?, ?>) meta).get("changes");
        }
        if (changes == null) {
            return 0;
        }
        if (changes instanceof Number) {
            return Math.max(0, ((Number) changes).intValue());
        }
        if (changes instanceof String) {
            try {
                return Math.max(0, Double.valueOf((String) changes).intValue());
            } catch (NumberFormatException e) {
                throw new SQLException("Invalid changes value: " + changes, e);
            }
        }
        throw new SQLException("Invalid changes value: " + changes);
    }

    /**
     * Entity reads preserve integer/decimal precision and require a complete result envelope.
     * Also used for SQLite/D1 INSERT ... RETURNING, so generated keys come from the same statement.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryEntityRows(String sql, Object... params) throws SQLException {
        Object results = entityResponse(sql, params).get("results");
        if (!(results instanceof List)) {
            throw new SQLException("WebAPI entity response is missing results");
        }
        for (Object row : (List<?>) results) {
            if (!(row instanceof Map)) { throw new SQLException("Invalid WebAPI entity row"); }
        }
        return (List<Map<String, Object>>) results;
    }

    /** Unlike the legacy API, absence of an affected-row count is not treated as zero. */
    public int updateEntity(String sql, Object... params) throws SQLException {
        Map<String, Object> response = entityResponse(sql, params);
        Object changes = response.get("changes");
        if (changes == null && response.get("meta") instanceof Map) {
            changes = ((Map<?, ?>) response.get("meta")).get("changes");
        }
        try {
            int count = new java.math.BigDecimal(Objects.requireNonNull(changes).toString()).intValueExact();
            if (count < 0) { throw new IllegalArgumentException("Negative changes"); }
            return count;
        } catch (RuntimeException e) {
            throw new SQLException("WebAPI entity response requires an exact affected-row count", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entityResponse(String sql, Object... params) throws SQLException {
        long start = System.currentTimeMillis();
        try {
            Object[] exactParams = params == null ? null : params.clone();
            if (exactParams != null) {
                for (int i = 0; i < exactParams.length; i++) {
                    Object value = exactParams[i];
                    if (value instanceof Long || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
                        // SQLite numeric column affinity restores the type without a JavaScript double round trip.
                        exactParams[i] = value.toString();
                    }
                }
            }
            HttpResponse<String> response = doRequest(sql, exactParams);
            if (response.statusCode() != 200) {
                throw new SQLException("WebAPI entity request failed: HTTP " + response.statusCode());
            }
            Map<String, Object> result = entityGson.fromJson(response.body(), Map.class);
            if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
                throw new SQLException("WebAPI entity statement was not acknowledged as successful");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("WebAPI entity request interrupted; reconcile before retrying", e);
        } catch (IOException | RuntimeException e) {
            throw new SQLException("WebAPI entity response unavailable or invalid; reconcile before retrying", e);
        } finally {
            if (dev) {
                LOGGER.log(Level.INFO, DaoLogContext.format(sql + " took " + (System.currentTimeMillis() - start) + "ms"));
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

    @Override
    public Integer getConnectActiveSize() {
        return connectSize.intValue();
    }

    @Override
    public Integer getConnectTotalSize() {
        if (connectSize.intValue() <= 0) {
            return 0;
        }
        return maximumPoolSize;
    }
}
