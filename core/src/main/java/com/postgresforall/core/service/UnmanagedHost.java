package com.postgresforall.core.service;

import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.util.CancelToken;

/** A server this app can't manage: remote, or local without a known service / container. */
public final class UnmanagedHost implements ServerHost {
    private final String reason;

    public UnmanagedHost(String reason) {
        this.reason = reason;
    }

    @Override
    public Kind kind() {
        return Kind.UNMANAGED;
    }

    @Override
    public String name() {
        return "";
    }

    @Override
    public String description() {
        return reason;
    }

    @Override
    public boolean canControl() {
        return false;
    }

    @Override
    public HostStatus status(CancelToken ct) {
        return new HostStatus(RunState.UNKNOWN, reason);
    }

    private static ProcessResult notSupported() {
        return new ProcessResult(-1, "", "This server isn't managed by PostgresForAll.");
    }

    @Override
    public ProcessResult start(CancelToken ct) {
        return notSupported();
    }

    @Override
    public ProcessResult stop(CancelToken ct) {
        return notSupported();
    }

    @Override
    public ProcessResult restart(CancelToken ct) {
        return notSupported();
    }

    @Override
    public String logs(int lines, CancelToken ct) {
        return null;
    }
}
