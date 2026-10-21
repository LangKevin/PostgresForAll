package com.postgresforall.core.service;

import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.util.CancelToken;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A located set of PostgreSQL client binaries (pg_dump / pg_restore). Backup
 * and restore run these against the server over TCP, so they work the same for
 * a native install, a Docker container or a remote server.
 */
public record PgClientTools(String binDirectory, int majorVersion) {

    public String pgDump() {
        return Path.of(binDirectory, exeName("pg_dump")).toString();
    }

    public String pgRestore() {
        return Path.of(binDirectory, exeName("pg_restore")).toString();
    }

    /**
     * pg_dump refuses to dump a server newer than itself, and pg_restore can't
     * read archives from a newer pg_dump. Older servers are fine.
     */
    public boolean supportsServer(int serverMajor) {
        return majorVersion >= serverMajor;
    }

    static String exeName(String tool) {
        return Platform.isWindows() ? tool + ".exe" : tool;
    }

    public static PgClientTools locate(String configuredBinPath) {
        return locate(configuredBinPath, new DefaultProcessRunner(), PgToolSearch.DEFAULT, CancelToken.NONE);
    }

    /**
     * Finds the newest usable client tools. Candidates, in order: the configured
     * folder, each PATH entry, then the platform's install folders. Each
     * candidate's version comes from {@code pg_dump --version}. Null if none found.
     */
    public static PgClientTools locate(String configuredBinPath, ProcessRunner runner, PgToolSearch search,
                                       CancelToken ct) {
        PgClientTools best = null;
        for (var dir : search.candidateDirectories(configuredBinPath)) {
            var dump = Path.of(dir, exeName("pg_dump")).toString();
            if (!search.fileExists(dump) || !search.fileExists(Path.of(dir, exeName("pg_restore")).toString())) {
                continue;
            }

            var r = runner.run(dump, List.of("--version"), Duration.ofSeconds(10), ct);
            var major = r.exitCode() == 0 ? parseMajorVersion(r.stdOut()) : null;
            if (major == null) continue;

            // An explicitly configured path wins outright.
            if (configuredBinPath != null && !configuredBinPath.isBlank()
                    && Path.of(dir).toAbsolutePath().normalize().toString()
                    .equalsIgnoreCase(Path.of(configuredBinPath).toAbsolutePath().normalize().toString())) {
                return new PgClientTools(dir, major);
            }

            if (best == null || major > best.majorVersion) best = new PgClientTools(dir, major);
        }
        return best;
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * The major version out of "pg_dump (PostgreSQL) 18.1" or a server version
     * like "16.4 (Debian 16.4-1.pgdg120+2)". Null if unparseable.
     */
    public static Integer parseMajorVersion(String text) {
        if (text == null || text.isBlank()) return null;
        // The first run of digits is the major version in every format we see.
        var m = DIGITS.matcher(text);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
