package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.PgSslMode;
import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.platform.DpapiSecretStore;
import com.postgresforall.core.testsupport.PlainSecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PasswordStoreTest {
    @TempDir
    Path dir;

    /** profiles.json exactly as the .NET app writes it. */
    private static final String DOTNET_PROFILES = """
            [
              {
                "Host": "localhost",
                "Port": 5434,
                "Database": "scheduleme",
                "Username": "scheduleme",
                "EncryptedPassword": "plain:first",
                "SslMode": 1,
                "LastUsed": "2026-07-24T13:17:35.6372203Z",
                "IsServerLogin": false
              },
              {
                "Host": "localhost",
                "Port": 5432,
                "Database": "postgres",
                "Username": "postgres",
                "EncryptedPassword": null,
                "SslMode": 2,
                "LastUsed": "2026-10-21T14:04:35.7092039Z",
                "IsServerLogin": false
              },
              {
                "Host": "localhost",
                "Port": 5434,
                "Database": "postgres",
                "Username": "operationshub",
                "EncryptedPassword": "plain:ops",
                "SslMode": 1,
                "LastUsed": "2026-10-21T14:04:48.2106923Z",
                "IsServerLogin": true
              }
            ]""";

    private PasswordStore store(Path file) {
        return new PasswordStore(file, new PlainSecretStore());
    }

    private static ConnectionProfile profile(int port, String db, String user, String pw) {
        var p = new ConnectionProfile();
        p.host = "localhost";
        p.port = port;
        p.database = db;
        p.username = user;
        p.password = pw;
        return p;
    }

    @Test
    void readsDotNetFormat() throws Exception {
        var file = dir.resolve("profiles.json");
        Files.writeString(file, DOTNET_PROFILES);
        var s = store(file);

        assertThat(s.load()).hasSize(3);

        // Most recent login-window login: server logins never pre-fill it.
        var recent = s.findMostRecent();
        assertThat(recent.port).isEqualTo(5432);
        assertThat(recent.username).isEqualTo("postgres");
        assertThat(recent.password).isEmpty();
        assertThat(recent.sslMode).isEqualTo(PgSslMode.REQUIRE);

        // Per server: the server login is newest for 5434.
        var forServer = s.findMostRecent("127.0.0.1", 5434);
        assertThat(forServer.username).isEqualTo("operationshub");
        assertThat(forServer.password).isEqualTo("ops");
        assertThat(forServer.sslMode).isEqualTo(PgSslMode.PREFER);
        assertThat(s.findMostRecent("localhost", 9999)).isNull();
    }

    @Test
    void save_writesDotNetCompatibleShape_andReplacesSameLogin() throws Exception {
        var file = dir.resolve("profiles.json");
        var s = store(file);
        s.save(profile(5444, "scheduleme", "scheduleme", "pw1"), true);
        s.save(profile(5444, "scheduleme", "scheduleme", "pw2"), true, true);

        var json = Files.readString(file);
        assertThat(json).contains("\"Host\"", "\"Port\" : 5444", "\"SslMode\" : 1", "\"LastUsed\"",
                "\"IsServerLogin\" : true", "\"EncryptedPassword\" : \"plain:pw2\"");
        assertThat(s.load()).hasSize(1);
        assertThat(s.findMostRecent("localhost", 5444).password).isEqualTo("pw2");
        // A server login doesn't count for the login window.
        assertThat(s.findMostRecent()).isNull();
    }

    @Test
    void save_withoutRemember_storesNoPassword_andForgetsSecret() {
        var secrets = new PlainSecretStore();
        var s = new PasswordStore(dir.resolve("profiles.json"), secrets);
        s.save(profile(5444, "db", "u", "secret"), false);

        assertThat(s.load().get(0).encryptedPassword).isNull();
        assertThat(s.findMostRecent().password).isEmpty();
        assertThat(secrets.forgotten).hasSize(1);
    }

    @Test
    void corruptFile_loadsEmpty() throws Exception {
        var file = dir.resolve("profiles.json");
        Files.writeString(file, "{ not json");
        assertThat(store(file).load()).isEmpty();
        assertThat(store(file).findMostRecent()).isNull();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dpapi_roundTrips() {
        var dpapi = new DpapiSecretStore(PasswordStore.ENTROPY);
        var stored = dpapi.protect("päss w0rd!", "id");
        assertThat(stored).isNotNull().isNotEqualTo("päss w0rd!");
        assertThat(dpapi.unprotect(stored, "id")).isEqualTo("päss w0rd!");
        // Different entropy (the db-credentials store) can't read it.
        assertThat(new DpapiSecretStore(DatabaseCredentialStore.ENTROPY).unprotect(stored, "id")).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dpapi_readsPasswordsSavedByDotNetApp() throws Exception {
        var real = AppPaths.dataDir().resolve("profiles.json");
        assumeTrue(Files.isRegularFile(real), "no profiles.json from the .NET app on this machine");

        // Read-only: work on a copy, never the real file.
        var copy = dir.resolve("profiles.json");
        Files.copy(real, copy, StandardCopyOption.REPLACE_EXISTING);
        var dpapi = new DpapiSecretStore(PasswordStore.ENTROPY);
        var s = new PasswordStore(copy, dpapi);
        var withSecret = s.load().stream().filter(p -> p.encryptedPassword != null).toList();
        assumeTrue(!withSecret.isEmpty(), "no remembered passwords in the .NET app's file");

        var decrypted = withSecret.stream()
                .map(p -> dpapi.unprotect(p.encryptedPassword, null))
                .filter(pw -> !pw.isEmpty())
                .count();
        // Never print the secret — only that it decrypted to something.
        assertThat(decrypted).as("decrypted .NET DPAPI passwords").isPositive();
    }
}
