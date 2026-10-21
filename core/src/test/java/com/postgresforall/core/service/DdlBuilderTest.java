package com.postgresforall.core.service;

import com.postgresforall.core.model.DdlSpecs.CheckSpec;
import com.postgresforall.core.model.DdlSpecs.ColumnSpec;
import com.postgresforall.core.model.DdlSpecs.ExclusionElement;
import com.postgresforall.core.model.DdlSpecs.ExclusionSpec;
import com.postgresforall.core.model.DdlSpecs.ForeignKeySpec;
import com.postgresforall.core.model.DdlSpecs.IndexElement;
import com.postgresforall.core.model.DdlSpecs.IndexSpec;
import com.postgresforall.core.model.DdlSpecs.KeySpec;
import com.postgresforall.core.model.DdlSpecs.TriggerSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.DdlStatement.Lock;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdlBuilderTest {

    private static final String T = "public.\"Order Items\"";

    private static List<String> sql(List<DdlStatement> stmts) {
        return stmts.stream().map(DdlStatement::sql).toList();
    }

    @Test
    void ident_quotesOnlyWhenNeeded() {
        assertThat(DdlBuilder.ident("orders")).isEqualTo("orders");
        assertThat(DdlBuilder.ident("_x1$")).isEqualTo("_x1$");
        assertThat(DdlBuilder.ident("Order Items")).isEqualTo("\"Order Items\"");
        assertThat(DdlBuilder.ident("CustomerId")).isEqualTo("\"CustomerId\"");
        assertThat(DdlBuilder.ident("user")).isEqualTo("\"user\"");
        assertThat(DdlBuilder.ident("order")).isEqualTo("\"order\"");
        assertThat(DdlBuilder.ident("1abc")).isEqualTo("\"1abc\"");
        assertThat(DdlBuilder.ident("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(DdlBuilder.qualified("public", "Order Items")).isEqualTo(T);
        assertThat(DdlBuilder.qualified(null, "t")).isEqualTo("t");
        assertThat(DdlBuilder.qualified("", "t")).isEqualTo("t");
    }

    @Test
    void createIndex_fullForm_andConcurrentlyIsNonTransactional() {
        var spec = new IndexSpec("public", "Order Items", "ix one", List.of(
                new IndexElement("CustomerId", false, true, false),
                new IndexElement("lower(note)", true, false, null)),
                "btree", true, List.of("price"), "qty > 1", 90, false);
        var s = DdlBuilder.createIndex(spec);
        assertThat(s.sql()).isEqualTo("CREATE UNIQUE INDEX \"ix one\" ON " + T
                + " (\"CustomerId\" DESC NULLS LAST, (lower(note))) INCLUDE (price) WITH (fillfactor = 90) WHERE (qty > 1);");
        assertThat(s.transactional()).isTrue();
        assertThat(s.lock()).isEqualTo(Lock.SHARE);
        assertThat(s.lockedTable()).isEqualTo(T);

        var c = DdlBuilder.createIndex(new IndexSpec("s", "t", null, List.of(new IndexElement("during", false, false, null)),
                "GIST", false, List.of(), null, null, true));
        assertThat(c.sql()).isEqualTo("CREATE INDEX CONCURRENTLY ON s.t USING gist (during);");
        assertThat(c.transactional()).isFalse();
        assertThat(c.lock()).isEqualTo(Lock.SHARE_UPDATE_EXCLUSIVE);
    }

    @Test
    void indexMaintenance() {
        var drop = DdlBuilder.dropIndex("public", "Order Items", "ix_a", false, true);
        assertThat(drop.sql()).isEqualTo("DROP INDEX public.ix_a CASCADE;");
        assertThat(drop.lock()).isEqualTo(Lock.ACCESS_EXCLUSIVE);
        var dropC = DdlBuilder.dropIndex("public", "Order Items", "ix_a", true, false);
        assertThat(dropC.sql()).isEqualTo("DROP INDEX CONCURRENTLY public.ix_a;");
        assertThat(dropC.transactional()).isFalse();
        assertThatThrownBy(() -> DdlBuilder.dropIndex("public", "t", "ix", true, true))
                .isInstanceOf(IllegalArgumentException.class);

        var rename = DdlBuilder.renameIndex("public", "t", "ix_a", "Ix B");
        assertThat(rename.sql()).isEqualTo("ALTER INDEX public.ix_a RENAME TO \"Ix B\";");
        assertThat(rename.lock()).isEqualTo(Lock.SHARE_UPDATE_EXCLUSIVE);

        assertThat(DdlBuilder.reindex("public", "t", "ix_a", false).sql()).isEqualTo("REINDEX INDEX public.ix_a;");
        var rc = DdlBuilder.reindex("public", "t", "ix_a", true);
        assertThat(rc.sql()).isEqualTo("REINDEX INDEX CONCURRENTLY public.ix_a;");
        assertThat(rc.transactional()).isFalse();
    }

    @Test
    void constraints() {
        var fk = DdlBuilder.addForeignKey(new ForeignKeySpec("public", "Order Items", "fk_customer", List.of("CustomerId"),
                "public", "customers", List.of("id"), "CASCADE", "SET NULL", true, true, true));
        assertThat(fk.sql()).isEqualTo("ALTER TABLE " + T + " ADD CONSTRAINT fk_customer FOREIGN KEY (\"CustomerId\") "
                + "REFERENCES public.customers (id) ON UPDATE CASCADE ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED NOT VALID;");
        assertThat(fk.lock()).isEqualTo(Lock.SHARE_ROW_EXCLUSIVE);

        var plainFk = DdlBuilder.addForeignKey(new ForeignKeySpec("s", "a", null, List.of("b_id"), "s", "user",
                List.of(), "NO ACTION", null, false, true, false));
        assertThat(plainFk.sql()).isEqualTo("ALTER TABLE s.a ADD FOREIGN KEY (b_id) REFERENCES s.\"user\";");

        var check = DdlBuilder.addCheck(new CheckSpec("s", "t", "qty_ok", "qty > 0", true, true));
        assertThat(check.sql()).isEqualTo("ALTER TABLE s.t ADD CONSTRAINT qty_ok CHECK (qty > 0) NO INHERIT NOT VALID;");
        assertThat(check.lock()).isEqualTo(Lock.ACCESS_EXCLUSIVE);

        assertThat(DdlBuilder.addKey(new KeySpec("s", "t", "t_pkey", List.of("id"), true, false, List.of())).sql())
                .isEqualTo("ALTER TABLE s.t ADD CONSTRAINT t_pkey PRIMARY KEY (id);");
        assertThat(DdlBuilder.addKey(new KeySpec("s", "t", null, List.of("a", "B"), false, true, List.of("c"))).sql())
                .isEqualTo("ALTER TABLE s.t ADD UNIQUE NULLS NOT DISTINCT (a, \"B\") INCLUDE (c);");

        assertThat(DdlBuilder.addExclusion(new ExclusionSpec("s", "t", "no_overlap", "gist",
                List.of(new ExclusionElement("room", "="), new ExclusionElement("during", "&&")), "not cancelled")).sql())
                .isEqualTo("ALTER TABLE s.t ADD CONSTRAINT no_overlap EXCLUDE USING gist (room WITH =, during WITH &&) WHERE (not cancelled);");

        assertThat(DdlBuilder.dropConstraint("s", "t", "Fk", true).sql()).isEqualTo("ALTER TABLE s.t DROP CONSTRAINT \"Fk\" CASCADE;");
        assertThat(DdlBuilder.renameConstraint("s", "t", "a", "b").sql()).isEqualTo("ALTER TABLE s.t RENAME CONSTRAINT a TO b;");
        var v = DdlBuilder.validateConstraint("s", "t", "a");
        assertThat(v.sql()).isEqualTo("ALTER TABLE s.t VALIDATE CONSTRAINT a;");
        assertThat(v.lock()).isEqualTo(Lock.SHARE_UPDATE_EXCLUSIVE);
    }

    @Test
    void addColumn_withCommentAndVariants() {
        var stmts = DdlBuilder.addColumn("public", "Order Items",
                new ColumnSpec("Note", "text", true, "'n/a'", null, null, "C", "It's a note"));
        assertThat(sql(stmts)).containsExactly(
                "ALTER TABLE " + T + " ADD COLUMN \"Note\" text COLLATE \"C\" DEFAULT 'n/a' NOT NULL;",
                "COMMENT ON COLUMN " + T + ".\"Note\" IS 'It''s a note';");
        assertThat(stmts.get(0).lock()).isEqualTo(Lock.ACCESS_EXCLUSIVE);
        assertThat(stmts.get(1).lock()).isEqualTo(Lock.SHARE_UPDATE_EXCLUSIVE);

        assertThat(sql(DdlBuilder.addColumn("s", "t", new ColumnSpec("id", "bigint", true, null, "by default", null, null, null))))
                .containsExactly("ALTER TABLE s.t ADD COLUMN id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL;");
        assertThat(sql(DdlBuilder.addColumn("s", "t", new ColumnSpec("total", "numeric", false, null, null, "(a * b)", null, ""))))
                .containsExactly("ALTER TABLE s.t ADD COLUMN total numeric GENERATED ALWAYS AS (a * b) STORED;");
    }

    @Test
    void alterColumn_emitsOnlyTheDifferences() {
        var before = new ColumnDetail("qty", "integer", true, "1", null, null, null, "old", 3, false);

        assertThat(DdlBuilder.alterColumn("s", "t", before,
                new ColumnSpec("qty", "INTEGER ", false, "1", null, null, null, null), null)).isEmpty();
        assertThat(DdlBuilder.alterColumn("s", "t", before,
                new ColumnSpec("qty", "integer", false, "1", null, null, null, "old"), null)).isEmpty();

        var all = DdlBuilder.alterColumn("s", "t", before,
                new ColumnSpec("Quantity", "bigint", true, null, null, null, null, ""), "qty::bigint");
        assertThat(sql(all)).containsExactly(
                "ALTER TABLE s.t RENAME COLUMN qty TO \"Quantity\";",
                "ALTER TABLE s.t ALTER COLUMN \"Quantity\" TYPE bigint USING qty::bigint;",
                "ALTER TABLE s.t ALTER COLUMN \"Quantity\" DROP DEFAULT;",
                "ALTER TABLE s.t ALTER COLUMN \"Quantity\" SET NOT NULL;",
                "COMMENT ON COLUMN s.t.\"Quantity\" IS NULL;");
        assertThat(all).allMatch(DdlStatement::transactional);

        var identity = new ColumnDetail("id", "bigint", false, null, "ALWAYS", null, null, null, 1, true);
        assertThat(sql(DdlBuilder.alterColumn("s", "t", identity,
                new ColumnSpec("id", "bigint", true, null, "BY DEFAULT", null, null, null), null)))
                .containsExactly("ALTER TABLE s.t ALTER COLUMN id SET GENERATED BY DEFAULT;");
        assertThat(sql(DdlBuilder.alterColumn("s", "t", identity,
                new ColumnSpec("id", "bigint", true, null, null, null, null, null), null)))
                .containsExactly("ALTER TABLE s.t ALTER COLUMN id DROP IDENTITY;");
        var plain = new ColumnDetail("id", "bigint", false, null, null, null, null, null, 1, true);
        assertThat(sql(DdlBuilder.alterColumn("s", "t", plain,
                new ColumnSpec("id", "bigint", true, null, "ALWAYS", null, null, null), null)))
                .containsExactly("ALTER TABLE s.t ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY;");

        var gen = new ColumnDetail("total", "numeric", true, null, null, "(a * b)", null, null, 5, false);
        assertThat(sql(DdlBuilder.alterColumn("s", "t", gen,
                new ColumnSpec("total", "numeric", false, null, null, null, null, null), null)))
                .containsExactly("ALTER TABLE s.t ALTER COLUMN total DROP EXPRESSION;");

        var text = new ColumnDetail("note", "text", true, null, null, null, null, null, 2, false);
        assertThat(sql(DdlBuilder.alterColumn("s", "t", text,
                new ColumnSpec("note", "text", false, "'x'", null, null, "C", null), null)))
                .containsExactly("ALTER TABLE s.t ALTER COLUMN note TYPE text COLLATE \"C\";",
                        "ALTER TABLE s.t ALTER COLUMN note SET DEFAULT 'x';");
    }

    @Test
    void columnRenameAndDrop() {
        assertThat(DdlBuilder.renameColumn("s", "t", "a", "select").sql()).isEqualTo("ALTER TABLE s.t RENAME COLUMN a TO \"select\";");
        assertThat(DdlBuilder.dropColumn("s", "t", "a", true).sql()).isEqualTo("ALTER TABLE s.t DROP COLUMN a CASCADE;");
    }

    @Test
    void triggers() {
        var t = DdlBuilder.createTrigger(new TriggerSpec("public", "Order Items", "trg_touch", "before",
                List.of("INSERT", "UPDATE"), List.of("qty", "Price"), true, "(pg_trigger_depth() = 0)", "public.touch"));
        assertThat(t.sql()).isEqualTo("CREATE TRIGGER trg_touch BEFORE INSERT OR UPDATE OF qty, \"Price\" ON " + T
                + " FOR EACH ROW WHEN (pg_trigger_depth() = 0) EXECUTE FUNCTION public.touch();");
        assertThat(t.lock()).isEqualTo(Lock.SHARE_ROW_EXCLUSIVE);
        assertThat(DdlBuilder.createTrigger(new TriggerSpec("s", "t", "a", "AFTER", List.of("TRUNCATE"), List.of(), false,
                null, "s.f")).sql()).isEqualTo("CREATE TRIGGER a AFTER TRUNCATE ON s.t FOR EACH STATEMENT EXECUTE FUNCTION s.f();");
        assertThatThrownBy(() -> DdlBuilder.createTrigger(new TriggerSpec("s", "t", " ", "AFTER", List.of("INSERT"),
                List.of(), true, null, "s.f"))).isInstanceOf(IllegalArgumentException.class);

        assertThat(DdlBuilder.dropTrigger("s", "t", "a").sql()).isEqualTo("DROP TRIGGER a ON s.t;");
        assertThat(DdlBuilder.renameTrigger("s", "t", "a", "B").sql()).isEqualTo("ALTER TRIGGER a ON s.t RENAME TO \"B\";");
        assertThat(DdlBuilder.setTriggerEnabled("s", "t", "a", false).sql()).isEqualTo("ALTER TABLE s.t DISABLE TRIGGER a;");
        assertThat(DdlBuilder.setTriggerEnabled("s", "t", "a", true).sql()).isEqualTo("ALTER TABLE s.t ENABLE TRIGGER a;");
    }

    @Test
    void tableStatements() {
        assertThat(DdlBuilder.renameTable("s", "t", "Order").sql()).isEqualTo("ALTER TABLE s.t RENAME TO \"Order\";");
        assertThat(DdlBuilder.dropTable("s", "t", true).sql()).isEqualTo("DROP TABLE s.t CASCADE;");
        assertThat(DdlBuilder.truncateTable("s", "t", true, true).sql()).isEqualTo("TRUNCATE TABLE s.t RESTART IDENTITY CASCADE;");
        assertThat(DdlBuilder.truncateTable("s", "t", false, false).sql()).isEqualTo("TRUNCATE TABLE s.t;");
        assertThat(DdlBuilder.commentOnTable("s", "t", "it's").sql()).isEqualTo("COMMENT ON TABLE s.t IS 'it''s';");
        assertThat(DdlBuilder.commentOnTable("s", "t", "").sql()).isEqualTo("COMMENT ON TABLE s.t IS NULL;");
        assertThat(DdlBuilder.dropTable("s", "t", false).lock()).isEqualTo(Lock.ACCESS_EXCLUSIVE);
    }
}
