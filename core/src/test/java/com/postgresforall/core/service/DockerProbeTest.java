package com.postgresforall.core.service;

import com.postgresforall.core.model.Diagnostics.ContainerState;
import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.testsupport.StubRunner;
import com.postgresforall.core.util.CancelToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DockerProbeTest {
    private static final CancelToken CT = CancelToken.NONE;

    @Test
    void isDaemonReachable_returnsFalse_whenDockerCommandFails() {
        var probe = new DockerProbe("pg", new StubRunner((f, a) -> new ProcessResult(1, "", "Cannot connect")));
        assertThat(probe.isDaemonReachable(CT)).isFalse();
    }

    @Test
    void isDaemonReachable_returnsTrue_onZeroExitWithVersionOutput() {
        var probe = new DockerProbe("pg", new StubRunner((f, a) -> new ProcessResult(0, "20.10.21", "")));
        assertThat(probe.isDaemonReachable(CT)).isTrue();
    }

    @Test
    void inspect_returnsNotFound_whenContainerMissing() {
        var probe = new DockerProbe("scheduleme-postgres", new StubRunner((f, a) -> a.get(0).equals("version")
                ? new ProcessResult(0, "20.10.21", "")
                : new ProcessResult(1, "", "Error: No such object: scheduleme-postgres")));
        assertThat(probe.inspect(CT).state()).isEqualTo(ContainerState.NOT_FOUND);
    }

    @Test
    void inspect_parsesRunningContainer() {
        var json = "{\"Id\":\"abc\",\"State\":{\"Status\":\"running\",\"StartedAt\":\"2026-05-23T10:00:00Z\"},"
                + "\"Config\":{\"Image\":\"postgres:16\"}}";
        var probe = new DockerProbe("pg", new StubRunner((f, a) -> a.get(0).equals("version")
                ? new ProcessResult(0, "20.10.21", "") : new ProcessResult(0, json, "")));
        var info = probe.inspect(CT);
        assertThat(info.state()).isEqualTo(ContainerState.RUNNING);
        assertThat(info.image()).isEqualTo("postgres:16");
        assertThat(info.startedAt()).isEqualTo("2026-05-23T10:00:00Z");
    }

    @Test
    void inspect_parsesExitedContainer() {
        var json = "{\"State\":{\"Status\":\"exited\",\"StartedAt\":\"2026-01-01T00:00:00Z\"},\"Config\":{\"Image\":\"postgres:16\"}}";
        var probe = new DockerProbe("pg", new StubRunner((f, a) -> a.get(0).equals("version")
                ? new ProcessResult(0, "20.10.21", "") : new ProcessResult(0, json, "")));
        assertThat(probe.inspect(CT).state()).isEqualTo(ContainerState.EXITED);
    }

    @Test
    void inspect_returnsDockerNotRunning_whenDaemonDown() {
        var probe = new DockerProbe("pg", new StubRunner((f, a) -> new ProcessResult(1, "", "")));
        assertThat(probe.inspect(CT).state()).isEqualTo(ContainerState.DOCKER_NOT_RUNNING);
    }

    @Test
    void containerCommands_passNameAsSeparateArgument() {
        var runner = new StubRunner((f, a) -> new ProcessResult(0, "", ""));
        var probe = new DockerProbe("my container", runner);
        probe.start(CT);
        probe.getLogs(25, CT);
        assertThat(runner.calls.get(0).args()).containsExactly("start", "my container");
        assertThat(runner.calls.get(1).args()).containsExactly("logs", "--tail", "25", "my container");
    }

    @Test
    void tryProbeTcp_returnsFalse_forClosedPort() {
        // Port 1 is reserved/unused — should refuse instantly
        assertThat(PostgresHandshake.tryProbeTcp("127.0.0.1", 1, Duration.ofMillis(500))).isFalse();
    }
}
