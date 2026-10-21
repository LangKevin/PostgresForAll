package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlSpecs.ForeignKeySpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.IndexDetail;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.service.DdlBuilder;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Create or edit a foreign key. Editing drops and re-adds it. On a large table the
 * key is added NOT VALID and validated in a second statement, which only takes a
 * light lock while it scans existing rows.
 */
public final class ForeignKeyDialog extends ChangeDialog {

    private static final String PICK_KEY = "Choose a key on that table…";

    private final TableRef table;
    private final ConstraintDetail existing;
    private final List<IndexDetail> existingIndexes;
    private final Function<TableRef, List<String>> columnsOf;
    private final Function<TableRef, List<List<String>>> keysOf;

    private final JTextField name;
    private final DesignForms.AutoName autoName;
    private final DesignForms.OrderedColumns local;
    private final JComboBox<TableRef> refTable;
    private final JComboBox<String> refKey = new JComboBox<>();
    private final DesignForms.OrderedColumns refColumns;
    private final JComboBox<String> onUpdate;
    private final JComboBox<String> onDelete;
    private final JCheckBox deferrable;
    private final JCheckBox initiallyDeferred;
    private final JCheckBox notValid;
    private final JLabel indexNote;
    private final JLabel loading;
    private List<List<String>> refKeys = List.of();
    private long lookupGeneration;

    public static ForeignKeyDialog create(Component owner, TableRef table, List<ColumnDetail> columns,
                                          List<IndexDetail> existingIndexes, List<TableRef> tables,
                                          Function<TableRef, List<String>> columnsOf,
                                          Function<TableRef, List<List<String>>> keysOf, boolean largeTable) {
        return new ForeignKeyDialog(owner, table, columns, existingIndexes, tables, columnsOf, keysOf, largeTable, null);
    }

    public static ForeignKeyDialog edit(Component owner, TableRef table, List<ColumnDetail> columns,
                                        List<IndexDetail> existingIndexes, List<TableRef> tables,
                                        Function<TableRef, List<String>> columnsOf,
                                        Function<TableRef, List<List<String>>> keysOf, boolean largeTable,
                                        ConstraintDetail existing) {
        return new ForeignKeyDialog(owner, table, columns, existingIndexes, tables, columnsOf, keysOf, largeTable, existing);
    }

    private ForeignKeyDialog(Component owner, TableRef table, List<ColumnDetail> columns, List<IndexDetail> existingIndexes,
                             List<TableRef> tables, Function<TableRef, List<String>> columnsOf,
                             Function<TableRef, List<List<String>>> keysOf, boolean largeTable, ConstraintDetail existing) {
        super(owner, existing == null ? "New foreign key" : "Edit foreign key",
                existing == null ? "New foreign key on " + table : "Edit foreign key " + existing.name(),
                existing == null ? "Every value in these columns must exist in the referenced table."
                        : "Postgres can't change a foreign key in place, so this drops it and adds it again.");
        this.table = table;
        this.existing = existing;
        this.existingIndexes = existingIndexes == null ? List.of() : existingIndexes;
        this.columnsOf = columnsOf;
        this.keysOf = keysOf;
        setHeaderIcon(Icons.get("link", Icons.LARGE));

        Runnable changed = this::onChanged;
        name = DesignForms.field(existing == null ? "" : existing.name(), "constraint name");
        autoName = new DesignForms.AutoName(name, existing == null);
        local = new DesignForms.OrderedColumns(DesignForms.names(columns),
                existing == null ? List.of() : existing.columns(), changed);

        refTable = new JComboBox<>(tables.toArray(TableRef[]::new));
        TableRef initialRef = null;
        if (existing != null && existing.refTable() != null) {
            initialRef = new TableRef(existing.refSchema(), existing.refTable());
            if (!tables.contains(initialRef)) refTable.addItem(initialRef);
        }
        refTable.setSelectedItem(initialRef);
        refColumns = new DesignForms.OrderedColumns(List.of(),
                existing == null || existing.refColumns() == null ? List.of() : existing.refColumns(), changed);
        refKey.addActionListener(e -> {
            var i = refKey.getSelectedIndex() - 1;
            if (i >= 0 && i < refKeys.size()) {
                refColumns.setValues(refKeys.get(i));
                onChanged();
            }
        });
        loading = DesignForms.label("Loading columns…");
        loading.setIcon(Icons.get("loader", Icons.SMALL));
        loading.setVisible(false);
        refTable.addActionListener(e -> {
            refColumns.setValues(List.of());
            loadReferenced();
            onChanged();
        });

        var actions = DesignForms.actions();
        onUpdate = DesignForms.combo(actions, existing == null || existing.onUpdate() == null ? "NO ACTION" : existing.onUpdate(), changed);
        onDelete = DesignForms.combo(actions, existing == null || existing.onDelete() == null ? "NO ACTION" : existing.onDelete(), changed);
        deferrable = DesignForms.check("Deferrable", existing != null && existing.deferrable(), changed);
        initiallyDeferred = DesignForms.check("Initially deferred (checked at commit)", existing != null && existing.initiallyDeferred(), changed);
        notValid = DesignForms.check("Don't block writes while checking existing rows (NOT VALID, then VALIDATE)", largeTable, changed);
        indexNote = DesignForms.note("No index starts with these columns. Deletes and key updates on the referenced table will scan "
                + table + "; consider adding one.");

        var form = DesignForms.form();
        form.add(DesignForms.label("Name"));
        form.add(name);
        form.add(DesignForms.label("Columns"), "top, gaptop 4");
        form.add(local);
        form.add(DesignForms.label("References"));
        form.add(refTable, "split 2");
        form.add(loading, "growx 0");
        form.add(DesignForms.label("Key"));
        form.add(refKey);
        form.add(DesignForms.label("Referenced columns"), "top, gaptop 4");
        form.add(refColumns);
        form.add(DesignForms.label("On update"));
        form.add(onUpdate, "split 3, growx 0");
        form.add(DesignForms.label("On delete"), "gapleft 16");
        form.add(onDelete, "growx 0");
        form.add(new JLabel());
        form.add(deferrable, "split 2");
        form.add(initiallyDeferred, "gapleft 12");
        form.add(new JLabel());
        form.add(notValid);
        form.add(new JLabel());
        form.add(indexNote);
        setForm(form);
        if (initialRef != null) loadReferenced();
        onChanged();
    }

    /** Fetches the referenced table's columns and keys off the EDT. */
    private void loadReferenced() {
        var ref = (TableRef) refTable.getSelectedItem();
        var gen = ++lookupGeneration;
        refKeys = List.of();
        refKey.removeAllItems();
        refColumns.setAvailable(List.of());
        if (ref == null) return;
        loading.setVisible(true);
        Thread.ofVirtual().start(() -> {
            List<String> cols;
            List<List<String>> ks;
            try {
                cols = columnsOf.apply(ref);
                ks = keysOf.apply(ref);
            } catch (RuntimeException ex) {
                cols = List.of();
                ks = List.of();
            }
            var fc = cols == null ? List.<String>of() : cols;
            var fk = ks == null ? List.<List<String>>of() : ks;
            Ui.later(() -> {
                if (gen != lookupGeneration) return;
                loading.setVisible(false);
                refColumns.setAvailable(fc);
                refKeys = fk;
                refKey.removeAllItems();
                refKey.addItem(fk.isEmpty() ? "(no primary key or unique constraint)" : PICK_KEY);
                for (var k : fk) refKey.addItem(String.join(", ", k));
                // New FK: default to the referenced table's first key (usually its primary key).
                if (refColumns.values().isEmpty() && !fk.isEmpty()) refKey.setSelectedIndex(1);
                onChanged();
            });
        });
    }

    private void onChanged() {
        initiallyDeferred.setEnabled(deferrable.isSelected());
        var cols = local.values();
        if (existing == null) {
            var ref = (TableRef) refTable.getSelectedItem();
            var cs = new ArrayList<>(cols);
            if (ref != null && cs.isEmpty()) cs.add(ref.name());
            autoName.suggest(table.name(), cs, "fkey");
        }
        indexNote.setVisible(!cols.isEmpty() && !hasSupportingIndex(cols));
        contentChanged();
    }

    private boolean hasSupportingIndex(List<String> cols) {
        for (var ix : existingIndexes) {
            if (!ix.valid() || ix.predicate() != null || ix.keys().size() < cols.size()) continue;
            var keys = ix.keys().stream().map(k -> DesignForms.unquote(k.replaceAll("(?i)\\s+(ASC|DESC|NULLS\\s+(FIRST|LAST))", "").trim())).toList();
            if (keys.subList(0, cols.size()).equals(cols)) return true;
        }
        return false;
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var cols = local.values();
        var ref = (TableRef) refTable.getSelectedItem();
        var refCols = refColumns.values();
        var n = name.getText().trim();
        if (n.isEmpty()) throw new IllegalArgumentException("The foreign key needs a name.");
        if (cols.isEmpty()) throw new IllegalArgumentException("Choose at least one column.");
        if (ref == null) throw new IllegalArgumentException("Choose the referenced table.");
        if (refCols.isEmpty()) throw new IllegalArgumentException("Choose the referenced columns (usually its primary key).");
        if (refCols.size() != cols.size()) {
            throw new IllegalArgumentException("Pick as many referenced columns as columns (" + cols.size() + " vs " + refCols.size() + ").");
        }
        var nv = notValid.isSelected();
        var spec = new ForeignKeySpec(table.schema(), table.name(), n, cols, ref.schema(), ref.name(), refCols,
                (String) onUpdate.getSelectedItem(), (String) onDelete.getSelectedItem(),
                deferrable.isSelected(), deferrable.isSelected() && initiallyDeferred.isSelected(), nv);
        var out = new ArrayList<DdlStatement>();
        if (existing != null) out.add(DdlBuilder.dropConstraint(table.schema(), table.name(), existing.name(), false));
        out.add(DdlBuilder.addForeignKey(spec));
        if (nv) out.add(DdlBuilder.validateConstraint(table.schema(), table.name(), n));
        return out;
    }
}
