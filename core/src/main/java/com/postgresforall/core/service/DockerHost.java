package com.postgresforall.core.service;

import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.util.CancelToken;

/** A Docker container. Only usable while the Docker engine is running. */
public final class DockerHost implements ServerHost {
    private final DockerProbe probe;

    public DockerHost(DockerProbe probe) {
        this.probe = probe;
    }

    public DockerProbe probe() {
        return probe;
    }

    @Override
    public Kind kind() {
        return Kind.DOCKER;
    }

    @Override
    public String name() {
        return probe.containerName();
    }

    @Override
    public String description() {
        return "Docker container '" + name() + "'";
    }

    @Override
    public boolean canControl() {
        return true;
    }

    @Override
    public HostStatus status(CancelToken ct) {
        var info = probe.inspect(ct);
        return switch (info.state()) {
            case RUNNING -> new HostStatus(RunState.RUNNING, info.statusText() != null ? info.statusText() : "running",
                    "image: " + nz(info.image()) + "   startedAt: " + nz(info.startedAt()));
            case NOT_FOUND -> new HostStatus(RunState.MISSING, "not found");
            case DOCKER_NOT_RUNNING -> new HostStatus(RunState.UNAVAILABLE, "Docker isn't running or isn't installed");
            case UNKNOWN -> new HostStatus(RunState.UNKNOWN, info.rawError() != null ? info.rawError() : "unknown");
            default -> new HostStatus(RunState.STOPPED,
                    info.statusText() != null ? info.statusText() : info.state().name().toLowerCase());
        };
    }

    private static String nz(String s) {
        return s == null ? "?" : s;
    }

    @Override
    public ProcessResult start(CancelToken ct) {
        return probe.start(ct);
    }

    @Override
    public ProcessResult stop(CancelToken ct) {
        return probe.stop(ct);
    }

    @Override
    public ProcessResult restart(CancelToken ct) {
        return probe.restart(ct);
    }

    @Override
    public String logs(int lines, CancelToken ct) {
        return probe.getLogs(lines, ct);
    }
}
