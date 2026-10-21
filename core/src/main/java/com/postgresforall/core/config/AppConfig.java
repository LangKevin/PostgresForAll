package com.postgresforall.core.config;

import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * User-editable application settings, loaded from a JSON config file at
 * startup. Lets the target Postgres port and the Docker container name be
 * changed without recompiling.
 *
 * <p>Lookup order (first file found wins):
 * <ol>
 *   <li>Path in the POSTGRESCONNECT_CONFIG environment variable.</li>
 *   <li>{@code postgresconnect.config.json} beside the app launcher (jpackage image).</li>
 *   <li>The same file in the per-user data folder ({@link AppPaths#dataDir()}).</li>
 *   <li>The same file in the working directory.</li>
 *   <li>The default copy bundled with the app.</li>
 * </ol>
 * If parsing fails the built-in defaults below are used. Values are read once
 * per process; restart the app after editing the file.
 */
public final class AppConfig {

    /** Host the app connects to and probes. */
    public String host = "localhost";

    /** TCP port Postgres listens on. */
    public int port = 5444;

    /** Default database name pre-filled on the login screen. */
    public String database = "scheduleme";

    /** Default username pre-filled on the login screen. */
    public String username = "scheduleme";

    /**
     * Optional password pre-filled on the login screen. Plain text in the
     * config file, so only put a throwaway local-dev password here.
     */
    public String password = "";

    /** Role name used by the "Superuser" preset on the login screen. */
    public String superuserUsername = "postgres";

    /** Database the "Superuser" preset connects to. */
    public String superuserDatabase = "postgres";

    /**
     * Host / port the "Superuser" preset targets — the server where
     * {@link #superuserUsername} actually exists. Empty / 0 = same as host / port.
     */
    public String superuserHost = "";
    public int superuserPort;

    /**
     * Optional Docker container behind host:port. Only used for container
     * diagnostics (status / logs / start-stop) when Docker is installed.
     */
    public String dockerContainerName = "scheduleme-postgres";

    /** Folder holding pg_dump / pg_restore. Empty = search PATH and the usual install folders. */
    public String pgBinPath = "";

    /** Extra PostgreSQL servers to show in Object Explorer alongside the login server. */
    public List<ServerEntry> servers = new ArrayList<>();

    /**
     * When logging in as a superuser, also look for other Postgres servers on
     * this machine (native services + a handshake probe of {@link #discoveryPorts}).
     */
    public boolean discoverServers = true;

    /**
     * Object Explorer layout. True: one server node whose Databases folder
     * lists every server's databases. False: one node per server.
     */
    public boolean combineServerDatabases = true;

    /**
     * Object Explorer: hide a server's 'postgres' maintenance database when the
     * server has other databases (it stays reachable from the Database dropdown).
     */
    public boolean hideMaintenanceDatabase = true;

    /** Localhost ports probed by discovery, e.g. "5432-5450,55432". */
    public String discoveryPorts = "5432-5450,55432";

    /**
     * Opt-in: pre-fill a discovered Docker container's login from its
     * POSTGRES_USER / POSTGRES_PASSWORD environment (via docker inspect).
     */
    public boolean readDockerCredentials;

    public static final String FILE_NAME = "postgresconnect.config.json";
    public static final String ENV_VAR_NAME = "POSTGRESCONNECT_CONFIG";

    private static volatile AppConfig current;
    private static volatile Path loadedFrom;

    public String effectiveSuperuserHost() {
        return superuserHost == null || superuserHost.isBlank() ? host : superuserHost;
    }

    public int effectiveSuperuserPort() {
        return superuserPort > 0 ? superuserPort : port;
    }

    /** The settings for this process, loaded once on first access. */
    public static AppConfig current() {
        var c = current;
        if (c == null) {
            synchronized (AppConfig.class) {
                if (current == null) current = load();
                c = current;
            }
        }
        return c;
    }

    /** The file the current settings came from; empty when they're the bundled / built-in defaults. */
    public static Optional<Path> loadedFrom() {
        current();
        return Optional.ofNullable(loadedFrom);
    }

    /** Candidate config files, in lookup order. */
    public static List<Path> candidatePaths() {
        var list = new ArrayList<Path>();
        var override = System.getenv(ENV_VAR_NAME);
        if (override != null && !override.isBlank()) list.add(Path.of(override));
        AppPaths.launcherDir().ifPresent(d -> list.add(d.resolve(FILE_NAME)));
        list.add(AppPaths.dataDir().resolve(FILE_NAME));
        list.add(Path.of("").toAbsolutePath().resolve(FILE_NAME));
        return list;
    }

    /**
     * The config file path in effect: the first existing candidate, else where a
     * user copy belongs (the data folder). Returned even if it doesn't exist yet.
     */
    public static Path resolvePath() {
        for (var p : candidatePaths()) {
            if (Files.isRegularFile(p)) return p;
        }
        return AppPaths.dataDir().resolve(FILE_NAME);
    }

    public static AppConfig load() {
        loadedFrom = null;
        for (var p : candidatePaths()) {
            if (!Files.isRegularFile(p)) continue;
            try {
                var cfg = Json.MAPPER.readValue(p.toFile(), AppConfig.class);
                if (cfg != null) {
                    loadedFrom = p;
                    return cfg.normalized();
                }
            } catch (IOException ignored) {
                // Malformed or unreadable config → keep looking, then fall back to defaults.
            }
        }
        try (var in = AppConfig.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                var cfg = Json.MAPPER.readValue(in, AppConfig.class);
                if (cfg != null) return cfg.normalized();
            }
        } catch (IOException ignored) {
            // fall through to built-in defaults
        }
        return new AppConfig();
    }

    /** Parses a config from JSON text (tests, scaffolding). */
    public static AppConfig parse(String json) throws IOException {
        return Json.MAPPER.readValue(json, AppConfig.class).normalized();
    }

    private AppConfig normalized() {
        if (servers == null) servers = new ArrayList<>();
        if (host == null) host = "localhost";
        if (database == null) database = "";
        if (username == null) username = "";
        if (password == null) password = "";
        if (superuserUsername == null) superuserUsername = "postgres";
        if (superuserDatabase == null) superuserDatabase = "postgres";
        if (pgBinPath == null) pgBinPath = "";
        if (discoveryPorts == null) discoveryPorts = "";
        return this;
    }

    /**
     * Expands {@link #discoveryPorts} ("5432-5450,55432") into port numbers.
     * Malformed pieces are skipped; the result is distinct, sorted and capped at 200 ports.
     */
    public List<Integer> parseDiscoveryPorts() {
        var ports = new TreeSet<Integer>();
        for (var piece : (discoveryPorts == null ? "" : discoveryPorts).split(",")) {
            var raw = piece.trim();
            if (raw.isEmpty()) continue;
            var dash = raw.indexOf('-');
            try {
                if (dash > 0) {
                    var lo = Integer.parseInt(raw.substring(0, dash).trim());
                    var hi = Integer.parseInt(raw.substring(dash + 1).trim());
                    for (var p = Math.max(1, lo); p <= Math.min(65535, hi) && ports.size() < 200; p++) {
                        ports.add(p);
                    }
                } else {
                    var single = Integer.parseInt(raw);
                    if (single > 0 && single <= 65535) ports.add(single);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed piece
            }
        }
        return ports.stream().limit(200).toList();
    }

    /** Writes these settings to {@code path} (used to scaffold a user copy). */
    public void save(Path path) throws IOException {
        var dir = path.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Json.MAPPER.writeValue(path.toFile(), this);
    }
}
