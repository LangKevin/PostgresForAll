package com.postgresforall.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    @Test
    void parse_allowsCommentsTrailingCommasAndAnyCase() throws Exception {
        var cfg = AppConfig.parse("""
                {
                  // line comment
                  "Host": "db.local",   /* block */
                  "port": 6543,
                  "SUPERUSERUSERNAME": "admin",
                  "SuperuserPort": 0,
                  "Servers": [
                    { "Host": "a", "Port": 5432, "Username": "u", "DockerContainer": "c", },
                  ],
                  "UnknownSetting": true,
                }""");
        assertThat(cfg.host).isEqualTo("db.local");
        assertThat(cfg.port).isEqualTo(6543);
        assertThat(cfg.superuserUsername).isEqualTo("admin");
        assertThat(cfg.effectiveSuperuserHost()).isEqualTo("db.local");
        assertThat(cfg.effectiveSuperuserPort()).isEqualTo(6543);
        assertThat(cfg.servers).hasSize(1);
        assertThat(cfg.servers.get(0).dockerContainer).isEqualTo("c");
        // Unset values keep their defaults.
        assertThat(cfg.superuserDatabase).isEqualTo("postgres");
        assertThat(cfg.discoverServers).isTrue();
        assertThat(cfg.combineServerDatabases).isTrue();
    }

    @Test
    void parse_nullsBecomeSafeDefaults() throws Exception {
        var cfg = AppConfig.parse("{ \"Servers\": null, \"PgBinPath\": null, \"Password\": null }");
        assertThat(cfg.servers).isEmpty();
        assertThat(cfg.pgBinPath).isEmpty();
        assertThat(cfg.password).isEmpty();
    }

    @Test
    void effectiveSuperuserTarget_usesOverridesWhenSet() throws Exception {
        var cfg = AppConfig.parse("{ \"Host\": \"h\", \"Port\": 5444, \"SuperuserHost\": \"s\", \"SuperuserPort\": 5432 }");
        assertThat(cfg.effectiveSuperuserHost()).isEqualTo("s");
        assertThat(cfg.effectiveSuperuserPort()).isEqualTo(5432);
    }

    @Test
    void shippedConfig_isTheOneTestsUse() {
        // The Gradle test task points POSTGRESCONNECT_CONFIG at the config in use
        // (your data-folder copy, the bundled one, or CI's); tests read that file.
        assertThat(AppConfig.loadedFrom()).isPresent();
        assertThat(AppConfig.loadedFrom().get().toString())
                .isEqualTo(java.nio.file.Path.of(System.getenv("POSTGRESCONNECT_CONFIG")).toString());
        assertThat(AppConfig.current().port).isEqualTo(5444);
    }

    @Test
    void save_thenParse_roundTrips(@TempDir Path dir) throws Exception {
        var cfg = new AppConfig();
        cfg.port = 7000;
        cfg.servers.add(new ServerEntry("x", 1));
        var file = dir.resolve("sub").resolve(AppConfig.FILE_NAME);
        cfg.save(file);
        var back = AppConfig.parse(java.nio.file.Files.readString(file));
        assertThat(back.port).isEqualTo(7000);
        assertThat(back.servers.get(0).host).isEqualTo("x");
    }
}
