package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.Diagnostics.ContainerInfo;
import com.postgresforall.core.model.Diagnostics.ContainerState;
import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.util.CancelToken;
import com.postgresforall.core.util.Json;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** One Docker container, through the docker CLI. Only usable while the engine runs. */
public final class DockerProbe {
    private final ProcessRunner runner;
    private final String containerName;

    public DockerProbe(String containerName, ProcessRunner runner) {
        this.containerName = containerName == null || containerName.isBlank()
                ? AppConfig.current().dockerContainerName
                : containerName;
        this.runner = runner != null ? runner : new DefaultProcessRunner();
    }

    public DockerProbe(String containerName) {
        this(containerName, null);
    }

    public String containerName() {
        return containerName;
    }

    public boolean isDaemonReachable(CancelToken ct) {
        var r = runner.run("docker", List.of("version", "--format", "{{.Server.Version}}"), Duration.ofSeconds(5), ct);
        return r.exitCode() == 0 && !r.stdOut().isBlank();
    }

    public ContainerInfo inspect(CancelToken ct) {
        if (!isDaemonReachable(ct)) {
            return new ContainerInfo(containerName, ContainerState.DOCKER_NOT_RUNNING, null, null, null,
                    "Docker daemon is not running or 'docker' is not on PATH.");
        }

        var r = runner.run("docker", List.of("inspect", "--format", "{{json .}}", containerName),
                Duration.ofSeconds(5), ct);
        if (r.exitCode() != 0) {
            var notFound = r.stdErr().toLowerCase(Locale.ROOT).contains("no such object");
            return new ContainerInfo(containerName, notFound ? ContainerState.NOT_FOUND : ContainerState.UNKNOWN,
                    null, null, null, r.stdErr().trim());
        }

        try {
            var root = Json.PLAIN.readTree(r.stdOut());
            var state = root.path("State");
            var statusText = state.path("Status").asText("unknown");
            var startedAt = state.hasNonNull("StartedAt") ? state.get("StartedAt").asText() : null;
            var image = root.path("Config").hasNonNull("Image") ? root.path("Config").get("Image").asText() : null;
            var cs = switch (statusText) {
                case "created" -> ContainerState.CREATED;
                case "running" -> ContainerState.RUNNING;
                case "restarting" -> ContainerState.RESTARTING;
                case "exited" -> ContainerState.EXITED;
                case "paused" -> ContainerState.PAUSED;
                case "dead" -> ContainerState.DEAD;
                default -> ContainerState.UNKNOWN;
            };
            return new ContainerInfo(containerName, cs, image, startedAt, statusText, null);
        } catch (Exception ex) {
            return new ContainerInfo(containerName, ContainerState.UNKNOWN, null, null, null,
                    "Could not parse docker inspect output: " + ex.getMessage());
        }
    }

    public ProcessResult start(CancelToken ct) {
        return runner.run("docker", List.of("start", containerName), Duration.ofSeconds(30), ct);
    }

    public ProcessResult stop(CancelToken ct) {
        return runner.run("docker", List.of("stop", containerName), Duration.ofSeconds(30), ct);
    }

    public ProcessResult restart(CancelToken ct) {
        return runner.run("docker", List.of("restart", containerName), Duration.ofSeconds(60), ct);
    }

    public String getLogs(int lines, CancelToken ct) {
        var r = runner.run("docker", List.of("logs", "--tail", String.valueOf(lines), containerName),
                Duration.ofSeconds(10), ct);
        if (r.exitCode() == 0) return r.stdOut().isBlank() ? r.stdErr() : r.stdOut() + r.stdErr();
        return r.stdErr().trim();
    }
}
