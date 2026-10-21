package com.postgresforall.core.service;

import com.postgresforall.core.model.QueryResult;
import com.postgresforall.core.model.QueryResult.Column;
import com.postgresforall.core.model.QueryResult.Message;
import com.postgresforall.core.model.QueryResult.MessageSeverity;
import com.postgresforall.core.model.QueryResult.ResultTable;
import com.postgresforall.core.util.CancelToken;
import com.postgresforall.core.util.CancelToken.CancelledException;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLWarning;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs a script statement by statement on one connection: optional
 * BEGIN/COMMIT wrapping, every result set captured, NOTICE / WARNING
 * collected, first error stops the run (and rolls back when wrapped).
 */
public final class QueryExecutor {
    private final ConnectionManager manager;

    public QueryExecutor(ConnectionManager manager) {
        this.manager = manager;
    }

    public QueryResult execute(String script, boolean wrapInTransaction, CancelToken ct) {
        return execute(ScriptSplitter.split(script), wrapInTransaction, ct);
    }

    public QueryResult execute(List<String> statements, boolean wrapInTransaction, CancelToken ct) {
        var started = System.nanoTime();
        try (var conn = manager.openConnection()) {
            return executeOnConnection(conn, statements, wrapInTransaction, ct);
        } catch (SQLException ex) {
            return new QueryResult(List.of(),
                    List.of(new Message(MessageSeverity.ERROR, ConnectionErrors.message(ex))),
                    elapsedMs(started), 0);
        }
    }

    /**
     * Like {@link #execute} but if the script contains a CREATE DATABASE, that
     * statement runs against the 'postgres' maintenance database (no transaction
     * allowed) and the statements after it run against the new database. Lets a
     * full "create + populate" script run from one editor tab.
     */
    public QueryResult smartExecute(String script, boolean wrapInTransaction, CancelToken ct) {
        var statements = ScriptSplitter.split(script);
        var createIdx = DdlPredicates.indexOfCreateDatabaseStatement(statements);
        if (createIdx < 0) return execute(statements, wrapInTransaction, ct);

        var newDbName = DdlPredicates.extractCreatedDatabaseName(statements.get(createIdx));
        if (newDbName == null) return execute(statements, wrapInTransaction, ct);

        var sets = new ArrayList<ResultTable>();
        var messages = new ArrayList<Message>();
        var started = System.nanoTime();
        var executed = 0;

        // Phase 1 — statements before the CREATE DATABASE, on this manager's connection.
        if (createIdx > 0) {
            var r = execute(statements.subList(0, createIdx), wrapInTransaction, ct);
            sets.addAll(r.resultSets());
            messages.addAll(r.messages());
            executed += r.statementsExecuted();
            if (r.hasErrors()) return new QueryResult(sets, messages, elapsedMs(started), executed);
        }

        // Phase 2 — the CREATE DATABASE itself, on the maintenance database, no transaction.
        messages.add(new Message(MessageSeverity.INFO,
                "-- Smart-execute: routing CREATE DATABASE to maintenance database 'postgres' "
                        + "(no transaction, as required by Postgres)."));
        try (var maint = manager.profile().withDatabase("postgres").open();
             var st = maint.createStatement();
             var ignored = ct.register(() -> cancelQuietly(st))) {
            st.execute(statements.get(createIdx));
            executed++;
            messages.add(new Message(MessageSeverity.INFO, "CREATE DATABASE \"" + newDbName + "\" — OK.", createIdx));
        } catch (SQLException ex) {
            messages.add(new Message(MessageSeverity.ERROR, "CREATE DATABASE failed: " + ConnectionErrors.message(ex),
                    createIdx));
            return new QueryResult(sets, messages, elapsedMs(started), executed);
        }

        // Phase 3 — everything after CREATE DATABASE, against the newly created database.
        var after = statements.subList(createIdx + 1, statements.size());
        if (!after.isEmpty()) {
            messages.add(new Message(MessageSeverity.INFO,
                    "-- Smart-execute: switching connection to '" + newDbName + "' to run the remaining "
                            + after.size() + " statement(s)."));
            try (var newConn = manager.profile().withDatabase(newDbName).open()) {
                var phase = executeOnConnection(newConn, after, wrapInTransaction, ct);
                sets.addAll(phase.resultSets());
                messages.addAll(phase.messages());
                executed += phase.statementsExecuted();
            } catch (SQLException ex) {
                messages.add(new Message(MessageSeverity.ERROR,
                        "Failed connecting to new database '" + newDbName + "': " + ConnectionErrors.message(ex)));
            }
        }

        return new QueryResult(sets, messages, elapsedMs(started), executed);
    }

    // ---- Per-connection runner ---------------------------------------------

    /** Thrown internally to stop after a statement error has been recorded. */
    private static final class StatementFailed extends Exception {
        StatementFailed() {
            super(null, null, false, false);
        }
    }

    static QueryResult executeOnConnection(Connection conn, List<String> statements, boolean wrapInTransaction,
                                           CancelToken ct) {
        var sets = new ArrayList<ResultTable>();
        var messages = new ArrayList<Message>();
        var started = System.nanoTime();
        var executed = 0;
        var inTx = false;

        try {
            if (wrapInTransaction) {
                conn.setAutoCommit(false);
                inTx = true;
            }

            for (var idx = 0; idx < statements.size(); idx++) {
                ct.throwIfCancelled();
                var sql = statements.get(idx);
                if (sql == null || sql.isBlank()) continue;

                try (var st = conn.createStatement();
                     var ignored = ct.register(() -> cancelQuietly(st))) {
                    try {
                        var isResultSet = st.execute(sql);
                        var first = true;
                        while (true) {
                            if (isResultSet) {
                                try (var rs = st.getResultSet()) {
                                    sets.add(readTable(rs, sql, idx, ct));
                                }
                            } else {
                                var count = st.getUpdateCount();
                                if (count == -1) break;
                                if (first && reportsRowCount(sql, count)) {
                                    messages.add(new Message(MessageSeverity.INFO, count + " row(s) affected.", idx));
                                }
                            }
                            first = false;
                            isResultSet = st.getMoreResults();
                        }
                        drainWarnings(st.getWarnings(), messages);
                        executed++;
                    } catch (SQLException ex) {
                        drainWarnings(st.getWarnings(), messages);
                        if (ct.isCancelled()) throw new CancelledException();
                        messages.add(new Message(MessageSeverity.ERROR, formatError(ex), idx));
                        throw new StatementFailed();
                    }
                }
                drainWarnings(conn.getWarnings(), messages);
                conn.clearWarnings();
            }

            if (inTx) conn.commit();
        } catch (CancelledException ex) {
            rollbackQuietly(conn, inTx);
            messages.add(new Message(MessageSeverity.WARNING, "Query cancelled."));
        } catch (StatementFailed ex) {
            rollbackQuietly(conn, inTx);
        } catch (SQLException | RuntimeException ex) {
            rollbackQuietly(conn, inTx);
            messages.add(new Message(MessageSeverity.ERROR, ConnectionErrors.message(ex)));
        } finally {
            if (inTx) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        }

        return new QueryResult(sets, messages, elapsedMs(started), executed);
    }

    private static ResultTable readTable(ResultSet rs, String sql, int idx, CancelToken ct) throws SQLException {
        var meta = rs.getMetaData();
        var n = meta.getColumnCount();
        var columns = new ArrayList<Column>(n);
        for (var c = 1; c <= n; c++) columns.add(new Column(meta.getColumnLabel(c), meta.getColumnTypeName(c)));

        var rows = new ArrayList<Object[]>();
        while (rs.next()) {
            if ((rows.size() & 1023) == 0) ct.throwIfCancelled();
            var row = new Object[n];
            for (var c = 1; c <= n; c++) row[c - 1] = readValue(rs, c);
            rows.add(row);
        }
        return new ResultTable(columns, rows, null, sql, idx);
    }

    /** Cell value as something a grid can show and sort: driver wrapper types become text. */
    static Object readValue(ResultSet rs, int c) throws SQLException {
        var v = rs.getObject(c);
        if (v == null) return null;
        if (v instanceof PGobject pg) return pg.getValue();
        if (v instanceof Array || v instanceof SQLXML || v instanceof Map<?, ?>) return rs.getString(c);
        return v;
    }

    private static final Pattern DML = Pattern.compile("^\\s*(INSERT|UPDATE|DELETE|MERGE|COPY)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * "N row(s) affected." only for statements that have a row count. The driver
     * reports 0 for DDL too, which would be noise.
     */
    private static boolean reportsRowCount(String sql, int count) {
        var cleaned = DdlPredicates.stripComments(sql);
        if (DML.matcher(cleaned).find()) return true;
        return count > 0 && cleaned.stripLeading().toUpperCase(Locale.ROOT).startsWith("WITH");
    }

    private static void drainWarnings(SQLWarning w, List<Message> messages) {
        for (; w != null; w = w.getNextWarning()) {
            var severity = "NOTICE";
            var text = w.getMessage();
            if (w instanceof PSQLWarning pw && pw.getServerErrorMessage() != null) {
                var sem = pw.getServerErrorMessage();
                if (sem.getSeverity() != null) severity = sem.getSeverity().toUpperCase(Locale.ROOT);
                if (sem.getMessage() != null) text = sem.getMessage();
            }
            var sev = switch (severity) {
                case "WARNING" -> MessageSeverity.WARNING;
                case "INFO" -> MessageSeverity.INFO;
                default -> MessageSeverity.NOTICE;
            };
            messages.add(new Message(sev, text));
        }
    }

    /** "[SQLSTATE] message" plus DETAIL / HINT lines when the server sent them. */
    static String formatError(SQLException ex) {
        if (ex instanceof PSQLException p && p.getServerErrorMessage() != null) {
            var sem = p.getServerErrorMessage();
            var sb = new StringBuilder("[").append(sem.getSQLState()).append("] ").append(sem.getMessage());
            if (sem.getDetail() != null) sb.append("\nDETAIL: ").append(sem.getDetail());
            if (sem.getHint() != null) sb.append("\nHINT: ").append(sem.getHint());
            return sb.toString();
        }
        var state = ex.getSQLState();
        return state == null ? ConnectionErrors.message(ex) : "[" + state + "] " + ConnectionErrors.message(ex);
    }

    private static void rollbackQuietly(Connection conn, boolean inTx) {
        if (!inTx) return;
        try { conn.rollback(); } catch (SQLException ignored) { }
    }

    private static void cancelQuietly(Statement st) {
        try { st.cancel(); } catch (SQLException ignored) { }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
