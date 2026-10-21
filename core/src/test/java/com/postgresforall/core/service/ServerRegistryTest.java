package com.postgresforall.core.service;

import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.ServerSource;
import com.postgresforall.core.service.ServerSession.State;
import com.postgresforall.core.testsupport.PlainSecretStore;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerRegistryTest {
    @TempDir
    Path dir;

    private DatabaseCredentialStore creds() {
        return new DatabaseCredentialStore(dir.resolve("db-credentials.json"), new PlainSecretStore());
    }

    private PasswordStore saved() {
        return new PasswordStore(dir.resolve("profiles.json"), new PlainSecretStore());
    }

    private static DiscoveredServer local(int port, String label) {
        return DiscoveredServer.of("localhost", port, ServerSource.PORT_SCAN).withLabel(label);
    }

    private static DiscoveredServer local(int port) {
        return local(port, null);
    }

    private static ConnectionProfile profile(String host, int port, String db, String user, String pw) {
        var p = new ConnectionProfile();
        p.host = host;
        p.port = port;
        p.database = db;
        p.username = user;
        p.password = pw;
        return p;
    }

    private static AppConfig superuserConfig() {
        var c = new AppConfig();
        c.superuserUsername = "postgres";
        c.superuserDatabase = "postgres";
        return c;
    }

    @Test
    void addOrUpdate_mergesSameServer_andRaisesAddedOnce() {
        try (var reg = new ServerRegistry(creds())) {
            var added = new AtomicInteger();
            reg.onSessionAdded(s -> added.incrementAndGet());

            var a = reg.addOrUpdate(local(5444));
            var b = reg.addOrUpdate(DiscoveredServer.of("127.0.0.1", 5444, ServerSource.DOCKER)
                    .withLabel("scheduleme-postgres"));

            assertThat(b).isSameAs(a);
            assertThat(added).hasValue(1);
            assertThat(a.server().label()).isEqualTo("scheduleme-postgres");
            assertThat(a.server().sources()).isEqualTo(ServerSource.DOCKER | ServerSource.PORT_SCAN);
        }
    }

    @Test
    void update_raisesInfoChangedOnlyWhenSomethingNew_neverStateChanged() {
        try (var reg = new ServerRegistry(creds())) {
            var s = reg.addOrUpdate(local(5444));
            var info = new AtomicInteger();
            var state = new AtomicInteger();
            s.onInfoChanged(x -> info.incrementAndGet());
            s.onStateChanged(x -> state.incrementAndGet());

            reg.addOrUpdate(local(5444, "scheduleme-postgres"));
            reg.addOrUpdate(local(5444, "scheduleme-postgres")); // nothing new

            assertThat(info).hasValue(1);
            assertThat(state).hasValue(0);
        }
    }

    @Test
    void suggestLogin_prefersRememberedPassword_thenHints_thenSuperuserPreset() {
        var store = creds();
        var cfg = superuserConfig();
        var saved = saved();

        var bare = new ServerSession(local(5434), store).suggestLogin(saved, cfg);
        assertThat(new String[]{bare.username, bare.database, bare.password}).containsExactly("postgres", "postgres", "");

        var hinted = new ServerSession(local(5434).withSuggestions("ops", "x", null), store).suggestLogin(saved, cfg);
        assertThat(hinted.username).isEqualTo("ops");
        assertThat(hinted.password).isEqualTo("x");

        saved.save(profile("localhost", 5434, "ops", "owner", "remembered"), true);
        var remembered = new ServerSession(local(5434), store).suggestLogin(saved, cfg);
        assertThat(new String[]{remembered.username, remembered.database, remembered.password})
                .containsExactly("owner", "ops", "remembered");
    }

    @Test
    void suggestLogin_configuredUserBeatsRememberedLoginForAnotherRole() {
        var store = creds();
        var saved = saved();
        // Stale entry: scheduleme saved against 5434, where only operationshub exists.
        saved.save(profile("localhost", 5434, "scheduleme", "scheduleme", "old"), true);

        var configured = new ServerSession(local(5434).withSuggestions("operationshub", null, "postgres"), store);
        var login = configured.suggestLogin(saved, new AppConfig());
        assertThat(new String[]{login.username, login.database, login.password})
                .containsExactly("operationshub", "postgres", "");

        // Once the right role is remembered, it's used silently again.
        saved.save(profile("localhost", 5434, "postgres", "operationshub", "pw"), true);
        assertThat(configured.suggestLogin(saved, new AppConfig()).password).isEqualTo("pw");
    }

    @Test
    void connectSilently_skipsServersWithoutAPassword() {
        try (var reg = new ServerRegistry(creds())) {
            var s = reg.addOrUpdate(local(1)); // nothing listens on port 1
            var tried = reg.connectSilently(saved());
            assertThat(tried).isEmpty();
            assertThat(s.state()).isEqualTo(State.DISCONNECTED);
        }
    }

    @Test
    void connect_failure_leavesSessionFailedWithError() {
        try (var reg = new ServerRegistry(creds())) {
            var s = reg.addOrUpdate(DiscoveredServer.of("127.0.0.1", 1, 0));
            assertThatThrownBy(() -> s.connect(profile("ignored", 0, "postgres", "x", "y")))
                    .isInstanceOf(Exception.class);
            assertThat(s.state()).isEqualTo(State.FAILED);
            assertThat(s.lastError()).isNotBlank();
            assertThat(s.factory()).isNull();
        }
    }

    @PostgresAvailable
    void connect_thenDisconnect_onLiveServer() throws Exception {
        try (var reg = new ServerRegistry(creds())) {
            var login = TestProfiles.owner();
            var s = reg.addOrUpdate(DiscoveredServer.of(login.host, login.port, 0));
            var states = new java.util.concurrent.CopyOnWriteArrayList<State>();
            s.onStateChanged(x -> states.add(x.state()));

            s.connect(login);
            assertThat(s.state()).isEqualTo(State.CONNECTED);
            assertThat(s.manager()).isNotNull();
            assertThat(s.operations()).isNotNull();

            // Same database name on another server must never share this pool.
            try (var lease = s.factory().acquire(login.database)) {
                assertThat(lease.manager().profile().port).isEqualTo(login.port);
            }

            s.disconnect();
            assertThat(s.state()).isEqualTo(State.DISCONNECTED);
            assertThat(s.factory()).isNull();
            assertThat(states).containsExactly(State.CONNECTING, State.CONNECTED, State.DISCONNECTED);
        }
    }
}
