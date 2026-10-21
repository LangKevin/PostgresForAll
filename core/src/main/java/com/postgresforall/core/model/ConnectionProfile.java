package com.postgresforall.core.model;

import com.postgresforall.core.config.AppConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** A login: where, which database, who, and how (SSL). Mutable, like the original. */
public final class ConnectionProfile {
    public static final String APPLICATION_NAME = "PostgresForAll";

    public String host = "localhost";
    public int port = 5444;
    public String database = "scheduleme";
    public String username = "scheduleme";
    public String password = "";
    public PgSslMode sslMode = PgSslMode.PREFER;

    public String displayName() {
        return username + "@" + host + ":" + port + "/" + database;
    }

    /**
     * A profile seeded from the user-editable {@link AppConfig} (host / port /
     * database / username / password). Pre-fills the login screen when there is
     * no saved profile.
     */
    public static ConnectionProfile fromConfig() {
        var c = AppConfig.current();
        var p = new ConnectionProfile();
        p.host = c.host;
        p.port = c.port;
        p.database = c.database;
        p.username = c.username;
        p.password = c.password;
        return p;
    }

    public String jdbcUrl() {
        var h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "jdbc:postgresql://" + h + ":" + port + "/"
                + URLEncoder.encode(database == null ? "" : database, StandardCharsets.UTF_8);
    }

    public Properties jdbcProperties() {
        var props = new Properties();
        props.setProperty("user", username == null ? "" : username);
        props.setProperty("password", password == null ? "" : password);
        props.setProperty("sslmode", (sslMode == null ? PgSslMode.PREFER : sslMode).libpqName());
        props.setProperty("ApplicationName", APPLICATION_NAME);
        props.setProperty("connectTimeout", "10");
        props.setProperty("loginTimeout", "15");
        props.setProperty("tcpKeepAlive", "true");
        return props;
    }

    /** A fresh, unpooled connection with these credentials. */
    public Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), jdbcProperties());
    }

    /** The same login against another database on the same server. */
    public ConnectionProfile withDatabase(String db) {
        var p = copy();
        p.database = db;
        return p;
    }

    public ConnectionProfile copy() {
        var p = new ConnectionProfile();
        p.host = host;
        p.port = port;
        p.database = database;
        p.username = username;
        p.password = password;
        p.sslMode = sslMode;
        return p;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
