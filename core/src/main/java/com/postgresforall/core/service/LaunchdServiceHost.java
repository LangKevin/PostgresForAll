package com.postgresforall.core.service;

import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.platform.Elevation;
import com.postgresforall.core.platform.MacPostgresServices;
import com.postgresforall.core.platform.MacPostgresServices.Domain;
import com.postgresforall.core.platform.MacPostgresServices.LaunchdJob;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.util.CancelToken;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A native install run by launchd on macOS. Homebrew jobs are driven with
 * {@code brew services} (no admin rights needed); other user jobs with
 * {@code launchctl} in the GUI domain; system daemons (the EDB installer's)
 * with {@code launchctl} in the system domain, elevated — macOS asks for an
 * administrator password.
 */
public final class LaunchdServiceHost implements ServerHost {
    private final String label;
    private final ProcessRunner runner;
    /**
     * "postgresql@16" for a Homebrew job. Taken from the label, not the plist,
     * because `brew services stop` deletes the plist and a stopped Homebrew
     * server must still be startable.
     */
    private final String formula;

    public LaunchdServiceHost(String label, ProcessRunner runner) {
        this.label = label;
        this.runner = runner != null ? runner : new DefaultProcessRunner();
        this.formula = label.startsWith(MacPostgresServices.HOMEBREW_PREFIX)
                ? label.substring(MacPostgresServices.HOMEBREW_PREFIX.length()) : null;
    }

    private LaunchdJob job(CancelToken ct) {
        return MacPostgresServices.findJob(label, runner, ct).orElse(null);
    }

    @Override
    public Kind kind() {
        return Kind.SERVICE;
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public String description() {
        return "launchd service '" + label + "'";
    }

    @Override
    public boolean canControl() {
        return Platform.isMac();
    }

    @Override
    public boolean needsElevation() {
        if (formula != null) return false; // brew services runs in the user's own domain
        var j = job(CancelToken.NONE);
        return j != null && j.domain() == Domain.SYSTEM;
    }

    @Override
    public HostStatus status(CancelToken ct) {
        if (formula != null) return homebrewStatus(ct);
        var j = job(ct);
        if (j == null) return new HostStatus(RunState.MISSING, "no launchd job definition found");
        Boolean running;
        if (j.domain() == Domain.SYSTEM) {
            running = MacPostgresServices.systemJobRunning(runner, label, ct);
        } else {
            var r = runner.run("launchctl", List.of("list"), Duration.ofSeconds(5), ct);
            running = MacPostgresServices.parseLaunchctlList(r.exitCode() == 0 ? r.stdOut() : null, label);
        }
        if (running == null) return new HostStatus(RunState.UNKNOWN, "unknown state");
        return running
                ? new HostStatus(RunState.RUNNING, "running", "plist: " + j.plist())
                : new HostStatus(RunState.STOPPED, "stopped", "plist: " + j.plist());
    }

    @Override
    public ProcessResult start(CancelToken ct) {
        if (formula != null) return brew("start", ct);
        var j = job(ct);
        if (j == null) return missing();
        var target = target(j);
        // bootstrap loads the job (an "already loaded" failure is fine); kickstart runs it.
        var commands = new ArrayList<List<String>>();
        commands.add(List.of("/bin/launchctl", "bootstrap", domainName(j), j.plist().toString()));
        commands.add(List.of("/bin/launchctl", "kickstart", target));
        return runAll(j, commands, true, ct);
    }

    @Override
    public ProcessResult stop(CancelToken ct) {
        if (formula != null) return brew("stop", ct);
        var j = job(ct);
        if (j == null) return missing();
        return runAll(j, List.of(List.of("/bin/launchctl", "bootout", target(j))), false, ct);
    }

    @Override
    public ProcessResult restart(CancelToken ct) {
        if (formula != null) return brew("restart", ct);
        var j = job(ct);
        if (j == null) return missing();
        return runAll(j, List.of(List.of("/bin/launchctl", "kickstart", "-k", target(j))), false, ct);
    }

    @Override
    public String logs(int lines, CancelToken ct) {
        return null;
    }

    private ProcessResult brew(String verb, CancelToken ct) {
        return runner.run("brew", List.of("services", verb, formula), Duration.ofMinutes(2), ct);
    }

    /** Running per launchctl; stopped when merely unloaded (brew deletes the plist on stop). */
    private HostStatus homebrewStatus(CancelToken ct) {
        var known = MacPostgresServices.brewServices(runner, ct).stream().anyMatch(b -> b.name().equals(formula));
        if (!known) {
            var installed = runner.run("brew", List.of("--prefix", formula), Duration.ofSeconds(20), ct);
            if (installed.exitCode() != 0) {
                return new HostStatus(RunState.MISSING, "Homebrew formula '" + formula + "' isn't installed");
            }
        }
        var r = runner.run("launchctl", List.of("list"), Duration.ofSeconds(5), ct);
        var running = MacPostgresServices.parseLaunchctlList(r.exitCode() == 0 ? r.stdOut() : null, label);
        if (running == null) return new HostStatus(RunState.UNKNOWN, "unknown state");
        var detail = "Homebrew formula: " + formula + " (brew services)";
        return running ? new HostStatus(RunState.RUNNING, "running", detail)
                : new HostStatus(RunState.STOPPED, "stopped", detail);
    }

    /**
     * Runs each command in order (as one elevated shell for system jobs, so the
     * user is asked once). With {@code tolerateFirst}, the first command may fail.
     */
    private ProcessResult runAll(LaunchdJob j, List<List<String>> commands, boolean tolerateFirst, CancelToken ct) {
        if (j.domain() == Domain.SYSTEM) {
            var script = new StringBuilder();
            for (var i = 0; i < commands.size(); i++) {
                if (i > 0) script.append(i == 1 && tolerateFirst ? " ; " : " && ");
                script.append(String.join(" ", commands.get(i).stream().map(LaunchdServiceHost::shQuote).toList()));
            }
            return Elevation.run(runner, List.of("/bin/sh", "-c", script.toString()), Duration.ofMinutes(2), ct);
        }
        ProcessResult last = new ProcessResult(0, "", "");
        for (var i = 0; i < commands.size(); i++) {
            var c = commands.get(i);
            last = runner.run(c.get(0), c.subList(1, c.size()), Duration.ofSeconds(30), ct);
            if (last.exitCode() != 0 && !(i == 0 && tolerateFirst)) return last;
        }
        return last;
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String domainName(LaunchdJob j) {
        return j.domain() == Domain.SYSTEM ? "system" : "gui/" + uid();
    }

    private String target(LaunchdJob j) {
        return domainName(j) + "/" + label;
    }

    private String uid() {
        var r = runner.run("id", List.of("-u"), Duration.ofSeconds(5));
        return r.exitCode() == 0 ? r.stdOut().trim() : "501";
    }

    private ProcessResult missing() {
        return new ProcessResult(-1, "", "No launchd job definition (.plist) found for '" + label + "'.");
    }
}
