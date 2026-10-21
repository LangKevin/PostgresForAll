package com.postgresforall.core.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Tells a PostgreSQL server apart from any other open port without logging in:
 * sends the protocol's SSLRequest packet and expects the one-byte 'S' (will do
 * SSL) or 'N' (won't) reply that only Postgres gives.
 */
public final class PostgresHandshake {
    private PostgresHandshake() { }

    // Int32 length (8) + Int32 SSLRequest code (80877103), big-endian.
    private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 0x04, (byte) 0xD2, 0x16, 0x2F};

    public static boolean isPostgres(String host, int port, Duration timeout) {
        var ms = (int) Math.max(1, timeout.toMillis());
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), ms);
            socket.setSoTimeout(ms);
            var out = socket.getOutputStream();
            out.write(SSL_REQUEST);
            out.flush();
            var reply = socket.getInputStream().read();
            return reply == 'S' || reply == 'N';
        } catch (IOException ex) {
            return false;
        }
    }

    /** Plain TCP reachability check. */
    public static boolean tryProbeTcp(String host, int port, Duration timeout) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) Math.max(1, timeout.toMillis()));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
