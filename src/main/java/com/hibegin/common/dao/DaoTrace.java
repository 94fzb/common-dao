package com.hibegin.common.dao;

import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;

public final class DaoTrace {

    private static final String TRACE_PROPERTY = "common.dao.trace";
    private static final String TRACE_ENV = "COMMON_DAO_TRACE";
    private static final String LEGACY_TRACE_PROPERTY = "zrlog.plugin.dao.trace";
    private static final String LEGACY_TRACE_ENV = "ZRLOG_PLUGIN_DAO_TRACE";

    private DaoTrace() {
    }

    public static void info(Logger logger, String action, String message) {
        if (!enabled()) {
            return;
        }
        String suffix = message == null || message.trim().isEmpty() ? "" : " " + message;
        logger.info(DaoLogContext.format("[dao-trace] " + action + suffix + " caller=" + caller()));
    }

    public static String valueSummary(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence) {
            return value.getClass().getSimpleName() + "(len=" + value.toString().length() + ")";
        }
        if (value instanceof Collection) {
            return value.getClass().getSimpleName() + "(size=" + ((Collection<?>) value).size() + ")";
        }
        if (value instanceof Map) {
            return value.getClass().getSimpleName() + "(size=" + ((Map<?, ?>) value).size() + ")";
        }
        return value.getClass().getSimpleName();
    }

    public static String keysSummary(Collection<String> keys) {
        if (keys == null) {
            return "keys=null";
        }
        return "keys(size=" + keys.size() + ")=" + keys;
    }

    public static boolean enabled() {
        return isDevMode()
                || Boolean.getBoolean(TRACE_PROPERTY)
                || Boolean.getBoolean(LEGACY_TRACE_PROPERTY)
                || "true".equalsIgnoreCase(System.getenv(TRACE_ENV))
                || "true".equalsIgnoreCase(System.getenv(LEGACY_TRACE_ENV));
    }

    private static boolean isDevMode() {
        return "true".equalsIgnoreCase(System.getenv("DEBUG_MODE"))
                || "true".equalsIgnoreCase(System.getenv("DEV_MODE"))
                || "debug".equalsIgnoreCase(System.getProperty("sws.run.mode"))
                || "dev".equalsIgnoreCase(System.getProperty("sws.run.mode"));
    }

    private static String caller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (skipCaller(className)) {
                continue;
            }
            return simpleName(className) + "." + element.getMethodName() + ":" + element.getLineNumber();
        }
        return "unknown";
    }

    private static boolean skipCaller(String className) {
        return className.equals(Thread.class.getName())
                || className.equals(DaoTrace.class.getName())
                || className.startsWith("com.hibegin.common.dao.")
                || className.endsWith("DAO")
                || className.endsWith("Store")
                || className.startsWith("java.");
    }

    private static String simpleName(String className) {
        int index = className.lastIndexOf('.');
        if (index < 0 || index + 1 >= className.length()) {
            return className;
        }
        return className.substring(index + 1);
    }
}
