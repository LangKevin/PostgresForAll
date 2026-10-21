package com.postgresforall.core.service;

import com.postgresforall.core.model.TableObjects.Dependency;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * What would break if an object were dropped or changed: views, materialized
 * views, foreign keys, triggers, functions, sequences owned by columns, etc.
 * (pg_depend / pg_constraint). Empty list = nothing depends on it.
 */
public final class DependencyInspector {
    private static final String REL = TableInspector.REL;

    /** kind / name / detail of views and materialized views whose rewrite rule depends on refclass/refobj. */
    private static final String VIEW_COLUMNS = "SELECT DISTINCT CASE v.relkind WHEN 'm' THEN 'Materialized view' ELSE 'View' END, "
            + "quote_ident(vn.nspname) || '.' || quote_ident(v.relname), "
            + "CASE v.relkind WHEN 'm' THEN 'materialized view' ELSE 'view' END || ' reads it' "
            + "FROM pg_depend d JOIN pg_rewrite r ON r.oid = d.objid "
            + "JOIN pg_class v ON v.oid = r.ev_class JOIN pg_namespace vn ON vn.oid = v.relnamespace "
            + "WHERE d.classid = 'pg_rewrite'::regclass ";

    private final ConnectionManager manager;

    public DependencyInspector(ConnectionManager manager) {
        this.manager = manager;
    }

    private void collect(LinkedHashSet<Dependency> out, String sql, Object... params) throws SQLException {
        try (var conn = manager.openConnection(); var ps = conn.prepareStatement(sql)) {
            for (var i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Dependency(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
    }

    public List<Dependency> forTable(String schema, String table) throws SQLException {
        var out = new LinkedHashSet<Dependency>();
        collect(out, VIEW_COLUMNS + "AND d.refclassid = 'pg_class'::regclass AND d.refobjid = " + REL
                + " AND v.oid <> d.refobjid ORDER BY 2", schema, table);
        collect(out, "SELECT 'Foreign key', c.conname, 'on ' || quote_ident(n.nspname) || '.' || quote_ident(cl.relname) "
                + "FROM pg_constraint c JOIN pg_class cl ON cl.oid = c.conrelid JOIN pg_namespace n ON n.oid = cl.relnamespace "
                + "WHERE c.contype = 'f' AND c.conparentid = 0 AND c.confrelid = " + REL
                + " AND c.conrelid <> c.confrelid ORDER BY 3, 2", schema, table);
        collect(out, "SELECT DISTINCT 'Function', p.oid::regprocedure::text, 'uses the table or its row type' "
                + "FROM pg_depend d JOIN pg_proc p ON p.oid = d.objid "
                + "WHERE d.classid = 'pg_proc'::regclass AND ((d.refclassid = 'pg_class'::regclass AND d.refobjid = " + REL + ") "
                + "OR (d.refclassid = 'pg_type'::regclass AND d.refobjid = (SELECT reltype FROM pg_class WHERE oid = " + REL + "))) "
                + "ORDER BY 2", schema, table, schema, table);
        collect(out, ownedSequences("") + " ORDER BY 2", schema, table);
        collect(out, "SELECT 'Policy', p.polname, 'row-level security policy (dropped with the table)' "
                + "FROM pg_policy p WHERE p.polrelid = " + REL + " ORDER BY 2", schema, table);
        return new ArrayList<>(out);
    }

    /** Sequences owned by the table's columns (serial); {@code columnFilter} narrows to one column. */
    private static String ownedSequences(String columnFilter) {
        return "SELECT 'Sequence', quote_ident(sn.nspname) || '.' || quote_ident(s.relname), "
                + "'owned by column ' || quote_ident(a.attname) || ' (dropped with it)' "
                + "FROM pg_depend d JOIN pg_class s ON s.oid = d.objid AND s.relkind = 'S' "
                + "JOIN pg_namespace sn ON sn.oid = s.relnamespace "
                + "JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid "
                + "WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass AND d.deptype = 'a' "
                + "AND d.refobjid = " + REL + columnFilter;
    }

    public List<Dependency> forColumn(String schema, String table, String column) throws SQLException {
        var out = new LinkedHashSet<Dependency>();
        var attnum = "(SELECT attnum FROM pg_attribute WHERE attrelid = " + REL + " AND attname = ? AND NOT attisdropped)";
        Object[] p = {schema, table, schema, table, column};

        collect(out, VIEW_COLUMNS + "AND d.refclassid = 'pg_class'::regclass AND d.refobjid = " + REL
                + " AND d.refobjsubid = " + attnum + " AND v.oid <> d.refobjid ORDER BY 2", p);
        collect(out, "SELECT DISTINCT 'Index', quote_ident(ic.relname), "
                + "CASE WHEN con.conname IS NULL THEN 'index on this column' ELSE 'backs constraint ' || quote_ident(con.conname) END "
                + "FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
                + "LEFT JOIN pg_constraint con ON con.conindid = i.indexrelid AND con.conrelid = i.indrelid AND con.contype IN ('p', 'u', 'x') "
                + "WHERE i.indrelid = " + REL + " AND (" + attnum + " = ANY (i.indkey::int2[]) "
                + "OR EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = i.indexrelid "
                + "AND d.refclassid = 'pg_class'::regclass AND d.refobjid = i.indrelid AND d.refobjsubid = " + attnum + ")) "
                + "ORDER BY 2", schema, table, schema, table, column, schema, table, column);
        collect(out, "SELECT CASE c.contype WHEN 'p' THEN 'Primary key' WHEN 'f' THEN 'Foreign key' WHEN 'u' THEN 'Unique' "
                + "WHEN 'c' THEN 'Check' ELSE 'Exclusion' END, c.conname, "
                + "CASE WHEN c.conrelid = " + REL + " THEN pg_get_constraintdef(c.oid, true) "
                + "ELSE 'on ' || c.conrelid::regclass::text || ' references this column' END "
                + "FROM pg_constraint c "
                + "WHERE c.contype IN ('p', 'f', 'u', 'c', 'x') AND c.conparentid = 0 AND ("
                + "(c.conrelid = " + REL + " AND " + attnum + " = ANY (c.conkey)) "
                + "OR (c.confrelid = " + REL + " AND " + attnum + " = ANY (c.confkey))) "
                + "ORDER BY position(c.contype IN 'pfucx'), 2",
                schema, table, schema, table, schema, table, column, schema, table, schema, table, column);
        collect(out, "SELECT DISTINCT 'Trigger', t.tgname, 'fires on / reads this column' "
                + "FROM pg_trigger t WHERE t.tgrelid = " + REL + " AND NOT t.tgisinternal AND ("
                + attnum + " = ANY (t.tgattr::int2[]) OR EXISTS (SELECT 1 FROM pg_depend d "
                + "WHERE d.classid = 'pg_trigger'::regclass AND d.objid = t.oid AND d.refclassid = 'pg_class'::regclass "
                + "AND d.refobjid = t.tgrelid AND d.refobjsubid = " + attnum + ")) ORDER BY 2",
                schema, table, schema, table, column, schema, table, column);
        collect(out, "SELECT DISTINCT 'Generated column', quote_ident(a.attname), 'computed from this column' "
                + "FROM pg_attribute a WHERE a.attrelid = " + REL + " AND a.attgenerated <> '' AND a.attname <> ? AND EXISTS ("
                + "SELECT 1 FROM pg_depend d WHERE d.refclassid = 'pg_class'::regclass AND d.refobjid = a.attrelid "
                + "AND d.refobjsubid = " + attnum + " AND ("
                + "(d.classid = 'pg_class'::regclass AND d.objid = a.attrelid AND d.objsubid = a.attnum) "
                + "OR (d.classid = 'pg_attrdef'::regclass AND d.objid = (SELECT ad.oid FROM pg_attrdef ad "
                + "WHERE ad.adrelid = a.attrelid AND ad.adnum = a.attnum)))) ORDER BY 2",
                schema, table, column, schema, table, column);
        collect(out, ownedSequences(" AND a.attname = ?") + " ORDER BY 2", schema, table, column);
        collect(out, "SELECT DISTINCT 'Policy', p.polname, 'row-level security policy uses this column' "
                + "FROM pg_policy p JOIN pg_depend d ON d.classid = 'pg_policy'::regclass AND d.objid = p.oid "
                + "WHERE d.refclassid = 'pg_class'::regclass AND d.refobjid = " + REL + " AND d.refobjsubid = " + attnum
                + " ORDER BY 2", schema, table, schema, table, column);
        return new ArrayList<>(out);
    }

    /** Objects depending on an index (e.g. foreign keys relying on the unique index it backs). */
    public List<Dependency> forIndex(String schema, String index) throws SQLException {
        var out = new LinkedHashSet<Dependency>();
        collect(out, "SELECT 'Constraint', c.conname, 'this index backs the constraint on ' || c.conrelid::regclass::text "
                + "|| ' — drop the constraint instead' "
                + "FROM pg_constraint c WHERE c.conindid = " + REL + " AND c.contype IN ('p', 'u', 'x') ORDER BY 2",
                schema, index);
        collect(out, fkUsingIndex("c.conindid = " + REL), schema, index);
        return new ArrayList<>(out);
    }

    private static String fkUsingIndex(String condition) {
        return "SELECT 'Foreign key', c.conname, 'on ' || c.conrelid::regclass::text "
                + "FROM pg_constraint c WHERE c.contype = 'f' AND c.conparentid = 0 AND " + condition + " ORDER BY 3, 2";
    }

    /** Objects depending on a constraint (e.g. foreign keys referencing a PK / unique constraint). */
    public List<Dependency> forConstraint(String schema, String table, String constraint) throws SQLException {
        var out = new LinkedHashSet<Dependency>();
        var con = "(SELECT oid FROM pg_constraint WHERE conrelid = " + REL + " AND conname = ?)";
        collect(out, fkUsingIndex("c.contype = 'f' AND c.conindid = (SELECT conindid FROM pg_constraint "
                + "WHERE conrelid = " + REL + " AND conname = ? AND contype IN ('p', 'u'))"), schema, table, constraint);
        collect(out, VIEW_COLUMNS + "AND d.refclassid = 'pg_constraint'::regclass AND d.refobjid = " + con + " ORDER BY 2",
                schema, table, constraint);
        return new ArrayList<>(out);
    }
}
