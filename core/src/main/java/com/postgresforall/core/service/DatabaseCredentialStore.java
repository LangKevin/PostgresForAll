package com.postgresforall.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.postgresforall.core.model.DatabaseCredential;
import com.postgresforall.core.model.DatabaseCredentialProvider;
import com.postgresforall.core.model.PgSslMode;
import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.platform.SecretStore;
import com.postgresforall.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alternate credentials keyed by server + database name, so a database owned by
 * a different role can be queried without logging in again. The same database
 * name on two servers is two separate entries.
 *
 * <p>Two layers, session cache consulted first:
 * <ol>
 *   <li>In-memory overrides — set for this run (used even when not persisted).</li>
 *   <li>{@code <data dir>/db-credentials.json}, same format as the original app —
 *       only written when the caller asks to remember the password, and the
 *       password itself goes through the OS {@link SecretStore}.</li>
 * </ol>
 */
public final class DatabaseCredentialStore {

    /** On-disk shape of one stored credential. */
    public static final class StoredDatabaseCredential {
        /** Server key ("host:port"). Empty in files written before multi-server support. */
        public String server = "";
        public String database = "";
        public String username = "";
        public String encryptedPassword;
        public PgSslMode sslMode = PgSslMode.PREFER;
    }

    public static final String ENTROPY = "PostGresConnect.DbCreds.v1";

    private final Path path;
    private final SecretStore secrets;
    private final Map<String, DatabaseCredential> session = new ConcurrentHashMap<>();
    private final Object gate = new Object();

    public DatabaseCredentialStore() {
        this(null, null);
    }

    public DatabaseCredentialStore(Path path, SecretStore secrets) {
        this.path = path != null ? path : AppPaths.dataDir().resolve("db-credentials.json");
        this.secrets = secrets != null ? secrets : SecretStore.forPlatform(ENTROPY);
    }

    public Path storagePath() {
        return path;
    }

    /** A provider bound to one server, for that server's connection factory. */
    public DatabaseCredentialProvider forServer(String server) {
        return database -> find(server, database);
    }

    /**
     * The credential to use for {@code database} on {@code server}, or null if
     * none is known. Session overrides win over the file.
     */
    public DatabaseCredential find(String server, String database) {
        if (database == null || database.isBlank()) return null;
        var live = session.get(sessionKey(server, database));
        if (live != null) return live.copy();

        StoredDatabaseCredential stored;
        synchronized (gate) {
            stored = loadFile().stream().filter(c -> matches(c, server, database)).findFirst().orElse(null);
        }
        if (stored == null || stored.encryptedPassword == null) return null;

        var c = new DatabaseCredential();
        c.server = stored.server;
        c.database = stored.database;
        c.username = stored.username;
        c.password = secrets.unprotect(stored.encryptedPassword, secretId(stored.server, stored.database));
        c.sslMode = stored.sslMode == null ? PgSslMode.PREFER : stored.sslMode;
        return c;
    }

    /**
     * Records a credential for this session and, when {@code persist}, writes it
     * to disk so it survives a restart. When not persisted, any previously saved
     * copy for that database is removed (the override lives only for this run).
     */
    public void set(DatabaseCredential credential, boolean persist) {
        if (credential.database == null || credential.database.isBlank()) {
            throw new IllegalArgumentException("Database name required.");
        }
        if (credential.server == null || credential.server.isBlank()) {
            throw new IllegalArgumentException("Server key required.");
        }

        synchronized (gate) {
            session.put(sessionKey(credential.server, credential.database), credential.copy());
            var all = loadFile();
            all.removeIf(c -> matches(c, credential.server, credential.database));
            var id = secretId(credential.server, credential.database);
            if (persist) {
                var s = new StoredDatabaseCredential();
                s.server = credential.server;
                s.database = credential.database;
                s.username = credential.username;
                s.encryptedPassword = secrets.protect(credential.password == null ? "" : credential.password, id);
                s.sslMode = credential.sslMode;
                all.add(s);
            } else {
                secrets.forget(id);
            }
            saveFile(all);
        }
    }

    /** Removes a credential from both the session cache and disk. */
    public void remove(String server, String database) {
        synchronized (gate) {
            session.remove(sessionKey(server, database));
            var all = loadFile();
            if (all.removeIf(c -> matches(c, server, database))) saveFile(all);
            secrets.forget(secretId(server, database));
        }
    }

    /**
     * Assigns entries saved before multi-server support (no server recorded) to
     * {@code server} — the login server, the only one those credentials could
     * have been entered against. Returns how many moved.
     */
    public int adoptUnscoped(String server) {
        synchronized (gate) {
            var all = loadFile();
            var legacy = all.stream().filter(c -> c.server == null || c.server.isBlank()).toList();
            if (legacy.isEmpty()) return 0;
            for (var c : legacy) {
                // Don't clobber a scoped entry that already exists for this server.
                var clash = all.stream().anyMatch(o -> o != c && matches(o, server, c.database));
                if (clash) all.remove(c);
                else c.server = server;
            }
            saveFile(all);
            return legacy.size();
        }
    }

    private static String sessionKey(String server, String database) {
        return (server + "/" + database).toLowerCase(Locale.ROOT);
    }

    private static String secretId(String server, String database) {
        return "dbcred/" + (server == null ? "" : server.toLowerCase(Locale.ROOT)) + "/" + database;
    }

    private static boolean matches(StoredDatabaseCredential c, String server, String database) {
        return (c.server == null ? "" : c.server).equalsIgnoreCase(server == null ? "" : server)
                && (c.database == null ? "" : c.database).equalsIgnoreCase(database);
    }

    private List<StoredDatabaseCredential> loadFile() {
        if (!Files.isRegularFile(path)) return new ArrayList<>();
        try {
            List<StoredDatabaseCredential> list = Json.MAPPER.readValue(path.toFile(),
                    new TypeReference<List<StoredDatabaseCredential>>() { });
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }

    private void saveFile(List<StoredDatabaseCredential> all) {
        try {
            Files.createDirectories(path.getParent());
            Json.MAPPER.writeValue(path.toFile(), all);
        } catch (IOException ignored) {
            // the session copy still works for this run
        }
    }
}
