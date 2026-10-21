package com.postgresforall.core.service;

import com.postgresforall.core.model.Relationships.DiagramColumn;
import com.postgresforall.core.model.Relationships.DiagramTable;
import com.postgresforall.core.model.Relationships.Relationship;
import com.postgresforall.core.model.Relationships.RelationshipGraph;
import com.postgresforall.core.model.TableObjects.TableRef;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.postgresforall.core.service.TableInspector.REL;
import static com.postgresforall.core.service.TableInspector.attNames;
import static com.postgresforall.core.service.TableInspector.strings;

/**
 * Walks foreign keys out from one table, both directions (tables it references
 * and tables referencing it), breadth first up to a number of hops, for the
 * relationship diagram.
 */
public final class RelationshipInspector {
    public static final int DEFAULT_MAX_TABLES = 60;

    private final ConnectionManager manager;

    public RelationshipInspector(ConnectionManager manager) {
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

    /**
     * @param depth     foreign-key hops to follow from the focus table (at least 1)
     * @param maxTables stop adding tables beyond this many (the graph is then {@code truncated})
     */
    public RelationshipGraph graph(String schema, String table, int depth, int maxTables) throws SQLException {
        var focus = new TableRef(schema, table);
        var distance = new LinkedHashMap<TableRef, Integer>();
        distance.put(focus, 0);
        var edges = new LinkedHashMap<String, Relationship>();
        var queue = new ArrayDeque<TableRef>();
        queue.add(focus);
        var truncated = false;

        while (!queue.isEmpty()) {
            var t = queue.poll();
            int d = distance.get(t);
            for (var r : foreignKeysTouching(t)) {
                edges.putIfAbsent(r.from() + "." + r.name(), r);
                if (d >= depth) continue;
                for (var other : List.of(r.from(), r.to())) {
                    if (distance.containsKey(other)) continue;
                    if (distance.size() >= maxTables) {
                        truncated = true;
                        continue;
                    }
                    distance.put(other, d + 1);
                    queue.add(other);
                }
            }
        }

        var tables = new ArrayList<DiagramTable>();
        for (var e : distance.entrySet()) tables.add(describe(e.getKey(), e.getValue()));
        var rels = edges.values().stream()
                .filter(r -> distance.containsKey(r.from()) && distance.containsKey(r.to()))
                .toList();
        return new RelationshipGraph(focus, tables, rels, truncated);
    }

    /** Foreign keys declared on {@code t} or referencing it. */
    List<Relationship> foreignKeysTouching(TableRef t) throws SQLException {
        return query("""
                SELECT con.conname, ns.nspname, cl.relname, %s, nf.nspname, cf.relname, %s,
                       con.confupdtype, con.confdeltype, con.convalidated,
                       EXISTS (SELECT 1 FROM pg_attribute a
                               WHERE a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey) AND NOT a.attnotnull),
                       EXISTS (SELECT 1 FROM pg_index i
                               WHERE i.indrelid = con.conrelid AND i.indisunique AND i.indpred IS NULL
                                 AND i.indnkeyatts = cardinality(con.conkey)
                                 AND string_to_array(i.indkey::text, ' ')::int2[] @> con.conkey)
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.conrelid JOIN pg_namespace ns ON ns.oid = cl.relnamespace
                JOIN pg_class cf ON cf.oid = con.confrelid JOIN pg_namespace nf ON nf.oid = cf.relnamespace
                WHERE con.contype = 'f' AND con.conparentid = 0
                  AND (con.conrelid = %s OR con.confrelid = %s)
                ORDER BY ns.nspname, cl.relname, con.conname
                """.formatted(attNames("con.conkey", "con.conrelid"), attNames("con.confkey", "con.confrelid"), REL, REL),
                rs -> new Relationship(rs.getString(1),
                        new TableRef(rs.getString(2), rs.getString(3)), strings(rs.getArray(4)),
                        new TableRef(rs.getString(5), rs.getString(6)), strings(rs.getArray(7)),
                        action(rs.getString(8)), action(rs.getString(9)), rs.getBoolean(10),
                        rs.getBoolean(11), rs.getBoolean(12)),
                t.schema(), t.name(), t.schema(), t.name());
    }

    private DiagramTable describe(TableRef t, int distance) throws SQLException {
        var columns = query("""
                SELECT a.attname, format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,
                       EXISTS (SELECT 1 FROM pg_constraint p
                               WHERE p.conrelid = a.attrelid AND p.contype = 'p' AND a.attnum = ANY (p.conkey)),
                       EXISTS (SELECT 1 FROM pg_constraint f
                               WHERE f.conrelid = a.attrelid AND f.contype = 'f' AND a.attnum = ANY (f.conkey)),
                       EXISTS (SELECT 1 FROM pg_constraint u
                               WHERE u.conrelid = a.attrelid AND u.contype IN ('p', 'u') AND u.conkey = ARRAY[a.attnum])
                FROM pg_attribute a
                WHERE a.attrelid = %s AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """.formatted(REL),
                rs -> new DiagramColumn(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getBoolean(6)),
                t.schema(), t.name());
        var info = query("SELECT c.reltuples::bigint, obj_description(c.oid, 'pg_class') FROM pg_class c WHERE c.oid = " + REL,
                rs -> new Object[]{rs.getLong(1), rs.getString(2)}, t.schema(), t.name());
        var rows = info.isEmpty() ? -1L : (Long) info.get(0)[0];
        var comment = info.isEmpty() ? null : (String) info.get(0)[1];
        return new DiagramTable(t, columns, rows, comment, distance);
    }

    private static final Map<String, String> ACTIONS = Map.of(
            "a", "NO ACTION", "r", "RESTRICT", "c", "CASCADE", "n", "SET NULL", "d", "SET DEFAULT");

    private static String action(String code) {
        return ACTIONS.getOrDefault(code, "NO ACTION");
    }
}
