package com.postgresforall.app;

import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small JSON-backed store at {@code <data dir>/settings.json} (same file as the
 * original app) for non-sensitive UI preferences like last-used folders.
 */
public final class AppSettings {
    public String lastBackupFolder;
    public String lastRestoreFolder;

    private static Path filePath() {
        return AppPaths.dataDir().resolve("settings.json");
    }

    public static AppSettings load() {
        try {
            var p = filePath();
            if (!Files.isRegularFile(p)) return new AppSettings();
            var s = Json.MAPPER.readValue(p.toFile(), AppSettings.class);
            return s == null ? new AppSettings() : s;
        } catch (IOException ex) {
            return new AppSettings();
        }
    }

    public void save() {
        try {
            Files.createDirectories(AppPaths.dataDir());
            Json.MAPPER.writeValue(filePath().toFile(), this);
        } catch (IOException ignored) {
            // settings save failures are non-fatal
        }
    }

    /** The first folder that still exists, else Documents. */
    public static Path folderOr(String... candidates) {
        for (var c : candidates) {
            if (c != null && !c.isBlank() && Files.isDirectory(Path.of(c))) return Path.of(c);
        }
        return AppPaths.documentsDir();
    }
}
