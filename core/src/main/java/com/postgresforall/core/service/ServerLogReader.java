package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;

import java.sql.SQLException;
import java.util.Arrays;

/**
 * Reads the tail of the server's current log file over SQL
 * ({@code pg_current_logfile()} + {@code pg_read_file()}), so server logs work
 * the same for a native install, a container or a remote server. Needs a
 * superuser (or pg_read_server_files) and logging_collector = on; when either is
 * missing, {@link LogTail#text()} is null and {@link LogTail#reason()} says why.
 */
public final class ServerLogReader {
    private ServerLogReader() { }

    public record LogTail(String text, String file, String reason) { }

    public static LogTail readTail(ConnectionProfile profile, int lines, int maxBytes) {
        try (var conn = profile.open()) {
            String file;
            try (var st = conn.createStatement(); var r = st.executeQuery("SELECT pg_current_logfile()")) {
                file = r.next() ? r.getString(1) : null;
            }
            if (file == null || file.isEmpty()) {
                return new LogTail(null, null,
                        "the server isn't writing a log file (logging_collector is off — common for "
                                + "containers, which log to stdout instead)");
            }

            try (var st = conn.prepareStatement(
                    "SELECT pg_read_file(?, GREATEST((pg_stat_file(?)).size - ?, 0), ?)")) {
                st.setString(1, file);
                st.setString(2, file);
                st.setLong(3, maxBytes);
                st.setLong(4, maxBytes);
                try (var r = st.executeQuery()) {
                    var text = r.next() ? r.getString(1) : "";
                    return new LogTail(lastLines(text == null ? "" : text, lines), file, null);
                }
            }
        } catch (SQLException ex) {
            if ("42501".equals(ex.getSQLState())) {
                return new LogTail(null, null, "reading server log files needs a superuser login");
            }
            return new LogTail(null, null, ConnectionErrors.message(ex));
        }
    }

    /** The last {@code lines} lines of {@code text}. */
    public static String lastLines(String text, int lines) {
        var normalized = text.replace("\r\n", "\n");
        while (normalized.endsWith("\n")) normalized = normalized.substring(0, normalized.length() - 1);
        var all = normalized.split("\n", -1);
        var from = Math.max(0, all.length - lines);
        return String.join("\n", Arrays.asList(all).subList(from, all.length));
    }
}
