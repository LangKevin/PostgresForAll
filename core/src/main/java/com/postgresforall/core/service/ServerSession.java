package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DatabaseCredentialProvider;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.PgSslMode;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Everything the app holds for one PostgreSQL server: its login, the
 * per-database pool factory, a primary lease that keeps the login database's
 * pool warm, and the server-scoped {@link DatabaseOperations}.
 *
 * <p>One session per server in the Object Explorer. Disconnecting closes the
 * factory, which tears down every pool on that server — callers close the
 * server's query tabs first.
 */
public final class ServerSession implements AutoCloseable {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private final DatabaseCredentialStore credentials;
    private volatile DiscoveredServer server;
    private volatile State state = State.DISCONNECTED;
    private volatile String lastError;
    private volatile ConnectionManagerFactory factory;
    private volatile ConnectionLease primaryLease;
    private volatile DatabaseOperations operations;

    private final List<Consumer<ServerSession>> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ServerSession>> infoListeners = new CopyOnWriteArrayList<>();

    public ServerSession(DiscoveredServer server, DatabaseCredentialStore credentials) {
        this.server = server;
        this.credentials = credentials;
    }

    public DiscoveredServer server() {
        return server;
    }

    public String key() {
        return server.key();
    }

    public State state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    /** Pool factory for this server. Null unless connected. */
    public ConnectionManagerFactory factory() {
        return factory;
    }

    /** Connection to the login database. Null unless connected. */
    public ConnectionManager manager() {
        var l = primaryLease;
        return l == null ? null : l.manager();
    }

    /** Backup / restore / drop etc. against this server. Null unless connected. */
    public DatabaseOperations operations() {
        return operations;
    }

    /** The credentials this session logged in with. Null unless connected. */
    public ConnectionProfile login() {
        var f = factory;
        return f == null ? null : f.baseProfile();
    }

    /** Per-database credential overrides scoped to this server. */
    public DatabaseCredentialProvider credentials() {
        return credentials.forServer(key());
    }

    /** Connected / disconnected / failed / connecting. Listeners run on the changing thread. */
    public void onStateChanged(Consumer<ServerSession> listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(Consumer<ServerSession> listener) {
        stateListeners.remove(listener);
    }

    /** Only descriptive details changed (label, container, hints) — connection untouched. */
    public void onInfoChanged(Consumer<ServerSession> listener) {
        infoListeners.add(listener);
    }

    public void removeInfoListener(Consumer<ServerSession> listener) {
        infoListeners.remove(listener);
    }

    /** Folds in what a later discovery pass learned (labels, hints). */
    public void update(DiscoveredServer other) {
        if (!other.key().equalsIgnoreCase(key())) throw new IllegalArgumentException("Different server.");
        var merged = server.mergeWith(other);
        if (merged.equals(server)) return;
        server = merged;
        for (var l : infoListeners) l.accept(this);
    }

    /**
     * Logs in with {@code login} (host / port are forced to this server's). On
     * failure the session is left FAILED with {@link #lastError()} set, and the
     * exception propagates.
     */
    public synchronized void connect(ConnectionProfile login) throws SQLException {
        disconnect();

        var profile = login.copy();
        profile.host = server.host();
        profile.port = server.port();
        if (profile.database == null || profile.database.isBlank()) {
            profile.database = AppConfig.current().superuserDatabase;
        }

        setState(State.CONNECTING, null);
        var f = new ConnectionManagerFactory(profile, credentials());
        try {
            primaryLease = f.acquire(profile.database);
        } catch (SQLException | RuntimeException ex) {
            f.close();
            setState(State.FAILED, ConnectionErrors.message(ex));
            throw ex;
        }

        factory = f;
        operations = new DatabaseOperations(primaryLease.manager());
        setState(State.CONNECTED, null);
    }

    public synchronized void disconnect() {
        var lease = primaryLease;
        var f = factory;
        primaryLease = null;
        factory = null;
        operations = null;

        if (lease != null) {
            try { lease.close(); } catch (RuntimeException ignored) { }
        }
        if (f != null) {
            try { f.close(); } catch (RuntimeException ignored) { }
        }
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED, null);
    }

    /**
     * The login to try for this server, best first: a remembered password, then
     * discovery hints (config / container env), then the config's superuser
     * preset. A blank password means "ask the user".
     */
    public ConnectionProfile suggestLogin(PasswordStore saved, AppConfig config) {
        if (config == null) config = AppConfig.current();
        var remembered = saved == null ? null : saved.findMostRecent(server.host(), server.port());
        // A login configured for this server outranks a remembered one for a
        // different role — usually a stale entry saved against the wrong port.
        if (remembered != null && server.suggestedUsername() != null
                && !remembered.username.equals(server.suggestedUsername())) {
            remembered = null;
        }
        if (remembered != null && remembered.password != null && !remembered.password.isEmpty()) return remembered;

        var p = new ConnectionProfile();
        p.host = server.host();
        p.port = server.port();
        p.username = server.suggestedUsername() != null ? server.suggestedUsername()
                : remembered != null ? remembered.username : config.superuserUsername;
        p.database = server.suggestedDatabase() != null ? server.suggestedDatabase()
                : remembered != null ? remembered.database : config.superuserDatabase;
        p.password = server.suggestedPassword() != null ? server.suggestedPassword() : "";
        p.sslMode = remembered != null ? remembered.sslMode : PgSslMode.PREFER;
        return p;
    }

    public ConnectionProfile suggestLogin(PasswordStore saved) {
        return suggestLogin(saved, null);
    }

    private void setState(State s, String error) {
        state = s;
        lastError = error;
        for (var l : stateListeners) l.accept(this);
    }

    @Override
    public void close() {
        disconnect();
    }
}
