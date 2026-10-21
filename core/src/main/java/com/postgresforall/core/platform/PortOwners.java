package com.postgresforall.core.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Which processes are listening on a TCP port: PowerShell's
 * Get-NetTCPConnection on Windows, lsof on macOS / Linux. Neither needs admin
 * rights for the current user's processes.
 */
public final class PortOwners {
    private PortOwners() { }

    public record Owner(long pid, String processName, String detail) { }

    public static List<Owner> find(int port, ProcessRunner runner) {
        if (runner == null) runner = new DefaultProcessRunner();
        return Platform.isWindows() ? findWindows(port, runner) : findUnix(port, runner);
    }

    private static List<Owner> findWindows(int port, ProcessRunner runner) {
        var script = "$c = Get-NetTCPConnection -State Listen -LocalPort " + port + " -ErrorAction SilentlyContinue; "
                + "if (-not $c) { return }; "
                + "$c | ForEach-Object { "
                + "  $p = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue; "
                + "  if ($p) { '{0}|{1}|{2}|{3}' -f $_.OwningProcess, $p.ProcessName, $p.StartTime, $p.Path } "
                + "  else { '{0}|<unknown>|<?>|<?>' -f $_.OwningProcess } "
                + "}";
        var r = runner.run("powershell", List.of("-NoProfile", "-NonInteractive", "-Command", script),
                Duration.ofSeconds(10));
        if (r.exitCode() != 0) return List.of();
        return parsePipeRows(r.stdOut());
    }

    /** Parses "pid|name|started|path" rows. */
    public static List<Owner> parsePipeRows(String out) {
        var rows = new ArrayList<Owner>();
        var seen = new HashSet<Long>();
        for (var line : out.split("\n")) {
            var clean = line.trim();
            if (clean.isEmpty()) continue;
            var parts = clean.split("\\|");
            if (parts.length < 2) continue;
            long pid;
            try {
                pid = Long.parseLong(parts[0]);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!seen.add(pid)) continue;
            var detail = parts.length >= 4 ? "started " + parts[2] + "   ·   " + parts[3] : "";
            rows.add(new Owner(pid, parts[1], detail));
        }
        return rows;
    }

    private static List<Owner> findUnix(int port, ProcessRunner runner) {
        var r = runner.run("lsof", List.of("-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-Fpc"), Duration.ofSeconds(10));
        var owners = parseLsofFields(r.stdOut());
        var rows = new ArrayList<Owner>();
        for (var o : owners) {
            var ps = runner.run("ps", List.of("-o", "lstart=,command=", "-p", String.valueOf(o.pid())),
                    Duration.ofSeconds(5));
            var detail = ps.exitCode() == 0 ? ps.stdOut().trim() : "";
            rows.add(new Owner(o.pid(), o.processName(), detail.isEmpty() ? "" : "started " + detail));
        }
        return rows;
    }

    /** Parses {@code lsof -F pc} output: "p&lt;pid&gt;" then "c&lt;command&gt;" lines. */
    public static List<Owner> parseLsofFields(String out) {
        var rows = new ArrayList<Owner>();
        var seen = new HashSet<Long>();
        long pid = -1;
        for (var raw : out.split("\n")) {
            var line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.charAt(0) == 'p') {
                try {
                    pid = Long.parseLong(line.substring(1));
                } catch (NumberFormatException ex) {
                    pid = -1;
                }
            } else if (line.charAt(0) == 'c' && pid > 0 && seen.add(pid)) {
                rows.add(new Owner(pid, line.substring(1), ""));
            }
        }
        return rows;
    }
}
