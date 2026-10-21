package com.postgresforall.core.model;

import java.util.Locale;

/** Normalised "host:port" identity, treating every loopback spelling as localhost. */
public final class ServerKey {
    private ServerKey() { }

    public static String of(String host, int port) {
        return normalize(host) + ":" + port;
    }

    public static boolean isLoopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"));
    }

    private static String normalize(String host) {
        return isLoopback(host) ? "localhost" : (host == null ? "" : host.trim().toLowerCase(Locale.ROOT));
    }
}
