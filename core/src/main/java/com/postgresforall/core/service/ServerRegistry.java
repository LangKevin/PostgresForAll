package com.postgresforall.core.service;

import com.postgresforall.core.model.DiscoveredServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The set of servers shown in the Object Explorer, one {@link ServerSession}
 * each, keyed by host:port. Owns the sessions: closing the registry
 * disconnects every server.
 */
public final class ServerRegistry implements AutoCloseable {
    private final List<ServerSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<ServerSession>> addedListeners = new CopyOnWriteArrayList<>();
    private final DatabaseCredentialStore credentials;

    public ServerRegistry(DatabaseCredentialStore credentials) {
        this.credentials = credentials;
    }

    public DatabaseCredentialStore credentials() {
        return credentials;
    }

    /** Snapshot of the sessions, in the order they were added. */
    public List<ServerSession> sessions() {
        return List.copyOf(sessions);
    }

    /** Raised after a new server is added (not when an existing one is updated). */
    public void onSessionAdded(Consumer<ServerSession> listener) {
        addedListeners.add(listener);
    }

    public void removeSessionAddedListener(Consumer<ServerSession> listener) {
        addedListeners.remove(listener);
    }

    public ServerSession find(String key) {
        return sessions.stream().filter(s -> s.key().equalsIgnoreCase(key)).findFirst().orElse(null);
    }

    /** Adds a server, or merges new details into the one already listed. */
    public synchronized ServerSession addOrUpdate(DiscoveredServer server) {
        var existing = find(server.key());
        if (existing != null) {
            existing.update(server);
            return existing;
        }
        var session = new ServerSession(server, credentials);
        sessions.add(session);
        for (var l : addedListeners) l.accept(session);
        return session;
    }

    /**
     * Connects, in parallel, every disconnected server that has a login usable
     * without asking (remembered password or a discovery hint). Failures are
     * recorded on each session, not thrown. Returns the sessions tried.
     */
    public List<ServerSession> connectSilently(PasswordStore saved) {
        var attempts = new ArrayList<ServerSession>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var s : sessions) {
                if (s.state() != ServerSession.State.DISCONNECTED && s.state() != ServerSession.State.FAILED) continue;
                var login = s.suggestLogin(saved);
                if (login.password == null || login.password.isEmpty()) continue;
                attempts.add(s);
                pool.submit(() -> {
                    try {
                        s.connect(login);
                    } catch (Exception ignored) {
                        // state + lastError already recorded on the session
                    }
                });
            }
        }
        return attempts;
    }

    @Override
    public void close() {
        for (var s : sessions) {
            try { s.close(); } catch (RuntimeException ignored) { }
        }
        sessions.clear();
    }
}
