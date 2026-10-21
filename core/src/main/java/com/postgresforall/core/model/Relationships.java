package com.postgresforall.core.model;

import com.postgresforall.core.model.TableObjects.TableRef;

import java.util.List;

/** A table's foreign-key neighbourhood, for the relationship diagram. */
public final class Relationships {
    private Relationships() { }

    /** @param unique part of a single-column PRIMARY KEY or UNIQUE constraint */
    public record DiagramColumn(String name, String dataType, boolean nullable, boolean primaryKey, boolean foreignKey,
                                boolean unique) { }

    /** @param distance foreign-key hops from the focus table (0 = the focus table itself) */
    public record DiagramTable(TableRef ref, List<DiagramColumn> columns, long estimatedRows, String comment,
                               int distance) { }

    /**
     * One foreign key: {@code from} (the referencing table, the "many" side)
     * points at {@code to} (the referenced table, the "one" side).
     *
     * @param optional  any referencing column is nullable (a row may have no parent)
     * @param oneToOne  the referencing columns are themselves unique (at most one child per parent)
     */
    public record Relationship(String name, TableRef from, List<String> fromColumns, TableRef to, List<String> toColumns,
                               String onUpdate, String onDelete, boolean validated, boolean optional, boolean oneToOne) {
        public boolean selfReference() {
            return from.equals(to);
        }

        /** "orders(customer_id) → customers(id)". */
        public String describe() {
            return from + "(" + String.join(", ", fromColumns) + ") → " + to + "(" + String.join(", ", toColumns) + ")";
        }
    }

    /**
     * @param truncated more tables were reachable than the limit allowed; the
     *                  edges listed only join tables that are in {@code tables}
     */
    public record RelationshipGraph(TableRef focus, List<DiagramTable> tables, List<Relationship> relationships,
                                    boolean truncated) {
        public DiagramTable table(TableRef ref) {
            for (var t : tables) if (t.ref().equals(ref)) return t;
            return null;
        }
    }
}
