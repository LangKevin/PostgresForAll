package com.postgresforall.core.model;

/** How a server came to be listed. A server found several ways carries several flags. */
public final class ServerSource {
    private ServerSource() { }

    public static final int NONE = 0;
    public static final int LOGIN = 1;
    public static final int CONFIG = 2;
    /** A native install: Windows service or launchd job. */
    public static final int SERVICE = 4;
    public static final int PORT_SCAN = 8;
    public static final int DOCKER = 16;
}
