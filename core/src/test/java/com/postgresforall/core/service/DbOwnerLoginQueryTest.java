package com.postgresforall.core.service;

import com.postgresforall.core.model.QueryResult;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DbOwnerLoginQueryTest {

    private static String messages(QueryResult r) {
        return r.messages().stream().map(m -> m.severity() + ": " + m.text()).collect(Collectors.joining(" | "));
    }

    @PostgresAvailable
    void loginAsOwner_andQueryUsers_returnsRows() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            assertThat(mgr.serverVersion()).isNotEmpty();

            var result = new QueryExecutor(mgr).execute("SELECT id, email, enabled FROM users LIMIT 5;", false,
                    CancelToken.NONE);

            assertThat(result.hasErrors()).as(messages(result)).isFalse();
            assertThat(result.resultSets()).hasSize(1);
            var rs = result.resultSets().get(0);
            assertThat(rs.columns()).extracting(QueryResult.Column::name).containsExactly("id", "email", "enabled");
            assertThat(result.elapsedMs()).isNotNegative();
        }
    }

    @PostgresAvailable
    void loginAsOwner_runsDailyPlanSampleQuery() throws Exception {
        var sql = """
                WITH me AS (SELECT id FROM users WHERE email = 'demo@scheduleme.local'),
                     day AS (SELECT CURRENT_DATE::timestamptz AS d_from,
                                    (CURRENT_DATE + 1)::timestamptz AS d_to)
                SELECT 'event' AS kind, title, start_time AS at, status::text AS state
                FROM events e, me, day
                WHERE e.user_id = me.id
                  AND e.start_time >= day.d_from AND e.start_time < day.d_to
                UNION ALL
                SELECT 'task', title, NULL, priority::text
                FROM tasks t, me
                WHERE t.user_id = me.id
                  AND t.due_date = CURRENT_DATE
                ORDER BY at NULLS LAST;""";
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var result = new QueryExecutor(mgr).execute(sql, false, CancelToken.NONE);
            assertThat(result.hasErrors()).as("Daily plan query failed: " + messages(result)).isFalse();
            assertThat(result.resultSets()).hasSize(1);
            assertThat(result.resultSets().get(0).columns()).hasSize(4);
        }
    }

    @PostgresAvailable
    void schemaInspector_loadsTables_asOwner() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var inspector = new SchemaInspector(mgr);

            var tables = inspector.getTables("public");
            assertThat(tables).extracting(t -> t.name()).contains("users", "events", "tasks");

            var userCols = inspector.getColumns("public", "users");
            assertThat(userCols).anyMatch(c -> c.name().equals("email"));
            assertThat(userCols).anyMatch(c -> c.isPrimaryKey());
        }
    }

    @PostgresAvailable
    void schemaInspector_serverWideAndScripting() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var inspector = new SchemaInspector(mgr);

            assertThat(inspector.getDatabases()).anyMatch(d -> d.name().equals("scheduleme") && d.allowConnections());
            assertThat(inspector.getSchemas()).anyMatch(s -> s.name().equals("public"));
            assertThat(inspector.getRoles()).anyMatch(r -> r.name().equals("scheduleme") && r.canLogin());
            assertThat(inspector.getViews("public")).isNotNull();
            assertThat(inspector.getFunctions("public")).isNotNull();

            var select = inspector.scriptTableSelect("public", "users", 1000);
            assertThat(select).startsWith("SELECT \"").contains("FROM \"public\".\"users\"").endsWith("LIMIT 1000;");
            var create = inspector.scriptTableCreate("public", "users");
            assertThat(create).startsWith("CREATE TABLE \"public\".\"users\" (").contains("PRIMARY KEY (");
            // A missing table: the ::regclass cast raises undefined_table.
            var ex = org.assertj.core.api.Assertions.catchThrowableOfType(java.sql.SQLException.class,
                    () -> inspector.scriptTableCreate("public", "no_such_table_xyz"));
            assertThat((Throwable) ex).isNotNull();
            assertThat(ex.getSQLState()).isEqualTo("42P01");
        }
    }
}
