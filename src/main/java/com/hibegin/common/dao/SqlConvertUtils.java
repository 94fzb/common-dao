package com.hibegin.common.dao;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlConvertUtils {

    public static List<String> extractExecutableSqlByInputStream(InputStream inputStream) {
        return extractExecutableSql(inputStreamToString(inputStream));
    }

    public static List<String> extractExecutableSql(String sql) {
        String[] sqlArr = sql.split("\n");
        StringBuilder tempSqlStr = new StringBuilder();
        List<String> sqlList = new ArrayList<>();
        for (String sqlSt : sqlArr) {
            if (sqlSt.startsWith("#") || sqlSt.startsWith("/*")) {
                continue;
            }
            if (sqlSt.startsWith("--")) {
                continue;
            }
            if (sqlSt.startsWith("USE")) {
                continue;
            }
            if (sqlSt.startsWith("CREATE DATABASE")) {
                continue;
            }
            if (sqlSt.startsWith("LOCK TABLES")) {
                continue;
            }
            if (sqlSt.startsWith("UNLOCK TABLES")) {
                continue;
            }
            tempSqlStr.append(sqlSt).append("\n");
        }
        String[] cleanSql = tempSqlStr.toString().split(";\n");
        for (String sqlSt : cleanSql) {
            if (sqlSt == null || sqlSt.trim().isEmpty()) {
                continue;
            }
            String[] split = sqlSt.split("\n");
            StringBuilder sb = new StringBuilder();
            for (String t : split) {
                if (t == null || t.trim().isEmpty()) {
                    continue;
                }
                sb.append(t);
            }
            sqlList.add(sb.toString());
        }
        return sqlList;
    }

    public static List<String> doMySQLToSqliteBySqlText(String rawSql) {
        List<String> result = new ArrayList<>();
        for (String sqlSt : SqlConvertUtils.extractExecutableSql(rawSql)) {
            String cleanText = sqlSt
                    .replace("_binary '\u0001'", "true")
                    .replaceAll("FOREIGN KEY \\(`?(\\w+)`\\) REFERENCES `?(\\w+)` \\(`?(\\w+)`\\)", "")
                    .replace("_binary '\u0001'", "true")
                    .replace("_binary '\u0000'", "false")
                    .replace("_binary '\\0'", "false")
                    .replace("_binary '\\1'", "true")
                    .replace("_binary '\\x00'", "false")
                    .replace("_binary '\\x01'", "true")
                    .replace("ENGINE=InnoDB", "")
                    .replace("DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci", "")
                    .replaceAll("UNIQUE KEY `?(\\w+)` \\(([^)]+)\\)", "UNIQUE($2)")
                    .replaceAll("KEY\\s+`?(\\w+)`\\s+\\(`?(\\w+)`\\),", "")
                    .replaceAll("KEY\\s+`?(\\w+)`\\s+\\(`?(\\w+)`\\)", "")
                    .replaceAll("bit\\(1\\)", "BOOLEAN")
                    .replace("DEFAULT b'1'", "")
                    .replace("DEFAULT b'0'", "")
                    .replaceAll("PRIMARY\\s+KEY\\s*\\(\\s*`?(\\w+)`?\\s*\\),", "")
                    .replaceAll("PRIMARY\\s+KEY\\s*\\(\\s*`?(\\w+)`?\\s*\\)", "")
                    .replace("int(11) NOT NULL AUTO_INCREMENT", "INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT")
                    .replaceAll("COMMENT\\s*'([^']*)'", "")
                    .replace("DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci", "")
                    .replaceAll("\\s+DEFAULT\\s+CHARSET=utf8", "")
                    .replaceAll("AUTO_INCREMENT=\\w+", "")
                    .replaceAll("COLLATE=utf8mb4_unicode_ci", "")
                    .replaceAll("CONSTRAINT `?(\\w+)`?\\s*,", "")
                    .replaceAll("CONSTRAINT `?(\\w+)`", "")
                    .replaceAll(",\\s+\\)", ")");

            if (cleanText.startsWith("INSERT INTO")) {
                result.addAll(splitInsertStatements(cleanText));
            } else {
                result.add(cleanText);
            }
        }
        return result;
    }

    public static List<Object> extractValues(String sql) {
        List<Object> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("VALUES\\s*\\((.+)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            return result;
        }
        String valuesPart = matcher.group(1);
        List<String> tokens = splitSqlValues(valuesPart);
        for (String token : tokens) {
            token = token.trim();
            if (token.equalsIgnoreCase("null")) {
                result.add(null);
            } else if (token.equalsIgnoreCase("true") || token.equalsIgnoreCase("false")) {
                result.add(Boolean.parseBoolean(token));
            } else if (token.startsWith("'") && token.endsWith("'")) {
                String unescaped = token.substring(1, token.length() - 1)
                        .replace("\\'", "'")
                        .replace("\\\\", "\\")
                        .replace("\\r", "\r")
                        .replace("\\n", "\n");
                result.add(unescaped);
            } else {
                try {
                    if (token.contains(".")) {
                        result.add(Double.parseDouble(token));
                    } else {
                        result.add(Long.parseLong(token));
                    }
                } catch (NumberFormatException e) {
                    result.add(token);
                }
            }
        }
        return result;
    }

    private static List<String> splitSqlValues(String input) {
        List<String> tokens = new ArrayList<>();
        boolean inQuote = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && (i == 0 || input.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            }
            if (c == ',' && !inQuote) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        tokens.add(current.toString());
        return tokens;
    }

    public static List<String> splitInsertStatements(String sql) {
        List<String> result = new ArrayList<>();
        int valuesIndex = sql.toUpperCase().indexOf("VALUES");
        if (valuesIndex == -1) {
            throw new IllegalArgumentException("SQL must contain VALUES");
        }
        String prefix = sql.substring(0, valuesIndex + "VALUES".length());
        String valuesPart = sql.substring(valuesIndex + "VALUES".length()).trim();
        if (valuesPart.endsWith(";")) {
            valuesPart = valuesPart.substring(0, valuesPart.length() - 1);
        }
        boolean inString = false;
        boolean escaping = false;
        int parenDepth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < valuesPart.length(); i++) {
            char c = valuesPart.charAt(i);
            current.append(c);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
            } else if (c == '\'') {
                inString = !inString;
            } else if (!inString) {
                if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth--;
                    if (parenDepth == 0) {
                        String insertSql = (prefix + " " + current.toString().trim())
                                .replace("\\'", "\"")
                                .replace("\\\"", "\"");
                        result.add(insertSql);
                        current.setLength(0);
                        while (i + 1 < valuesPart.length() &&
                                (valuesPart.charAt(i + 1) == ',' || Character.isWhitespace(valuesPart.charAt(i + 1)))) {
                            i++;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static String inputStreamToString(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Read SQL input stream failed", e);
        }
    }
}
