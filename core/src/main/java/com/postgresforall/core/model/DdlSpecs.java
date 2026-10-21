package com.postgresforall.core.model;

import java.util.List;

/** What the user asked for in a design dialog; DdlBuilder turns these into SQL. */
public final class DdlSpecs {
    private DdlSpecs() { }

    /** One index key: a column name or an expression (expressions are wrapped in parentheses). */
    public record IndexElement(String columnOrExpression, boolean expression, boolean descending, Boolean nullsFirst) { }

    /**
     * @param method     btree, hash, gin, gist, brin, spgist
     * @param include    INCLUDE columns (may be empty)
     * @param where      partial-index predicate, or null
     * @param fillfactor null = default
     */
    public record IndexSpec(String schema, String table, String name, List<IndexElement> elements, String method,
                            boolean unique, List<String> include, String where, Integer fillfactor,
                            boolean concurrently) { }

    /** onUpdate / onDelete: "NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT". */
    public record ForeignKeySpec(String schema, String table, String name, List<String> columns,
                                 String refSchema, String refTable, List<String> refColumns,
                                 String onUpdate, String onDelete, boolean deferrable, boolean initiallyDeferred,
                                 boolean notValid) { }

    public record CheckSpec(String schema, String table, String name, String expression, boolean notValid,
                            boolean noInherit) { }

    /** UNIQUE or PRIMARY KEY. */
    public record KeySpec(String schema, String table, String name, List<String> columns, boolean primaryKey,
                          boolean nullsNotDistinct, List<String> include) { }

    /** One exclusion element: expression/column WITH operator. */
    public record ExclusionElement(String expression, String operator) { }

    public record ExclusionSpec(String schema, String table, String name, String method,
                                List<ExclusionElement> elements, String where) { }

    /**
     * @param identity  "ALWAYS", "BY DEFAULT" or null
     * @param generated STORED generation expression or null
     * @param comment   null = leave / no comment, "" = remove
     */
    public record ColumnSpec(String name, String dataType, boolean notNull, String defaultExpr, String identity,
                             String generated, String collation, String comment) { }

    /**
     * @param timing   "BEFORE", "AFTER", "INSTEAD OF"
     * @param events   INSERT / UPDATE / DELETE / TRUNCATE
     * @param updateOf columns for UPDATE OF (may be empty)
     * @param forEachRow true = FOR EACH ROW, false = FOR EACH STATEMENT
     * @param function schema-qualified function name (no parentheses)
     */
    public record TriggerSpec(String schema, String table, String name, String timing, List<String> events,
                              List<String> updateOf, boolean forEachRow, String whenExpr, String function) { }
}
