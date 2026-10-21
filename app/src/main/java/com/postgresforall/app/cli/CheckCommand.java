package com.postgresforall.app.cli;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.Diagnostics.ContainerState;
import com.postgresforall.core.model.Diagnostics.ProbeStatus;
import com.postgresforall.core.model.Diagnostics.ProbeStep;
import com.postgresforall.core.model.Diagnostics.Snapshot;
import com.postgresforall.core.service.ConnectionProbe;
import com.postgresforall.core.service.DockerHost;
import com.postgresforall.core.service.ServerHosts;
import com.postgresforall.core.util.CancelToken;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;

/**
 * {@code --check [--start] [--host H] [--port P] [--db D] [--user U] [--password X]}:
 * prints the connection health check and exits with a code a script can act on.
 */
public final class CheckCommand {
    private CheckCommand() { }

    public static final int EXIT_OK = 0;
    public static final int EXIT_TCP_CLOSED = 2;
    public static final int EXIT_AUTH_FAILED = 3;
    public static final int EXIT_QUERY_FAILED = 4;
    public static final int EXIT_DOCKER_DOWN = 5;
    public static final int EXIT_CONTAINER_MISSING = 6;
    public static final int EXIT_START_TIMEOUT = 7;

    public static int run(String[] args, PrintStream out) {
        var start = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("--start"));
        var profile = buildProfile(args);
        var ct = CancelToken.NONE;
        // Native service, configured container, or unmanaged — Docker is only
        // consulted when the server is known to live in a container.
        var host = ServerHosts.forProfile(profile, ct);
        var probe = new ConnectionProbe(host);

        out.println("[INFO ] " + profile.host + ":" + profile.port + " — " + host.description());
        var snap = probe.run(profile, true, ct);
        print(snap, out);
        var classified = classify(snap);

        if (classified == EXIT_OK || !start) return classified;

        if (!host.canControl()) {
            out.println("[INFO ] --start ignored: " + host.description() + " isn't managed by this app.");
            return classified;
        }
        if (classified == EXIT_DOCKER_DOWN) {
            out.println("[FAIL ] Docker isn't running. Start Docker Desktop (or your Docker engine) and retry.");
            return EXIT_DOCKER_DOWN;
        }
        if (classified == EXIT_CONTAINER_MISSING) {
            out.println("[FAIL ] Container '" + host.name() + "' not found.");
            out.println("        Create it (e.g. docker compose up -d in the ScheduleMe repo), or");
            out.println("        point Host/Port at a native PostgreSQL install instead.");
            return EXIT_CONTAINER_MISSING;
        }

        out.println("[INFO ] Attempting to start " + host.description() + "...");
        var t0 = System.nanoTime();
        var started = host.start(ct);
        if (started.exitCode() != 0) {
            out.println("[FAIL ] start failed: " + started.stdErr().trim());
            return EXIT_START_TIMEOUT;
        }
        out.printf("[ OK  ] start                           (%d ms)%n", (System.nanoTime() - t0) / 1_000_000);

        out.println("[INFO ] Waiting for port " + profile.port + "...");
        if (!ServerHosts.waitForPort(profile.host, profile.port, Duration.ofSeconds(30), ct)) {
            out.println("[FAIL ] Started but the port never opened within 30s.");
            if (host instanceof DockerHost) out.println("        Check 'docker logs " + host.name() + "'");
            return EXIT_START_TIMEOUT;
        }

        out.println("[ OK  ] Port open. Re-running checks...");
        out.println();
        snap = probe.run(profile, true, ct);
        print(snap, out);
        return classify(snap);
    }

    private static ConnectionProfile buildProfile(String[] args) {
        var p = ConnectionProfile.fromConfig();
        for (var i = 0; i < args.length; i++) {
            var hasNext = i + 1 < args.length;
            switch (args[i]) {
                case "--host" -> { if (hasNext) p.host = args[++i]; }
                case "--port" -> {
                    if (hasNext) {
                        try {
                            p.port = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException ignored) {
                            // keep the configured port
                        }
                    }
                }
                case "--db" -> { if (hasNext) p.database = args[++i]; }
                case "--user" -> { if (hasNext) p.username = args[++i]; }
                case "--password" -> { if (hasNext) p.password = args[++i]; }
                default -> { }
            }
        }
        if (p.password == null || p.password.isEmpty()) {
            var env = System.getenv("PGPASSWORD");
            p.password = env == null ? "" : env;
        }
        return p;
    }

    static int classify(Snapshot snap) {
        ProbeStep tcp = null, docker = null, container = null, auth = null, q = null;
        for (var s : snap.steps()) {
            if (s.name().startsWith("TCP")) tcp = s;
            else if (s.name().equals("Docker daemon")) docker = s;
            else if (s.name().startsWith("Container")) container = s;
            else if (s.name().startsWith("Auth")) auth = s;
            else if (s.name().equals("SELECT 1")) q = s;
        }
        if (is(auth, ProbeStatus.OK) && is(q, ProbeStatus.OK)) return EXIT_OK;
        if (is(q, ProbeStatus.FAILED)) return EXIT_QUERY_FAILED;
        if (is(auth, ProbeStatus.FAILED)) return EXIT_AUTH_FAILED;
        // Host steps only fail when the port is dead, so these never fire on a
        // machine that simply has no Docker.
        if (is(container, ProbeStatus.FAILED) && snap.container() != null
                && snap.container().state() == ContainerState.NOT_FOUND) {
            return EXIT_CONTAINER_MISSING;
        }
        if (is(docker, ProbeStatus.FAILED)) return EXIT_DOCKER_DOWN;
        return EXIT_TCP_CLOSED;
    }

    private static boolean is(ProbeStep s, ProbeStatus status) {
        return s != null && s.status() == status;
    }

    private static void print(Snapshot snap, PrintStream out) {
        for (var s : snap.steps()) {
            var marker = switch (s.status()) {
                case OK -> "[ OK  ]";
                case FAILED -> "[FAIL ]";
                case SKIPPED -> "[SKIP ]";
                default -> "[ .. ]";
            };
            var elapsed = s.elapsedMs() > 0 ? String.format(" (%,.0f ms)", s.elapsedMs()) : "";
            out.printf("%s %-42s %s%s%n", marker, s.name(), s.message() == null ? "" : s.message(), elapsed);
        }
        if (snap.hostStatus() != null && snap.hostStatus().detail() != null) {
            out.println("        " + snap.hostStatus().detail());
        }
    }
}
