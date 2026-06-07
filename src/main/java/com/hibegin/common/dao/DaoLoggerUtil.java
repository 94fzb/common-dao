package com.hibegin.common.dao;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DaoLoggerUtil {

    private DaoLoggerUtil() {
    }

    static Logger getLogger(Class<?> clazz) {
        Logger logger = Logger.getLogger(clazz.getName());
        logger.setLevel(Level.ALL);
        return logger;
    }

    static String recordStackTraceMsg(Throwable e) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        e.printStackTrace(writer);
        return stringWriter.getBuffer().toString();
    }
}
