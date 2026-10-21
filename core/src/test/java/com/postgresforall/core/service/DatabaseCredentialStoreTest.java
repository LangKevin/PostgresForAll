package com.postgresforall.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.postgresforall.core.model.DatabaseCredential;
import com.postgresforall.core.model.PgSslMode;
import com.postgresforall.core.service.DatabaseCredentialStore.StoredDatabaseCredential;
import com.postgresforall.core.testsupport.PlainSecretStore;
import com.postgresforall.core.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCredentialStoreTest {
    private static final String SERVER_A = "localhost:5444";
    private static final String SERVER_B = "localhost:5434";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("db-credentials.json");
    }

    private DatabaseCredentialStore store() {
        return new DatabaseCredentialStore(file(), new PlainSecretStore());
    }

    private static DatabaseCredential cred(String server, String db, String user, String pw) {
        var c = new DatabaseCredential();
        c.server = server;
        c.database = db;
        c.username = user;
        c.password = pw;
        return c;
    }

    private static DatabaseCredential cred(String server, String db) {
        return cred(server, db, "u", "p");
    }

    @Test
    void find_returnsNull_whenNoOverride() {
        assertThat(store().find(SERVER_A, "scheduleme_prod")).isNull();
    }

    @Test
    void set_persist_roundTripsThroughDisk() throws Exception {
        var c = cred(SERVER_A, "scheduleme_prod", "scheduleme_prod", "s3cret!");
        c.sslMode = PgSslMode.REQUIRE;
        store().set(c, true);

        // A fresh store (no session cache) must read it back from disk.
        var found = store().find(SERVER_A, "scheduleme_prod");
        assertThat(found).isNotNull();
        assertThat(found.username).isEqualTo("scheduleme_prod");
        assertThat(found.password).isEqualTo("s3cret!");
        assertThat(found.sslMode).isEqualTo(PgSslMode.REQUIRE);
        assertThat(found.server).isEqualTo(SERVER_A);

        // The file uses the original app's shape: PascalCase keys, SslMode as a number.
        var json = Files.readString(file());
        assertThat(json).contains("\"Server\"", "\"EncryptedPassword\"", "\"SslMode\" : 2");
        // The secret only ever goes through the SecretStore (the fake's "plain:" output), never a Password key.
        assertThat(json).doesNotContain("\"Password\"").contains("\"EncryptedPassword\" : \"plain:s3cret!\"");
    }

    @Test
    void set_notPersisted_isSessionOnly() {
        var s = store();
        s.set(cred(SERVER_A, "scheduleme_prod"), false);
        assertThat(s.find(SERVER_A, "scheduleme_prod")).isNotNull();
        assertThat(store().find(SERVER_A, "scheduleme_prod")).isNull();
    }

    @Test
    void find_isCaseInsensitiveOnDatabaseName() {
        var s = store();
        s.set(cred(SERVER_A, "Scheduleme_Prod"), true);
        assertThat(s.find(SERVER_A, "scheduleme_prod")).isNotNull();
        assertThat(store().find(SERVER_A, "scheduleme_prod")).isNotNull();
    }

    @Test
    void sameDatabaseName_onTwoServers_isTwoEntries() {
        var s = store();
        s.set(cred(SERVER_A, "app", "alice", "p"), true);
        s.set(cred(SERVER_B, "app", "bob", "p"), true);

        var reopened = store();
        assertThat(reopened.find(SERVER_A, "app").username).isEqualTo("alice");
        assertThat(reopened.find(SERVER_B, "app").username).isEqualTo("bob");
        assertThat(reopened.find("localhost:5432", "app")).isNull();
    }

    @Test
    void forServer_providerOnlySeesThatServer() {
        var s = store();
        s.set(cred(SERVER_A, "app", "alice", "p"), false);
        assertThat(s.forServer(SERVER_A).find("app").username).isEqualTo("alice");
        assertThat(s.forServer(SERVER_B).find("app")).isNull();
    }

    @Test
    void remove_clearsSessionAndDisk() {
        var s = store();
        s.set(cred(SERVER_A, "scheduleme_prod"), true);
        s.remove(SERVER_A, "scheduleme_prod");
        assertThat(s.find(SERVER_A, "scheduleme_prod")).isNull();
        assertThat(store().find(SERVER_A, "scheduleme_prod")).isNull();
    }

    @Test
    void adoptUnscoped_movesLegacyEntriesToLoginServer() throws Exception {
        // A file written before multi-server support: entries have no Server.
        store().set(cred(SERVER_A, "legacy_db", "old", "p"), true);
        List<StoredDatabaseCredential> raw = Json.MAPPER.readValue(file().toFile(),
                new TypeReference<List<StoredDatabaseCredential>>() { });
        raw.forEach(c -> c.server = "");
        Json.MAPPER.writeValue(file().toFile(), raw);

        var s = store();
        assertThat(s.find(SERVER_B, "legacy_db")).isNull();
        assertThat(s.adoptUnscoped(SERVER_B)).isEqualTo(1);
        assertThat(store().find(SERVER_B, "legacy_db").username).isEqualTo("old");
        assertThat(s.adoptUnscoped(SERVER_A)).isZero(); // nothing left to adopt
    }

    @Test
    void readsFileWrittenByDotNetApp() throws Exception {
        Files.writeString(file(), """
                [
                  {
                    "Server": "localhost:5444",
                    "Database": "prod",
                    "Username": "owner",
                    "EncryptedPassword": "plain:pw",
                    "SslMode": 3
                  }
                ]""");
        var found = store().find("LOCALHOST:5444", "PROD");
        assertThat(found.username).isEqualTo("owner");
        assertThat(found.password).isEqualTo("pw");
        assertThat(found.sslMode).isEqualTo(PgSslMode.VERIFY_CA);
    }

    @Test
    void set_requiresServer() {
        assertThatThrownBy(() -> store().set(cred("", "db"), false)).isInstanceOf(IllegalArgumentException.class);
    }
}
