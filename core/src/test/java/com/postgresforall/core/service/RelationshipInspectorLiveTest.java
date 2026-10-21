package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.Relationships.DiagramTable;
import com.postgresforall.core.model.Relationships.Relationship;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** RelationshipInspector against a live server, in a throwaway database dropped afterwards. */
class RelationshipInspectorLiveTest {

    private static final String FIXTURE = """
            CREATE TABLE customers (id integer PRIMARY KEY, email text UNIQUE);
            CREATE TABLE products (id integer PRIMARY KEY, name text);
            CREATE TABLE orders (id integer PRIMARY KEY,
                                 customer_id integer NOT NULL REFERENCES customers (id));
            CREATE TABLE order_items (id integer PRIMARY KEY,
                                      order_id integer NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
                                      product_id integer REFERENCES products (id));
            CREATE TABLE profiles (customer_id integer PRIMARY KEY REFERENCES customers (id), bio text);
            CREATE TABLE employees (id integer PRIMARY KEY, manager_id integer REFERENCES employees (id));
            CREATE TABLE unrelated (id integer PRIMARY KEY);
            """;

    @FunctionalInterface
    interface Body {
        void run(RelationshipInspector ri) throws Exception;
    }

    private static void withDatabase(Body body) throws Exception {
        var admin = TestProfiles.owner();
        var db = "pfa_rel_" + TestProfiles.tag();
        exec(admin.withDatabase("postgres"), "CREATE DATABASE " + db);
        try {
            var profile = admin.withDatabase(db);
            exec(profile, FIXTURE);
            try (var mgr = ConnectionManager.open(profile)) {
                body.run(new RelationshipInspector(mgr));
            }
        } finally {
            try {
                exec(admin.withDatabase("postgres"), "DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
            } catch (SQLException ignored) {
                // best effort cleanup
            }
        }
    }

    private static void exec(ConnectionProfile profile, String sql) throws SQLException {
        try (var conn = profile.open(); var st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static TableRef t(String name) {
        return new TableRef("public", name);
    }

    private static Relationship rel(com.postgresforall.core.model.Relationships.RelationshipGraph g, String from, String to) {
        return g.relationships().stream()
                .filter(r -> r.from().equals(t(from)) && r.to().equals(t(to)))
                .findFirst().orElseThrow(() -> new AssertionError("no FK " + from + " → " + to));
    }

    @PostgresAvailable
    void oneHop_followsBothDirections() throws Exception {
        withDatabase(ri -> {
            var g = ri.graph("public", "orders", 1, 50);
            assertThat(g.focus()).isEqualTo(t("orders"));
            assertThat(g.tables()).extracting(DiagramTable::ref)
                    .containsExactlyInAnyOrder(t("orders"), t("customers"), t("order_items"));
            assertThat(g.table(t("orders")).distance()).isZero();
            assertThat(g.table(t("customers")).distance()).isEqualTo(1);
            assertThat(g.relationships()).hasSize(2);
            assertThat(g.truncated()).isFalse();

            var toCustomer = rel(g, "orders", "customers");
            assertThat(toCustomer.fromColumns()).containsExactly("customer_id");
            assertThat(toCustomer.toColumns()).containsExactly("id");
            assertThat(toCustomer.optional()).isFalse();
            assertThat(toCustomer.oneToOne()).isFalse();
            assertThat(rel(g, "order_items", "orders").onDelete()).isEqualTo("CASCADE");

            var orders = g.table(t("orders"));
            assertThat(orders.columns()).extracting(c -> c.name()).containsExactly("id", "customer_id");
            assertThat(orders.columns().get(0).primaryKey()).isTrue();
            assertThat(orders.columns().get(1).foreignKey()).isTrue();
            assertThat(orders.columns().get(1).primaryKey()).isFalse();
        });
    }

    @PostgresAvailable
    void twoHops_reachesFurtherTables_withCardinality() throws Exception {
        withDatabase(ri -> {
            var g = ri.graph("public", "orders", 2, 50);
            assertThat(g.tables()).extracting(DiagramTable::ref)
                    .containsExactlyInAnyOrder(t("orders"), t("customers"), t("order_items"), t("products"), t("profiles"));
            assertThat(g.tables()).extracting(DiagramTable::ref).doesNotContain(t("unrelated"), t("employees"));
            assertThat(rel(g, "order_items", "products").optional()).isTrue();
            assertThat(rel(g, "profiles", "customers").oneToOne()).isTrue();
        });
    }

    @PostgresAvailable
    void selfReference_isOneTableOneEdge() throws Exception {
        withDatabase(ri -> {
            var g = ri.graph("public", "employees", 2, 50);
            assertThat(g.tables()).hasSize(1);
            assertThat(g.relationships()).singleElement().satisfies(r -> {
                assertThat(r.selfReference()).isTrue();
                assertThat(r.optional()).isTrue();
            });
        });
    }

    @PostgresAvailable
    void limit_truncatesAndKeepsOnlyEdgesBetweenShownTables() throws Exception {
        withDatabase(ri -> {
            var g = ri.graph("public", "orders", 2, 2);
            assertThat(g.tables()).hasSize(2);
            assertThat(g.truncated()).isTrue();
            for (var r : g.relationships()) {
                assertThat(g.table(r.from())).isNotNull();
                assertThat(g.table(r.to())).isNotNull();
            }
        });
    }

    @PostgresAvailable
    void tableWithoutForeignKeys_isAlone() throws Exception {
        withDatabase(ri -> {
            var g = ri.graph("public", "unrelated", 3, 50);
            assertThat(g.tables()).extracting(DiagramTable::ref).containsExactly(t("unrelated"));
            assertThat(g.relationships()).isEmpty();
        });
    }
}
