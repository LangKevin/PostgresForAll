package com.postgresforall.core.model;

/**
 * Alternate credentials for a specific database on the same server. When a
 * query tab targets a database that has one of these, the connection is opened
 * with this username / password instead of the base login — so a database owned
 * by a different role can be queried without logging out.
 */
public final class DatabaseCredential {
    /**
     * {@link ServerKey} of the server the database lives on. Empty only for
     * entries saved before multi-server support; those are adopted by the first
     * server logged into.
     */
    public String server = "";
    public String database = "";
    public String username = "";
    public String password = "";
    public PgSslMode sslMode = PgSslMode.PREFER;

    public DatabaseCredential copy() {
        var c = new DatabaseCredential();
        c.server = server;
        c.database = database;
        c.username = username;
        c.password = password;
        c.sslMode = sslMode;
        return c;
    }
}
