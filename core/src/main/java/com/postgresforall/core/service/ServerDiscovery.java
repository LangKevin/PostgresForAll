package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.ServerKey;
import com.postgresforall.core.model.ServerSource;
import com.postgresforall.core.platform.LocalPgService;
import com.postgresforall.core.platform.LocalServiceSource;
import com.postgresforall.core.util.CancelToken;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Builds the list of PostgreSQL servers for the Object Explorer, the way SSMS
 * lists registered servers. Sources, merged by host:port:
 * <ol>
 *   <li>The login server (always first).</li>
 *   <li>{@link AppConfig#servers}.</li>
 *   <li>With a local scan: native services, a Postgres handshake probe of
 *       {@link AppConfig#discoveryPorts} on localhost, and — only if Docker is
 *       installed — container names for published ports.</li>
 * </ol>
 * Nothing here requires Docker; it only adds labels and (opt-in) login hints.
 */
public final class ServerDiscovery {

    @FunctionalInterface
    public interface HandshakeProbe {
        boolean isPostgres(String host, int port, Duration timeout);
    }

    public static final Duration PROBE_TIMEOUT = Duration.ofMillis(400);

    private final AppConfig config;
    private final LocalServiceSource services;
    private final DockerContainers docker;
    private final HandshakeProbe handshake;

    public ServerDiscovery() {
        this(null, null, null, null);
    }

    public ServerDiscovery(AppConfig config, LocalServiceSource services, DockerContainers docker,
                           HandshakeProbe handshake) {
        this.config = config != null ? config : AppConfig.current();
        this.services = services != null ? services : LocalServiceSource.forPlatform(null);
        this.docker = docker != null ? docker : new DockerContainers();
        this.handshake = handshake != null ? handshake : PostgresHandshake::isPostgres;
    }

    public List<DiscoveredServer> discover(ConnectionProfile login, boolean scanLocalMachine, CancelToken ct) {
        Map<String, DiscoveredServer> byKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        var order = new ArrayList<String>();

        java.util.function.Consumer<DiscoveredServer> add = s -> {
            var existing = byKey.get(s.key());
            if (existing != null) {
                byKey.put(s.key(), existing.mergeWith(s));
            } else {
                byKey.put(s.key(), s);
                order.add(s.key());
            }
        };

        add.accept(DiscoveredServer.of(login.host, login.port, ServerSource.LOGIN)
                .withDockerContainer(ServerKey.isLoopback(login.host) && login.port == config.port
                        ? nullIfBlank(config.dockerContainerName) : null));

        for (var e : config.servers) {
            add.accept(new DiscoveredServer(e.host, e.port, nullIfBlank(e.name), ServerSource.CONFIG,
                    nullIfBlank(e.dockerContainer), null, null,
                    nullIfBlank(e.username), null, nullIfBlank(e.database)));
        }

        if (scanLocalMachine) {
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var servicesTask = pool.submit(() -> services.list(ct));
                var dockerTask = pool.submit(() -> docker.listRunning(ct));
                List<LocalPgService> svcs = get(servicesTask, List.of());
                List<DockerContainers.ContainerPorts> containers = get(dockerTask, List.of());

                // Handshake the configured port range, plus every port a service or
                // Postgres container claims (they may sit outside the range).
                var ports = new TreeSet<>(config.parseDiscoveryPorts());
                for (var s : svcs) ports.add(s.port());
                for (var c : containers) if (c.looksLikePostgres()) ports.addAll(c.hostPorts());

                var probes = new ArrayList<Future<Integer>>();
                for (var p : ports) {
                    probes.add(pool.submit(() -> handshake.isPostgres("localhost", p, PROBE_TIMEOUT) ? p : -1));
                }
                Set<Integer> live = probes.stream().map(f -> get(f, -1)).filter(p -> p > 0)
                        .collect(Collectors.toCollection(TreeSet::new));

                for (var s : svcs) {
                    // A stopped service is still worth listing — diagnostics can start it.
                    if (!live.contains(s.port()) && !Boolean.FALSE.equals(s.isRunning())) continue;
                    add.accept(DiscoveredServer.of("localhost", s.port(), ServerSource.SERVICE)
                            .withLabel(s.displayName() != null ? s.displayName() : s.name())
                            .withService(s.name(), s.isRunning()));
                }

                for (var c : containers) {
                    for (var p : c.hostPorts()) {
                        if (!live.contains(p)) continue;
                        var server = DiscoveredServer.of("localhost", p, ServerSource.DOCKER)
                                .withLabel(c.name()).withDockerContainer(c.name());
                        if (config.readDockerCredentials) {
                            var env = docker.readPostgresEnv(c.name(), ct);
                            server = server.withSuggestions(env.user(), env.password(), env.database());
                        }
                        add.accept(server);
                    }
                }

                for (var p : live) add.accept(DiscoveredServer.of("localhost", p, ServerSource.PORT_SCAN));
            }
        }

        // Login server first, then the rest by host and port for a stable tree.
        var result = new ArrayList<DiscoveredServer>();
        if (!order.isEmpty()) result.add(byKey.get(order.get(0)));
        order.stream().skip(1).map(byKey::get)
                .sorted(Comparator.comparing(DiscoveredServer::host).thenComparingInt(DiscoveredServer::port))
                .forEach(result::add);
        return result;
    }

    private static <T> T get(Future<T> f, T fallback) {
        try {
            return f.get();
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
