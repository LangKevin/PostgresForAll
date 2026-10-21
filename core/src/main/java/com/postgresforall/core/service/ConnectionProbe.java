package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.Diagnostics.ContainerInfo;
import com.postgresforall.core.model.Diagnostics.ProbeStatus;
import com.postgresforall.core.model.Diagnostics.ProbeStep;
import com.postgresforall.core.model.Diagnostics.Snapshot;
import com.postgresforall.core.service.ServerHost.HostStatus;
import com.postgresforall.core.service.ServerHost.RunState;
import com.postgresforall.core.util.CancelToken;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * Step-by-step connection health check: TCP, the server's host (container /
 * native service / unmanaged), auth, a query, version.
 *
 * <p>Host steps are informational whenever the server answers on TCP — a
 * machine without Docker, or a server this app doesn't manage, still checks
 * green. They only fail when the server is unreachable, because then the host
 * is the likely culprit and offers the fix.
 */
public final class ConnectionProbe {
    private final ServerHost host;

    /** @param host the server's host; null resolves it from the profile at run time */
    public ConnectionProbe(ServerHost host) {
        this.host = host;
    }

    public Snapshot run(ConnectionProfile profile, boolean includeHost, CancelToken ct) {
        var steps = new ArrayList<ProbeStep>();
        ContainerInfo container = null;
        var dockerAvailable = false;
        HostStatus hostStatus = null;

        // 1) TCP
        var t0 = System.nanoTime();
        var tcpOk = PostgresHandshake.tryProbeTcp(profile.host, profile.port, Duration.ofSeconds(2));
        steps.add(ProbeStep.named("TCP " + profile.host + ":" + profile.port).with(
                tcpOk ? ProbeStatus.OK : ProbeStatus.FAILED,
                tcpOk ? "open" : "connection refused / no listener", ms(t0)));

        // 2) Host
        var h = includeHost
                ? (host != null ? host : ServerHosts.forProfile(profile, ct))
                : new UnmanagedHost("host checks skipped");
        // A host problem only counts as a failure when it explains a dead port.
        var problem = tcpOk ? ProbeStatus.SKIPPED : ProbeStatus.FAILED;

        if (h instanceof DockerHost docker && includeHost) {
            t0 = System.nanoTime();
            dockerAvailable = docker.probe().isDaemonReachable(ct);
            steps.add(ProbeStep.named("Docker daemon").with(
                    dockerAvailable ? ProbeStatus.OK : problem,
                    dockerAvailable ? "reachable"
                            : tcpOk ? "not running or not installed — not needed, the server answers anyway"
                            : "not running or not installed",
                    ms(t0)));

            var containerStep = ProbeStep.named("Container '" + docker.name() + "'");
            if (dockerAvailable) {
                t0 = System.nanoTime();
                container = docker.probe().inspect(ct);
                hostStatus = docker.status(ct);
                steps.add(containerStep.with(hostStatus.state() == RunState.RUNNING ? ProbeStatus.OK : problem,
                        hostStatus.text(), ms(t0)));
            } else {
                steps.add(containerStep.with(ProbeStatus.SKIPPED, "Docker unavailable"));
            }
        } else if (h.kind() == ServerHost.Kind.SERVICE) {
            t0 = System.nanoTime();
            hostStatus = h.status(ct);
            steps.add(ProbeStep.named("Service '" + h.name() + "'").with(
                    hostStatus.state() == RunState.RUNNING ? ProbeStatus.OK : problem, hostStatus.text(), ms(t0)));
        } else {
            steps.add(ProbeStep.named("Server host").with(ProbeStatus.SKIPPED, h.description()));
        }

        // 3) Auth
        var auth = ProbeStep.named("Auth " + profile.username + "@" + profile.database);
        if (!tcpOk) {
            steps.add(auth.with(ProbeStatus.SKIPPED, "TCP not reachable"));
            steps.add(ProbeStep.named("SELECT 1").with(ProbeStatus.SKIPPED, "TCP not reachable"));
            steps.add(ProbeStep.named("PG version").with(ProbeStatus.SKIPPED, "TCP not reachable"));
            return new Snapshot(steps, container, dockerAvailable, h, hostStatus, LocalDateTime.now());
        }

        t0 = System.nanoTime();
        try (var conn = profile.open()) {
            steps.add(auth.with(ProbeStatus.OK, "authenticated", ms(t0)));
            var meta = conn.getMetaData();
            var version = meta.getDatabaseMajorVersion() + "." + meta.getDatabaseMinorVersion();

            // 4) SELECT 1
            var query = ProbeStep.named("SELECT 1");
            t0 = System.nanoTime();
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT 1")) {
                rs.next();
                steps.add(query.with(ProbeStatus.OK, "returned " + rs.getInt(1), ms(t0)));
            } catch (SQLException ex) {
                steps.add(query.with(ProbeStatus.FAILED, ConnectionErrors.message(ex), ms(t0)));
            }

            // 5) PG version
            steps.add(ProbeStep.named("PG version").with(ProbeStatus.OK, version));
        } catch (SQLException ex) {
            steps.add(auth.with(ProbeStatus.FAILED, ConnectionErrors.message(ex), ms(t0)));
            steps.add(ProbeStep.named("SELECT 1").with(ProbeStatus.SKIPPED, "auth failed"));
            steps.add(ProbeStep.named("PG version").with(ProbeStatus.SKIPPED, "auth failed"));
        }
        return new Snapshot(steps, container, dockerAvailable, h, hostStatus, LocalDateTime.now());
    }

    /**
     * Starts the server through its host (container or service) and waits for
     * the port. False when the host can't be controlled or the start failed.
     */
    public boolean startAndWait(ConnectionProfile profile, Duration wait, CancelToken ct) {
        var h = host != null ? host : ServerHosts.forProfile(profile, ct);
        if (!h.canControl()) return false;
        var status = h.status(ct);
        if (status.state() == RunState.MISSING || status.state() == RunState.UNAVAILABLE) return false;
        if (status.state() != RunState.RUNNING && h.start(ct).exitCode() != 0) return false;
        return ServerHosts.waitForPort(profile.host, profile.port, wait, ct);
    }

    private static double ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1e6;
    }
}
