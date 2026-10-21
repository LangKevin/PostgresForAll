package com.postgresforall.core.service;

import com.postgresforall.core.model.SchemaNodes.ColumnInfo;
import com.postgresforall.core.model.SchemaNodes.DatabaseInfo;
import com.postgresforall.core.model.SchemaNodes.FunctionInfo;
import com.postgresforall.core.model.SchemaNodes.RoleInfo;
import com.postgresforall.core.model.SchemaNodes.SchemaInfo;
import com.postgresforall.core.model.SchemaNodes.TableInfo;
import com.postgresforall.core.model.SchemaNodes.ViewInfo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** pg_catalog queries behind the Object Explorer tree and table scripting. */
public final class SchemaInspector {
    private final ConnectionManager manager;

    public SchemaInspector(ConnectionManager manager) {
        this.manager = manager;
    }

    public ConnectionManager manager() {
        return manager;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet r) throws SQLException;
    }

    public List<DatabaseInfo> getDatabases() throws SQLException {
        var sql = """
                SELECT d.datname, d.datistemplate,
                       pg_catalog.pg_get_userbyid(d.datdba) AS owner,
                       d.datallowconn
                FROM pg_database d
                WHERE NOT d.datistemplate
                ORDER BY d.datname""";
        return readRowsServerWide(sql, r -> new DatabaseInfo(
                r.getString(1), r.getBoolean(2), r.getString(3), r.getBoolean(4)));
    }

    public List<SchemaInfo> getSchemas() throws SQLException {
        var sql = """
                SELECT n.nspname, pg_catalog.pg_get_userbyid(n.nspowner) AS owner
                FROM pg_catalog.pg_namespace n
                WHERE n.nspname NOT IN ('pg_catalog','information_schema','pg_toast')
                  AND n.nspname NOT LIKE 'pg_temp_%'
                  AND n.nspname NOT LIKE 'pg_toast_temp_%'
                ORDER BY n.nspname""";
        return readRows(sql, r -> new SchemaInfo(r.getString(1), r.getString(2)));
    }

    public List<TableInfo> getTables(String schema) throws SQLException {
        var sql = """
                SELECT n.nspname AS schema,
                       c.relname,
                       CASE c.relkind
                         WHEN 'r' THEN 'TABLE'
                         WHEN 'p' THEN 'PARTITIONED TABLE'
                         WHEN 'f' THEN 'FOREIGN TABLE'
                         ELSE c.relkind::text
                       END AS kind
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relkind IN ('r','p','f')
                ORDER BY c.relname""";
        return readRows(sql, r -> new TableInfo(r.getString(1), r.getString(2), r.getString(3)), schema);
    }

    public List<ViewInfo> getViews(String schema) throws SQLException {
        var sql = """
                SELECT n.nspname, c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relkind IN ('v','m')
                ORDER BY c.relname""";
        return readRows(sql, r -> new ViewInfo(r.getString(1), r.getString(2)), schema);
    }

    public List<ColumnInfo> getColumns(String schema, String table) throws SQLException {
        // quote_ident keeps mixed-case / odd names working in the ::regclass cast.
        var sql = """
                WITH rel AS (SELECT (quote_ident(?) || '.' || quote_ident(?))::regclass AS oid),
                pk AS (
                    SELECT a.attname
                    FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                    WHERE i.indrelid = (SELECT oid FROM rel) AND i.indisprimary
                )
                SELECT a.attname,
                       format_type(a.atttypid, a.atttypmod) AS data_type,
                       NOT a.attnotnull AS is_nullable,
                       (a.attname IN (SELECT attname FROM pk)) AS is_pk,
                       a.attnum,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expr
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = (SELECT oid FROM rel)
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY a.attnum""";
        return readRows(sql, r -> new ColumnInfo(
                r.getString(1), r.getString(2), r.getBoolean(3), r.getBoolean(4), r.getInt(5),
                r.getString(6)), schema, table);
    }

    public List<FunctionInfo> getFunctions(String schema) throws SQLException {
        var sql = """
                SELECT n.nspname,
                       p.proname,
                       pg_get_function_result(p.oid) AS result,
                       pg_get_function_arguments(p.oid) AS args
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = ?
                  AND p.prokind = 'f'
                ORDER BY p.proname""";
        return readRows(sql, r -> new FunctionInfo(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4)), schema);
    }

    public List<RoleInfo> getRoles() throws SQLException {
        var sql = """
                SELECT rolname, rolsuper, rolcanlogin, rolcreaterole, rolcreatedb
                FROM pg_roles
                ORDER BY rolname""";
        return readRowsServerWide(sql, r -> new RoleInfo(
                r.getString(1), r.getBoolean(2), r.getBoolean(3), r.getBoolean(4), r.getBoolean(5)));
    }

    public String scriptTableSelect(String schema, String table, int limit) throws SQLException {
        var cols = getColumns(schema, table);
        var colList = cols.isEmpty()
                ? "*"
                : cols.stream().map(c -> "\"" + c.name() + "\"").collect(Collectors.joining(", "));
        return "SELECT " + colList + "\nFROM \"" + schema + "\".\"" + table + "\"\nLIMIT " + limit + ";";
    }

    public String scriptTableCreate(String schema, String table) throws SQLException {
        var cols = getColumns(schema, table);
        if (cols.isEmpty()) return "-- (table " + schema + "." + table + " not found)";

        var lines = cols.stream().map(c -> {
            var notNull = c.isNullable() ? "" : " NOT NULL";
            var def = c.defaultExpr() == null ? "" : " DEFAULT " + c.defaultExpr();
            return "    \"" + c.name() + "\" " + c.dataType() + notNull + def;
        }).collect(Collectors.joining(",\n"));

        var pk = cols.stream().filter(ColumnInfo::isPrimaryKey).map(c -> "\"" + c.name() + "\"").toList();
        var pkLine = pk.isEmpty() ? "" : ",\n    PRIMARY KEY (" + String.join(", ", pk) + ")";
        return "CREATE TABLE \"" + schema + "\".\"" + table + "\" (\n" + lines + pkLine + "\n);";
    }

    private <T> List<T> readRows(String sql, RowMapper<T> map, String... params) throws SQLException {
        try (var conn = manager.openConnection()) {
            return readRowsOn(conn, sql, map, params);
        }
    }

    /**
     * Like {@link #readRows} but falls back to the maintenance database
     * ('postgres') if the selected database can't be reached (offline, dropped).
     * For server-wide queries (databases, roles) that don't depend on it.
     */
    private <T> List<T> readRowsServerWide(String sql, RowMapper<T> map, String... params) throws SQLException {
        try {
            return readRows(sql, map, params);
        } catch (SQLException ex) {
            // Selected DB offline (55000) or the pool can't connect — try the maintenance DB.
        }
        try (var conn = manager.openMaintenanceConnection()) {
            return readRowsOn(conn, sql, map, params);
        }
    }

    private static <T> List<T> readRowsOn(Connection conn, String sql, RowMapper<T> map, String... params)
            throws SQLException {
        try (var st = conn.prepareStatement(sql)) {
            for (var i = 0; i < params.length; i++) st.setString(i + 1, params[i]);
            try (var r = st.executeQuery()) {
                var list = new ArrayList<T>();
                while (r.next()) list.add(map.map(r));
                return list;
            }
        }
    }
}
