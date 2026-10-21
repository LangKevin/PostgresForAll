package com.postgresforall.core.service;

import org.postgresql.util.PSQLException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Locale;

/** Classifies connection failures so the UI can offer the right recovery. */
public final class ConnectionErrors {
    private ConnectionErrors() { }

    public enum Kind { UNREACHABLE, AUTH_FAILED, DATABASE_MISSING, DATABASE_OFFLINE, PERMISSION_DENIED, OTHER }

    public static Kind classify(Throwable ex) {
        for (var e = ex; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof ConnectException || e instanceof NoRouteToHostException
                    || e instanceof SocketTimeoutException || e instanceof UnknownHostException) {
                return Kind.UNREACHABLE;
            }
            if (e instanceof SQLException sql && sql.getSQLState() != null) {
                switch (sql.getSQLState()) {
                    case "28P01", "28000": return Kind.AUTH_FAILED;
                    case "3D000": return Kind.DATABASE_MISSING;
                    case "55000": return Kind.DATABASE_OFFLINE;
                    case "42501": return Kind.PERMISSION_DENIED;
                    case "08001", "08004", "08006":
                        if (e instanceof PSQLException && e.getCause() == null) return Kind.UNREACHABLE;
                        break;
                    default:
                        break;
                }
            }
        }
        var msg = message(ex).toLowerCase(Locale.ROOT);
        if (msg.contains("refused") || msg.contains("could not connect") || msg.contains("connection attempt failed")) {
            return Kind.UNREACHABLE;
        }
        return Kind.OTHER;
    }

    /**
     * The most useful message in a cause chain: the server's own text when there
     * is one, rather than a pool's "connection is not available" wrapper.
     */
    public static String message(Throwable ex) {
        if (ex == null) return "";
        for (var e = ex; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof PSQLException p && p.getServerErrorMessage() != null) {
                return p.getServerErrorMessage().getMessage();
            }
        }
        var deepest = ex;
        while (deepest.getCause() != null && deepest.getCause() != deepest
                && deepest instanceof SQLException && !(deepest instanceof PSQLException)) {
            deepest = deepest.getCause();
        }
        var m = deepest.getMessage();
        return m == null ? deepest.getClass().getSimpleName() : m;
    }

    /** The SQLSTATE anywhere in the cause chain, or null. */
    public static String sqlState(Throwable ex) {
        for (var e = ex; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }
}
