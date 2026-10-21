package com.postgresforall.core.platform;

import java.util.Locale;

/** Which operating system we're on. Everything OS-specific branches on this, once. */
public enum Platform {
    WINDOWS, MAC, LINUX;

    private static final Platform CURRENT = detect();

    public static Platform current() {
        return CURRENT;
    }

    public static boolean isWindows() {
        return CURRENT == WINDOWS;
    }

    public static boolean isMac() {
        return CURRENT == MAC;
    }

    private static Platform detect() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return WINDOWS;
        if (os.startsWith("mac") || os.startsWith("darwin")) return MAC;
        return LINUX;
    }

    /** "Windows service", "launchd service" — what a native install runs as here. */
    public String nativeServiceNoun() {
        return switch (this) {
            case WINDOWS -> "Windows service";
            case MAC -> "launchd service";
            case LINUX -> "system service";
        };
    }
}
