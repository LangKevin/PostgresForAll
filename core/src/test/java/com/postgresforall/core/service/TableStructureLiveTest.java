package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DdlSpecs.ColumnSpec;
import com.postgresforall.core.model.DdlSpecs.IndexElement;
import com.postgresforall.core.model.DdlSpecs.IndexSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.ConstraintType;
import com.postgresforall.core.model.TableObjects.Dependency;
import com.postgresforall.core.model.TableObjects.IndexDetail;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TableInspector / DependencyInspector / DdlApplier / scriptCreate against a live
 * server, each in its own throwaway database (dropped WITH (FORCE) afterwards).
 */
class TableStructureLiveTest {

    private static final String PREREQUISITES = """
            CREATE TYPE mood AS ENUM ('ok', 'meh');
            CREATE DOMAIN posint AS integer CHECK (VALUE > 0);
            CREATE TABLE customers (id integer PRIMARY KEY, email text UNIQUE);
            CREATE FUNCTION touch() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN NEW."Updated" := now(); RETURN NEW; END';
            """;

    private static final String FIXTURE = """
            CREATE TABLE "Order Items" (
                id bigint GENERATED ALWAYS AS IDENTITY,
                "CustomerId" integer,
                qty integer NOT NULL DEFAULT 1,
                price numeric(10,2),
                total numeric GENERATED ALWAYS AS (qty * price) STORED,
                note text COLLATE "C",
                during tsrange,
                "Updated" timestamptz,
                code text,
                legacy serial,
                m mood,
                CONSTRAINT "Order Items_pkey" PRIMARY KEY (id),
                CONSTRAINT fk_customer FOREIGN KEY ("CustomerId") REFERENCES customers (id)
                    ON UPDATE CASCADE ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED,
                CONSTRAINT uq_code UNIQUE (code),
                CONSTRAINT no_overlap EXCLUDE USING gist (during WITH &&)
            );
            ALTER TABLE "Order Items" ADD CONSTRAINT qty_positive CHECK (qty > 0) NOT VALID;
            CREATE INDEX ix_partial ON "Order Items" (lower(note) DESC) INCLUDE (price) WHERE qty > 1;
            CREATE TRIGGER trg_touch BEFORE INSERT OR UPDATE OF qty, price ON "Order Items"
                FOR EACH ROW WHEN (pg_trigger_depth() = 0) EXECUTE FUNCTION touch();
            CREATE TRIGGER trg_off AFTER DELETE ON "Order Items" FOR EACH STATEMENT EXECUTE FUNCTION touch();
            ALTER TABLE "Order Items" DISABLE TRIGGER trg_off;
            COMMENT ON TABLE "Order Items" IS 'Line items, it''s';
            COMMENT ON COLUMN "Order Items".qty IS 'How many';
            CREATE VIEW item_view AS SELECT id, qty FROM "Order Items";
            CREATE TABLE refunds (id integer PRIMARY KEY, item_id bigint REFERENCES "Order Items" (id));
            """;

    private static final String T = "Order Items";

    @FunctionalInterface
    interface DbBody {
        void run(ConnectionManager mgr, ConnectionProfile profile) throws Exception;
    }

    /** Creates a throwaway database, runs {@code setup} in it, then the body; always drops it. */
    private static void withDatabase(String setup, DbBody body) throws Exception {
        var admin = TestProfiles.owner();
        var db = "pfa_tbl_" + TestProfiles.tag();
        exec(admin.withDatabase("postgres"), "CREATE DATABASE " + db);
        try {
            var profile = admin.withDatabase(db);
            if (setup != null) exec(profile, setup);
            try (var mgr = ConnectionManager.open(profile)) {
                body.run(mgr, profile);
            }
        } finally {
            dropDatabase(admin, db);
        }
    }

    private static void dropDatabase(ConnectionProfile admin, String db) {
        try {
            exec(admin.withDatabase("postgres"), "DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
        } catch (SQLException ignored) {
            // best effort cleanup
        }
    }

    private static void exec(ConnectionProfile profile, String sql) throws SQLException {
        try (var conn = profile.open(); var st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static boolean exists(ConnectionManager mgr, String sql) throws SQLException {
        try (var conn = mgr.openConnection(); var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    @PostgresAvailable
    void inspector_readsEveryPartOfTheTable() throws Exception {
        withDatabase(PREREQUISITES + FIXTURE, (mgr, profile) -> {
            var ti = new TableInspector(mgr);

            var summary = ti.summary("public", T);
            assertThat(summary.name()).isEqualTo(T);
            assertThat(summary.owner()).isEqualTo(profile.username);
            assertThat(summary.canAlter()).isTrue();
            assertThat(summary.comment()).isEqualTo("Line items, it's");
            assertThat(summary.totalBytes()).isPositive();

            var cols = ti.columns("public", T);
            assertThat(cols).extracting(c -> c.name()).containsExactly(
                    "id", "CustomerId", "qty", "price", "total", "note", "during", "Updated", "code", "legacy", "m");
            var id = cols.get(0);
            assertThat(id.identity()).isEqualTo("ALWAYS");
            assertThat(id.primaryKey()).isTrue();
            assertThat(id.nullable()).isFalse();
            assertThat(id.defaultExpr()).isNull();
            var qty = cols.get(2);
            assertThat(qty.defaultExpr()).isEqualTo("1");
            assertThat(qty.comment()).isEqualTo("How many");
            assertThat(qty.nullable()).isFalse();
            assertThat(cols.get(3).dataType()).isEqualTo("numeric(10,2)");
            assertThat(cols.get(4).generated()).contains("qty").contains("price");
            assertThat(cols.get(4).defaultExpr()).isNull();
            assertThat(cols.get(5).collation()).isEqualTo("C");
            assertThat(cols.get(8).collation()).isNull();
            assertThat(cols.get(9).defaultExpr()).startsWith("nextval(");
            assertThat(cols.get(10).dataType()).isEqualTo("mood");

            var cons = ti.constraints("public", T);
            assertThat(cons).extracting(ConstraintDetail::type).containsExactly(ConstraintType.PRIMARY_KEY,
                    ConstraintType.FOREIGN_KEY, ConstraintType.UNIQUE, ConstraintType.CHECK, ConstraintType.EXCLUSION);
            var fk = cons.get(1);
            assertThat(fk.name()).isEqualTo("fk_customer");
            assertThat(fk.columns()).containsExactly("CustomerId");
            assertThat(fk.refSchema()).isEqualTo("public");
            assertThat(fk.refTable()).isEqualTo("customers");
            assertThat(fk.refColumns()).containsExactly("id");
            assertThat(fk.onUpdate()).isEqualTo("CASCADE");
            assertThat(fk.onDelete()).isEqualTo("SET NULL");
            assertThat(fk.deferrable()).isTrue();
            assertThat(fk.initiallyDeferred()).isTrue();
            var check = cons.get(3);
            assertThat(check.name()).isEqualTo("qty_positive");
            assertThat(check.validated()).isFalse();
            assertThat(check.definition()).contains("qty > 0");
            assertThat(cons.get(0).refTable()).isNull();
            assertThat(cons.get(0).onDelete()).isNull();

            var idx = ti.indexes("public", T);
            assertThat(idx.get(0).primary()).isTrue();
            assertThat(idx.get(0).constraintName()).isEqualTo("Order Items_pkey");
            var partial = idx.stream().filter(i -> i.name().equals("ix_partial")).findFirst().orElseThrow();
            assertThat(partial.method()).isEqualTo("btree");
            assertThat(partial.constraintName()).isNull();
            assertThat(partial.keys()).containsExactly("lower(note)");
            assertThat(partial.predicate()).isEqualTo("qty > 1");
            assertThat(partial.valid()).isTrue();
            var gist = idx.stream().filter(i -> "no_overlap".equals(i.constraintName())).findFirst().orElseThrow();
            assertThat(gist.method()).isEqualTo("gist");
            assertThat(idx).extracting(IndexDetail::constraintName).contains("uq_code");

            var trg = ti.triggers("public", T);
            assertThat(trg).hasSize(2);
            var off = trg.get(0);
            assertThat(off.name()).isEqualTo("trg_off");
            assertThat(off.enabled()).isFalse();
            assertThat(off.level()).isEqualTo("STATEMENT");
            assertThat(off.timing()).isEqualTo("AFTER");
            assertThat(off.events()).containsExactly("DELETE");
            var touch = trg.get(1);
            assertThat(touch.timing()).isEqualTo("BEFORE");
            assertThat(touch.events()).containsExactly("INSERT", "UPDATE");
            assertThat(touch.level()).isEqualTo("ROW");
            assertThat(touch.enabled()).isTrue();
            assertThat(touch.function()).isEqualTo("public.touch");
            assertThat(touch.whenExpr()).isEqualTo("pg_trigger_depth() = 0");

            var refs = ti.referencedBy("public", T);
            assertThat(refs).hasSize(1);
            assertThat(refs.get(0).table()).isEqualTo("refunds");
            assertThat(refs.get(0).columns()).containsExactly("item_id");

            assertThat(ti.tables()).contains(new TableRef("public", T), new TableRef("public", "customers"))
                    .doesNotContain(new TableRef("public", "item_view"));
            assertThat(ti.candidateKeys("public", "customers")).containsExactly(List.of("id"), List.of("email"));
            assertThat(ti.triggerFunctions()).containsExactly("public.touch");
            assertThat(ti.dataTypes()).contains("integer", "text", "mood", "posint");
        });
    }

    @PostgresAvailable
    void dependencies_forTableColumnIndexConstraint() throws Exception {
        withDatabase(PREREQUISITES + FIXTURE, (mgr, profile) -> {
            var di = new DependencyInspector(mgr);

            var table = di.forTable("public", T);
            assertThat(table).extracting(Dependency::kind, Dependency::name)
                    .contains(org.assertj.core.groups.Tuple.tuple("View", "public.item_view"),
                            org.assertj.core.groups.Tuple.tuple("Foreign key", "refunds_item_id_fkey"),
                            org.assertj.core.groups.Tuple.tuple("Sequence", "public.\"Order Items_legacy_seq\""));

            var qty = di.forColumn("public", T, "qty");
            assertThat(qty).extracting(Dependency::name)
                    .contains("public.item_view", "qty_positive", "trg_touch", "total", "ix_partial");
            assertThat(qty).extracting(Dependency::kind).contains("Generated column", "Check", "Trigger", "Index", "View");

            var id = di.forColumn("public", T, "id");
            assertThat(id).extracting(Dependency::name).contains("Order Items_pkey", "refunds_item_id_fkey", "public.item_view");
            assertThat(di.forColumn("public", T, "m")).isEmpty();

            assertThat(di.forIndex("public", "Order Items_pkey")).extracting(Dependency::name)
                    .containsExactly("Order Items_pkey", "refunds_item_id_fkey");
            assertThat(di.forIndex("public", "ix_partial")).isEmpty();

            assertThat(di.forConstraint("public", T, "Order Items_pkey")).extracting(Dependency::name)
                    .containsExactly("refunds_item_id_fkey");
            assertThat(di.forConstraint("public", T, "uq_code")).isEmpty();
            assertThat(di.forTable("public", "refunds")).isEmpty();
        });
    }

    @PostgresAvailable
    void scriptCreate_roundTripsIntoAnotherDatabase() throws Exception {
        withDatabase(PREREQUISITES + FIXTURE, (mgr, profile) -> {
            var ti = new TableInspector(mgr);
            var script = ti.scriptCreate("public", T);
            assertThat(script).contains("CREATE TABLE public.\"Order Items\" (")
                    .contains("GENERATED ALWAYS AS IDENTITY")
                    .contains("COLLATE \"C\"")
                    .contains("OWNER TO");

            withDatabase(PREREQUISITES + script, (copy, copyProfile) -> {
                var tc = new TableInspector(copy);
                assertThat(tc.columns("public", T)).isEqualTo(ti.columns("public", T));
                assertThat(tc.constraints("public", T)).isEqualTo(ti.constraints("public", T));
                assertThat(tc.triggers("public", T)).isEqualTo(ti.triggers("public", T));
                assertThat(stripStats(tc.indexes("public", T))).isEqualTo(stripStats(ti.indexes("public", T)));
                assertThat(tc.summary("public", T).comment()).isEqualTo("Line items, it's");
                assertThat(tc.scriptCreate("public", T)).isEqualTo(script);
            });
        });
    }

    private static List<IndexDetail> stripStats(List<IndexDetail> list) {
        return list.stream().map(i -> new IndexDetail(i.name(), i.method(), i.unique(), i.primary(), i.constraintName(),
                i.valid(), i.definition(), i.keys(), i.predicate(), 0, 0)).toList();
    }

    @PostgresAvailable
    void applier_commitsRollsBackDryRunsAndRunsConcurrentlyOutsideTheTransaction() throws Exception {
        withDatabase("CREATE TABLE things (id integer PRIMARY KEY, name text);", (mgr, profile) -> {
            var applier = new DdlApplier(mgr);
            var events = new CopyOnWriteArrayList<OperationEvent>();

            // Success: two statements in one transaction.
            var ok = applier.apply(List.of(
                    DdlBuilder.addColumn("public", "things", new ColumnSpec("size", "integer", false, null, null, null, null, "sz")).get(0),
                    DdlBuilder.commentOnTable("public", "things", "stuff")), false, events::add, new CancelToken());
            assertThat(ok.success()).isTrue();
            assertThat(exists(mgr, "SELECT count(*) = 1 FROM pg_attribute WHERE attrelid = 'things'::regclass AND attname = 'size'")).isTrue();
            assertThat(events).extracting(OperationEvent::severity).contains(OperationEvent.Severity.SUCCESS);

            // Second statement fails → the first is rolled back too.
            events.clear();
            var fail = applier.apply(List.of(
                    DdlBuilder.addColumn("public", "things", new ColumnSpec("color", "text", false, null, null, null, null, null)).get(0),
                    DdlBuilder.addColumn("public", "things", new ColumnSpec("size", "integer", false, null, null, null, null, null)).get(0)),
                    false, events::add, new CancelToken());
            assertThat(fail.success()).isFalse();
            assertThat(fail.sqlState()).isEqualTo("42701");
            assertThat(fail.failedStatement().sql()).contains("ADD COLUMN size");
            assertThat(fail.message()).contains("[42701]").contains("rolled back");
            assertThat(exists(mgr, "SELECT count(*) = 0 FROM pg_attribute WHERE attrelid = 'things'::regclass AND attname = 'color'")).isTrue();
            assertThat(events).extracting(OperationEvent::severity).contains(OperationEvent.Severity.ERROR);

            // Dry run: executes and rolls back; CONCURRENTLY is skipped with a warning.
            events.clear();
            var concurrent = DdlBuilder.createIndex(new IndexSpec("public", "things", "ix_name",
                    List.of(new IndexElement("name", false, false, null)), "btree", false, List.of(), null, null, true));
            var dry = applier.apply(List.of(
                    DdlBuilder.addColumn("public", "things", new ColumnSpec("weight", "real", false, null, null, null, null, null)).get(0),
                    concurrent), true, events::add, new CancelToken());
            assertThat(dry.success()).isTrue();
            assertThat(exists(mgr, "SELECT count(*) = 0 FROM pg_attribute WHERE attrelid = 'things'::regclass AND attname = 'weight'")).isTrue();
            assertThat(exists(mgr, "SELECT to_regclass('public.ix_name') IS NULL")).isTrue();
            assertThat(events).anyMatch(e -> e.severity() == OperationEvent.Severity.WARN && e.text().contains("CONCURRENTLY"));

            // Real run: transactional part, then CONCURRENTLY in autocommit.
            var real = applier.apply(List.of(concurrent,
                    DdlBuilder.addColumn("public", "things", new ColumnSpec("weight", "real", false, null, null, null, null, null)).get(0)),
                    false, null, null);
            assertThat(real.success()).as(real.message()).isTrue();
            assertThat(exists(mgr, "SELECT to_regclass('public.ix_name') IS NOT NULL")).isTrue();
            assertThat(exists(mgr, "SELECT count(*) = 1 FROM pg_attribute WHERE attrelid = 'things'::regclass AND attname = 'weight'")).isTrue();
            assertThat(new TableInspector(mgr).indexes("public", "things")).extracting(IndexDetail::name).contains("ix_name");

            // The lock_timeout set for the autocommit part must not leak into pooled connections.
            var timeouts = new ArrayList<String>();
            var held = new ArrayList<java.sql.Connection>();
            try {
                for (var i = 0; i < 3; i++) {
                    var c = mgr.openConnection();
                    held.add(c);
                    try (var st = c.createStatement(); var rs = st.executeQuery("SHOW lock_timeout")) {
                        rs.next();
                        timeouts.add(rs.getString(1));
                    }
                }
            } finally {
                for (var c : held) c.close();
            }
            assertThat(timeouts).allMatch(t -> !t.equals(DdlApplier.DEFAULT_LOCK_TIMEOUT));
        });
    }

    @PostgresAvailable
    void applier_failsFastWhenTheTableIsBusy() throws Exception {
        withDatabase("CREATE TABLE busy (id integer);", (mgr, profile) -> {
            try (var other = profile.open(); var st = other.createStatement()) {
                other.setAutoCommit(false);
                st.execute("LOCK TABLE busy IN ACCESS EXCLUSIVE MODE");
                var started = System.nanoTime();
                var r = new DdlApplier(mgr).apply(
                        DdlBuilder.addColumn("public", "busy", new ColumnSpec("x", "integer", false, null, null, null, null, null)),
                        false, null, new CancelToken());
                var seconds = (System.nanoTime() - started) / 1e9;
                other.rollback();
                assertThat(r.success()).isFalse();
                assertThat(r.sqlState()).isEqualTo("55P03");
                assertThat(r.message()).contains("busy").contains("Nothing changed");
                assertThat(seconds).isLessThan(30);
            }
            assertThat(exists(mgr, "SELECT count(*) = 0 FROM pg_attribute WHERE attrelid = 'busy'::regclass AND attname = 'x'")).isTrue();
        });
    }

    @PostgresAvailable
    void applier_emptyListIsANoOp() throws Exception {
        withDatabase(null, (mgr, profile) -> {
            List<DdlStatement> none = List.of();
            assertThat(new DdlApplier(mgr).apply(none, false, null, null).success()).isTrue();
        });
    }
}
