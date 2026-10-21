package com.postgresforall.core.model;

import com.postgresforall.core.util.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgSslModeTest {

    @Test
    void writesOrdinal_likeTheDotNetApp() throws Exception {
        assertThat(Json.MAPPER.writeValueAsString(PgSslMode.DISABLE)).isEqualTo("0");
        assertThat(Json.MAPPER.writeValueAsString(PgSslMode.PREFER)).isEqualTo("1");
        assertThat(Json.MAPPER.writeValueAsString(PgSslMode.VERIFY_FULL)).isEqualTo("4");
    }

    @Test
    void readsOrdinalsAndNames() throws Exception {
        for (var m : PgSslMode.values()) {
            assertThat(Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(m), PgSslMode.class)).isEqualTo(m);
        }
        assertThat(Json.MAPPER.readValue("\"Require\"", PgSslMode.class)).isEqualTo(PgSslMode.REQUIRE);
        assertThat(Json.MAPPER.readValue("\"verify-ca\"", PgSslMode.class)).isEqualTo(PgSslMode.VERIFY_CA);
        assertThat(Json.MAPPER.readValue("99", PgSslMode.class)).isEqualTo(PgSslMode.PREFER);
    }

    @Test
    void libpqNames() {
        assertThat(PgSslMode.VERIFY_FULL.libpqName()).isEqualTo("verify-full");
        assertThat(PgSslMode.PREFER.toString()).isEqualTo("Prefer");
    }

    @Test
    void jdbcUrl_bracketsIpv6_andEncodesDatabase() {
        var p = new ConnectionProfile();
        p.host = "::1";
        p.port = 5432;
        p.database = "my db";
        assertThat(p.jdbcUrl()).isEqualTo("jdbc:postgresql://[::1]:5432/my+db");
        assertThat(p.jdbcProperties().getProperty("sslmode")).isEqualTo("prefer");
        assertThat(p.withDatabase("x").database).isEqualTo("x");
        assertThat(p.database).isEqualTo("my db");
    }

    @Test
    void discoveredServer_mergeKeepsFirstKnownValues() {
        var a = DiscoveredServer.of("localhost", 5444, ServerSource.LOGIN);
        var b = DiscoveredServer.of("127.0.0.1", 5444, ServerSource.DOCKER).withLabel("pg").withDockerContainer("pg");
        var m = a.mergeWith(b);
        assertThat(m.label()).isEqualTo("pg");
        assertThat(m.sources()).isEqualTo(ServerSource.LOGIN | ServerSource.DOCKER);
        assertThat(m.host()).isEqualTo("localhost");
        assertThat(m.displayName()).isEqualTo("localhost:5444  pg");
    }
}
