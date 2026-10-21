package com.postgresforall.core.service;

import com.postgresforall.core.model.QueryResult.MessageSeverity;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SmartExecuteTest {

    @PostgresAvailable
    void smartExecute_routesCreateDatabase_andRunsRestInNewDb() throws Exception {
        var newDb = "smart_exec_test_" + TestProfiles.tag();
        var script = "CREATE DATABASE " + newDb + " OWNER scheduleme;\n"
                + "CREATE TABLE smart_test (id int NOT NULL, label text);\n"
                + "INSERT INTO smart_test (id, label) VALUES (1, 'first'), (2, 'second'), (3, 'third');\n"
                + "SELECT count(*) FROM smart_test;\n";

        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var result = new QueryExecutor(mgr).smartExecute(script, false, CancelToken.NONE);
            assertThat(result.hasErrors()).as("Smart execute reported errors:\n" + result.messages().stream()
                    .filter(m -> m.severity() == MessageSeverity.ERROR).map(m -> m.text())
                    .collect(Collectors.joining("\n"))).isFalse();

            // The SELECT count(*) in phase 3 must return 3.
            assertThat(result.resultSets()).hasSize(1);
            var rows = result.resultSets().get(0).rows();
            assertThat(rows).hasSize(1);
            assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(3L);
            assertThat(result.messages()).anyMatch(m -> m.text().contains("3 row(s) affected."));

            // The table exists in the NEW database, and NOT in the original one.
            try (var conn = TestProfiles.owner().withDatabase(newDb).open(); var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM smart_test")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(3L);
            }
            try (var conn = TestProfiles.owner().open(); var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT to_regclass('public.smart_test') IS NOT NULL")) {
                rs.next();
                assertThat(rs.getBoolean(1))
                        .as("smart_test should NOT exist in the original scheduleme DB — only in the new DB.")
                        .isFalse();
            }
        } finally {
            dropQuietly(newDb);
        }
    }

    @PostgresAvailable
    void smartExecute_withNoCreateDatabase_behavesLikeNormalExecute() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var result = new QueryExecutor(mgr).smartExecute("SELECT 1; SELECT 2;", false, CancelToken.NONE);
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.resultSets()).hasSize(2);
            assertThat(result.statementsExecuted()).isEqualTo(2);
        }
    }

    @PostgresAvailable
    void smartExecute_failedCreateDatabase_stopsBeforeLaterStatements() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            // 'postgres' always exists, so CREATE DATABASE fails (42P04).
            var result = new QueryExecutor(mgr).smartExecute("CREATE DATABASE postgres; SELECT 1;", false,
                    CancelToken.NONE);
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.messages()).anyMatch(m -> m.severity() == MessageSeverity.ERROR
                    && m.text().startsWith("CREATE DATABASE failed:"));
            assertThat(result.resultSets()).isEmpty();
        }
    }

    static void dropQuietly(String db) {
        try (var admin = TestProfiles.owner().withDatabase("postgres").open(); var st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DatabaseOperations.quoteIdentifier(db) + " WITH (FORCE)");
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
