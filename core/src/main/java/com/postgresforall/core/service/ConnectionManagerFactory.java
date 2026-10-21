package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DatabaseCredentialProvider;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Builds and caches {@link ConnectionManager}s keyed by database name for one
 * server. Tabs on the same database share one pool. Reference-counted: a
 * manager is closed when its last lease is released, or when the factory is.
 */
public final class ConnectionManagerFactory implements AutoCloseable {
    private final ConnectionProfile baseProfile;
    private final DatabaseCredentialProvider credentials;
    private final Map<String, Entry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    private static final class Entry {
        final ConnectionManager manager;
        int refCount;

        Entry(ConnectionManager manager) {
            this.manager = manager;
        }
    }

    /** Opens a manager for a profile; replaceable in tests. */
    @FunctionalInterface
    public interface Opener {
        ConnectionManager open(ConnectionProfile profile) throws SQLException;
    }

    private final Opener opener;

    public ConnectionManagerFactory(ConnectionProfile baseProfile, DatabaseCredentialProvider credentials) {
        this(baseProfile, credentials, ConnectionManager::open);
    }

    public ConnectionManagerFactory(ConnectionProfile baseProfile, DatabaseCredentialProvider credentials,
                                    Opener opener) {
        this.baseProfile = baseProfile;
        this.credentials = credentials;
        this.opener = opener;
    }

    /** The base credentials used to build managers for any database. */
    public ConnectionProfile baseProfile() {
        return baseProfile;
    }

    /**
     * Acquires a manager for {@code dbName}. Close the returned lease when done;
     * that decrements the count and closes the manager when it reaches zero.
     */
    public ConnectionLease acquire(String dbName) throws SQLException {
        if (dbName == null || dbName.isBlank()) throw new IllegalArgumentException("Database name required.");

        lock.lock();
        try {
            if (closed) throw new IllegalStateException("The server connection was closed.");

            var existing = entries.get(dbName);
            if (existing != null) {
                existing.refCount++;
                return new ConnectionLease(this, dbName, existing.manager);
            }

            var profile = baseProfile.withDatabase(dbName);
            // A database owned by a different role can carry its own credentials
            // (same server, different login) — apply them over the base profile.
            var override = credentials == null ? null : credentials.find(dbName);
            if (override != null) {
                profile.username = override.username;
                profile.password = override.password;
                profile.sslMode = override.sslMode;
            }

            var manager = opener.open(profile);
            var entry = new Entry(manager);
            entry.refCount = 1;
            entries.put(dbName, entry);
            return new ConnectionLease(this, dbName, manager);
        } finally {
            lock.unlock();
        }
    }

    /** How many databases currently hold a pool (tests / diagnostics). */
    public int openPoolCount() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    void release(String dbName) {
        // The factory already tore every manager down (server disconnected);
        // a lease outliving it has nothing left to release.
        if (closed) return;
        lock.lock();
        try {
            var entry = entries.get(dbName);
            if (entry == null) return;
            entry.refCount--;
            if (entry.refCount <= 0) {
                entries.remove(dbName);
                entry.manager.close();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        lock.lock();
        try {
            closed = true;
            for (var e : entries.values()) {
                try { e.manager.close(); } catch (RuntimeException ignored) { }
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return "factory " + baseProfile.host + ":" + baseProfile.port + " " + entries.keySet().toString().toLowerCase(Locale.ROOT);
    }
}
