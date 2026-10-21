package com.postgresforall.core.service;

import com.postgresforall.core.model.QueryResult;
import com.postgresforall.core.model.QueryResult.MessageSeverity;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** QueryExecutor against the live server. Only temp tables / throwaway objects. */
class QueryExecutorTest {
    private static ConnectionManager mgr;

    @BeforeAll
    static void open() {
        try {
            mgr = ConnectionManager.open(TestProfiles.owner());
        } catch (Exception ex) {
            mgr = null; // live tests will be skipped by @PostgresAvailable anyway
        }
    }

    @AfterAll
    static void close() {
        if (mgr != null) mgr.close();
    }

    private static QueryResult run(String sql, boolean tx) {
        return new QueryExecutor(mgr).execute(sql, tx, CancelToken.NONE);
    }

    private static String describe(QueryResult r) {
        return r.messages().stream().map(m -> m.severity() + ": " + m.text()).collect(Collectors.joining(" | "));
    }

    @PostgresAvailable
    void multipleStatements_eachResultSetCaptured() {
        var r = run("SELECT 1 AS a; SELECT 'x' AS b, 2.5::numeric AS c; SELECT * FROM generate_series(1,3) g", false);
        assertThat(r.hasErrors()).as(describe(r)).isFalse();
        assertThat(r.statementsExecuted()).isEqualTo(3);
        assertThat(r.resultSets()).hasSize(3);
        assertThat(r.resultSets().get(1).columns()).extracting(QueryResult.Column::name).containsExactly("b", "c");
        assertThat(r.resultSets().get(1).rows().get(0)[1]).isEqualTo(new BigDecimal("2.5"));
        assertThat(r.resultSets().get(2).rows()).hasSize(3);
        assertThat(r.resultSets().get(2).statementIndex()).isEqualTo(2);
        assertThat(r.resultSets().get(0).columns().get(0).dataType()).isEqualTo("int4");
    }

    @PostgresAvailable
    void driverWrapperTypes_becomeText_andNullsStayNull() {
        var r = run("SELECT '{\"k\":1}'::jsonb AS j, ARRAY[1,2] AS arr, NULL::text AS n, interval '1 day' AS i, "
                + "'\\x0102'::bytea AS b", false);
        assertThat(r.hasErrors()).as(describe(r)).isFalse();
        var row = r.resultSets().get(0).rows().get(0);
        assertThat(row[0]).isEqualTo("{\"k\": 1}");
        assertThat(row[1]).isEqualTo("{1,2}");
        assertThat(row[2]).isNull();
        assertThat(row[3]).isInstanceOf(String.class);
        assertThat(CellText.of(row[4])).isEqualTo("\\x0102");
    }

    @PostgresAvailable
    void notices_areCaptured() {
        var r = run("DO $$ BEGIN RAISE NOTICE 'hello %', 42; RAISE WARNING 'careful'; END $$; SELECT 1", false);
        assertThat(r.hasErrors()).as(describe(r)).isFalse();
        assertThat(r.messages()).anyMatch(m -> m.severity() == MessageSeverity.NOTICE && m.text().equals("hello 42"));
        assertThat(r.messages()).anyMatch(m -> m.severity() == MessageSeverity.WARNING && m.text().equals("careful"));
        assertThat(r.resultSets()).hasSize(1);
    }

    @PostgresAvailable
    void error_stopsTheRun_withSqlStateDetailAndHint() {
        var r = run("SELECT 1; SELECT * FROM no_such_table_xyz; SELECT 3", false);
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.statementsExecuted()).isEqualTo(1);
        assertThat(r.resultSets()).hasSize(1);
        var err = r.messages().stream().filter(m -> m.severity() == MessageSeverity.ERROR).findFirst().orElseThrow();
        assertThat(err.text()).startsWith("[42P01] ");
        assertThat(err.statementIndex()).isEqualTo(1);
    }

    @PostgresAvailable
    void dmlReportsRowsAffected_ddlDoesNot() {
        var r = run("CREATE TEMP TABLE qe_t(id int); INSERT INTO qe_t VALUES (1),(2); UPDATE qe_t SET id = id + 1",
                false);
        assertThat(r.hasErrors()).as(describe(r)).isFalse();
        var infos = r.messages().stream().filter(m -> m.severity() == MessageSeverity.INFO).map(m -> m.text()).toList();
        assertThat(infos).containsExactly("2 row(s) affected.", "2 row(s) affected.");
    }

    @PostgresAvailable
    void wrappedTransaction_rollsBackOnError() throws Exception {
        var table = "qe_tx_" + TestProfiles.tag();
        try (var conn = mgr.openConnection()) {
            // One connection: temp table visible across both runs.
            var first = QueryExecutor.executeOnConnection(conn, List.of(
                    "CREATE TEMP TABLE " + table + "(id int)"), false, CancelToken.NONE);
            assertThat(first.hasErrors()).isFalse();

            var failed = QueryExecutor.executeOnConnection(conn, List.of(
                    "INSERT INTO " + table + " VALUES (1)",
                    "SELECT 1/0"), true, CancelToken.NONE);
            assertThat(failed.hasErrors()).isTrue();

            var committed = QueryExecutor.executeOnConnection(conn, List.of(
                    "INSERT INTO " + table + " VALUES (2)",
                    "SELECT count(*) FROM " + table), true, CancelToken.NONE);
            assertThat(committed.hasErrors()).as(describe(committed)).isFalse();
            assertThat(((Number) committed.resultSets().get(0).rows().get(0)[0]).longValue()).isEqualTo(1L);
            assertThat(conn.getAutoCommit()).as("autocommit restored for the pool").isTrue();
        }
    }

    @PostgresAvailable
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void cancel_stopsALongStatement() throws Exception {
        var ct = new CancelToken();
        var canceller = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                // test ended
            }
            ct.cancel();
        });
        var started = System.nanoTime();
        var r = new QueryExecutor(mgr).execute("SELECT pg_sleep(30); SELECT 2", true, ct);
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;
        canceller.join();

        assertThat(elapsedMs).isLessThan(10_000);
        assertThat(r.messages()).anyMatch(m -> m.severity() == MessageSeverity.WARNING
                && m.text().equals("Query cancelled."));
        assertThat(r.resultSets()).isEmpty();

        // The pooled connection is still usable afterwards.
        var after = run("SELECT 1", false);
        assertThat(after.hasErrors()).as(describe(after)).isFalse();
    }

    @PostgresAvailable
    void alreadyCancelledToken_runsNothing() {
        var ct = new CancelToken();
        ct.cancel();
        var r = new QueryExecutor(mgr).execute("SELECT 1", false, ct);
        assertThat(r.statementsExecuted()).isZero();
        assertThat(r.messages()).anyMatch(m -> m.text().equals("Query cancelled."));
    }
}
