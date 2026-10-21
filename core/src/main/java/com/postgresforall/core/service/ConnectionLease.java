package com.postgresforall.core.service;

/**
 * A reference to a {@link ConnectionManager} checked out from a
 * {@link ConnectionManagerFactory}. Closing it releases the reference; the
 * manager itself is torn down only when its last lease closes.
 */
public final class ConnectionLease implements AutoCloseable {
    private final ConnectionManagerFactory factory;
    private final String dbName;
    private final ConnectionManager manager;
    private boolean closed;

    ConnectionLease(ConnectionManagerFactory factory, String dbName, ConnectionManager manager) {
        this.factory = factory;
        this.dbName = dbName;
        this.manager = manager;
    }

    public ConnectionManager manager() {
        return manager;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        factory.release(dbName);
    }
}
