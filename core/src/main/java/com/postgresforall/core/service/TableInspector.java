package com.postgresforall.core.service;

import com.postgresforall.core.model.DdlSpecs.ColumnSpec;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.ConstraintType;
import com.postgresforall.core.model.TableObjects.IndexDetail;
import com.postgresforall.core.model.TableObjects.ReferencingFk;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.model.TableObjects.TableSummary;
import com.postgresforall.core.model.TableObjects.TriggerDetail;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Catalog reads for one table's structure (columns, keys, constraints, indexes, triggers). */
public final class TableInspector {
    /** The table's oid from two bind parameters (schema, table); quote_ident keeps odd names intact. */
    static final String REL = "(quote_ident(?) || '.' || quote_ident(?))::regclass";

    private static final String USER_SCHEMAS = "n.nspname NOT IN ('pg_catalog', 'information_schema') "
            + "AND n.nspname NOT LIKE 'pg\\_toast%' AND n.nspname NOT LIKE 'pg\\_temp%'";

    private static final List<String> BUILTIN_TYPES = List.of(
            "bigint", "integer", "smallint", "numeric", "numeric(10,2)", "real", "double precision",
            "text", "character varying", "character varying(255)", "character(1)", "boolean",
            "date", "time without time zone", "timestamp without time zone", "timestamp with time zone",
            "interval", "uuid", "jsonb", "json", "bytea", "inet", "cidr", "macaddr", "money", "xml",
            "tsvector", "int4range", "int8range", "numrange", "tsrange", "tstzrange", "daterange",
            "integer[]", "bigint[]", "text[]");

    private final ConnectionManager manager;

    public TableInspector(ConnectionManager manager) {
        this.manager = manager;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (var conn = manager.openConnection(); var ps = conn.prepareStatement(sql)) {
            for (var i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<T>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        }
    }

    static List<String> strings(Array a) throws SQLException {
        if (a == null) return List.of();
        return List.of((String[]) a.getArray());
    }

    /** "ARRAY(attnames of keys k on rel r, in key order)::text[]" for conkey / confkey style arrays. */
    static String attNames(String keys, String rel) {
        return "ARRAY(SELECT a.attname FROM unnest(" + keys + ") WITH ORDINALITY k(num, ord) "
                + "JOIN pg_attribute a ON a.attrelid = " + rel + " AND a.attnum = k.num ORDER BY k.ord)::text[]";
    }

    public TableSummary summary(String schema, String table) throws SQLException {
        var rows = query("""
                SELECT n.nspname, c.relname, pg_get_userbyid(c.relowner), c.reltuples::bigint,
                       pg_total_relation_size(c.oid), pg_has_role(c.relowner, 'USAGE'),
                       obj_description(c.oid, 'pg_class')
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.oid = """ + REL,
                rs -> new TableSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getLong(5), rs.getBoolean(6), rs.getString(7)),
                schema, table);
        if (rows.isEmpty()) throw new SQLException("Table " + schema + "." + table + " not found.", "42P01");
        return rows.get(0);
    }

    public List<ColumnDetail> columns(String schema, String table) throws SQLException {
        return query("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,
                       CASE WHEN a.attgenerated = '' THEN pg_get_expr(d.adbin, d.adrelid) END,
                       CASE a.attidentity WHEN 'a' THEN 'ALWAYS' WHEN 'd' THEN 'BY DEFAULT' END,
                       CASE WHEN a.attgenerated <> '' THEN pg_get_expr(d.adbin, d.adrelid) END,
                       CASE WHEN a.attcollation <> 0 AND a.attcollation <> t.typcollation THEN co.collname END,
                       col_description(a.attrelid, a.attnum), a.attnum,
                       EXISTS (SELECT 1 FROM pg_index i
                               WHERE i.indrelid = a.attrelid AND i.indisprimary AND a.attnum = ANY (i.indkey::int2[]))
                FROM pg_attribute a
                JOIN pg_type t ON t.oid = a.atttypid
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                LEFT JOIN pg_collation co ON co.oid = a.attcollation
                WHERE a.attrelid = """ + REL + """
                 AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum""",
                rs -> new ColumnDetail(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                        rs.getBoolean(10)),
                schema, table);
    }

    /** All constraints, primary key first, then foreign keys, unique, check, exclusion; by name. */
    public List<ConstraintDetail> constraints(String schema, String table) throws SQLException {
        return query("SELECT c.conname, c.contype, " + attNames("c.conkey", "c.conrelid") + ", "
                        + "pg_get_constraintdef(c.oid, true), c.convalidated, c.condeferrable, c.condeferred, "
                        + "rn.nspname, rc.relname, " + attNames("c.confkey", "c.confrelid") + ", "
                        + "c.confupdtype, c.confdeltype "
                        + "FROM pg_constraint c "
                        + "LEFT JOIN pg_class rc ON rc.oid = c.confrelid "
                        + "LEFT JOIN pg_namespace rn ON rn.oid = rc.relnamespace "
                        + "WHERE c.conrelid = " + REL + " AND c.contype IN ('p', 'f', 'u', 'c', 'x') "
                        + "ORDER BY position(c.contype IN 'pfucx'), c.conname",
                rs -> {
                    var type = constraintType(rs.getString(2));
                    var fk = type == ConstraintType.FOREIGN_KEY;
                    return new ConstraintDetail(rs.getString(1), type, strings(rs.getArray(3)), rs.getString(4),
                            rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7),
                            fk ? rs.getString(8) : null, fk ? rs.getString(9) : null,
                            fk ? strings(rs.getArray(10)) : List.of(),
                            fk ? fkAction(rs.getString(11)) : null, fk ? fkAction(rs.getString(12)) : null);
                },
                schema, table);
    }

    private static ConstraintType constraintType(String c) {
        return switch (c) {
            case "p" -> ConstraintType.PRIMARY_KEY;
            case "f" -> ConstraintType.FOREIGN_KEY;
            case "u" -> ConstraintType.UNIQUE;
            case "c" -> ConstraintType.CHECK;
            default -> ConstraintType.EXCLUSION;
        };
    }

    private static String fkAction(String c) {
        if (c == null) return null;
        return switch (c) {
            case "r" -> "RESTRICT";
            case "c" -> "CASCADE";
            case "n" -> "SET NULL";
            case "d" -> "SET DEFAULT";
            default -> "NO ACTION";
        };
    }

    public List<IndexDetail> indexes(String schema, String table) throws SQLException {
        return query("""
                SELECT ic.relname, am.amname, i.indisunique, i.indisprimary, con.conname, i.indisvalid,
                       pg_get_indexdef(i.indexrelid),
                       ARRAY(SELECT pg_get_indexdef(i.indexrelid, k, true)
                             FROM generate_series(1, i.indnkeyatts) k ORDER BY k)::text[],
                       pg_get_expr(i.indpred, i.indrelid, true),
                       pg_relation_size(i.indexrelid), coalesce(s.idx_scan, 0)
                FROM pg_index i
                JOIN pg_class ic ON ic.oid = i.indexrelid
                JOIN pg_am am ON am.oid = ic.relam
                LEFT JOIN pg_constraint con ON con.conindid = i.indexrelid AND con.conrelid = i.indrelid
                                           AND con.contype IN ('p', 'u', 'x')
                LEFT JOIN pg_stat_all_indexes s ON s.indexrelid = i.indexrelid
                WHERE i.indrelid = """ + REL + """

                ORDER BY i.indisprimary DESC, ic.relname""",
                rs -> new IndexDetail(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getString(5), rs.getBoolean(6), rs.getString(7), strings(rs.getArray(8)),
                        rs.getString(9), rs.getLong(10), rs.getLong(11)),
                schema, table);
    }

    /** User triggers only (no internal FK triggers). */
    public List<TriggerDetail> triggers(String schema, String table) throws SQLException {
        return query("""
                SELECT t.tgname, t.tgtype, t.tgenabled <> 'D',
                       quote_ident(pn.nspname) || '.' || quote_ident(p.proname),
                       pg_get_triggerdef(t.oid, true)
                FROM pg_trigger t
                JOIN pg_proc p ON p.oid = t.tgfoid
                JOIN pg_namespace pn ON pn.oid = p.pronamespace
                WHERE t.tgrelid = """ + REL + """
                 AND NOT t.tgisinternal
                ORDER BY t.tgname""",
                rs -> {
                    int type = rs.getInt(2);
                    var timing = (type & 64) != 0 ? "INSTEAD OF" : (type & 2) != 0 ? "BEFORE" : "AFTER";
                    var events = new ArrayList<String>();
                    if ((type & 4) != 0) events.add("INSERT");
                    if ((type & 16) != 0) events.add("UPDATE");
                    if ((type & 8) != 0) events.add("DELETE");
                    if ((type & 32) != 0) events.add("TRUNCATE");
                    var def = rs.getString(5);
                    return new TriggerDetail(rs.getString(1), timing, List.copyOf(events),
                            (type & 1) != 0 ? "ROW" : "STATEMENT", rs.getBoolean(3), rs.getString(4),
                            whenClause(def), def);
                },
                schema, table);
    }

    /** The expression inside "WHEN (...)" of a trigger definition, or null. */
    static String whenClause(String def) {
        if (def == null) return null;
        var start = def.indexOf(" WHEN (");
        if (start < 0) return null;
        var open = start + " WHEN ".length();
        var depth = 0;
        var inQuote = false;
        for (var i = open; i < def.length(); i++) {
            var ch = def.charAt(i);
            if (ch == '\'') inQuote = !inQuote;
            if (inQuote) continue;
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return def.substring(open + 1, i).trim();
        }
        return null;
    }

    /** Foreign keys in other tables (or this one) that reference this table. */
    public List<ReferencingFk> referencedBy(String schema, String table) throws SQLException {
        return query("SELECT n.nspname, cl.relname, c.conname, " + attNames("c.conkey", "c.conrelid") + ", "
                        + "pg_get_constraintdef(c.oid, true) "
                        + "FROM pg_constraint c "
                        + "JOIN pg_class cl ON cl.oid = c.conrelid "
                        + "JOIN pg_namespace n ON n.oid = cl.relnamespace "
                        + "WHERE c.contype = 'f' AND c.conparentid = 0 AND c.confrelid = " + REL + " "
                        + "ORDER BY n.nspname, cl.relname, c.conname",
                rs -> new ReferencingFk(rs.getString(1), rs.getString(2), rs.getString(3), strings(rs.getArray(4)),
                        rs.getString(5)),
                schema, table);
    }

    /** Ordinary / partitioned tables in this database, outside system schemas — FK target picker. */
    public List<TableRef> tables() throws SQLException {
        return query("SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition AND " + USER_SCHEMAS
                        + " ORDER BY n.nspname, c.relname",
                rs -> new TableRef(rs.getString(1), rs.getString(2)));
    }

    /** Column lists of the PRIMARY KEY and UNIQUE constraints of a table (FK target suggestions), PK first. */
    public List<List<String>> candidateKeys(String schema, String table) throws SQLException {
        return query("SELECT " + attNames("c.conkey", "c.conrelid") + " FROM pg_constraint c "
                        + "WHERE c.conrelid = " + REL + " AND c.contype IN ('p', 'u') "
                        + "ORDER BY c.contype = 'p' DESC, c.conname",
                rs -> strings(rs.getArray(1)),
                schema, table);
    }

    /** Functions returning {@code trigger}, schema-qualified, outside system schemas. */
    public List<String> triggerFunctions() throws SQLException {
        return query("SELECT DISTINCT quote_ident(n.nspname) || '.' || quote_ident(p.proname) "
                        + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE p.prorettype = 'trigger'::regtype AND " + USER_SCHEMAS + " ORDER BY 1",
                rs -> rs.getString(1));
    }

    /** Type names for the column dialog: common built-ins, then this database's domains and enums. */
    public List<String> dataTypes() throws SQLException {
        var out = new ArrayList<>(BUILTIN_TYPES);
        out.addAll(query("SELECT CASE WHEN n.nspname = 'public' THEN quote_ident(t.typname) "
                        + "ELSE quote_ident(n.nspname) || '.' || quote_ident(t.typname) END "
                        + "FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                        + "WHERE t.typtype IN ('d', 'e') AND " + USER_SCHEMAS + " ORDER BY 1",
                rs -> rs.getString(1)));
        return out;
    }

    /**
     * Full CREATE script for the table, close to pg_dump's: CREATE TABLE with
     * columns (identity / generated / defaults / collation), constraints,
     * indexes not backing constraints, triggers, comments and owner.
     */
    public String scriptCreate(String schema, String table) throws SQLException {
        var summary = summary(schema, table);
        var t = DdlBuilder.qualified(schema, table);
        var columns = columns(schema, table);
        var constraints = query("SELECT c.conname, c.contype, pg_get_constraintdef(c.oid), c.convalidated, c.conislocal "
                        + "FROM pg_constraint c WHERE c.conrelid = " + REL
                        + " AND c.contype IN ('p', 'f', 'u', 'c', 'x') ORDER BY position(c.contype IN 'pucxf'), c.conname",
                rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        Boolean.toString(rs.getBoolean(4)), Boolean.toString(rs.getBoolean(5))},
                schema, table);
        var sequences = query("""
                SELECT quote_ident(sn.nspname) || '.' || quote_ident(s.relname), a.attname
                FROM pg_depend d
                JOIN pg_class s ON s.oid = d.objid AND s.relkind = 'S'
                JOIN pg_namespace sn ON sn.oid = s.relnamespace
                JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
                WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
                  AND d.deptype = 'a' AND d.refobjid = """ + REL + """

                ORDER BY 1""",
                rs -> new String[] {rs.getString(1), rs.getString(2)},
                schema, table);
        var partitionKey = query("SELECT pg_get_partkeydef(" + REL + ")", rs -> rs.getString(1), schema, table).get(0);
        var indexes = query("SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
                        + "WHERE i.indrelid = " + REL + " AND NOT EXISTS (SELECT 1 FROM pg_constraint c "
                        + "WHERE c.conindid = i.indexrelid AND c.conrelid = i.indrelid AND c.contype IN ('p', 'u', 'x')) "
                        + "ORDER BY ic.relname",
                rs -> rs.getString(1), schema, table);
        var triggers = query("SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t WHERE t.tgrelid = " + REL
                        + " AND NOT t.tgisinternal ORDER BY t.tgname",
                rs -> rs.getString(1), schema, table);
        var disabledTriggers = query("SELECT t.tgname FROM pg_trigger t WHERE t.tgrelid = " + REL
                        + " AND NOT t.tgisinternal AND t.tgenabled = 'D' ORDER BY t.tgname",
                rs -> rs.getString(1), schema, table);

        var sb = new StringBuilder();
        sb.append("-- Table: ").append(schema).append('.').append(table).append("\n\n");
        for (var s : sequences) sb.append("CREATE SEQUENCE IF NOT EXISTS ").append(s[0]).append(";\n\n");

        var lines = new ArrayList<String>();
        for (var c : columns) {
            lines.add("    " + DdlBuilder.columnDefinition(new ColumnSpec(c.name(), c.dataType(), !c.nullable(),
                    c.defaultExpr(), c.identity(), c.generated(), c.collation(), null)));
        }
        var after = new ArrayList<String>();
        for (var c : constraints) {
            if (!Boolean.parseBoolean(c[4])) continue; // inherited from a parent table
            var def = "CONSTRAINT " + DdlBuilder.ident(c[0]) + " " + c[2];
            if (c[1].equals("f") || !Boolean.parseBoolean(c[3])) {
                after.add("ALTER TABLE " + t + " ADD " + def + ";");
            } else {
                lines.add("    " + def);
            }
        }
        sb.append("CREATE TABLE ").append(t).append(" (\n").append(String.join(",\n", lines)).append("\n)");
        if (partitionKey != null && !partitionKey.isBlank()) sb.append(" PARTITION BY ").append(partitionKey);
        sb.append(";\n");

        for (var s : after) sb.append('\n').append(s).append('\n');
        for (var s : sequences) {
            sb.append("\nALTER SEQUENCE ").append(s[0]).append(" OWNED BY ").append(t).append('.')
                    .append(DdlBuilder.ident(s[1])).append(";\n");
        }
        for (var s : indexes) sb.append('\n').append(s).append(";\n");
        for (var s : triggers) sb.append('\n').append(s).append(";\n");
        for (var name : disabledTriggers) {
            sb.append('\n').append("ALTER TABLE ").append(t).append(" DISABLE TRIGGER ").append(DdlBuilder.ident(name)).append(";\n");
        }
        if (summary.comment() != null) {
            sb.append('\n').append(DdlBuilder.commentOnTable(schema, table, summary.comment()).sql()).append('\n');
        }
        for (var c : columns) {
            if (c.comment() != null) {
                sb.append('\n').append(DdlBuilder.commentOnColumn(schema, table, c.name(), c.comment()).sql()).append('\n');
            }
        }
        sb.append("\nALTER TABLE ").append(t).append(" OWNER TO ").append(DdlBuilder.ident(summary.owner())).append(";\n");
        return sb.toString();
    }
}
