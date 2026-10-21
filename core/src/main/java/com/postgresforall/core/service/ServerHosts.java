package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.ServerKey;
import com.postgresforall.core.platform.LocalServiceSource;
import com.postgresforall.core.platform.MacPostgresServices;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.platform.ProcessRunner;
import com.postgresforall.core.util.CancelToken;

import java.time.Duration;

/** Works out what runs a server, and waits for servers to come up. */
public final class ServerHosts {
    private ServerHosts() { }

    private static String localUnmanagedReason() {
        return "local server not run by a known " + Platform.current().nativeServiceNoun() + " or container";
    }

    /** The host for a discovered server: its container, its service, or unmanaged. */
    public static ServerHost forServer(DiscoveredServer server, ProcessRunner runner) {
        if (server.dockerContainer() != null && !server.dockerContainer().isBlank()) {
            return new DockerHost(new DockerProbe(server.dockerContainer(), runner));
        }
        if (server.service() != null && !server.service().isBlank()) return forService(server.service(), runner);
        return new UnmanagedHost(ServerKey.isLoopback(server.host()) ? localUnmanagedReason() : "remote server");
    }

    /** The host for a native service name / launchd label on this OS. */
    public static ServerHost forService(String service, ProcessRunner runner) {
        if (service.startsWith(MacPostgresServices.POSTGRES_APP_PREFIX)) {
            return new UnmanagedHost("Postgres.app server — start / stop it from the Postgres.app window");
        }
        return Platform.isWindows()
                ? new WindowsServiceHost(service, runner)
                : new LaunchdServiceHost(service, runner);
    }

    /**
     * The host for a bare host:port (login window, --check): a native service
     * listening on that port wins, then the config's DockerContainerName when the
     * port is the configured one, else unmanaged.
     */
    public static ServerHost forProfile(ConnectionProfile profile, LocalServiceSource services, ProcessRunner runner,
                                        AppConfig config, CancelToken ct) {
        if (config == null) config = AppConfig.current();
        if (!ServerKey.isLoopback(profile.host)) return new UnmanagedHost("remote server");

        if (services == null) services = LocalServiceSource.forPlatform(runner);
        var svc = services.list(ct).stream().filter(s -> s.port() == profile.port).findFirst();
        if (svc.isPresent()) return forService(svc.get().name(), runner);

        if (profile.port == config.port && config.dockerContainerName != null && !config.dockerContainerName.isBlank()) {
            return new DockerHost(new DockerProbe(config.dockerContainerName, runner));
        }
        return new UnmanagedHost(localUnmanagedReason());
    }

    public static ServerHost forProfile(ConnectionProfile profile, CancelToken ct) {
        return forProfile(profile, null, null, null, ct);
    }

    public static boolean waitForPort(String host, int port, Duration total, CancelToken ct) {
        var deadline = System.nanoTime() + total.toNanos();
        while (System.nanoTime() < deadline) {
            ct.throwIfCancelled();
            if (PostgresHandshake.tryProbeTcp(host, port, Duration.ofMillis(500))) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
