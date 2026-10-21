package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns the connection pool for one login against one database. Opening it
 * proves the credentials work (a direct connection, so a bad password fails
 * at once with the server's message rather than after a pool timeout).
 */
public final class ConnectionManager implements AutoCloseable {
    private final ConnectionProfile profile;
    private final HikariDataSource pool;
    private String serverVersion = "";
    private String currentUser = "";
    private boolean superuser;

    private ConnectionManager(ConnectionProfile profile, HikariDataSource pool) {
        this.profile = profile;
        this.pool = pool;
    }

    public static ConnectionManager open(ConnectionProfile profile) throws SQLException {
        var p = profile.copy();
        String version;
        String user = "";
        boolean su = false;
        try (var probe = p.open()) {
            var meta = probe.getMetaData();
            version = meta.getDatabaseMajorVersion() + "." + meta.getDatabaseMinorVersion();
            try (var st = probe.createStatement();
                 var rs = st.executeQuery("SELECT current_user, current_setting('is_superuser') = 'on'")) {
                if (rs.next()) {
                    user = rs.getString(1);
                    su = rs.getBoolean(2);
                }
            }
        }

        var cfg = new HikariConfig();
        cfg.setPoolName("pg " + p.displayName());
        cfg.setJdbcUrl(p.jdbcUrl());
        cfg.setDataSourceProperties(p.jdbcProperties());
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(120_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.setConnectionTimeout(15_000);
        cfg.setInitializationFailTimeout(-1); // credentials were just proven above
        var manager = new ConnectionManager(p, new HikariDataSource(cfg));
        manager.serverVersion = version;
        manager.currentUser = user;
        manager.superuser = su;
        return manager;
    }

    public ConnectionProfile profile() {
        return profile;
    }

    /** "16.4" — major.minor of the server. */
    public String serverVersion() {
        return serverVersion;
    }

    /**
     * The role this session actually runs as, as reported by the server (may
     * differ from the profile's username if the login mapped elsewhere).
     */
    public String currentUser() {
        return currentUser;
    }

    /**
     * True when this session has superuser rights. Read from
     * {@code current_setting('is_superuser')} so it reflects the session's
     * effective state and is readable by any role.
     */
    public boolean isSuperuser() {
        return superuser;
    }

    /** A pooled connection; close it to return it. */
    public Connection openConnection() throws SQLException {
        return pool.getConnection();
    }

    /**
     * A one-off connection to the 'postgres' maintenance database with the same
     * credentials. Server-wide queries use it so they work even when the selected
     * database is offline.
     */
    public Connection openMaintenanceConnection() throws SQLException {
        return profile.withDatabase("postgres").open();
    }

    @Override
    public void close() {
        pool.close();
    }
}
