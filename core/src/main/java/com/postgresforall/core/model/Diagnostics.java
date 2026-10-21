package com.postgresforall.core.model;

import com.postgresforall.core.service.ServerHost;
import com.postgresforall.core.service.ServerHost.HostStatus;

import java.time.LocalDateTime;
import java.util.List;

/** Types produced by the connection health check. */
public final class Diagnostics {
    private Diagnostics() { }

    public enum ProbeStatus { PENDING, RUNNING, OK, FAILED, SKIPPED }

    public record ProbeStep(String name, ProbeStatus status, String message, double elapsedMs, String detail) {
        public static ProbeStep named(String name) {
            return new ProbeStep(name, ProbeStatus.PENDING, null, 0, null);
        }

        public ProbeStep with(ProbeStatus s, String msg) {
            return new ProbeStep(name, s, msg, 0, null);
        }

        public ProbeStep with(ProbeStatus s, String msg, double elapsed) {
            return new ProbeStep(name, s, msg, elapsed, null);
        }
    }

    public enum ContainerState { DOCKER_NOT_RUNNING, NOT_FOUND, CREATED, RUNNING, RESTARTING, EXITED, PAUSED, DEAD, UNKNOWN }

    public record ContainerInfo(String name, ContainerState state, String image, String startedAt,
                                String statusText, String rawError) { }

    /**
     * @param host       what runs the server (container / service / unmanaged)
     * @param hostStatus the host's own view of the server process, when it has one
     */
    public record Snapshot(List<ProbeStep> steps, ContainerInfo container, boolean dockerAvailable,
                           ServerHost host, HostStatus hostStatus, LocalDateTime checkedAt) {

        public boolean allOk() {
            return steps.stream().allMatch(s -> s.status() == ProbeStatus.OK || s.status() == ProbeStatus.SKIPPED)
                    && steps.stream().anyMatch(s -> s.status() == ProbeStatus.OK);
        }
    }
}
