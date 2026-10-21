package com.postgresforall.core.platform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Finds command-line tools. Apps launched from Finder don't inherit the shell's
 * PATH (it's just /usr/bin:/bin:/usr/sbin:/sbin), so on macOS / Linux the usual
 * Homebrew, Docker Desktop and Postgres.app folders are searched too.
 */
public final class Executables {
    private Executables() { }

    /** Folders searched after PATH on macOS / Linux. */
    public static final List<String> EXTRA_UNIX_DIRS = List.of(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/Applications/Docker.app/Contents/Resources/bin",
            "/Applications/Postgres.app/Contents/Versions/latest/bin",
            "/opt/local/bin");

    /** PATH entries plus the extra folders, deduplicated, in search order. */
    public static List<String> searchPath() {
        var dirs = new LinkedHashSet<String>();
        var path = System.getenv("PATH");
        if (path != null) {
            for (var p : path.split(File.pathSeparator)) {
                if (!p.isBlank()) dirs.add(p.trim());
            }
        }
        if (!Platform.isWindows()) dirs.addAll(EXTRA_UNIX_DIRS);
        return new ArrayList<>(dirs);
    }

    /** The PATH value to hand to child processes. */
    public static String childPath() {
        return String.join(File.pathSeparator, searchPath());
    }

    /**
     * Resolves a bare command name ("docker") to a full path. Paths, and names
     * that aren't found, come back unchanged so the OS reports the failure.
     * On Windows the OS search (with PATHEXT) is already right.
     */
    public static String resolve(String command) {
        if (Platform.isWindows() || command.contains("/") || command.contains(File.separator)) {
            return command;
        }
        for (var dir : searchPath()) {
            var candidate = Path.of(dir, command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return command;
    }
}
