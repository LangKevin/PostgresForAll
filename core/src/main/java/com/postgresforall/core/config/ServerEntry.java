package com.postgresforall.core.config;

/**
 * One PostgreSQL server listed in {@link AppConfig#servers}. Only host and
 * port are required.
 */
public final class ServerEntry {
    /** Display name for the tree. Defaults to "host:port". */
    public String name;

    public String host = "localhost";
    public int port = 5432;

    /** Login pre-filled when connecting. Defaults to the config's SuperuserUsername. */
    public String username;

    /** Database to log in to. Defaults to the config's SuperuserDatabase. */
    public String database;

    /** Docker container behind this server, if any — enables container diagnostics. */
    public String dockerContainer;

    public ServerEntry() { }

    public ServerEntry(String host, int port) {
        this.host = host;
        this.port = port;
    }
}
