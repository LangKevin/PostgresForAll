package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlSpecs.KeySpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.ConstraintType;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.service.DdlBuilder;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Create or edit a PRIMARY KEY or UNIQUE constraint. Editing drops and re-adds it. */
public final class KeyDialog extends ChangeDialog {

    private final TableRef table;
    private final List<ColumnDetail> columns;
    private final ConstraintDetail existing;
    private final boolean primaryKey;
    private final JTextField name;
    private final DesignForms.AutoName autoName;
    private final DesignForms.OrderedColumns keyColumns;
    private final DesignForms.CheckList include;
    private final JCheckBox nullsNotDistinct;
    private final JLabel nullableNote;

    public static KeyDialog create(Component owner, TableRef table, List<ColumnDetail> columns, boolean primaryKey) {
        return new KeyDialog(owner, table, columns, null, primaryKey);
    }

    public static KeyDialog edit(Component owner, TableRef table, List<ColumnDetail> columns, ConstraintDetail existing) {
        return new KeyDialog(owner, table, columns, existing, existing.type() == ConstraintType.PRIMARY_KEY);
    }

    private KeyDialog(Component owner, TableRef table, List<ColumnDetail> columns, ConstraintDetail existing, boolean primaryKey) {
        super(owner, titleFor(existing, primaryKey),
                existing == null ? "New " + kind(primaryKey) + " on " + table : "Edit " + kind(primaryKey) + " " + existing.name(),
                existing != null ? "Postgres can't change a key in place, so this drops it and adds it again."
                        : primaryKey ? "Identifies each row; the columns become NOT NULL and get a unique index."
                        : "No two rows may share the same values in these columns.");
        this.table = table;
        this.columns = columns;
        this.existing = existing;
        this.primaryKey = primaryKey;
        setHeaderIcon(Icons.get("key-round", Icons.LARGE));

        Runnable changed = this::onChanged;
        var names = DesignForms.names(columns);
        name = DesignForms.field(existing == null ? "" : existing.name(), "constraint name");
        autoName = new DesignForms.AutoName(name, existing == null);
        keyColumns = new DesignForms.OrderedColumns(names, existing == null ? List.of() : existing.columns(), changed);
        var def = existing == null || existing.definition() == null ? "" : existing.definition();
        include = new DesignForms.CheckList(names, DesignForms.splitList(DesignForms.parenAfter(def, " INCLUDE ")), changed);
        nullsNotDistinct = DesignForms.check("Treat nulls as equal (NULLS NOT DISTINCT, Postgres 15+)",
                def.toUpperCase(Locale.ROOT).contains("NULLS NOT DISTINCT"), changed);
        nullableNote = DesignForms.note("Some of these columns allow nulls; adding the key sets them NOT NULL, which scans the table.");

        var form = DesignForms.form();
        form.add(DesignForms.label("Name"));
        form.add(name);
        form.add(DesignForms.label("Columns"), "top, gaptop 4");
        form.add(keyColumns);
        form.add(DesignForms.label("Include"), "top");
        form.add(include);
        if (!primaryKey) {
            form.add(new JLabel());
            form.add(nullsNotDistinct);
        }
        form.add(new JLabel());
        form.add(nullableNote);
        setForm(form);
        onChanged();
    }

    private static String kind(boolean primaryKey) {
        return primaryKey ? "primary key" : "unique constraint";
    }

    private static String titleFor(ConstraintDetail existing, boolean primaryKey) {
        var k = primaryKey ? "primary key" : "unique constraint";
        return (existing == null ? "New " : "Edit ") + k;
    }

    private void onChanged() {
        var cols = keyColumns.values();
        if (existing == null) autoName.suggest(table.name(), primaryKey ? List.of() : cols, primaryKey ? "pkey" : "key");
        nullableNote.setVisible(primaryKey && columns.stream().anyMatch(c -> c.nullable() && cols.contains(c.name())));
        contentChanged();
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var n = name.getText().trim();
        var cols = keyColumns.values();
        if (n.isEmpty()) throw new IllegalArgumentException("The " + kind(primaryKey) + " needs a name.");
        if (cols.isEmpty()) throw new IllegalArgumentException("Choose at least one column.");
        var inc = include.values().stream().filter(c -> !cols.contains(c)).toList();
        var out = new ArrayList<DdlStatement>();
        if (existing != null) out.add(DdlBuilder.dropConstraint(table.schema(), table.name(), existing.name(), false));
        out.add(DdlBuilder.addKey(new KeySpec(table.schema(), table.name(), n, cols, primaryKey,
                !primaryKey && nullsNotDistinct.isSelected(), inc)));
        return out;
    }
}
