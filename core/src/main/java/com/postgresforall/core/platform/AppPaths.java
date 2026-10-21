package com.postgresforall.core.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Per-user folders. The data folder keeps the original app's name
 * ("PostGresConnect") so saved logins and settings carry over from it.
 */
public final class AppPaths {
    public static final String DATA_FOLDER_NAME = "PostGresConnect";

    private AppPaths() { }

    /**
     * %APPDATA%\PostGresConnect on Windows, ~/Library/Application Support/PostGresConnect
     * on macOS, $XDG_CONFIG_HOME/PostGresConnect elsewhere.
     */
    public static Path dataDir() {
        return switch (Platform.current()) {
            case WINDOWS -> {
                var appData = System.getenv("APPDATA");
                yield (appData == null || appData.isBlank()
                        ? home().resolve("AppData").resolve("Roaming")
                        : Path.of(appData)).resolve(DATA_FOLDER_NAME);
            }
            case MAC -> home().resolve("Library").resolve("Application Support").resolve(DATA_FOLDER_NAME);
            case LINUX -> {
                var xdg = System.getenv("XDG_CONFIG_HOME");
                yield (xdg == null || xdg.isBlank() ? home().resolve(".config") : Path.of(xdg))
                        .resolve(DATA_FOLDER_NAME);
            }
        };
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    /** The user's Documents folder when it exists, else home. */
    public static Path documentsDir() {
        var docs = home().resolve("Documents");
        return Files.isDirectory(docs) ? docs : home();
    }

    /**
     * Folder holding the running app's launcher when started from a jpackage
     * image (the .exe's folder, or Contents/MacOS in a .app); empty otherwise.
     */
    public static Optional<Path> launcherDir() {
        var appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) return Optional.empty();
        var parent = Path.of(appPath).getParent();
        return Optional.ofNullable(parent);
    }
}
