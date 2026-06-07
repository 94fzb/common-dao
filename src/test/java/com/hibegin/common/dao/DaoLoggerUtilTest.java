package com.hibegin.common.dao;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DaoLoggerUtilTest {

    @Test
    void getLoggerWritesToInjectedFileHandler() throws Exception {
        Path logFile = Files.createTempFile("common-dao", ".log");
        FileHandler fileHandler = new FileHandler(logFile.toString(), true);
        fileHandler.setFormatter(new SimpleFormatter());
        try {
            DaoLoggerUtil.initFileHandle(fileHandler);
            Logger logger = DaoLoggerUtil.getLogger(DaoLoggerUtilTest.class);
            logger.info("dao-file-handler-marker");
            fileHandler.flush();
            assertTrue(Files.readString(logFile).contains("dao-file-handler-marker"));
        } finally {
            DaoLoggerUtil.initFileHandle(null);
            fileHandler.close();
        }
    }
}
