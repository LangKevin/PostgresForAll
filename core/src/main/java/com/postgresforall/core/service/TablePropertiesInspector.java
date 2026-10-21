package com.postgresforall.core.service;

import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.ConstraintType;
import com.postgresforall.core.model.TableObjects.PropertyNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.postgresforall.core.service.TableInspector.REL;
import static com.postgresforall.core.service.TableInspector.strings;

/**
 * The table Properties tree: General, Storage, Structure, Activity and
 * Access groups. Rows that have more to show (columns, indexes, constraints,
 * triggers, inbound foreign keys, the owner role, size breakdown, policies,
 * grants, vacuum / analyze history) carry children. Values are display text.
 */
public final class TablePropertiesInspector {
    private final ConnectionManager manager;

    public TablePropertiesInspector(ConnectionManager manager) {
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

    private static PropertyNode leaf(String name, String value) {
        return new PropertyNode(name, value);
    }

    private static PropertyNode node(String name, String value, String icon, List<PropertyNode> children) {
        return new PropertyNode(name, value, icon, List.copyOf(children));
    }

    static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    private static String bytes(long b) {
        return DatabaseOperations.formatBytes(b);
    }

    private static String num(ResultSet rs, String col) throws SQLException {
        var v = rs.getLong(col);
        return rs.wasNull() ? "—" : String.format("%,d", v);
    }

    private static String when(ResultSet rs, String col) throws SQLException {
        var v = rs.getString(col);
        return v == null ? "never" : v;
    }

    /** Top-level groups, in display order. */
    public List<PropertyNode> properties(String schema, String table) throws SQLException {
        var base = query("""
                SELECT n.nspname AS schema, c.relname AS name, pg_get_userbyid(c.relowner) AS owner, c.oid::bigint AS oid,
                       CASE c.relkind WHEN 'r' THEN 'Table' WHEN 'p' THEN 'Partitioned table'
                                      WHEN 'f' THEN 'Foreign table' ELSE c.relkind::text END AS kind,
                       CASE c.relpersistence WHEN 'u' THEN 'Unlogged' WHEN 't' THEN 'Temporary' ELSE 'Permanent' END AS persistence,
                       ts.spcname AS tablespace, pg_tablespace_location(ts.oid) AS ts_location,
                       pg_get_userbyid(ts.spcowner) AS ts_owner,
                       obj_description(c.oid, 'pg_class') AS comment,
                       c.reltuples::bigint AS est_rows, c.relpages::bigint AS pages,
                       pg_total_relation_size(c.oid) AS total_bytes, pg_relation_size(c.oid) AS heap_bytes,
                       pg_relation_size(c.oid, 'fsm') + pg_relation_size(c.oid, 'vm') AS map_bytes,
                       pg_indexes_size(c.oid) AS index_bytes,
                       CASE WHEN c.reltoastrelid = 0 THEN 0 ELSE pg_total_relation_size(c.reltoastrelid) END AS toast_bytes,
                       pg_get_partkeydef(c.oid) AS partkey,
                       (SELECT string_agg(i.inhparent::regclass::text, ', ') FROM pg_inherits i WHERE i.inhrelid = c.oid) AS parents,
                       (SELECT count(*) FROM pg_inherits i WHERE i.inhparent = c.oid) AS child_count,
                       c.relrowsecurity AS rls, c.relforcerowsecurity AS rls_forced, c.reloptions AS options,
                       c.relhasindex AS has_index, pg_relation_filepath(c.oid) AS filepath,
                       s.n_live_tup AS live, s.n_dead_tup AS dead, s.n_mod_since_analyze AS mod_since_analyze,
                       s.seq_scan AS seq_scan, s.seq_tup_read AS seq_read, s.idx_scan AS idx_scan,
                       s.idx_tup_fetch AS idx_fetch, s.n_tup_ins AS ins, s.n_tup_upd AS upd, s.n_tup_hot_upd AS hot_upd,
                       s.n_tup_del AS del,
                       to_char(s.last_vacuum, 'YYYY-MM-DD HH24:MI') AS last_vacuum,
                       to_char(s.last_autovacuum, 'YYYY-MM-DD HH24:MI') AS last_autovacuum,
                       to_char(s.last_analyze, 'YYYY-MM-DD HH24:MI') AS last_analyze,
                       to_char(s.last_autoanalyze, 'YYYY-MM-DD HH24:MI') AS last_autoanalyze,
                       s.vacuum_count AS vacuum_count, s.autovacuum_count AS autovacuum_count,
                       s.analyze_count AS analyze_count, s.autoanalyze_count AS autoanalyze_count,
                       array_to_string(ARRAY(SELECT p FROM unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE',
                                                                         'REFERENCES', 'TRIGGER']) p
                                             WHERE has_table_privilege(c.oid, p)), ', ') AS my_privileges,
                       pg_has_role(c.relowner, 'USAGE') AS can_alter
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_tablespace ts ON ts.oid = c.reltablespace
                LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid
                WHERE c.oid = """ + REL,
                rs -> {
                    var m = new HashMap<String, Object>();
                    var md = rs.getMetaData();
                    for (var i = 1; i <= md.getColumnCount(); i++) {
                        var label = md.getColumnLabel(i);
                        m.put(label, label.equals("options") ? strings(rs.getArray(i)) : rs.getObject(i));
                    }
                    // Pre-formatted pieces that need the ResultSet's null handling.
                    for (var c : List.of("live", "dead", "mod_since_analyze", "seq_scan", "seq_read", "idx_scan",
                            "idx_fetch", "ins", "upd", "hot_upd", "del", "vacuum_count", "autovacuum_count",
                            "analyze_count", "autoanalyze_count")) {
                        m.put(c + "_text", num(rs, c));
                    }
                    for (var c : List.of("last_vacuum", "last_autovacuum", "last_analyze", "last_autoanalyze")) {
                        m.put(c + "_text", when(rs, c));
                    }
                    return m;
                },
                schema, table);
        if (base.isEmpty()) throw new SQLException("Table " + schema + "." + table + " not found.", "42P01");
        var b = base.get(0);
        var ti = new TableInspector(manager);
        return List.of(
                general(b),
                storage(b, ti, schema, table),
                structure(b, ti, schema, table),
                activity(b),
                access(b, schema, table));
    }

    private static String s(Map<String, Object> b, String key) {
        var v = b.get(key);
        return v == null ? null : v.toString();
    }

    private static long l(Map<String, Object> b, String key) {
        return b.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static boolean bool(Map<String, Object> b, String key) {
        return Boolean.TRUE.equals(b.get(key));
    }

    // ---- General ---------------------------------------------------------

    private PropertyNode general(Map<String, Object> b) throws SQLException {
        var owner = s(b, "owner");
        return node("General", null, "group", List.of(
                leaf("Name", s(b, "name")),
                leaf("Schema", s(b, "schema")),
                node("Owner", owner, "role", ownerDetails(owner)),
                leaf("Type", s(b, "kind")),
                leaf("Persistence", s(b, "persistence")),
                leaf("Comment", orDash(s(b, "comment"))),
                leaf("OID", s(b, "oid"))));
    }

    private List<PropertyNode> ownerDetails(String role) throws SQLException {
        var rows = query("""
                SELECT r.rolsuper, r.rolinherit, r.rolcreaterole, r.rolcreatedb, r.rolcanlogin, r.rolreplication,
                       r.rolbypassrls, r.rolconnlimit, to_char(r.rolvaliduntil, 'YYYY-MM-DD'),
                       ARRAY(SELECT g.rolname FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
                             WHERE m.member = r.oid ORDER BY 1)::text[]
                FROM pg_roles r WHERE r.rolname = ?""",
                rs -> {
                    var out = new ArrayList<PropertyNode>();
                    out.add(leaf("Superuser", yesNo(rs.getBoolean(1))));
                    out.add(leaf("Can log in", yesNo(rs.getBoolean(5))));
                    out.add(leaf("Create databases", yesNo(rs.getBoolean(4))));
                    out.add(leaf("Create roles", yesNo(rs.getBoolean(3))));
                    out.add(leaf("Inherits privileges", yesNo(rs.getBoolean(2))));
                    out.add(leaf("Replication", yesNo(rs.getBoolean(6))));
                    out.add(leaf("Bypasses row-level security", yesNo(rs.getBoolean(7))));
                    var limit = rs.getInt(8);
                    out.add(leaf("Connection limit", limit < 0 ? "none" : Integer.toString(limit)));
                    out.add(leaf("Password valid until", rs.getString(9) == null ? "no expiry" : rs.getString(9)));
                    var groups = strings(rs.getArray(10));
                    var members = new ArrayList<PropertyNode>();
                    for (var g : groups) members.add(new PropertyNode(g, null, "role", List.of()));
                    out.add(node("Member of", groups.isEmpty() ? "—" : groups.size() + " role(s)", null, members));
                    return out;
                }, role);
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    // ---- Storage ---------------------------------------------------------

    private PropertyNode storage(Map<String, Object> b, TableInspector ti, String schema, String table) throws SQLException {
        var est = l(b, "est_rows");
        var out = new ArrayList<PropertyNode>();
        out.add(node("Estimated rows", est < 0 ? "unknown (never analyzed)" : String.format("%,d", est), null, List.of(
                leaf("Live rows (statistics)", s(b, "live_text")),
                leaf("Dead rows (statistics)", s(b, "dead_text")),
                leaf("Pages (8 KB each)", String.format("%,d", l(b, "pages"))),
                leaf("Last analyzed", latest(s(b, "last_analyze_text"), s(b, "last_autoanalyze_text"))))));

        var indexSizes = new ArrayList<PropertyNode>();
        for (var ix : ti.indexes(schema, table)) {
            indexSizes.add(new PropertyNode(ix.name(), bytes(ix.sizeBytes()), "index", List.of()));
        }
        out.add(node("Total size", bytes(l(b, "total_bytes")), null, List.of(
                leaf("Table data", bytes(l(b, "heap_bytes"))),
                leaf("Free space + visibility maps", bytes(l(b, "map_bytes"))),
                node("Indexes", bytes(l(b, "index_bytes")), null, indexSizes),
                leaf("TOAST (large values)", bytes(l(b, "toast_bytes"))))));

        var spc = s(b, "tablespace");
        out.add(spc == null ? leaf("Tablespace", "pg_default (database default)")
                : node("Tablespace", spc, null, List.of(
                        leaf("Location", orDash(s(b, "ts_location"))),
                        leaf("Owner", orDash(s(b, "ts_owner"))))));

        @SuppressWarnings("unchecked")
        var options = (List<String>) b.getOrDefault("options", List.of());
        var optionNodes = new ArrayList<PropertyNode>();
        for (var o : options) {
            var eq = o.indexOf('=');
            optionNodes.add(eq < 0 ? leaf(o, "") : leaf(o.substring(0, eq), o.substring(eq + 1)));
        }
        out.add(node("Storage options", options.isEmpty() ? "— (defaults)" : options.size() + " set", null, optionNodes));
        out.add(leaf("Data file", orDash(s(b, "filepath"))));
        return node("Storage", null, "group", out);
    }

    private static String latest(String manual, String auto) {
        if ("never".equals(manual)) return auto;
        if ("never".equals(auto)) return manual;
        return manual.compareTo(auto) >= 0 ? manual : auto;
    }

    // ---- Structure -------------------------------------------------------

    private PropertyNode structure(Map<String, Object> b, TableInspector ti, String schema, String table)
            throws SQLException {
        var out = new ArrayList<PropertyNode>();

        var stats = columnStats(schema, table);
        var columns = ti.columns(schema, table);
        var colNodes = new ArrayList<PropertyNode>();
        for (var c : columns) {
            var kids = new ArrayList<PropertyNode>();
            kids.add(leaf("Position", Integer.toString(c.position())));
            kids.add(leaf("Data type", c.dataType()));
            kids.add(leaf("Nullable", yesNo(c.nullable())));
            kids.add(leaf("Default", orDash(c.defaultExpr())));
            if (c.identity() != null) kids.add(leaf("Identity", "GENERATED " + c.identity() + " AS IDENTITY"));
            if (c.generated() != null) kids.add(leaf("Generated", "ALWAYS AS " + c.generated() + " STORED"));
            if (c.collation() != null) kids.add(leaf("Collation", c.collation()));
            kids.add(leaf("Primary key", yesNo(c.primaryKey())));
            kids.add(leaf("Comment", orDash(c.comment())));
            var st = stats.get(c.name());
            if (st != null) kids.add(st);
            var summary = c.dataType() + (c.nullable() ? "" : " NOT NULL");
            colNodes.add(node(c.name(), summary, c.primaryKey() ? "key" : "column", kids));
        }
        out.add(node("Columns", Integer.toString(columns.size()), null, colNodes));

        var indexes = ti.indexes(schema, table);
        var ixNodes = new ArrayList<PropertyNode>();
        for (var ix : indexes) {
            var kids = new ArrayList<PropertyNode>();
            kids.add(leaf("Definition", ix.definition()));
            kids.add(leaf("Method", ix.method()));
            kids.add(leaf("Keys", String.join(", ", ix.keys())));
            kids.add(leaf("Unique", yesNo(ix.unique())));
            kids.add(leaf("Primary key", yesNo(ix.primary())));
            kids.add(leaf("Backs constraint", orDash(ix.constraintName())));
            kids.add(leaf("Partial (WHERE)", orDash(ix.predicate())));
            kids.add(leaf("Valid", ix.valid() ? "yes" : "no — INVALID, rebuild with REINDEX"));
            kids.add(leaf("Size", bytes(ix.sizeBytes())));
            kids.add(leaf("Scans", String.format("%,d", ix.scans()) + (ix.scans() == 0 && !ix.primary() && !ix.unique()
                    ? " (unused since statistics reset)" : "")));
            var summary = ix.method() + (ix.primary() ? " · primary" : ix.unique() ? " · unique" : "")
                    + " · (" + String.join(", ", ix.keys()) + ")";
            ixNodes.add(node(ix.name(), summary, "index", kids));
        }
        out.add(node("Indexes", Integer.toString(indexes.size()), null, ixNodes));

        var constraints = ti.constraints(schema, table);
        var conNodes = new ArrayList<PropertyNode>();
        for (var c : constraints) conNodes.add(constraintNode(c));
        out.add(node("Constraints", Integer.toString(constraints.size()), null, conNodes));

        var triggers = ti.triggers(schema, table);
        var trNodes = new ArrayList<PropertyNode>();
        for (var t : triggers) {
            trNodes.add(node(t.name(), t.timing() + " " + String.join(" OR ", t.events()) + (t.enabled() ? "" : " · disabled"),
                    "trigger", List.of(
                            leaf("Timing", t.timing()),
                            leaf("Events", String.join(", ", t.events())),
                            leaf("For each", t.level()),
                            leaf("Function", t.function()),
                            leaf("Condition (WHEN)", orDash(t.whenExpr())),
                            leaf("Enabled", yesNo(t.enabled())),
                            leaf("Definition", t.definition()))));
        }
        out.add(node("Triggers", Integer.toString(triggers.size()), null, trNodes));

        var refs = ti.referencedBy(schema, table);
        var refNodes = new ArrayList<PropertyNode>();
        for (var r : refs) {
            refNodes.add(node(r.schema() + "." + r.table(), "(" + String.join(", ", r.columns()) + ")", "fk", List.of(
                    leaf("Constraint", r.constraintName()),
                    leaf("Columns", String.join(", ", r.columns())),
                    leaf("Definition", r.definition()))));
        }
        out.add(node("Referenced by", refs.size() + " foreign key(s)", null, refNodes));

        out.add(leaf("Partition key", orDash(s(b, "partkey"))));
        var children = l(b, "child_count");
        out.add(leaf("Inherits / partition of", orDash(s(b, "parents"))));
        if (children > 0) out.add(leaf("Partitions / child tables", Long.toString(children)));

        var policies = policies(schema, table);
        var rls = bool(b, "rls") ? (bool(b, "rls_forced") ? "enabled (forced for owner)" : "enabled") : "off";
        out.add(node("Row-level security", rls + (policies.isEmpty() ? "" : " · " + policies.size() + " policy(ies)"),
                null, policies));
        return node("Structure", null, "group", out);
    }

    private static PropertyNode constraintNode(ConstraintDetail c) {
        var kids = new ArrayList<PropertyNode>();
        var type = switch (c.type()) {
            case PRIMARY_KEY -> "Primary key";
            case FOREIGN_KEY -> "Foreign key";
            case UNIQUE -> "Unique";
            case CHECK -> "Check";
            case EXCLUSION -> "Exclusion";
        };
        kids.add(leaf("Type", type));
        if (!c.columns().isEmpty()) kids.add(leaf("Columns", String.join(", ", c.columns())));
        kids.add(leaf("Definition", c.definition()));
        if (c.type() == ConstraintType.FOREIGN_KEY) {
            kids.add(leaf("References", c.refSchema() + "." + c.refTable() + " (" + String.join(", ", c.refColumns()) + ")"));
            kids.add(leaf("On update", c.onUpdate()));
            kids.add(leaf("On delete", c.onDelete()));
        }
        kids.add(leaf("Validated", c.validated() ? "yes" : "no — NOT VALID, existing rows unchecked"));
        kids.add(leaf("Deferrable", c.deferrable() ? (c.initiallyDeferred() ? "yes, initially deferred" : "yes") : "no"));
        var icon = switch (c.type()) {
            case PRIMARY_KEY -> "key";
            case FOREIGN_KEY -> "fk";
            default -> "constraint";
        };
        return node(c.name(), type.toLowerCase(Locale.ROOT) + (c.validated() ? "" : " · NOT VALID"), icon, kids);
    }

    /** Planner statistics per column ("Statistics" node), when ANALYZE has run and the login can read them. */
    private Map<String, PropertyNode> columnStats(String schema, String table) throws SQLException {
        var out = new HashMap<String, PropertyNode>();
        for (var row : query("""
                SELECT attname, null_frac, n_distinct, avg_width, correlation
                FROM pg_stats WHERE schemaname = ? AND tablename = ?""",
                rs -> {
                    var nd = rs.getDouble(3);
                    var distinct = nd >= 0 ? String.format("%,.0f", nd) : String.format("%.0f%% of rows", -nd * 100);
                    var corr = rs.getObject(5) == null ? "—" : String.format("%.2f", rs.getDouble(5));
                    return Map.entry(rs.getString(1), node("Statistics", "from last ANALYZE", null, List.of(
                            leaf("Null fraction", String.format("%.1f%%", rs.getDouble(2) * 100)),
                            leaf("Distinct values", distinct),
                            leaf("Average width", rs.getInt(4) + " bytes"),
                            leaf("Physical order correlation", corr))));
                }, schema, table)) {
            out.put(row.getKey(), row.getValue());
        }
        return out;
    }

    private List<PropertyNode> policies(String schema, String table) throws SQLException {
        return query("""
                SELECT p.polname,
                       CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE'
                                     WHEN 'd' THEN 'DELETE' ELSE 'ALL' END,
                       p.polpermissive,
                       ARRAY(SELECT CASE WHEN r = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(r) END FROM unnest(p.polroles) r)::text[],
                       pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid)
                FROM pg_policy p WHERE p.polrelid = %s ORDER BY 1""".formatted(REL),
                rs -> node(rs.getString(1), rs.getString(2) + (rs.getBoolean(3) ? "" : " · restrictive"), "policy", List.of(
                        leaf("Command", rs.getString(2)),
                        leaf("Kind", rs.getBoolean(3) ? "permissive" : "restrictive"),
                        leaf("Roles", String.join(", ", strings(rs.getArray(4)))),
                        leaf("USING", orDash(rs.getString(5))),
                        leaf("WITH CHECK", orDash(rs.getString(6))))),
                schema, table);
    }

    // ---- Activity --------------------------------------------------------

    private static PropertyNode activity(Map<String, Object> b) {
        return node("Activity (since statistics reset)", null, "group", List.of(
                leaf("Live / dead rows", s(b, "live_text") + " / " + s(b, "dead_text")),
                leaf("Rows changed since last analyze", s(b, "mod_since_analyze_text")),
                node("Sequential scans", s(b, "seq_scan_text"), null, List.of(
                        leaf("Rows read by sequential scans", s(b, "seq_read_text")))),
                node("Index scans", s(b, "idx_scan_text"), null, List.of(
                        leaf("Rows fetched by index scans", s(b, "idx_fetch_text")))),
                node("Rows inserted / updated / deleted",
                        s(b, "ins_text") + " / " + s(b, "upd_text") + " / " + s(b, "del_text"), null, List.of(
                                leaf("Inserted", s(b, "ins_text")),
                                leaf("Updated", s(b, "upd_text")),
                                leaf("Updated in place (HOT)", s(b, "hot_upd_text")),
                                leaf("Deleted", s(b, "del_text")))),
                node("Last vacuum", latest(s(b, "last_vacuum_text"), s(b, "last_autovacuum_text")), null, List.of(
                        leaf("Last manual VACUUM", s(b, "last_vacuum_text")),
                        leaf("Last autovacuum", s(b, "last_autovacuum_text")),
                        leaf("Manual vacuums", s(b, "vacuum_count_text")),
                        leaf("Autovacuums", s(b, "autovacuum_count_text")))),
                node("Last analyze", latest(s(b, "last_analyze_text"), s(b, "last_autoanalyze_text")), null, List.of(
                        leaf("Last manual ANALYZE", s(b, "last_analyze_text")),
                        leaf("Last autoanalyze", s(b, "last_autoanalyze_text")),
                        leaf("Manual analyzes", s(b, "analyze_count_text")),
                        leaf("Autoanalyzes", s(b, "autoanalyze_count_text"))))));
    }

    // ---- Access ----------------------------------------------------------

    private PropertyNode access(Map<String, Object> b, String schema, String table) throws SQLException {
        var grants = query("""
                SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END AS grantee,
                       string_agg(a.privilege_type || CASE WHEN a.is_grantable THEN ' (grantable)' ELSE '' END, ', '
                                  ORDER BY a.privilege_type) AS privs,
                       pg_get_userbyid(a.grantor) AS grantor
                FROM pg_class c, aclexplode(COALESCE(c.relacl, acldefault('r', c.relowner))) a
                WHERE c.oid = %s
                GROUP BY 1, 3 ORDER BY 1""".formatted(REL),
                rs -> node(rs.getString(1), rs.getString(2), "role", List.of(
                        leaf("Privileges", rs.getString(2)),
                        leaf("Granted by", rs.getString(3)))),
                schema, table);
        var owner = s(b, "owner");
        return node("Your access", null, "group", List.of(
                leaf("Your privileges", orDash(s(b, "my_privileges"))),
                leaf("Can change structure", bool(b, "can_alter") ? "yes (owner)" : "no (only " + owner + " can)"),
                node("All grants", grants.size() + " role(s)", null, grants)));
    }
}
