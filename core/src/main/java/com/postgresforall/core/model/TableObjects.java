package com.postgresforall.core.model;

import java.util.List;

/** What a table is made of, as read from the catalogs (TableInspector). */
public final class TableObjects {
    private TableObjects() { }

    /**
     * @param estimatedRows pg_class.reltuples (may be -1 for never-analysed tables)
     * @param totalBytes    pg_total_relation_size
     * @param canAlter      the login owns the table (or is a member of the owner role) or is a superuser
     */
    public record TableSummary(String schema, String name, String owner, long estimatedRows, long totalBytes,
                               boolean canAlter, String comment) {

        /** Above this, index builds default to CONCURRENTLY and constraints to NOT VALID. */
        public boolean isLarge(long rowThreshold, long byteThreshold) {
            return estimatedRows >= rowThreshold || totalBytes >= byteThreshold;
        }
    }

    /**
     * @param identity  "ALWAYS", "BY DEFAULT" or null
     * @param generated the STORED generation expression, or null
     */
    public record ColumnDetail(String name, String dataType, boolean nullable, String defaultExpr, String identity,
                               String generated, String collation, String comment, int position, boolean primaryKey) { }

    public enum ConstraintType { PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK, EXCLUSION }

    /**
     * One table constraint. {@code definition} is pg_get_constraintdef. The ref* /
     * on* fields are set for foreign keys only ("NO ACTION", "RESTRICT", "CASCADE",
     * "SET NULL", "SET DEFAULT").
     */
    public record ConstraintDetail(String name, ConstraintType type, List<String> columns, String definition,
                                   boolean validated, boolean deferrable, boolean initiallyDeferred,
                                   String refSchema, String refTable, List<String> refColumns,
                                   String onUpdate, String onDelete) { }

    /**
     * @param constraintName the PK / unique / exclusion constraint this index backs, or null
     * @param valid          false for an index left invalid by a failed CREATE INDEX CONCURRENTLY
     * @param definition     pg_get_indexdef
     * @param keys           display form of the key columns / expressions
     */
    public record IndexDetail(String name, String method, boolean unique, boolean primary, String constraintName,
                              boolean valid, String definition, List<String> keys, String predicate,
                              long sizeBytes, long scans) { }

    /**
     * @param timing   "BEFORE", "AFTER" or "INSTEAD OF"
     * @param events   e.g. ["INSERT", "UPDATE"]
     * @param level    "ROW" or "STATEMENT"
     * @param function schema-qualified trigger function
     */
    public record TriggerDetail(String name, String timing, List<String> events, String level, boolean enabled,
                                String function, String whenExpr, String definition) { }

    /** A foreign key on another table that points at this one. */
    public record ReferencingFk(String schema, String table, String constraintName, List<String> columns,
                                String definition) { }

    /** An object that depends on what's about to change (shown before drops / type changes). */
    public record Dependency(String kind, String name, String detail) { }

    public record TableRef(String schema, String name) {
        @Override
        public String toString() {
            return schema + "." + name;
        }
    }
}
