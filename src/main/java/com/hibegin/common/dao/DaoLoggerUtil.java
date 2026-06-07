package com.hibegin.common.dao;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class DaoLoggerUtil {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
    private static final String LOG_FILE_SUFFIX = ".log";
    private static final Lock LOAD_LOCK = new ReentrantLock();
    private static final Set<Logger> LOGGERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static FileHandler fileHandler;
    private static boolean ownsFileHandler;
    private static boolean fileHandlerInitialized;

    private DaoLoggerUtil() {
    }

    public static FileHandler buildFileHandle() {
        if (!isLoggingToFileEnabled()) {
            return null;
        }
        try {
            File fileName = new File(getLogFilePath());
            File parentFile = fileName.getParentFile();
            if (parentFile != null && !parentFile.exists()) {
                parentFile.mkdirs();
            }
            FileHandler handler = new FileHandler(fileName.toString(), true);
            handler.setFormatter(new SimpleFormatter());
            return handler;
        } catch (IOException e) {
            Logger.getLogger(DaoLoggerUtil.class.getName()).log(Level.SEVERE, "Init dao logger error", e);
            return null;
        }
    }

    public static void initFileHandle(FileHandler newFileHandler) {
        LOAD_LOCK.lock();
        try {
            FileHandler previousFileHandler = fileHandler;
            boolean closePrevious = ownsFileHandler && previousFileHandler != null && previousFileHandler != newFileHandler;
            fileHandler = newFileHandler;
            ownsFileHandler = false;
            fileHandlerInitialized = true;
            for (Logger logger : LOGGERS) {
                replaceHandler(logger, previousFileHandler, newFileHandler);
            }
            if (closePrevious) {
                previousFileHandler.close();
            }
        } finally {
            LOAD_LOCK.unlock();
        }
    }

    public static FileHandler getFileHandler() {
        return fileHandler;
    }

    public static Logger getLogger(Class<?> clazz) {
        LOAD_LOCK.lock();
        try {
            Logger logger = Logger.getLogger(clazz.getName());
            LOGGERS.add(logger);
            if (!fileHandlerInitialized) {
                fileHandler = buildFileHandle();
                ownsFileHandler = fileHandler != null;
                fileHandlerInitialized = true;
            }
            if (fileHandler != null && Arrays.stream(logger.getHandlers()).noneMatch(handler -> Objects.equals(handler, fileHandler))) {
                logger.addHandler(fileHandler);
            }
            logger.setLevel(Level.ALL);
            return logger;
        } finally {
            LOAD_LOCK.unlock();
        }
    }

    public static String recordStackTraceMsg(Throwable e) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        e.printStackTrace(writer);
        return stringWriter.getBuffer().toString();
    }

    private static String getLogFilePath() {
        return getLogDir() + File.separatorChar + SDF.format(new Date()) + LOG_FILE_SUFFIX;
    }

    private static String getLogDir() {
        String configuredLogDir = firstNonBlank(
                System.getProperty("common.dao.log.dir"),
                System.getenv("COMMON_DAO_LOG_DIR"),
                System.getProperty("sws.log.path")
        );
        if (configuredLogDir != null) {
            return configuredLogDir;
        }
        return System.getProperty("user.dir", ".") + File.separatorChar + "log";
    }

    private static boolean isLoggingToFileEnabled() {
        String enabled = firstNonBlank(System.getProperty("common.dao.log.file"), System.getenv("COMMON_DAO_LOG_FILE"));
        return enabled == null || Boolean.parseBoolean(enabled);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static void replaceHandler(Logger logger, FileHandler previousFileHandler, FileHandler newFileHandler) {
        if (previousFileHandler != null) {
            logger.removeHandler(previousFileHandler);
        }
        if (newFileHandler == null || Arrays.stream(logger.getHandlers()).anyMatch(handler -> Objects.equals(handler, newFileHandler))) {
            return;
        }
        logger.addHandler(newFileHandler);
    }
}
