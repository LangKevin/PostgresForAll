package com.postgresforall.core.platform;

import com.postgresforall.core.util.CancelToken;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads services named postgresql* from the registry, finds each one's data
 * directory from its {@code -D} argument and its port from that directory's
 * postgresql.conf, and asks {@code sc query} whether it's running.
 */
public final class WindowsPostgresServices implements LocalServiceSource {
    private static final String SERVICES_KEY = "SYSTEM\\CurrentControlSet\\Services";

    private final ProcessRunner runner;

    public WindowsPostgresServices(ProcessRunner runner) {
        this.runner = runner != null ? runner : new DefaultProcessRunner();
    }

    @Override
    public List<LocalPgService> list(CancelToken ct) {
        if (!Platform.isWindows()) return List.of();

        var found = new ArrayList<LocalPgService>();
        try {
            for (var name : Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, SERVICES_KEY)) {
                if (!name.toLowerCase(Locale.ROOT).startsWith("postgresql")) continue;
                var key = SERVICES_KEY + "\\" + name;
                var imagePath = readString(key, "ImagePath");
                var display = readString(key, "DisplayName");
                var dataDir = parseDataDirectory(imagePath);
                var port = LocalServiceSource.readPort(dataDir);

                var sc = runner.run("sc", List.of("query", name), Duration.ofSeconds(5), ct);
                var running = sc.exitCode() == 0 ? parseScRunning(sc.stdOut()) : null;
                found.add(new LocalPgService(name, display, dataDir, port, running));
            }
        } catch (RuntimeException ex) {
            // Registry access can fail under restricted accounts; discovery is best-effort.
        }
        return found;
    }

    private static String readString(String key, String value) {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, key, value)
                    ? Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, value)
                    : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final Pattern DATA_DIR = Pattern.compile("-D\\s+(?:\"([^\"]+)\"|(\\S+))");

    /** Extracts the data directory from pg_ctl's ImagePath: {@code ... -D "C:\...\data" ...}. */
    public static String parseDataDirectory(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return null;
        var m = DATA_DIR.matcher(imagePath);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static final Pattern SC_STATE = Pattern.compile("STATE\\s*:\\s*\\d+\\s+(\\w+)");

    public static Boolean parseScRunning(String scOutput) {
        var m = SC_STATE.matcher(scOutput == null ? "" : scOutput);
        return m.find() ? m.group(1).equalsIgnoreCase("RUNNING") : null;
    }
}
