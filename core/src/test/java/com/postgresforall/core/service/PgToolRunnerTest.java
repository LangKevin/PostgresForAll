package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.PgSslMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PgToolRunnerTest {
    private static final PgClientTools TOOLS = new PgClientTools("C:\\PG\\18\\bin", 18);

    private static ConnectionProfile server() {
        var p = new ConnectionProfile();
        p.host = "localhost";
        p.port = 5444;
        p.username = "scheduleme";
        p.password = "s3cret!";
        p.sslMode = PgSslMode.PREFER;
        return p;
    }

    @Test
    void dump_runsHostPgDumpOverTcp() {
        var cmd = PgToolRunner.dump(TOOLS, server(), "scheduleme", "C:\\backups\\db.dump");
        assertThat(cmd.file()).isEqualTo(Path.of("C:\\PG\\18\\bin", PgClientTools.exeName("pg_dump")).toString());
        assertThat(cmd.args()).contains("--host=localhost", "--port=5444", "--username=scheduleme",
                "--dbname=scheduleme", "--format=custom", "--verbose", "--file=C:\\backups\\db.dump");
        assertThat(String.join(" ", cmd.args())).doesNotContain("docker");
    }

    @Test
    void commands_neverPromptAndKeepPasswordOffTheCommandLine() {
        var cmd = PgToolRunner.dump(TOOLS, server(), "scheduleme", "C:\\x.dump");
        assertThat(cmd.args()).contains("--no-password");
        assertThat(String.join(" ", cmd.args())).doesNotContain("s3cret!");
        assertThat(cmd.environment()).containsEntry("PGPASSWORD", "s3cret!").containsEntry("PGSSLMODE", "prefer");
    }

    @Test
    void environment_omitsPasswordWhenEmpty_andMapsSslMode() {
        var p = server();
        p.password = "";
        p.sslMode = PgSslMode.VERIFY_FULL;
        var env = PgToolRunner.environment(p);
        assertThat(env).doesNotContainKey("PGPASSWORD").containsEntry("PGSSLMODE", "verify-full");
    }

    @Test
    void dumpArgs_keepNamesWithSpacesAsOneArgument() {
        // Arguments are passed as a list, so no shell quoting is needed or added.
        var cmd = PgToolRunner.dump(TOOLS, server(), "my db", "C:\\path with spaces\\x.dump");
        assertThat(cmd.args()).contains("--dbname=my db", "--file=C:\\path with spaces\\x.dump");
    }

    @Test
    void restore_includesCleanIfExistsAndFile() {
        var cmd = PgToolRunner.restore(TOOLS, server(), "scheduleme", "C:\\in\\x.dump");
        assertThat(cmd.file()).isEqualTo(Path.of("C:\\PG\\18\\bin", PgClientTools.exeName("pg_restore")).toString());
        assertThat(cmd.args()).contains("--clean", "--if-exists", "--verbose", "--dbname=scheduleme");
        assertThat(cmd.args()).last().isEqualTo("C:\\in\\x.dump");
    }

    @Test
    void listDumpToc_needsNoServerOrPassword() {
        var cmd = PgToolRunner.listDumpToc(TOOLS, "C:\\in\\x.dump");
        assertThat(cmd.args()).first().isEqualTo("--list");
        assertThat(cmd.args()).noneMatch(a -> a.startsWith("--host"));
        assertThat(cmd.environment()).isEmpty();
    }
}
