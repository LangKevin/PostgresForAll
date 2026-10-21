package com.postgresforall.core.platform;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure parsing helpers behind the macOS / Windows / lsof integrations. */
class PlatformParsingTest {

    private static final String HOMEBREW_PLIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>KeepAlive</key>
              <true/>
              <key>Label</key>
              <string>homebrew.mxcl.postgresql@16</string>
              <key>LimitLoadToSessionType</key>
              <array>
                <string>Aqua</string>
                <string>Background</string>
              </array>
              <key>ProgramArguments</key>
              <array>
                <string>/opt/homebrew/opt/postgresql@16/bin/postgres</string>
                <string>-D</string>
                <string>/opt/homebrew/var/postgresql@16</string>
              </array>
              <key>RunAtLoad</key>
              <true/>
              <key>WorkingDirectory</key>
              <string>/opt/homebrew</string>
            </dict>
            </plist>
            """;

    private static final String EDB_PLIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
              <key>Label</key><string>com.edb.launchd.postgresql-16</string>
              <key>ProgramArguments</key>
              <array>
                <string>/Library/PostgreSQL/16/bin/postmaster</string>
                <string>-D/Library/PostgreSQL/16/data</string>
              </array>
              <key>UserName</key><string>postgres</string>
            </dict>
            </plist>
            """;

    private static final String ENV_PLIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
              <key>EnvironmentVariables</key>
              <dict><key>LANG</key><string>C</string><key>PGDATA</key><string>/srv/pg</string></dict>
              <key>Label</key><string>org.example.postgres</string>
              <key>ProgramArguments</key><array><string>/usr/local/bin/postgres</string></array>
            </dict>
            </plist>
            """;

    @Test
    void parsePlist_homebrew() {
        var p = MacPostgresServices.parsePlist(HOMEBREW_PLIST.getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertThat(p.label()).isEqualTo("homebrew.mxcl.postgresql@16");
        assertThat(p.args()).hasSize(3);
        assertThat(p.dataDir()).isEqualTo("/opt/homebrew/var/postgresql@16");
    }

    @Test
    void parsePlist_edbJoinedDashD() {
        var p = MacPostgresServices.parsePlist(EDB_PLIST.getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertThat(p.label()).isEqualTo("com.edb.launchd.postgresql-16");
        assertThat(p.dataDir()).isEqualTo("/Library/PostgreSQL/16/data");
    }

    @Test
    void parsePlist_fallsBackToPgdataEnvironment() {
        var p = MacPostgresServices.parsePlist(ENV_PLIST.getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertThat(p.label()).isEqualTo("org.example.postgres");
        assertThat(p.dataDir()).isEqualTo("/srv/pg");
    }

    @Test
    void parsePlist_rejectsGarbageAndLabelless() {
        assertThat(MacPostgresServices.parsePlist("not xml".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(MacPostgresServices.parsePlist(
                "<plist><dict><key>RunAtLoad</key><true/></dict></plist>".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void homebrewJob_formula() {
        var job = new MacPostgresServices.LaunchdJob("homebrew.mxcl.postgresql@16", null,
                MacPostgresServices.Domain.USER, List.of(), null);
        assertThat(job.isHomebrew()).isTrue();
        assertThat(job.homebrewFormula()).isEqualTo("postgresql@16");
        var edb = new MacPostgresServices.LaunchdJob("com.edb.launchd.postgresql-16", null,
                MacPostgresServices.Domain.SYSTEM, List.of(), null);
        assertThat(edb.homebrewFormula()).isNull();
    }

    @Test
    void dataDirFromArgs_handlesEveryForm() {
        assertThat(MacPostgresServices.dataDirFromArgs(List.of("postgres", "-D", "/a"))).isEqualTo("/a");
        assertThat(MacPostgresServices.dataDirFromArgs(List.of("postmaster", "-D/b"))).isEqualTo("/b");
        assertThat(MacPostgresServices.dataDirFromArgs(List.of("/bin/sh", "-c", "exec pg_ctl start -D \"/c d\" -w")))
                .isEqualTo("/c d");
        assertThat(MacPostgresServices.dataDirFromArgs(List.of("postgres"))).isNull();
    }

    @Test
    void parseLaunchctlList_readsPidColumn() {
        var out = """
                PID	Status	Label
                1234	0	homebrew.mxcl.postgresql@16
                -	0	homebrew.mxcl.postgresql@15
                -	78	com.apple.something
                """;
        assertThat(MacPostgresServices.parseLaunchctlList(out, "homebrew.mxcl.postgresql@16")).isTrue();
        assertThat(MacPostgresServices.parseLaunchctlList(out, "homebrew.mxcl.postgresql@15")).isFalse();
        assertThat(MacPostgresServices.parseLaunchctlList(out, "homebrew.mxcl.postgresql@14")).isFalse();
        assertThat(MacPostgresServices.parseLaunchctlList(null, "x")).isNull();
    }

    @Test
    void parseLsofFields_dedupesPids() {
        var out = "p812\ncpostgres\nf7\np812\ncpostgres\np901\ncdocker-proxy\n";
        var owners = PortOwners.parseLsofFields(out);
        assertThat(owners).extracting(PortOwners.Owner::pid).containsExactly(812L, 901L);
        assertThat(owners).extracting(PortOwners.Owner::processName).containsExactly("postgres", "docker-proxy");
    }

    @Test
    void parsePipeRows_readsPowerShellOutput() {
        var out = "4242|postgres|10/21/2026 9:00:00 AM|C:\\PG\\bin\\postgres.exe\r\n4242|postgres|x|y\r\nbad\r\n77|<unknown>|<?>|<?>\r\n";
        var owners = PortOwners.parsePipeRows(out);
        assertThat(owners).hasSize(2);
        assertThat(owners.get(0).pid()).isEqualTo(4242L);
        assertThat(owners.get(0).detail()).contains("C:\\PG\\bin\\postgres.exe");
        assertThat(owners.get(1).processName()).isEqualTo("<unknown>");
    }

    @Test
    void elevationQuoting() {
        assertThat(Elevation.shQuote("it's here")).isEqualTo("'it'\\''s here'");
        assertThat(Elevation.psQuote("it's")).isEqualTo("'it''s'");
    }

    @Test
    void elevation_mac_wrapsCommandInAdministratorPrivileges() {
        var calls = new java.util.ArrayList<List<String>>();
        ProcessRunner runner = (file, args, timeout, ct, env) -> {
            calls.add(new java.util.ArrayList<>(List.of(file)));
            calls.get(calls.size() - 1).addAll(args);
            return new ProcessResult(1, "", "execution error: User canceled. (-128)");
        };
        if (!Platform.isMac()) return; // the macOS path only runs on macOS
        var r = Elevation.run(runner, List.of("/bin/launchctl", "kickstart", "system/x"),
                java.time.Duration.ofSeconds(5), com.postgresforall.core.util.CancelToken.NONE);
        assertThat(calls.get(0).get(0)).isEqualTo("osascript");
        assertThat(calls.get(0).get(2)).contains("with administrator privileges").contains("'/bin/launchctl'");
        assertThat(r.stdErr()).isEqualTo(Elevation.CANCELLED_MESSAGE);
    }

    @Test
    void executables_searchPathIncludesHomebrewOnUnix() {
        var path = Executables.searchPath();
        if (Platform.isWindows()) {
            assertThat(path).doesNotContain("/opt/homebrew/bin");
            assertThat(Executables.resolve("docker")).isEqualTo("docker");
        } else {
            assertThat(path).contains("/opt/homebrew/bin", "/usr/local/bin");
        }
    }

    @Test
    void defaultProcessRunner_capturesOutputAndExitCode() {
        var runner = new DefaultProcessRunner();
        var r = Platform.isWindows()
                ? runner.run("cmd.exe", List.of("/c", "echo hello& exit 3"), java.time.Duration.ofSeconds(10))
                : runner.run("/bin/sh", List.of("-c", "echo hello; exit 3"), java.time.Duration.ofSeconds(10));
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stdOut().trim()).isEqualTo("hello");
    }

    @Test
    void defaultProcessRunner_timesOut() {
        var runner = new DefaultProcessRunner();
        var r = Platform.isWindows()
                ? runner.run("powershell", List.of("-NoProfile", "-Command", "Start-Sleep -Seconds 20"),
                java.time.Duration.ofMillis(500))
                : runner.run("/bin/sh", List.of("-c", "sleep 20"), java.time.Duration.ofMillis(500));
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.stdErr()).contains("timeout");
    }

    @Test
    void defaultProcessRunner_reportsMissingProgram() {
        var r = new DefaultProcessRunner().run("definitely-not-a-program-xyz", List.of(), java.time.Duration.ofSeconds(5));
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.stdErr()).isNotBlank();
    }

    @Test
    void defaultProcessRunner_streamsLinesAndPassesEnvironment() {
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var code = Platform.isWindows()
                ? new DefaultProcessRunner().stream("cmd.exe", List.of("/c", "echo %PFA_TEST%& echo err 1>&2"),
                (err, line) -> lines.add((err ? "E:" : "O:") + line.trim()),
                com.postgresforall.core.util.CancelToken.NONE, java.util.Map.of("PFA_TEST", "v1"))
                : new DefaultProcessRunner().stream("/bin/sh", List.of("-c", "echo $PFA_TEST; echo err 1>&2"),
                (err, line) -> lines.add((err ? "E:" : "O:") + line.trim()),
                com.postgresforall.core.util.CancelToken.NONE, java.util.Map.of("PFA_TEST", "v1"));
        assertThat(code).isZero();
        assertThat(lines).contains("O:v1", "E:err");
    }

    @org.junit.jupiter.api.Test
    void brewServicesJson_keepsPostgresFormulae_withStartedState() {
        var json = """
                [{"name":"postgresql@16","status":"started","user":"me","file":"/x.plist","exit_code":0},
                 {"name":"postgresql@14","status":"none","user":null,"file":null,"exit_code":null},
                 {"name":"redis","status":"started","user":"me","file":"/r.plist","exit_code":0}]""";
        var list = MacPostgresServices.parseBrewServicesJson(json);
        org.assertj.core.api.Assertions.assertThat(list).containsExactly(
                new MacPostgresServices.BrewService("postgresql@16", true),
                new MacPostgresServices.BrewService("postgresql@14", false));
        org.assertj.core.api.Assertions.assertThat(MacPostgresServices.parseBrewServicesJson("Error: not json")).isEmpty();
    }
}
