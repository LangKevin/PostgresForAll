package com.postgresforall.core.service;

import com.postgresforall.core.platform.Executables;
import com.postgresforall.core.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/** File-system seam for {@link PgClientTools#locate}, so tests can fake it. */
public class PgToolSearch {
    public static final PgToolSearch DEFAULT = new PgToolSearch();

    public boolean fileExists(String path) {
        return Files.isRegularFile(Path.of(path));
    }

    /** PATH entries, plus the usual tool folders a Finder-launched app doesn't see. */
    public List<String> pathEntries() {
        return Executables.searchPath();
    }

    /** Version-specific install folders: {@code <root>/<version>/bin} and friends. */
    public List<String> installRoots() {
        var dirs = new ArrayList<String>();
        switch (Platform.current()) {
            case WINDOWS -> {
                for (var env : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                    var pf = System.getenv(env);
                    if (pf != null && !pf.isBlank()) addChildren(dirs, Path.of(pf, "PostgreSQL"), "bin");
                }
            }
            case MAC -> {
                addChildren(dirs, Path.of("/Library/PostgreSQL"), "bin");                       // EDB installer
                addChildren(dirs, Path.of("/Applications/Postgres.app/Contents/Versions"), "bin");
                for (var brew : List.of("/opt/homebrew/opt", "/usr/local/opt")) {               // Homebrew (arm / intel)
                    addMatching(dirs, Path.of(brew), "postgresql", "bin");
                    dirs.add(brew + "/libpq/bin");
                }
            }
            case LINUX -> {
                addChildren(dirs, Path.of("/usr/lib/postgresql"), "bin");
                addMatching(dirs, Path.of("/usr"), "pgsql-", "bin");
            }
        }
        return dirs;
    }

    public List<String> candidateDirectories(String configuredBinPath) {
        var seen = new LinkedHashSet<String>();
        if (configuredBinPath != null && !configuredBinPath.isBlank()) seen.add(configuredBinPath);
        seen.addAll(pathEntries());
        seen.addAll(installRoots());
        return new ArrayList<>(seen);
    }

    private static void addChildren(List<String> out, Path root, String sub) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).sorted().forEach(d -> out.add(d.resolve(sub).toString()));
        } catch (IOException ignored) {
            // unreadable root: nothing to add
        }
    }

    private static void addMatching(List<String> out, Path root, String prefix, String sub) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> s = Files.list(root)) {
            s.filter(d -> d.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .forEach(d -> out.add(d.resolve(sub).toString()));
        } catch (IOException ignored) {
            // unreadable root: nothing to add
        }
    }
}
