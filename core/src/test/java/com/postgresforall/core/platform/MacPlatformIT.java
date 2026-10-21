package com.postgresforall.core.platform;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.service.LaunchdServiceHost;
import com.postgresforall.core.service.PgClientTools;
import com.postgresforall.core.service.PostgresHandshake;
import com.postgresforall.core.service.ServerHost;
import com.postgresforall.core.service.ServerHosts;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real macOS behaviour, against the Homebrew PostgreSQL the Mac CI job installs
 * (launchd job homebrew.mxcl.postgresql@NN, listening on the config's port).
 * Opt-in via PFA_MAC_CI=1 because it stops and restarts that server.
 */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "PFA_MAC_CI", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MacPlatformIT {
    private static final CancelToken CT = CancelToken.NONE;

    /** Whatever happened above, leave the server running for the rest of the suite. */
    @org.junit.jupiter.api.AfterAll
    static void ensureRunning() {
        if (PostgresHandshake.tryProbeTcp("localhost", port(), Duration.ofMillis(500))) return;
        ServerHosts.forProfile(TestProfiles.owner(), CT).start(CT);
        ServerHosts.waitForPort("localhost", port(), Duration.ofSeconds(60), CT);
    }

    private static int port() {
        return AppConfig.current().port;
    }

    @Test
    @Order(1)
    void discovery_findsHomebrewLaunchdJob_withPortFromDataDir() {
        var services = new MacPostgresServices(null).list(CT);
        System.out.println("launchd services: " + services);
        assertThat(services).anySatisfy(s -> {
            assertThat(s.name()).startsWith("homebrew.mxcl.postgresql");
            assertThat(s.port()).isEqualTo(port());
            assertThat(s.isRunning()).isTrue();
            assertThat(s.dataDirectory()).isNotBlank();
        });
    }

    @Test
    @Order(2)
    void forProfile_resolvesLaunchdHost() {
        var host = ServerHosts.forProfile(TestProfiles.owner(), CT);
        assertThat(host).isInstanceOf(LaunchdServiceHost.class);
        assertThat(host.canControl()).isTrue();
        assertThat(host.needsElevation()).isFalse(); // user agent, driven by brew services
        assertThat(host.status(CT).state()).isEqualTo(ServerHost.RunState.RUNNING);
    }

    @Test
    @Order(3)
    void launchdHost_stopsAndStartsServer() {
        var host = ServerHosts.forProfile(TestProfiles.owner(), CT);

        var stop = host.stop(CT);
        assertThat(stop.exitCode()).as(stop.stdErr()).isZero();
        assertThat(waitClosed("localhost", port(), Duration.ofSeconds(30))).isTrue();
        assertThat(host.status(CT).state()).isEqualTo(ServerHost.RunState.STOPPED);
        // brew removed the plist; discovery must still list the stopped server.
        assertThat(new MacPostgresServices(null).list(CT)).anySatisfy(s -> {
            assertThat(s.name()).isEqualTo(host.name());
            assertThat(s.port()).isEqualTo(port());
            assertThat(s.isRunning()).isFalse();
        });

        var start = host.start(CT);
        assertThat(start.exitCode()).as(start.stdErr()).isZero();
        assertThat(ServerHosts.waitForPort("localhost", port(), Duration.ofSeconds(60), CT)).isTrue();
        assertThat(host.status(CT).state()).isEqualTo(ServerHost.RunState.RUNNING);
    }

    @Test
    @Order(4)
    void portOwners_lsofFindsPostgres() {
        var owners = PortOwners.find(port(), null);
        assertThat(owners).anySatisfy(o -> assertThat(o.processName()).startsWith("postgres"));
    }

    @Test
    @Order(5)
    void clientTools_foundInKegOnlyHomebrewFormula() {
        var tools = PgClientTools.locate("");
        assertThat(tools).isNotNull();
        System.out.println("pg tools: " + tools);
        assertThat(tools.majorVersion()).isGreaterThanOrEqualTo(14);
    }

    @Test
    @Order(6)
    void keychain_roundTrip() {
        var store = new KeyringSecretStore();
        var id = "ci-test/" + UUID.randomUUID();
        var stored = store.protect("s3cret-value", id);
        assertThat(stored).isEqualTo("keychain:" + id);
        assertThat(store.unprotect(stored, id)).isEqualTo("s3cret-value");
        store.forget(id);
        assertThat(store.unprotect(stored, id)).isEmpty();
    }

    @Test
    @Order(7)
    void executables_resolveWithoutShellPath() {
        // A Finder-launched app gets PATH=/usr/bin:/bin:/usr/sbin:/sbin; brew and
        // the Postgres binaries must still be found via the extra folders.
        assertThat(Executables.EXTRA_UNIX_DIRS).contains("/opt/homebrew/bin", "/usr/local/bin");
        var brew = Executables.resolve("brew");
        assertThat(brew).startsWith("/");
        assertThat(PostgresHandshake.isPostgres("localhost", port(), Duration.ofSeconds(2))).isTrue();
    }

    private static boolean waitClosed(String host, int port, Duration total) {
        var deadline = System.nanoTime() + total.toNanos();
        while (System.nanoTime() < deadline) {
            if (!PostgresHandshake.tryProbeTcp(host, port, Duration.ofMillis(300))) return true;
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
