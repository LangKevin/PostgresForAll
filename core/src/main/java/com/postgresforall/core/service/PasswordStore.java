package com.postgresforall.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.PgSslMode;
import com.postgresforall.core.model.ServerKey;
import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.platform.SecretStore;
import com.postgresforall.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Recent logins at {@code <data dir>/profiles.json}, same format as the
 * original app. Passwords are kept only when asked ("Remember password") and
 * then only through the OS {@link SecretStore}.
 */
public final class PasswordStore {

    /** On-disk shape of one saved login. */
    public static final class StoredProfile {
        public String host = "";
        public int port;
        public String database = "";
        public String username = "";
        public String encryptedPassword;
        public PgSslMode sslMode = PgSslMode.PREFER;
        /** ISO-8601 UTC timestamp. */
        public String lastUsed;
        /**
         * True for a login entered from the Object Explorer for an additional
         * server, rather than on the login window. Such entries never pre-fill the
         * login window, so connecting a second server can't change which server
         * the app opens on next time.
         */
        public boolean isServerLogin;

        Instant lastUsedInstant() {
            try {
                return lastUsed == null ? Instant.EPOCH : Instant.parse(lastUsed);
            } catch (RuntimeException ex) {
                return Instant.EPOCH;
            }
        }

        String secretId() {
            return "profile/" + ServerKey.of(host, port) + "/" + database + "/" + username;
        }
    }

    public static final String ENTROPY = "PostGresConnect.v1";

    private final Path path;
    private final SecretStore secrets;

    public PasswordStore() {
        this(null, null);
    }

    public PasswordStore(Path path, SecretStore secrets) {
        this.path = path != null ? path : AppPaths.dataDir().resolve("profiles.json");
        this.secrets = secrets != null ? secrets : SecretStore.forPlatform(ENTROPY);
    }

    public Path storagePath() {
        return path;
    }

    public synchronized List<StoredProfile> load() {
        if (!Files.isRegularFile(path)) return new ArrayList<>();
        try {
            List<StoredProfile> list = Json.MAPPER.readValue(path.toFile(), new TypeReference<List<StoredProfile>>() { });
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }

    public void save(ConnectionProfile profile, boolean rememberPassword) {
        save(profile, rememberPassword, false);
    }

    public synchronized void save(ConnectionProfile profile, boolean rememberPassword, boolean isServerLogin) {
        var existing = load();
        existing.removeIf(p -> p.host.equals(profile.host) && p.port == profile.port
                && p.database.equals(profile.database) && p.username.equals(profile.username));

        var stored = new StoredProfile();
        stored.host = profile.host;
        stored.port = profile.port;
        stored.database = profile.database;
        stored.username = profile.username;
        stored.sslMode = profile.sslMode;
        stored.lastUsed = Instant.now().toString();
        stored.isServerLogin = isServerLogin;
        if (rememberPassword) {
            stored.encryptedPassword = secrets.protect(profile.password == null ? "" : profile.password, stored.secretId());
        } else {
            secrets.forget(stored.secretId());
        }
        existing.add(stored);

        try {
            Files.createDirectories(path.getParent());
            Json.MAPPER.writeValue(path.toFile(), existing);
        } catch (IOException ignored) {
            // not being able to remember a login is not fatal
        }
    }

    /** The most recent login-window login (not Object Explorer server logins), or null. */
    public ConnectionProfile findMostRecent() {
        return load().stream().filter(p -> !p.isServerLogin)
                .max(Comparator.comparing(StoredProfile::lastUsedInstant))
                .map(this::toProfile).orElse(null);
    }

    /**
     * The most recently used saved login for one server, or null. Used to
     * connect discovered servers silently when a password was remembered.
     */
    public ConnectionProfile findMostRecent(String host, int port) {
        var key = ServerKey.of(host, port);
        return load().stream()
                .filter(p -> ServerKey.of(p.host, p.port).equalsIgnoreCase(key))
                .max(Comparator.comparing(StoredProfile::lastUsedInstant))
                .map(this::toProfile).orElse(null);
    }

    private ConnectionProfile toProfile(StoredProfile p) {
        var c = new ConnectionProfile();
        c.host = p.host;
        c.port = p.port;
        c.database = p.database;
        c.username = p.username;
        c.password = p.encryptedPassword == null ? "" : secrets.unprotect(p.encryptedPassword, p.secretId());
        c.sslMode = p.sslMode == null ? PgSslMode.PREFER : p.sslMode;
        return c;
    }
}
