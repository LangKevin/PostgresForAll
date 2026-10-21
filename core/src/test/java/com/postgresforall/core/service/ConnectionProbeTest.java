package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.Diagnostics.ProbeStatus;
import com.postgresforall.core.model.Diagnostics.ProbeStep;
import com.postgresforall.core.model.Diagnostics.Snapshot;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.ServerSource;
import com.postgresforall.core.platform.Elevation;
import com.postgresforall.core.platform.LocalPgService;
import com.postgresforall.core.platform.LocalServiceSource;
import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.service.ServerHost.Kind;
import com.postgresforall.core.service.ServerHost.RunState;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.StubRunner;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProbeTest {
    private static final CancelToken CT = CancelToken.NONE;

    private static ConnectionProfile profile(String host, int port) {
        var p = new ConnectionProfile();
        p.host = host;
        p.port = port;
        return p;
    }

    private static ProbeStep step(Snapshot snap, String prefix) {
        return snap.steps().stream().filter(s -> s.name().startsWith(prefix)).findFirst().orElseThrow();
    }

    private static String describe(Snapshot snap) {
        return snap.steps().stream().map(s -> s.status() + " " + s.name() + ": " + s.message())
                .collect(Collectors.joining("\n"));
    }

    @Test
    void probe_tcpClosed_skipsAuthAndQuerySteps() {
        // Port 1 — guaranteed closed
        var probe = new ConnectionProbe(new DockerHost(new DockerProbe("pg", StubRunner.noDocker())));

        var snap = probe.run(profile("127.0.0.1", 1), true, CT);

        assertThat(step(snap, "TCP").status()).isEqualTo(ProbeStatus.FAILED);
        assertThat(step(snap, "Auth").status()).isEqualTo(ProbeStatus.SKIPPED);
        assertThat(step(snap, "SELECT 1").status()).isEqualTo(ProbeStatus.SKIPPED);
        // Dead port + container server + no Docker → Docker is the likely culprit.
        assertThat(step(snap, "Docker daemon").status()).isEqualTo(ProbeStatus.FAILED);
        assertThat(snap.allOk()).isFalse();
    }

    @Test
    void probe_remoteHost_skipsHostSteps() {
        var snap = new ConnectionProbe(null).run(profile("remote.example.invalid", 5434), true, CT);

        var host = step(snap, "Server host");
        assertThat(host.status()).isEqualTo(ProbeStatus.SKIPPED);
        assertThat(host.message()).isEqualTo("remote server");
        assertThat(snap.steps()).noneMatch(s -> s.name().equals("Docker daemon"));
    }

    @PostgresAvailable
    void probe_withoutDocker_isHealthy_whenServerAnswers() {
        // The configured server is reachable; pretend it's "a container" on a
        // machine where Docker isn't installed. That must not fail the check.
        var probe = new ConnectionProbe(new DockerHost(new DockerProbe("scheduleme-postgres", StubRunner.noDocker())));

        var snap = probe.run(TestProfiles.owner(), true, CT);

        assertThat(snap.allOk()).as(describe(snap)).isTrue();
        var docker = step(snap, "Docker daemon");
        assertThat(docker.status()).isEqualTo(ProbeStatus.SKIPPED);
        assertThat(docker.message()).contains("not needed");
        assertThat(step(snap, "PG version").message()).isNotBlank();
    }

    @Test
    void probe_stoppedService_failsServiceStep_whenPortIsDead() {
        var sc = new StubRunner((file, args) -> file.equals("sc")
                ? new ProcessResult(0, "        STATE              : 1  STOPPED \n", "")
                : new ProcessResult(1, "", ""));
        var probe = new ConnectionProbe(new WindowsServiceHost("postgresql-x64-17", sc));

        var snap = probe.run(profile("127.0.0.1", 1), true, CT);

        assertThat(step(snap, "Service 'postgresql-x64-17'").status()).isEqualTo(ProbeStatus.FAILED);
        assertThat(snap.hostStatus().state()).isEqualTo(RunState.STOPPED);
    }

    @Test
    void forProfile_prefersNativeService_thenConfiguredContainer_thenUnmanaged() {
        var cfg = new AppConfig();
        cfg.port = 5444;
        cfg.dockerContainerName = "scheduleme-postgres";
        LocalServiceSource services = ct -> List.of(new LocalPgService("postgresql-x64-18", null, null, 5432, true));
        var runner = StubRunner.noDocker();

        var nativeHost = ServerHosts.forProfile(profile("localhost", 5432), services, runner, cfg, CT);
        var container = ServerHosts.forProfile(profile("127.0.0.1", 5444), services, runner, cfg, CT);
        var other = ServerHosts.forProfile(profile("localhost", 6000), services, runner, cfg, CT);
        var remote = ServerHosts.forProfile(profile("db.example.com", 5432), services, runner, cfg, CT);

        assertThat(nativeHost.kind()).isEqualTo(Kind.SERVICE);
        assertThat(nativeHost.name()).isEqualTo("postgresql-x64-18");
        assertThat(container.kind()).isEqualTo(Kind.DOCKER);
        assertThat(other.kind()).isEqualTo(Kind.UNMANAGED);
        assertThat(remote.kind()).isEqualTo(Kind.UNMANAGED);
        assertThat(remote.canControl()).isFalse();
    }

    @Test
    void forServer_picksContainerOrServiceOrUnmanaged() {
        var base = DiscoveredServer.of("localhost", 5444, ServerSource.PORT_SCAN);
        assertThat(ServerHosts.forServer(base.withDockerContainer("scheduleme-postgres"), null).kind())
                .isEqualTo(Kind.DOCKER);
        assertThat(ServerHosts.forServer(DiscoveredServer.of("localhost", 5432, 0)
                .withService("postgresql-x64-18", true), null).kind()).isEqualTo(Kind.SERVICE);
        assertThat(ServerHosts.forServer(DiscoveredServer.of("localhost", 5450, 0), null).kind())
                .isEqualTo(Kind.UNMANAGED);
    }

    @Test
    void forService_postgresApp_isUnmanaged() {
        var host = ServerHosts.forService("postgresapp:var-16", null);
        assertThat(host.kind()).isEqualTo(Kind.UNMANAGED);
        assertThat(host.description()).contains("Postgres.app");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsService_startStop_runNetElevated_andReportUacCancel() {
        var runner = new StubRunner((file, args) -> new ProcessResult(0, "", ""));
        var calls = runner.calls;
        // The user declines the UAC prompt: PowerShell's Start-Process fails and the script exits 1223.
        var declining = new StubRunner((file, args) -> new ProcessResult(1223, "", "The operation was canceled by the user."));
        var host = new WindowsServiceHost("postgresql-x64-18", runner);

        var start = host.start(CT);
        var stop = new WindowsServiceHost("postgresql-x64-18", declining).stop(CT);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).file()).isEqualTo("powershell");
        var script = calls.get(0).args().get(calls.get(0).args().size() - 1);
        assertThat(script).contains("-Verb RunAs")
                .contains("'cmd.exe'")
                .contains("'net start \"postgresql-x64-18\"'");
        assertThat(declining.calls.get(0).args().get(declining.calls.get(0).args().size() - 1))
                .contains("'net stop \"postgresql-x64-18\"'");
        assertThat(start.exitCode()).isZero();
        assertThat(stop.exitCode()).isNotZero();
        assertThat(stop.stdErr()).isEqualTo(Elevation.CANCELLED_MESSAGE).contains("UAC");
    }

    @Test
    void lastLines_keepsTail() {
        assertThat(ServerLogReader.lastLines("a\r\nb\nc\nd\n", 2)).isEqualTo("c\nd");
        assertThat(ServerLogReader.lastLines("a\nb", 10)).isEqualTo("a\nb");
    }

    @PostgresAvailable
    void serverLogReader_returnsLogOrSaysWhyNot() {
        var tail = ServerLogReader.readTail(TestProfiles.owner(), 50, 64 * 1024);
        assertThat(tail.text() != null || (tail.reason() != null && !tail.reason().isEmpty())).isTrue();
    }
}
