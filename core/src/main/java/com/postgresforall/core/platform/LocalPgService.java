package com.postgresforall.core.platform;

/**
 * A native PostgreSQL install registered with the OS service manager: a
 * Windows service, or a launchd job on macOS (Homebrew, EDB installer).
 *
 * @param name       service name (Windows) or launchd label (macOS) — what controls it
 * @param isRunning  null when the state couldn't be read
 */
public record LocalPgService(String name, String displayName, String dataDirectory, int port, Boolean isRunning) {
}
