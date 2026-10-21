package com.postgresforall.core.service;

import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.OperationEvent.Severity;
import com.postgresforall.core.util.CancelToken;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a change set safely: all transactional statements in ONE transaction
 * with {@code SET LOCAL lock_timeout} (so a busy table makes it fail fast rather
 * than queue and block others), rolling back everything on the first error.
 * Non-transactional statements (… CONCURRENTLY) then run one by one in
 * autocommit with the same lock_timeout. A dry run executes the transactional
 * part and rolls back, skipping (and reporting) non-transactional statements.
 */
public final class DdlApplier {
    public static final String DEFAULT_LOCK_TIMEOUT = "5s";

    static final String LOCK_NOT_AVAILABLE = "55P03";

    private final ConnectionManager manager;

    public DdlApplier(ConnectionManager manager) {
        this.manager = manager;
    }

    /**
     * @param failedStatement the statement that failed (null on success)
     * @param sqlState        SQLSTATE of the failure (e.g. 55P03 lock_not_available), or null
     */
    public record Result(boolean success, String message, String sqlState, DdlStatement failedStatement) { }

    public Result apply(List<DdlStatement> statements, boolean dryRun, Consumer<OperationEvent> emit, CancelToken ct) {
        Consumer<OperationEvent> out = emit == null ? e -> { } : emit;
        var token = ct == null ? new CancelToken() : ct;
        var tx = statements.stream().filter(DdlStatement::transactional).toList();
        var nonTx = statements.stream().filter(s -> !s.transactional()).toList();
        if (statements.isEmpty()) {
            out.accept(new OperationEvent(Severity.INFO, "Nothing to change."));
            return new Result(true, "Nothing to change.", null, null);
        }

        try (var conn = manager.openConnection()) {
            var autoCommit = conn.getAutoCommit();
            try {
                if (!tx.isEmpty()) {
                    var r = runTransaction(conn, tx, dryRun, out, token);
                    if (r != null) return r;
                }
                if (!nonTx.isEmpty()) {
                    if (dryRun) {
                        for (var s : nonTx) {
                            out.accept(new OperationEvent(Severity.WARN,
                                    "Skipped in dry run (cannot run inside a transaction): " + s.sql()));
                        }
                    } else {
                        var r = runAutocommit(conn, nonTx, out, token);
                        if (r != null) return r;
                    }
                }
            } finally {
                try { conn.setAutoCommit(autoCommit); } catch (SQLException ignored) { }
            }
        } catch (SQLException ex) {
            var msg = "Could not connect: " + ConnectionErrors.message(ex);
            out.accept(new OperationEvent(Severity.ERROR, msg));
            return new Result(false, msg, ex.getSQLState(), null);
        } catch (CancelToken.CancelledException ex) {
            out.accept(new OperationEvent(Severity.WARN, "Cancelled. Nothing changed."));
            return new Result(false, "Cancelled.", null, null);
        }

        var msg = dryRun
                ? "Dry run succeeded — all changes were rolled back."
                : statements.size() == 1 ? "Change applied." : statements.size() + " statements applied.";
        out.accept(new OperationEvent(Severity.SUCCESS, msg));
        return new Result(true, msg, null, null);
    }

    /** Returns a failure result, or null when the transaction committed (or rolled back for a dry run). */
    private Result runTransaction(Connection conn, List<DdlStatement> tx, boolean dryRun,
                                  Consumer<OperationEvent> out, CancelToken token) throws SQLException {
        conn.setAutoCommit(false);
        DdlStatement current = null;
        try (var st = conn.createStatement(); var reg = token.register(() -> cancelQuietly(st))) {
            st.execute("SET LOCAL lock_timeout = '" + DEFAULT_LOCK_TIMEOUT + "'");
            for (var s : tx) {
                token.throwIfCancelled();
                current = s;
                out.accept(new OperationEvent(Severity.INFO, s.sql()));
                st.execute(s.sql());
            }
            token.throwIfCancelled();
            if (dryRun) {
                conn.rollback();
                out.accept(new OperationEvent(Severity.INFO, "Dry run: rolled back."));
            } else {
                conn.commit();
            }
            return null;
        } catch (SQLException ex) {
            rollbackQuietly(conn);
            if (token.isCancelled()) {
                out.accept(new OperationEvent(Severity.WARN, "Cancelled. Nothing changed."));
                return new Result(false, "Cancelled. Nothing changed.", ex.getSQLState(), current);
            }
            return failure(ex, current, "Nothing changed — the whole change was rolled back.", out);
        } catch (RuntimeException ex) {
            rollbackQuietly(conn);
            throw ex;
        }
    }

    private Result runAutocommit(Connection conn, List<DdlStatement> stmts, Consumer<OperationEvent> out,
                                 CancelToken token) throws SQLException {
        conn.setAutoCommit(true);
        DdlStatement current = null;
        try (var st = conn.createStatement(); var reg = token.register(() -> cancelQuietly(st))) {
            st.execute("SET lock_timeout = '" + DEFAULT_LOCK_TIMEOUT + "'");
            try {
                for (var s : stmts) {
                    token.throwIfCancelled();
                    current = s;
                    out.accept(new OperationEvent(Severity.INFO, s.sql()));
                    st.execute(s.sql());
                }
                return null;
            } catch (SQLException ex) {
                if (token.isCancelled()) {
                    out.accept(new OperationEvent(Severity.WARN, "Cancelled."));
                    return new Result(false, "Cancelled.", ex.getSQLState(), current);
                }
                return failure(ex, current, "Statements before this one (if any) were applied.", out);
            } finally {
                // Pooled connection: don't leak the session setting.
                try { st.execute("RESET lock_timeout"); } catch (SQLException ignored) { }
            }
        }
    }

    private static Result failure(SQLException ex, DdlStatement failed, String tail, Consumer<OperationEvent> out) {
        var state = ex.getSQLState();
        String msg;
        if (LOCK_NOT_AVAILABLE.equals(state)) {
            msg = "The table is busy (another session holds a lock on "
                    + (failed == null ? "it" : failed.lockedTable()) + "). " + tail + " Try again later.";
        } else {
            msg = QueryExecutor.formatError(ex) + "\n" + tail;
        }
        out.accept(new OperationEvent(Severity.ERROR, msg));
        return new Result(false, msg, state, failed);
    }

    private static void cancelQuietly(java.sql.Statement st) {
        try { st.cancel(); } catch (SQLException ignored) { }
    }

    private static void rollbackQuietly(Connection conn) {
        try { conn.rollback(); } catch (SQLException ignored) { }
    }
}
