package com.postgresforall.core.service;

import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.platform.Elevation;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.platform.WindowsPostgresServices;
import com.postgresforall.core.util.CancelToken;

import java.time.Duration;
import java.util.List;

/**
 * A native install running as a Windows service. Status needs no rights;
 * start / stop / restart run {@code net start|stop} elevated, so Windows shows
 * a UAC prompt.
 */
public final class WindowsServiceHost implements ServerHost {
    private final String name;
    private final ProcessRunner runner;

    public WindowsServiceHost(String serviceName, ProcessRunner runner) {
        this.name = serviceName;
        this.runner = runner != null ? runner : new DefaultProcessRunner();
    }

    @Override
    public Kind kind() {
        return Kind.SERVICE;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Windows service '" + name + "'";
    }

    @Override
    public boolean canControl() {
        return Platform.isWindows();
    }

    @Override
    public boolean needsElevation() {
        return true;
    }

    @Override
    public HostStatus status(CancelToken ct) {
        var r = runner.run("sc", List.of("query", name), Duration.ofSeconds(5), ct);
        if (r.exitCode() != 0) {
            return r.stdOut().contains("1060") || r.stdErr().contains("1060")
                    ? new HostStatus(RunState.MISSING, "service not installed")
                    : new HostStatus(RunState.UNKNOWN, (r.stdErr() + r.stdOut()).trim());
        }
        var running = WindowsPostgresServices.parseScRunning(r.stdOut());
        if (running == null) return new HostStatus(RunState.UNKNOWN, "unknown state");
        return running ? new HostStatus(RunState.RUNNING, "running") : new HostStatus(RunState.STOPPED, "stopped");
    }

    @Override
    public ProcessResult start(CancelToken ct) {
        return elevated(List.of("cmd.exe", "/c", "net start \"" + name + "\""), ct);
    }

    @Override
    public ProcessResult stop(CancelToken ct) {
        return elevated(List.of("cmd.exe", "/c", "net stop \"" + name + "\""), ct);
    }

    @Override
    public ProcessResult restart(CancelToken ct) {
        return elevated(List.of("cmd.exe", "/c", "net stop \"" + name + "\" & net start \"" + name + "\""), ct);
    }

    @Override
    public String logs(int lines, CancelToken ct) {
        return null;
    }

    private ProcessResult elevated(List<String> command, CancelToken ct) {
        var r = Elevation.run(runner, command, Duration.ofMinutes(2), ct);
        return r.exitCode() == 0 ? r : new ProcessResult(r.exitCode(), r.stdOut(),
                r.stdErr().isBlank() ? "exit code " + r.exitCode() : r.stdErr().trim());
    }
}
