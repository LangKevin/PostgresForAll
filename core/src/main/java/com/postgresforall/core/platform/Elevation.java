package com.postgresforall.core.platform;

import com.postgresforall.core.util.CancelToken;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Runs one command with administrator rights, letting the OS ask the user:
 * the UAC prompt on Windows, the macOS admin password dialog (osascript).
 */
public final class Elevation {
    private Elevation() { }

    /** Message used when the user dismissed the OS prompt. */
    public static final String CANCELLED_MESSAGE = Platform.isWindows()
            ? "cancelled at the Windows admin (UAC) prompt"
            : "cancelled at the administrator password prompt";

    /** Runs {@code command} elevated; exit code 0 on success. */
    public static ProcessResult run(ProcessRunner runner, List<String> command, Duration timeout, CancelToken ct) {
        return switch (Platform.current()) {
            case WINDOWS -> runWindows(runner, command, timeout, ct);
            case MAC -> runMac(runner, command, timeout, ct);
            case LINUX -> runner.run("pkexec", command, timeout, ct);
        };
    }

    private static ProcessResult runWindows(ProcessRunner runner, List<String> command, Duration timeout,
                                            CancelToken ct) {
        // Start-Process -Verb RunAs triggers UAC; -PassThru + -Wait hands back the exit code.
        var file = psQuote(command.get(0));
        var args = command.size() > 1
                ? " -ArgumentList " + command.subList(1, command.size()).stream()
                .map(Elevation::psQuote).collect(Collectors.joining(","))
                : "";
        var script = "$ErrorActionPreference='Stop'; try { $p = Start-Process -FilePath " + file + args
                + " -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode } "
                + "catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1223 }";
        var r = runner.run("powershell", List.of("-NoProfile", "-NonInteractive", "-Command", script), timeout, ct);
        if (r.exitCode() == 1223 || r.stdErr().toLowerCase(Locale.ROOT).contains("canceled by the user")) {
            return new ProcessResult(1223, r.stdOut(), CANCELLED_MESSAGE);
        }
        return r;
    }

    private static ProcessResult runMac(ProcessRunner runner, List<String> command, Duration timeout,
                                        CancelToken ct) {
        var shell = command.stream().map(Elevation::shQuote).collect(Collectors.joining(" "));
        var apple = "do shell script \"" + shell.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\" with administrator privileges";
        var r = runner.run("osascript", List.of("-e", apple), timeout, ct);
        if (r.exitCode() != 0 && r.stdErr().contains("-128")) {
            return new ProcessResult(r.exitCode(), r.stdOut(), CANCELLED_MESSAGE);
        }
        return r;
    }

    static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
