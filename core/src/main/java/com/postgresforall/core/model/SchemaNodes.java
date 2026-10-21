package com.postgresforall.core.model;

/** Record types for Object Explorer tree payloads. */
public final class SchemaNodes {
    private SchemaNodes() { }

    public record DatabaseInfo(String name, boolean isTemplate, String owner, boolean allowConnections) { }

    public record SchemaInfo(String name, String owner) { }

    public record TableInfo(String schema, String name, String kind) { }

    public record ColumnInfo(String name, String dataType, boolean isNullable, boolean isPrimaryKey,
                             int ordinalPosition, String defaultExpr) { }

    public record ViewInfo(String schema, String name) { }

    public record FunctionInfo(String schema, String name, String returnType, String arguments) { }

    public record RoleInfo(String name, boolean isSuperuser, boolean canLogin, boolean canCreateRole,
                           boolean canCreateDb) { }
}
