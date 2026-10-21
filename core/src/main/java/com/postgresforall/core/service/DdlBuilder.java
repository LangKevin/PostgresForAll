package com.postgresforall.core.service;

import com.postgresforall.core.model.DdlSpecs.CheckSpec;
import com.postgresforall.core.model.DdlSpecs.ColumnSpec;
import com.postgresforall.core.model.DdlSpecs.ExclusionSpec;
import com.postgresforall.core.model.DdlSpecs.ForeignKeySpec;
import com.postgresforall.core.model.DdlSpecs.IndexElement;
import com.postgresforall.core.model.DdlSpecs.IndexSpec;
import com.postgresforall.core.model.DdlSpecs.KeySpec;
import com.postgresforall.core.model.DdlSpecs.TriggerSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.DdlStatement.Lock;
import com.postgresforall.core.model.TableObjects.ColumnDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure SQL generation for structure changes. Every identifier is quoted with
 * {@link #ident}; expressions / types / default values are passed through as
 * written by the user. Each statement carries the lock it takes and whether it
 * can run inside a transaction.
 */
public final class DdlBuilder {
    private DdlBuilder() { }

    private static final Pattern PLAIN_IDENT = Pattern.compile("^[a-z_][a-z0-9_$]*$");

    /** PostgreSQL reserved key words (plus those reserved in type / function position) that must be quoted. */
    private static final Set<String> RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "authorization",
            "between", "bigint", "binary", "bit", "boolean", "both", "case", "cast", "char", "character", "check",
            "coalesce", "collate", "collation", "column", "concurrently", "constraint", "create", "cross",
            "current_catalog", "current_date", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "dec", "decimal", "default", "deferrable", "desc", "distinct",
            "do", "else", "end", "except", "exists", "extract", "false", "fetch", "float", "for", "foreign",
            "freeze", "from", "full", "grant", "greatest", "group", "grouping", "having", "ilike", "in",
            "initially", "inner", "inout", "int", "integer", "intersect", "interval", "into", "is", "isnull",
            "join", "lateral", "leading", "least", "left", "like", "limit", "localtime", "localtimestamp",
            "national", "natural", "nchar", "none", "normalize", "not", "notnull", "null", "nullif", "numeric",
            "offset", "on", "only", "or", "order", "out", "outer", "overlaps", "overlay", "placing", "position",
            "precision", "primary", "real", "references", "returning", "right", "row", "select", "session_user",
            "setof", "similar", "smallint", "some", "substring", "symmetric", "system_user", "table",
            "tablesample", "then", "time", "timestamp", "to", "trailing", "treat", "trim", "true", "union",
            "unique", "user", "using", "values", "varchar", "variadic", "verbose", "when", "where", "window",
            "with", "xmlattributes", "xmlconcat", "xmlelement", "xmlexists", "xmlforest", "xmlnamespaces",
            "xmlparse", "xmlpi", "xmlroot", "xmlserialize", "xmltable");

    /** Quotes an identifier only when needed (lower-case letters, digits, _ and not a reserved word stay bare). */
    public static String ident(String name) {
        if (name == null) throw new IllegalArgumentException("identifier is null");
        if (PLAIN_IDENT.matcher(name).matches() && !RESERVED.contains(name)) return name;
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static String qualified(String schema, String name) {
        return blank(schema) ? ident(name) : ident(schema) + "." + ident(name);
    }

    /** A SQL string literal ('it''s'). */
    static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String idents(List<String> names) {
        return names.stream().map(DdlBuilder::ident).collect(Collectors.joining(", "));
    }

    private static String constraintPrefix(String name) {
        return blank(name) ? "" : "CONSTRAINT " + ident(name) + " ";
    }

    private static DdlStatement stmt(String sql, Lock lock, String schema, String table) {
        return new DdlStatement(sql, true, lock, qualified(schema, table));
    }

    private static DdlStatement nonTx(String sql, Lock lock, String schema, String table) {
        return new DdlStatement(sql, false, lock, qualified(schema, table));
    }

    // ---- Indexes ----

    public static DdlStatement createIndex(IndexSpec spec) {
        if (spec.elements() == null || spec.elements().isEmpty()) throw new IllegalArgumentException("An index needs at least one key.");
        var sb = new StringBuilder("CREATE ");
        if (spec.unique()) sb.append("UNIQUE ");
        sb.append("INDEX ");
        if (spec.concurrently()) sb.append("CONCURRENTLY ");
        if (!blank(spec.name())) sb.append(ident(spec.name())).append(' ');
        sb.append("ON ").append(qualified(spec.schema(), spec.table()));
        if (!blank(spec.method()) && !spec.method().equalsIgnoreCase("btree")) {
            sb.append(" USING ").append(spec.method().toLowerCase(Locale.ROOT));
        }
        sb.append(" (").append(spec.elements().stream().map(DdlBuilder::element).collect(Collectors.joining(", "))).append(')');
        if (spec.include() != null && !spec.include().isEmpty()) sb.append(" INCLUDE (").append(idents(spec.include())).append(')');
        if (spec.fillfactor() != null) sb.append(" WITH (fillfactor = ").append(spec.fillfactor()).append(')');
        if (!blank(spec.where())) sb.append(" WHERE (").append(spec.where().trim()).append(')');
        sb.append(';');
        return spec.concurrently()
                ? nonTx(sb.toString(), Lock.SHARE_UPDATE_EXCLUSIVE, spec.schema(), spec.table())
                : stmt(sb.toString(), Lock.SHARE, spec.schema(), spec.table());
    }

    private static String element(IndexElement e) {
        var s = e.expression() ? "(" + e.columnOrExpression().trim() + ")" : ident(e.columnOrExpression());
        if (e.descending()) s += " DESC";
        if (e.nullsFirst() != null) s += e.nullsFirst() ? " NULLS FIRST" : " NULLS LAST";
        return s;
    }

    /** CASCADE can't be combined with CONCURRENTLY (PostgreSQL rejects it). */
    public static DdlStatement dropIndex(String schema, String table, String index, boolean concurrently, boolean cascade) {
        if (concurrently && cascade) throw new IllegalArgumentException("DROP INDEX CONCURRENTLY does not support CASCADE.");
        var sql = "DROP INDEX " + (concurrently ? "CONCURRENTLY " : "") + qualified(schema, index) + (cascade ? " CASCADE" : "") + ";";
        return concurrently ? nonTx(sql, Lock.SHARE_UPDATE_EXCLUSIVE, schema, table) : stmt(sql, Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement renameIndex(String schema, String table, String index, String newName) {
        return stmt("ALTER INDEX " + qualified(schema, index) + " RENAME TO " + ident(newName) + ";",
                Lock.SHARE_UPDATE_EXCLUSIVE, schema, table);
    }

    public static DdlStatement reindex(String schema, String table, String index, boolean concurrently) {
        var sql = "REINDEX INDEX " + (concurrently ? "CONCURRENTLY " : "") + qualified(schema, index) + ";";
        return concurrently ? nonTx(sql, Lock.SHARE_UPDATE_EXCLUSIVE, schema, table) : stmt(sql, Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    // ---- Constraints ----

    public static DdlStatement addForeignKey(ForeignKeySpec spec) {
        var sb = new StringBuilder("ALTER TABLE ").append(qualified(spec.schema(), spec.table()))
                .append(" ADD ").append(constraintPrefix(spec.name()))
                .append("FOREIGN KEY (").append(idents(spec.columns())).append(") REFERENCES ")
                .append(qualified(spec.refSchema(), spec.refTable()));
        if (spec.refColumns() != null && !spec.refColumns().isEmpty()) sb.append(" (").append(idents(spec.refColumns())).append(')');
        if (action(spec.onUpdate()) != null) sb.append(" ON UPDATE ").append(action(spec.onUpdate()));
        if (action(spec.onDelete()) != null) sb.append(" ON DELETE ").append(action(spec.onDelete()));
        if (spec.deferrable()) {
            sb.append(" DEFERRABLE");
            if (spec.initiallyDeferred()) sb.append(" INITIALLY DEFERRED");
        }
        if (spec.notValid()) sb.append(" NOT VALID");
        sb.append(';');
        return stmt(sb.toString(), Lock.SHARE_ROW_EXCLUSIVE, spec.schema(), spec.table());
    }

    /** Referential action in SQL form, or null for the default NO ACTION. */
    private static String action(String a) {
        if (blank(a)) return null;
        var u = a.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        return u.equals("NO ACTION") ? null : u;
    }

    public static DdlStatement addCheck(CheckSpec spec) {
        var sql = "ALTER TABLE " + qualified(spec.schema(), spec.table()) + " ADD " + constraintPrefix(spec.name())
                + "CHECK (" + spec.expression().trim() + ")"
                + (spec.noInherit() ? " NO INHERIT" : "") + (spec.notValid() ? " NOT VALID" : "") + ";";
        return stmt(sql, Lock.ACCESS_EXCLUSIVE, spec.schema(), spec.table());
    }

    public static DdlStatement addKey(KeySpec spec) {
        var sb = new StringBuilder("ALTER TABLE ").append(qualified(spec.schema(), spec.table()))
                .append(" ADD ").append(constraintPrefix(spec.name()));
        if (spec.primaryKey()) {
            sb.append("PRIMARY KEY");
        } else {
            sb.append("UNIQUE");
            if (spec.nullsNotDistinct()) sb.append(" NULLS NOT DISTINCT");
        }
        sb.append(" (").append(idents(spec.columns())).append(')');
        if (spec.include() != null && !spec.include().isEmpty()) sb.append(" INCLUDE (").append(idents(spec.include())).append(')');
        sb.append(';');
        return stmt(sb.toString(), Lock.ACCESS_EXCLUSIVE, spec.schema(), spec.table());
    }

    public static DdlStatement addExclusion(ExclusionSpec spec) {
        var method = blank(spec.method()) ? "gist" : spec.method().toLowerCase(Locale.ROOT);
        var elems = spec.elements().stream().map(e -> e.expression().trim() + " WITH " + e.operator().trim())
                .collect(Collectors.joining(", "));
        var sql = "ALTER TABLE " + qualified(spec.schema(), spec.table()) + " ADD " + constraintPrefix(spec.name())
                + "EXCLUDE USING " + method + " (" + elems + ")"
                + (blank(spec.where()) ? "" : " WHERE (" + spec.where().trim() + ")") + ";";
        return stmt(sql, Lock.ACCESS_EXCLUSIVE, spec.schema(), spec.table());
    }

    public static DdlStatement dropConstraint(String schema, String table, String name, boolean cascade) {
        return stmt("ALTER TABLE " + qualified(schema, table) + " DROP CONSTRAINT " + ident(name) + (cascade ? " CASCADE" : "") + ";",
                Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement renameConstraint(String schema, String table, String name, String newName) {
        return stmt("ALTER TABLE " + qualified(schema, table) + " RENAME CONSTRAINT " + ident(name) + " TO " + ident(newName) + ";",
                Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement validateConstraint(String schema, String table, String name) {
        return stmt("ALTER TABLE " + qualified(schema, table) + " VALIDATE CONSTRAINT " + ident(name) + ";",
                Lock.SHARE_UPDATE_EXCLUSIVE, schema, table);
    }

    // ---- Columns ----

    /** ADD COLUMN, plus COMMENT ON COLUMN when a comment is given. */
    public static List<DdlStatement> addColumn(String schema, String table, ColumnSpec spec) {
        var list = new ArrayList<DdlStatement>();
        list.add(stmt("ALTER TABLE " + qualified(schema, table) + " ADD COLUMN " + columnDefinition(spec) + ";",
                Lock.ACCESS_EXCLUSIVE, schema, table));
        if (!blank(spec.comment())) list.add(commentOnColumn(schema, table, spec.name(), spec.comment()));
        return list;
    }

    /** {@code name type [COLLATE c] [DEFAULT d | GENERATED ...] [NOT NULL]} — also used by CREATE TABLE scripts. */
    public static String columnDefinition(ColumnSpec spec) {
        var sb = new StringBuilder(ident(spec.name())).append(' ').append(spec.dataType().trim());
        if (!blank(spec.collation())) sb.append(" COLLATE ").append(ident(spec.collation()));
        if (!blank(spec.generated())) {
            sb.append(" GENERATED ALWAYS AS (").append(stripParens(spec.generated())).append(") STORED");
        } else if (!blank(spec.identity())) {
            sb.append(" GENERATED ").append(spec.identity().trim().toUpperCase(Locale.ROOT)).append(" AS IDENTITY");
        } else if (!blank(spec.defaultExpr())) {
            sb.append(" DEFAULT ").append(spec.defaultExpr().trim());
        }
        if (spec.notNull()) sb.append(" NOT NULL");
        return sb.toString();
    }

    /** "(a * 2)" → "a * 2" when the outer parentheses wrap the whole expression. */
    private static String stripParens(String expr) {
        var e = expr.trim();
        if (!e.startsWith("(") || !e.endsWith(")")) return e;
        var depth = 0;
        for (var i = 0; i < e.length(); i++) {
            if (e.charAt(i) == '(') depth++;
            else if (e.charAt(i) == ')') depth--;
            if (depth == 0 && i < e.length() - 1) return e; // first paren closes before the end
        }
        return e.substring(1, e.length() - 1).trim();
    }

    /**
     * The statements turning {@code before} into {@code after}: rename, type (with
     * USING when {@code usingExpr} is set), nullability, default, identity, comment.
     * Empty when nothing changed.
     */
    public static List<DdlStatement> alterColumn(String schema, String table, ColumnDetail before, ColumnSpec after,
                                                 String usingExpr) {
        var list = new ArrayList<DdlStatement>();
        var t = qualified(schema, table);
        var name = before.name();
        if (!blank(after.name()) && !after.name().equals(before.name())) {
            list.add(renameColumn(schema, table, before.name(), after.name()));
            name = after.name();
        }
        var col = ident(name);
        var alter = "ALTER TABLE " + t + " ALTER COLUMN " + col + " ";

        var typeChanged = !blank(after.dataType()) && !normType(after.dataType()).equals(normType(before.dataType()));
        var collationChanged = !eq(after.collation(), before.collation());
        if (typeChanged || collationChanged || !blank(usingExpr)) {
            var type = blank(after.dataType()) ? before.dataType() : after.dataType().trim();
            var sql = alter + "TYPE " + type
                    + (blank(after.collation()) ? "" : " COLLATE " + ident(after.collation()))
                    + (blank(usingExpr) ? "" : " USING " + usingExpr.trim()) + ";";
            list.add(stmt(sql, Lock.ACCESS_EXCLUSIVE, schema, table));
        }

        if (before.generated() != null && blank(after.generated())) {
            list.add(stmt(alter + "DROP EXPRESSION;", Lock.ACCESS_EXCLUSIVE, schema, table));
        }

        var beforeIdentity = blank(before.identity()) ? null : before.identity().trim().toUpperCase(Locale.ROOT);
        var afterIdentity = blank(after.identity()) ? null : after.identity().trim().toUpperCase(Locale.ROOT);
        if (beforeIdentity == null && afterIdentity != null) {
            list.add(stmt(alter + "ADD GENERATED " + afterIdentity + " AS IDENTITY;", Lock.ACCESS_EXCLUSIVE, schema, table));
        } else if (beforeIdentity != null && afterIdentity == null) {
            list.add(stmt(alter + "DROP IDENTITY;", Lock.ACCESS_EXCLUSIVE, schema, table));
        } else if (beforeIdentity != null && !beforeIdentity.equals(afterIdentity)) {
            list.add(stmt(alter + "SET GENERATED " + afterIdentity + ";", Lock.ACCESS_EXCLUSIVE, schema, table));
        }

        if (afterIdentity == null && blank(after.generated()) && !eq(after.defaultExpr(), before.defaultExpr())) {
            list.add(stmt(blank(after.defaultExpr()) ? alter + "DROP DEFAULT;" : alter + "SET DEFAULT " + after.defaultExpr().trim() + ";",
                    Lock.ACCESS_EXCLUSIVE, schema, table));
        }

        if (after.notNull() == before.nullable()) {
            list.add(stmt(alter + (after.notNull() ? "SET NOT NULL;" : "DROP NOT NULL;"), Lock.ACCESS_EXCLUSIVE, schema, table));
        }

        if (after.comment() != null && !eq(after.comment(), before.comment())) {
            list.add(commentOnColumn(schema, table, name, after.comment()));
        }
        return list;
    }

    private static String normType(String type) {
        return type == null ? "" : type.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Null and blank are the same; otherwise trimmed text equality. */
    private static boolean eq(String a, String b) {
        var x = blank(a) ? "" : a.trim();
        var y = blank(b) ? "" : b.trim();
        return x.equals(y);
    }

    /** COMMENT ON COLUMN; null or "" removes the comment. */
    public static DdlStatement commentOnColumn(String schema, String table, String column, String comment) {
        return stmt("COMMENT ON COLUMN " + qualified(schema, table) + "." + ident(column) + " IS "
                + (blank(comment) ? "NULL" : literal(comment)) + ";", Lock.SHARE_UPDATE_EXCLUSIVE, schema, table);
    }

    public static DdlStatement renameColumn(String schema, String table, String column, String newName) {
        return stmt("ALTER TABLE " + qualified(schema, table) + " RENAME COLUMN " + ident(column) + " TO " + ident(newName) + ";",
                Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement dropColumn(String schema, String table, String column, boolean cascade) {
        return stmt("ALTER TABLE " + qualified(schema, table) + " DROP COLUMN " + ident(column) + (cascade ? " CASCADE" : "") + ";",
                Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    // ---- Triggers ----

    public static DdlStatement createTrigger(TriggerSpec spec) {
        if (blank(spec.name())) throw new IllegalArgumentException("A trigger needs a name.");
        if (spec.events() == null || spec.events().isEmpty()) throw new IllegalArgumentException("A trigger needs at least one event.");
        var events = spec.events().stream().map(e -> {
            var ev = e.trim().toUpperCase(Locale.ROOT);
            return ev.equals("UPDATE") && spec.updateOf() != null && !spec.updateOf().isEmpty()
                    ? "UPDATE OF " + idents(spec.updateOf()) : ev;
        }).collect(Collectors.joining(" OR "));
        var sql = "CREATE TRIGGER " + ident(spec.name()) + " " + spec.timing().trim().toUpperCase(Locale.ROOT) + " " + events
                + " ON " + qualified(spec.schema(), spec.table())
                + " FOR EACH " + (spec.forEachRow() ? "ROW" : "STATEMENT")
                + (blank(spec.whenExpr()) ? "" : " WHEN (" + stripParens(spec.whenExpr()) + ")")
                + " EXECUTE FUNCTION " + spec.function().trim() + "();";
        return stmt(sql, Lock.SHARE_ROW_EXCLUSIVE, spec.schema(), spec.table());
    }

    public static DdlStatement dropTrigger(String schema, String table, String trigger) {
        return stmt("DROP TRIGGER " + ident(trigger) + " ON " + qualified(schema, table) + ";", Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement renameTrigger(String schema, String table, String trigger, String newName) {
        return stmt("ALTER TRIGGER " + ident(trigger) + " ON " + qualified(schema, table) + " RENAME TO " + ident(newName) + ";",
                Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement setTriggerEnabled(String schema, String table, String trigger, boolean enabled) {
        return stmt("ALTER TABLE " + qualified(schema, table) + (enabled ? " ENABLE" : " DISABLE") + " TRIGGER " + ident(trigger) + ";",
                Lock.SHARE_ROW_EXCLUSIVE, schema, table);
    }

    // ---- Table ----

    public static DdlStatement renameTable(String schema, String table, String newName) {
        return stmt("ALTER TABLE " + qualified(schema, table) + " RENAME TO " + ident(newName) + ";", Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement dropTable(String schema, String table, boolean cascade) {
        return stmt("DROP TABLE " + qualified(schema, table) + (cascade ? " CASCADE" : "") + ";", Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    public static DdlStatement truncateTable(String schema, String table, boolean restartIdentity, boolean cascade) {
        return stmt("TRUNCATE TABLE " + qualified(schema, table) + (restartIdentity ? " RESTART IDENTITY" : "")
                + (cascade ? " CASCADE" : "") + ";", Lock.ACCESS_EXCLUSIVE, schema, table);
    }

    /** COMMENT ON TABLE; null or "" removes the comment. */
    public static DdlStatement commentOnTable(String schema, String table, String comment) {
        return stmt("COMMENT ON TABLE " + qualified(schema, table) + " IS " + (blank(comment) ? "NULL" : literal(comment)) + ";",
                Lock.SHARE_UPDATE_EXCLUSIVE, schema, table);
    }
}
