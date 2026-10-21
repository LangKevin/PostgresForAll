package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.OperationEvent.Severity;
import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.platform.StreamingProcessRunner;
import com.postgresforall.core.util.CancelToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Server-level operations against one server: drop, take offline / bring
 * online, create, and backup / restore through the host's pg_dump / pg_restore.
 * Long operations report progress through an {@link OperationEvent} sink.
 */
public final class DatabaseOperations {
    private final ConnectionManager manager;
    private final ProcessRunner runner;
    private final StreamingProcessRunner streamingRunner;
    private final Function<CancelToken, PgClientTools> locateTools;
    private volatile PgClientTools tools;
    private volatile boolean toolsLocated;

    public DatabaseOperations(ConnectionManager manager) {
        this(manager, null, null, null);
    }

    /**
     * @param locateTools finds pg_dump / pg_restore on the host. Defaults to
     *                    {@link PgClientTools#locate} honouring the config's PgBinPath.
     *                    Located once, on first use.
     */
    public DatabaseOperations(ConnectionManager manager, ProcessRunner runner,
                              StreamingProcessRunner streamingRunner,
                              Function<CancelToken, PgClientTools> locateTools) {
        this.manager = manager;
        var def = new DefaultProcessRunner();
        this.runner = runner != null ? runner : def;
        this.streamingRunner = streamingRunner != null ? streamingRunner : def;
        this.locateTools = locateTools != null ? locateTools
                : ct -> PgClientTools.locate(AppConfig.current().pgBinPath, this.runner, PgToolSearch.DEFAULT, ct);
    }

    /** The host's pg_dump / pg_restore, or null when none are installed. */
    public PgClientTools getClientTools(CancelToken ct) {
        if (!toolsLocated) {
            synchronized (this) {
                if (!toolsLocated) {
                    tools = locateTools.apply(ct);
                    toolsLocated = true;
                }
            }
        }
        return tools;
    }

    /**
     * Why backup / restore can't run against this server, or null when they can:
     * no client tools, or tools older than the server.
     */
    public String clientToolsProblem() {
        var t = getClientTools(CancelToken.NONE);
        if (t == null) {
            return "PostgreSQL client tools (pg_dump / pg_restore) weren't found. Install the PostgreSQL "
                    + "command-line tools or set \"PgBinPath\" in postgresconnect.config.json.";
        }
        var serverMajor = PgClientTools.parseMajorVersion(manager.serverVersion());
        if (serverMajor != null && !t.supportsServer(serverMajor)) {
            return "The installed client tools are version " + t.majorVersion() + ", older than this PostgreSQL "
                    + serverMajor + " server. Install version " + serverMajor + "+ or point PgBinPath at one.";
        }
        return null;
    }

    /**
     * Client tools able to talk to this server, emitting an actionable error
     * (install hint / version mismatch) and returning null otherwise.
     */
    private PgClientTools requireClientTools(Consumer<OperationEvent> emit, CancelToken ct) {
        var t = getClientTools(ct);
        if (t == null) {
            emit.accept(new OperationEvent(Severity.ERROR,
                    "PostgreSQL client tools (pg_dump / pg_restore) were not found on this machine. "
                            + installHint() + " Or set \"PgBinPath\" in postgresconnect.config.json to their bin folder."));
            return null;
        }
        var serverMajor = PgClientTools.parseMajorVersion(manager.serverVersion());
        if (serverMajor != null && !t.supportsServer(serverMajor)) {
            emit.accept(new OperationEvent(Severity.ERROR,
                    "The client tools in " + t.binDirectory() + " are version " + t.majorVersion()
                            + ", but the server is PostgreSQL " + serverMajor + ". pg_dump / pg_restore must be at least "
                            + "the server's version — install a newer version or point PgBinPath at one."));
            return null;
        }
        emit.accept(new OperationEvent(Severity.INFO,
                "Using PostgreSQL " + t.majorVersion() + " client tools from " + t.binDirectory()));
        return t;
    }

    private static String installHint() {
        return switch (com.postgresforall.core.platform.Platform.current()) {
            case WINDOWS -> "Install the PostgreSQL command-line tools (the EDB installer's \"Command Line Tools\" "
                    + "component is enough).";
            case MAC -> "Install them with Homebrew (brew install libpq, or brew install postgresql@17), "
                    + "Postgres.app, or the EDB installer.";
            case LINUX -> "Install your distribution's postgresql-client package.";
        };
    }

    private ConnectionProfile admin() {
        return manager.profile().withDatabase("postgres");
    }

    // ---- Drop ---------------------------------------------------------------

    public boolean dropDatabase(String dbName, Consumer<OperationEvent> emit, CancelToken ct) {
        if (dbName.equals(manager.profile().database)) {
            emit.accept(new OperationEvent(Severity.WARN,
                    "'" + dbName + "' is your active database. WITH (FORCE) will terminate "
                            + "all connections to it (including this app's pool), then drop the "
                            + "database. You'll need to reconnect via the Login window afterward."));
        }

        emit.accept(new OperationEvent(Severity.INFO,
                "Connecting to maintenance database 'postgres' as " + manager.profile().username + "..."));

        var started = System.nanoTime();
        long sizeBytes = 0;
        int activeBefore = 0;
        try (var conn = admin().open()) {
            try (var st = conn.prepareStatement(
                    "SELECT pg_database_size(?), (SELECT count(*) FROM pg_stat_activity WHERE datname = ?)")) {
                st.setString(1, dbName);
                st.setString(2, dbName);
                try (var r = st.executeQuery()) {
                    if (r.next()) {
                        sizeBytes = r.getLong(1);
                        activeBefore = r.getInt(2);
                    }
                }
            } catch (SQLException ignored) {
                // size is informational
            }

            emit.accept(new OperationEvent(Severity.INFO,
                    "Before drop — size: " + formatBytes(sizeBytes) + ", active sessions: " + activeBefore));
            emit.accept(new OperationEvent(Severity.INFO, "Issuing DROP DATABASE \"" + dbName + "\" WITH (FORCE)..."));

            try (var st = conn.createStatement(); var ignored = ct.register(() -> cancelQuietly(st))) {
                st.setQueryTimeout(120);
                st.execute("DROP DATABASE " + quoteIdentifier(dbName) + " WITH (FORCE)");
            }

            emit.accept(new OperationEvent(Severity.SUCCESS,
                    "Dropped '" + dbName + "' — freed " + formatBytes(sizeBytes) + " on disk, terminated "
                            + activeBefore + " session(s), elapsed " + ms(started) + " ms."));
            return true;
        } catch (SQLException ex) {
            emit.accept(new OperationEvent(Severity.ERROR,
                    "DROP failed after " + ms(started) + " ms: " + ConnectionErrors.message(ex)));
            return false;
        }
    }

    // ---- Bring online / take offline ----------------------------------------

    public boolean bringOnline(String dbName, Consumer<OperationEvent> emit, CancelToken ct) {
        var started = System.nanoTime();
        try (var conn = admin().open(); var st = conn.createStatement()) {
            var sql = "ALTER DATABASE " + quoteIdentifier(dbName) + " WITH ALLOW_CONNECTIONS true";
            emit.accept(new OperationEvent(Severity.INFO, sql));
            st.setQueryTimeout(30);
            st.execute(sql);
            emit.accept(new OperationEvent(Severity.SUCCESS,
                    "Database '" + dbName + "' is ONLINE again. Elapsed: " + ms(started) + " ms. "
                            + "New connections are now accepted."));
            return true;
        } catch (SQLException ex) {
            emit.accept(new OperationEvent(Severity.ERROR,
                    "Bring-online failed after " + ms(started) + " ms: " + ConnectionErrors.message(ex)));
            return false;
        }
    }

    public boolean takeOffline(String dbName, Consumer<OperationEvent> emit, CancelToken ct) {
        if (dbName.equals(manager.profile().database)) {
            emit.accept(new OperationEvent(Severity.WARN,
                    "'" + dbName + "' is the database you're connected to — the app's connection "
                            + "pool will be invalidated and you'll need to reconnect (or bring the DB "
                            + "back online) before running further queries."));
        }

        emit.accept(new OperationEvent(Severity.INFO,
                "Connecting to maintenance database 'postgres' as " + manager.profile().username + "..."));

        var started = System.nanoTime();
        try (var conn = admin().open()) {
            var alter = "ALTER DATABASE " + quoteIdentifier(dbName) + " WITH ALLOW_CONNECTIONS false";
            emit.accept(new OperationEvent(Severity.INFO, alter));
            try (var st = conn.createStatement()) {
                st.setQueryTimeout(30);
                st.execute(alter);
            }
            emit.accept(new OperationEvent(Severity.INFO, "New connections blocked. Terminating existing sessions..."));

            int terminated;
            try (var st = conn.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()")) {
                st.setString(1, dbName);
                try (var r = st.executeQuery()) {
                    r.next();
                    terminated = r.getInt(1);
                }
            }
            try (var st = conn.prepareStatement(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()")) {
                st.setString(1, dbName);
                st.execute();
            }

            emit.accept(new OperationEvent(Severity.SUCCESS,
                    "Database '" + dbName + "' is OFFLINE — terminated " + terminated + " session(s), elapsed "
                            + ms(started) + " ms. Bring online via: ALTER DATABASE " + quoteIdentifier(dbName)
                            + " WITH ALLOW_CONNECTIONS true"));
            return true;
        } catch (SQLException ex) {
            emit.accept(new OperationEvent(Severity.ERROR,
                    "Take-offline failed after " + ms(started) + " ms: " + ConnectionErrors.message(ex)));
            return false;
        }
    }

    // ---- Backup -------------------------------------------------------------

    public boolean backup(String dbName, String hostFilePath, Consumer<OperationEvent> emit, CancelToken ct) {
        var started = System.nanoTime();
        var t = requireClientTools(emit, ct);
        if (t == null) return false;

        emit.accept(new OperationEvent(Severity.INFO, "Counting tables in '" + dbName + "' for progress..."));
        var totalTables = countTables(dbName);
        if (totalTables > 0) {
            emit.accept(new OperationEvent(Severity.INFO, totalTables + " table(s) to dump.", 0, totalTables));
        }

        var server = manager.profile();
        emit.accept(new OperationEvent(Severity.INFO,
                "pg_dump " + server.host + ":" + server.port + "/" + dbName + " → " + hostFilePath));

        var dump = PgToolRunner.dump(t, server, dbName, hostFilePath);
        var tablesDone = new int[1];
        var exit = streamingRunner.stream(dump.file(), dump.args(), (isErr, line) -> {
            // pg_dump emits ALL --verbose output on stderr; "dumping contents of table" marks progress.
            var sev = classifyPgLine(line);
            if (line.toLowerCase(Locale.ROOT).contains("dumping contents of table")) {
                tablesDone[0]++;
                emit.accept(new OperationEvent(sev, line.trim(), tablesDone[0], totalTables > 0 ? totalTables : null));
            } else {
                emit.accept(new OperationEvent(sev, line.trim()));
            }
        }, ct, dump.environment());

        if (exit != 0) {
            emit.accept(new OperationEvent(Severity.ERROR,
                    ct.isCancelled() ? "pg_dump was cancelled." : "pg_dump exited with code " + exit + "."));
            // Don't leave a truncated archive behind that looks like a good backup.
            try { Files.deleteIfExists(Path.of(hostFilePath)); } catch (IOException ignored) { }
            return false;
        }

        long size = 0;
        try { size = Files.size(Path.of(hostFilePath)); } catch (IOException ignored) { }
        var seconds = (System.nanoTime() - started) / 1e9;
        var throughput = seconds > 0 ? formatBytes((long) (size / seconds)) + "/s" : "—";
        emit.accept(new OperationEvent(Severity.SUCCESS, String.format(
                "Backup complete — %s written, %d/%d tables, elapsed %.1fs (%s). File: %s",
                formatBytes(size), tablesDone[0], totalTables > 0 ? totalTables : tablesDone[0], seconds,
                throughput, hostFilePath)));
        return true;
    }

    // ---- Restore ------------------------------------------------------------

    public boolean restore(String dbName, String hostFilePath, Consumer<OperationEvent> emit, CancelToken ct) {
        var file = Path.of(hostFilePath);
        if (!Files.isRegularFile(file)) {
            emit.accept(new OperationEvent(Severity.ERROR, "Backup file not found: " + hostFilePath));
            return false;
        }

        var started = System.nanoTime();
        long fileSize = 0;
        try { fileSize = Files.size(file); } catch (IOException ignored) { }
        emit.accept(new OperationEvent(Severity.INFO, "Source file: " + hostFilePath + " (" + formatBytes(fileSize) + ")"));

        var t = requireClientTools(emit, ct);
        if (t == null) return false;

        // Count TOC entries via pg_restore --list for progress.
        var totalItems = 0;
        var list = PgToolRunner.listDumpToc(t, hostFilePath);
        var listed = runner.run(list.file(), list.args(), Duration.ofSeconds(30), ct, list.environment());
        if (listed.exitCode() == 0) {
            totalItems = (int) listed.stdOut().lines().filter(l -> !l.isEmpty() && !l.startsWith(";")).count();
            emit.accept(new OperationEvent(Severity.INFO, totalItems + " TOC entries to restore.", 0, totalItems));
        }

        var server = manager.profile();
        emit.accept(new OperationEvent(Severity.INFO,
                "pg_restore " + hostFilePath + " → " + server.host + ":" + server.port + "/" + dbName));

        var counts = new int[3]; // done, errors, benign errors
        var total = totalItems;
        var restore = PgToolRunner.restore(t, server, dbName, hostFilePath);
        var exit = streamingRunner.stream(restore.file(), restore.args(), (isErr, line) -> {
            var lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error:")) {
                counts[1]++;
                if (isBenignRestoreError(line)) counts[2]++;
            }
            var sev = classifyPgLine(line);
            // pg_restore --verbose emits "restoring data for table" / "processing item"
            if (lower.contains("processing item") || lower.contains("restoring data for table")) {
                counts[0]++;
                emit.accept(new OperationEvent(sev, line.trim(), counts[0], total > 0 ? total : null));
            } else {
                emit.accept(new OperationEvent(sev, line.trim()));
            }
        }, ct, restore.environment());

        if (exit != 0 && counts[1] > 0 && counts[1] == counts[2]) {
            emit.accept(new OperationEvent(Severity.WARN,
                    "pg_restore reported " + counts[1] + " harmless error(s): the dump was made by newer "
                            + "client tools and sets parameters this older server doesn't have "
                            + "(e.g. transaction_timeout). All objects and data were still restored."));
        } else if (exit != 0) {
            emit.accept(new OperationEvent(Severity.WARN, ct.isCancelled()
                    ? "pg_restore was cancelled; the database may be partly restored."
                    : "pg_restore exited with code " + exit + ". Some errors during restore are common "
                    + "(e.g. role already exists); review the output."));
            return false;
        }

        var seconds = (System.nanoTime() - started) / 1e9;
        emit.accept(new OperationEvent(Severity.SUCCESS, String.format(
                "Restore complete — %d/%d TOC entries restored, elapsed %.1fs. Target: '%s'.",
                counts[0], totalItems > 0 ? totalItems : counts[0], seconds, dbName)));
        return true;
    }

    // ---- Standalone helpers --------------------------------------------------

    /**
     * Turns ALLOW_CONNECTIONS back on for a database, authenticating against the
     * 'postgres' maintenance database with {@code profile}. Used by the login
     * flow when the chosen database is offline.
     */
    public static void bringOnlineUsingProfile(ConnectionProfile profile, String dbName) throws SQLException {
        try (var conn = profile.withDatabase("postgres").open(); var st = conn.createStatement()) {
            st.setQueryTimeout(30);
            st.execute("ALTER DATABASE " + quoteIdentifier(dbName) + " WITH ALLOW_CONNECTIONS true");
        }
    }

    // ---- Diagnostics --------------------------------------------------------

    public record DatabaseFingerprint(int tables, long estimatedRows, long sizeBytes, boolean exists) { }

    /**
     * What's in a database — Restore uses it to warn before overwriting a
     * populated one. Row counts are pg_class.reltuples estimates (instant).
     */
    public DatabaseFingerprint getDatabaseFingerprint(String dbName) {
        try (var conn = admin().open();
             var st = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname = ?)")) {
            st.setString(1, dbName);
            try (var r = st.executeQuery()) {
                r.next();
                if (!r.getBoolean(1)) return new DatabaseFingerprint(0, 0, 0, false);
            }
        } catch (SQLException ignored) {
            // carry on; the target query below decides
        }

        // Tables + estimated rows — must connect to the target database itself.
        try (var conn = manager.profile().withDatabase(dbName).open(); var st = conn.createStatement();
             var r = st.executeQuery("SELECT count(*), COALESCE(SUM(reltuples)::bigint, 0), "
                     + "COALESCE(pg_database_size(current_database()), 0) "
                     + "FROM pg_class WHERE relkind IN ('r','p') "
                     + "AND relnamespace NOT IN ('pg_catalog'::regnamespace, 'information_schema'::regnamespace)")) {
            r.next();
            return new DatabaseFingerprint(r.getInt(1), Math.max(0, r.getLong(2)), r.getLong(3), true);
        } catch (SQLException ex) {
            return new DatabaseFingerprint(0, 0, 0, true);
        }
    }

    /** CREATE DATABASE name OWNER owner. Used by "Restore as new database". */
    public boolean createDatabase(String dbName, String owner, Consumer<OperationEvent> emit, CancelToken ct) {
        try (var conn = admin().open(); var st = conn.createStatement()) {
            var ownerClause = owner == null || owner.isBlank() ? "" : " OWNER " + quoteIdentifier(owner);
            var sql = "CREATE DATABASE " + quoteIdentifier(dbName) + ownerClause;
            emit.accept(new OperationEvent(Severity.INFO, sql));
            st.setQueryTimeout(60);
            st.execute(sql);
            emit.accept(new OperationEvent(Severity.SUCCESS, "Created database '" + dbName + "'"
                    + (owner == null || owner.isBlank() ? "" : " owned by '" + owner + "'") + "."));
            return true;
        } catch (SQLException ex) {
            emit.accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
            return false;
        }
    }

    /** Other sessions on {@code dbName}, or -1 when the count failed. */
    public int countActiveSessions(String dbName) {
        try (var conn = admin().open();
             var st = conn.prepareStatement(
                     "SELECT count(*) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()")) {
            st.setString(1, dbName);
            try (var r = st.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        } catch (SQLException ex) {
            return -1;
        }
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * pg_dump 17+ writes {@code SET transaction_timeout = 0} into every archive;
     * restoring into a pre-17 server fails only that SET and pg_restore exits 1.
     * Such lines are safe to ignore.
     */
    public static boolean isBenignRestoreError(String line) {
        return line.toLowerCase(Locale.ROOT).contains("unrecognized configuration parameter \"transaction_timeout\"");
    }

    private static final Pattern ERROR_WORDS = Pattern.compile("\\b(ERROR|FATAL|PANIC)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WARN_WORDS = Pattern.compile("\\b(WARNING|HINT)\\b", Pattern.CASE_INSENSITIVE);

    public static Severity classifyPgLine(String line) {
        if (ERROR_WORDS.matcher(line).find()) return Severity.ERROR;
        if (WARN_WORDS.matcher(line).find()) return Severity.WARN;
        return Severity.INFO;
    }

    private int countTables(String dbName) {
        try (var conn = manager.profile().withDatabase(dbName).open(); var st = conn.createStatement();
             var r = st.executeQuery("SELECT count(*) FROM information_schema.tables "
                     + "WHERE table_schema NOT IN ('pg_catalog','information_schema') AND table_type = 'BASE TABLE'")) {
            r.next();
            return r.getInt(1);
        } catch (SQLException ex) {
            return 0;
        }
    }

    public static String quoteIdentifier(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    public static String formatBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB"};
        double v = bytes;
        var u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        var s = new java.text.DecimalFormat("0.##").format(v);
        return s + " " + units[u];
    }

    private static long ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void cancelQuietly(java.sql.Statement st) {
        try { st.cancel(); } catch (SQLException ignored) { }
    }
}
