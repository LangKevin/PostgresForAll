package com.postgresforall.core.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight regex-based detection for SQL fragments the app special-cases
 * (transaction wrapping, smart connection switching). Pure string analysis.
 */
public final class DdlPredicates {
    private DdlPredicates() { }

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern NON_TX = Pattern.compile(
            "\\b(CREATE|DROP|ALTER)\\s+(DATABASE|TABLESPACE)\\b|"
                    + "\\bVACUUM\\b|"
                    + "\\bREINDEX\\s+(DATABASE|SYSTEM)\\b|"
                    + "\\bCLUSTER\\b(?:\\s+VERBOSE)?\\s*;|"
                    + "\\bCREATE\\s+INDEX\\s+CONCURRENTLY\\b|"
                    + "\\bDROP\\s+INDEX\\s+CONCURRENTLY\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_DATABASE = Pattern.compile(
            "^\\s*CREATE\\s+DATABASE\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_DATABASE_NAME = Pattern.compile(
            "\\bCREATE\\s+DATABASE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?(?<name>[A-Za-z_][A-Za-z0-9_]*)\"?",
            Pattern.CASE_INSENSITIVE);

    public static String stripComments(String sql) {
        if (sql == null || sql.isEmpty()) return sql;
        var noBlock = BLOCK_COMMENT.matcher(sql).replaceAll("");
        return LINE_COMMENT.matcher(noBlock).replaceAll("");
    }

    /**
     * Does this script contain any statement Postgres won't allow inside an
     * explicit transaction block (CREATE/DROP/ALTER DATABASE, VACUUM, etc.)?
     */
    public static boolean containsNonTransactionalStatements(String sql) {
        if (sql == null) return false;
        return NON_TX.matcher(stripComments(sql)).find();
    }

    /** Index of the first statement that starts with CREATE DATABASE, or -1. */
    public static int indexOfCreateDatabaseStatement(List<String> statements) {
        for (var i = 0; i < statements.size(); i++) {
            var s = statements.get(i);
            var cleaned = stripComments(s == null ? "" : s).stripLeading();
            if (CREATE_DATABASE.matcher(cleaned).find()) return i;
        }
        return -1;
    }

    /** The new database name from a CREATE DATABASE statement, or null. */
    public static String extractCreatedDatabaseName(String createDbStatement) {
        var cleaned = stripComments(createDbStatement == null ? "" : createDbStatement);
        var m = CREATE_DATABASE_NAME.matcher(cleaned);
        return m.find() ? m.group("name") : null;
    }
}
