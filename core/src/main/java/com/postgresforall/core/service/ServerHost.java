package com.postgresforall.core.service;

import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.util.CancelToken;

/**
 * Whatever runs a PostgreSQL server: a Docker container, a native service
 * (Windows service / launchd job), or something this app can't manage (remote,
 * unknown). Diagnostics use it for status, start / stop and host-level logs;
 * nothing requires a host to be controllable.
 */
public interface ServerHost {

    enum Kind { UNMANAGED, DOCKER, SERVICE }

    enum RunState { RUNNING, STOPPED, MISSING, UNAVAILABLE, UNKNOWN }

    /** What the host says about the server process (independent of whether Postgres answers). */
    record HostStatus(RunState state, String text, String detail) {
        public HostStatus(RunState state, String text) {
            this(state, text, null);
        }
    }

    Kind kind();

    /** Container, service name or launchd label; empty for unmanaged. */
    String name();

    /** "Docker container 'x'", "Windows service 'y'", ... */
    String description();

    boolean canControl();

    /** True when start / stop make the OS ask for admin rights. */
    default boolean needsElevation() {
        return false;
    }

    HostStatus status(CancelToken ct);

    ProcessResult start(CancelToken ct);

    ProcessResult stop(CancelToken ct);

    ProcessResult restart(CancelToken ct);

    /** Host-level server output (e.g. docker logs); null when the host has none. */
    String logs(int lines, CancelToken ct);
}
