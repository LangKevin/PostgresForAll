package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.config.ServerEntry;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.ServerKey;
import com.postgresforall.core.model.ServerSource;
import com.postgresforall.core.platform.LocalPgService;
import com.postgresforall.core.platform.LocalServiceSource;
import com.postgresforall.core.platform.ProcessResult;
import com.postgresforall.core.platform.WindowsPostgresServices;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.StubRunner;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ServerDiscoveryTest {
    private static final CancelToken CT = CancelToken.NONE;

    // This machine's real layout, as `docker ps` reports it.
    private static final String DOCKER_PS =
            "scheduleme-postgres\tpostgres:16\t0.0.0.0:5444->5432/tcp, [::]:5444->5432/tcp\n"
                    + "operationshub-postgres\tpostgres:16-alpine\t0.0.0.0:5434->5432/tcp, [::]:5434->5432/tcp\n"
                    + "operationshub-redis\tredis:7\t0.0.0.0:6380->6379/tcp, [::]:6380->6379/tcp\n"
                    + "clinical-audit-db\tpostgres:15\t0.0.0.0:55432->5432/tcp\n";

    private static LocalServiceSource services(LocalPgService... s) {
        return ct -> List.of(s);
    }

    private static ServerDiscovery.HandshakeProbe postgresOn(Integer... ports) {
        var set = Set.of(ports);
        return (host, port, timeout) -> set.contains(port);
    }

    private static DockerContainers docker(String ps, String inspectEnv) {
        return new DockerContainers(new StubRunner((file, args) ->
                args.get(0).equals("ps") && ps != null ? new ProcessResult(0, ps, "")
                        : args.get(0).equals("inspect") && inspectEnv != null ? new ProcessResult(0, inspectEnv, "")
                        : new ProcessResult(1, "", "docker: command not found")));
    }

    private static DockerContainers docker(String ps) {
        return docker(ps, null);
    }

    private static ConnectionProfile login(int port) {
        var p = new ConnectionProfile();
        p.host = "localhost";
        p.port = port;
        return p;
    }

    private static AppConfig config() {
        var c = new AppConfig();
        c.port = 5444;
        c.dockerContainerName = "";
        c.discoveryPorts = "5432-5450,55432";
        return c;
    }

    private static List<String> keys(List<DiscoveredServer> servers) {
        return servers.stream().map(DiscoveredServer::key).toList();
    }

    @Test
    void parsePs_readsNamesImagesAndHostPorts() {
        var list = DockerContainers.parsePs(DOCKER_PS);
        assertThat(list).hasSize(4);
        assertThat(list.get(0).hostPorts()).containsExactly(5444);
        assertThat(list.get(0).looksLikePostgres()).isTrue();
        assertThat(list.get(2).looksLikePostgres()).isFalse();
        assertThat(list.get(3).hostPorts()).containsExactly(55432);
    }

    @Test
    void parseEnv_readsPostgresVariables_defaultingUserToPostgres() {
        assertThat(DockerContainers.parseEnv(
                "[\"PATH=/usr/bin\",\"POSTGRES_USER=app\",\"POSTGRES_PASSWORD=pw\",\"POSTGRES_DB=appdb\"]"))
                .isEqualTo(new DockerContainers.PostgresEnv("app", "pw", "appdb"));
        assertThat(DockerContainers.parseEnv("[\"POSTGRES_PASSWORD=pw\"]"))
                .isEqualTo(new DockerContainers.PostgresEnv("postgres", "pw", null));
        assertThat(DockerContainers.parseEnv("not json"))
                .isEqualTo(new DockerContainers.PostgresEnv(null, null, null));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "\"C:\\PG\\18\\bin\\pg_ctl.exe\" runservice -N \"pg18\" -D \"C:\\PG\\18\\data\" -w|C:\\PG\\18\\data",
            "pg_ctl.exe runservice -D C:\\data -w|C:\\data",
            "pg_ctl.exe runservice|"})
    void parseDataDirectory_readsDashD(String imagePath, String expected) {
        assertThat(WindowsPostgresServices.parseDataDirectory(imagePath)).isEqualTo(expected);
    }

    @Test
    void parsePort_usesLastUncommentedSetting() {
        assertThat(LocalServiceSource.parsePort("#port = 6000\nmax_connections = 100")).isEqualTo(5432);
        assertThat(LocalServiceSource.parsePort("port = 5440\n#port = 1\nport = 5433   # comment")).isEqualTo(5433);
        assertThat(LocalServiceSource.parsePort(null)).isEqualTo(5432);
    }

    @Test
    void parseScRunning_readsState() {
        assertThat(WindowsPostgresServices.parseScRunning("        STATE              : 4  RUNNING \n")).isTrue();
        assertThat(WindowsPostgresServices.parseScRunning("        STATE              : 1  STOPPED \n")).isFalse();
        assertThat(WindowsPostgresServices.parseScRunning("garbage")).isNull();
    }

    @Test
    void discover_findsEveryPostgresOnTheBox_andSkipsRedis() {
        var discovery = new ServerDiscovery(config(),
                services(new LocalPgService("postgresql-x64-18", "PostgreSQL Server 18", "C:\\data", 5432, true)),
                docker(DOCKER_PS), postgresOn(5432, 5434, 5444, 55432));

        var servers = discovery.discover(login(5432), true, CT);

        assertThat(keys(servers)).containsExactly("localhost:5432", "localhost:5434", "localhost:5444", "localhost:55432");
        assertThat(servers.get(0).sources() & ServerSource.LOGIN).isNotZero();
        assertThat(servers.get(0).service()).isEqualTo("postgresql-x64-18");
        assertThat(servers.stream().filter(s -> s.port() == 5444).findFirst().orElseThrow().dockerContainer())
                .isEqualTo("scheduleme-postgres");
        assertThat(servers).noneMatch(s -> s.port() == 6380);
    }

    @Test
    void discover_worksWithoutDocker() {
        var discovery = new ServerDiscovery(config(), services(), docker(null), postgresOn(5432, 5444));

        var servers = discovery.discover(login(5432), true, CT);

        assertThat(servers.stream().map(DiscoveredServer::port).toList()).containsExactly(5432, 5444);
        assertThat(servers).allMatch(s -> s.dockerContainer() == null);
    }

    @Test
    void discover_withoutScan_isLoginPlusConfiguredServersOnly() {
        var cfg = config();
        var prod = new ServerEntry("db.example.com", 5432);
        prod.name = "prod";
        prod.username = "admin";
        cfg.servers.add(prod);
        var discovery = new ServerDiscovery(cfg, services(), docker(DOCKER_PS), postgresOn(5434, 5444));

        var servers = discovery.discover(login(5444), false, CT);

        assertThat(keys(servers)).containsExactly("localhost:5444", "db.example.com:5432");
        assertThat(servers.get(1).suggestedUsername()).isEqualTo("admin");
        assertThat(servers.get(1).label()).isEqualTo("prod");
    }

    @Test
    void discover_readsContainerCredentials_onlyWhenOptedIn() {
        var env = "[\"POSTGRES_USER=ops\",\"POSTGRES_PASSWORD=opspw\"]";

        var off = new ServerDiscovery(config(), services(), docker(DOCKER_PS, env), postgresOn(5434))
                .discover(login(5432), true, CT);
        assertThat(off.stream().filter(s -> s.port() == 5434).findFirst().orElseThrow().suggestedPassword()).isNull();

        var cfg = config();
        cfg.readDockerCredentials = true;
        var on = new ServerDiscovery(cfg, services(), docker(DOCKER_PS, env), postgresOn(5434))
                .discover(login(5432), true, CT);
        var ops = on.stream().filter(s -> s.port() == 5434).findFirst().orElseThrow();
        assertThat(ops.suggestedUsername()).isEqualTo("ops");
        assertThat(ops.suggestedPassword()).isEqualTo("opspw");
    }

    @Test
    void discover_listsStoppedService_soItCanBeStarted() {
        var discovery = new ServerDiscovery(config(),
                services(new LocalPgService("postgresql-x64-17", null, null, 5433, false)),
                docker(null), postgresOn());

        var servers = discovery.discover(login(5444), true, CT);

        var stopped = servers.stream().filter(s -> s.port() == 5433).findFirst().orElseThrow();
        assertThat(stopped.serviceRunning()).isFalse();
        assertThat(stopped.service()).isEqualTo("postgresql-x64-17");
    }

    @Test
    void discover_skipsRunningServiceWhoseHandshakeFails() {
        var discovery = new ServerDiscovery(config(),
                services(new LocalPgService("postgresql-x64-17", null, null, 5433, true)),
                docker(null), postgresOn());

        assertThat(discovery.discover(login(5444), true, CT)).noneMatch(s -> s.port() == 5433);
    }

    @PostgresAvailable
    void discover_onThisMachine_findsTheConfiguredServerByHandshake() {
        var login = TestProfiles.owner();
        assertThat(PostgresHandshake.isPostgres(login.host, login.port, Duration.ofSeconds(2))).isTrue();

        var servers = new ServerDiscovery().discover(login, true, CT);

        assertThat(servers.get(0).key()).isEqualTo(ServerKey.of(login.host, login.port));
        assertThat(servers.stream().map(DiscoveredServer::key).distinct().count()).isEqualTo(servers.size());
    }

    @Test
    void handshake_rejectsNonPostgresListener() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var serve = Thread.ofVirtual().start(() -> {
                try (var c = listener.accept()) {
                    c.getOutputStream().write("HTTP/1.1 400\r\n".getBytes(StandardCharsets.US_ASCII));
                    c.getOutputStream().flush();
                } catch (Exception ignored) {
                    // listener closed
                }
            });
            assertThat(PostgresHandshake.isPostgres("127.0.0.1", listener.getLocalPort(), Duration.ofSeconds(2)))
                    .isFalse();
            serve.join(2000);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "5432-5434,55432|5432 5433 5434 55432",
            "' 6000 , bad, 7000-6999 '|6000",
            "''|''"})
    void parseDiscoveryPorts_expandsRanges(String spec, String expected) {
        var cfg = new AppConfig();
        cfg.discoveryPorts = spec;
        var want = expected.isBlank() ? List.<Integer>of()
                : Arrays.stream(expected.split(" ")).map(Integer::valueOf).collect(Collectors.toList());
        assertThat(cfg.parseDiscoveryPorts()).isEqualTo(want);
    }

    @ParameterizedTest
    @CsvSource({
            "localhost,5432,127.0.0.1,5432,true",
            "LOCALHOST,5432,::1,5432,true",
            "localhost,5432,localhost,5444,false",
            "DB.example.com,5432,db.example.com,5432,true"})
    void serverKey_normalisesLoopbackAndCase(String h1, int p1, String h2, int p2, boolean same) {
        assertThat(ServerKey.of(h1, p1).equals(ServerKey.of(h2, p2))).isEqualTo(same);
    }
}
