package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlSpecs.ColumnSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.service.DdlBuilder;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.util.List;
import java.util.Locale;

/**
 * Add a column, or change one (name, type, nullability, default, identity,
 * comment). Type changes get a USING field, prefilled with a cast.
 */
public final class ColumnDialog extends ChangeDialog {

    private static final String[] IDENTITY = {"none", "ALWAYS", "BY DEFAULT"};

    private final TableRef table;
    private final ColumnDetail existing;
    private final JTextField name;
    private final JComboBox<String> type;
    private final JCheckBox notNull;
    private final JTextField defaultExpr;
    private final JComboBox<String> identity;
    private final JTextField generated;
    private final JTextField collation;
    private final JTextField comment;
    private final JLabel usingLabel = DesignForms.label("Using");
    private final JTextField using;
    private final JLabel rewriteNote;
    private final JLabel notNullNote;
    private String lastPrefill = "";

    public static ColumnDialog create(Component owner, TableRef table, List<String> dataTypes) {
        return new ColumnDialog(owner, table, null, dataTypes);
    }

    public static ColumnDialog edit(Component owner, TableRef table, ColumnDetail existing, List<String> dataTypes) {
        return new ColumnDialog(owner, table, existing, dataTypes);
    }

    private ColumnDialog(Component owner, TableRef table, ColumnDetail existing, List<String> dataTypes) {
        super(owner, existing == null ? "New column" : "Edit column",
                existing == null ? "New column on " + table : "Edit column " + existing.name(),
                existing == null ? null : table.toString());
        this.table = table;
        this.existing = existing;
        setHeaderIcon(Icons.get(existing == null ? "columns-3" : "pencil", Icons.LARGE));

        Runnable changed = this::onChanged;
        name = DesignForms.field(existing == null ? "" : existing.name(), "column name");
        DesignForms.onChange(name, changed);
        type = DesignForms.editableCombo(dataTypes, existing == null ? "text" : existing.dataType(), changed);
        notNull = DesignForms.check("Not null", existing != null && !existing.nullable(), changed);
        defaultExpr = DesignForms.field(existing == null ? "" : existing.defaultExpr(), "e.g. now(), 0, 'pending'");
        DesignForms.onChange(defaultExpr, changed);
        identity = DesignForms.combo(IDENTITY,
                existing == null || existing.identity() == null ? "none" : existing.identity().toUpperCase(Locale.ROOT), changed);
        generated = DesignForms.field(existing == null ? "" : existing.generated(), "STORED expression, e.g. price * qty");
        DesignForms.onChange(generated, changed);
        // Postgres can't turn an ordinary column into a generated one; it can only drop the expression.
        generated.setEnabled(existing == null || existing.generated() != null);
        collation = DesignForms.field(existing == null ? "" : existing.collation(), "default");
        DesignForms.onChange(collation, changed);
        comment = DesignForms.field(existing == null ? "" : existing.comment(), "optional");
        DesignForms.onChange(comment, changed);
        using = DesignForms.field("", null);
        DesignForms.onChange(using, changed);
        rewriteNote = DesignForms.warning("Changing the type rewrites the whole table and blocks it, including reads, until done.");
        notNullNote = DesignForms.note("Setting NOT NULL scans every row to check there are no nulls.");

        var form = DesignForms.form();
        form.add(DesignForms.label("Name"));
        form.add(name);
        form.add(DesignForms.label("Type"));
        form.add(type);
        form.add(usingLabel);
        form.add(using);
        form.add(new JLabel());
        form.add(rewriteNote);
        form.add(new JLabel());
        form.add(notNull);
        form.add(new JLabel());
        form.add(notNullNote);
        form.add(DesignForms.label("Default"));
        form.add(defaultExpr);
        form.add(DesignForms.label("Identity"));
        form.add(identity, "growx 0");
        form.add(DesignForms.label("Generated"));
        form.add(generated);
        form.add(DesignForms.label("Collation"));
        form.add(collation);
        form.add(DesignForms.label("Comment"));
        form.add(comment);
        setForm(form);
        onChanged();
    }

    private boolean typeChanged() {
        if (existing == null) return false;
        var t = DesignForms.comboText(type);
        return !t.isEmpty() && !norm(t).equals(norm(existing.dataType()));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void onChanged() {
        var changedType = typeChanged();
        usingLabel.setVisible(changedType);
        using.setVisible(changedType);
        rewriteNote.setVisible(changedType);
        if (changedType) {
            // Keep the prefilled cast in step with the type until the user edits it.
            // A rename runs before the type change, so the cast refers to the new name.
            var col = name.getText().isBlank() ? existing.name() : name.getText().trim();
            var prefill = DesignForms.quoteIfNeeded(col) + "::" + DesignForms.comboText(type);
            if (using.getText().equals(lastPrefill) && !prefill.equals(lastPrefill)) {
                lastPrefill = prefill;
                javax.swing.SwingUtilities.invokeLater(() -> using.setText(prefill));
            }
        } else if (using.getText().equals(lastPrefill)) {
            lastPrefill = "";
            if (!using.getText().isEmpty()) javax.swing.SwingUtilities.invokeLater(() -> using.setText(""));
        }
        notNullNote.setVisible(notNull.isSelected() && (existing == null ? false : existing.nullable()));
        var hasIdentity = !"none".equals(identity.getSelectedItem());
        defaultExpr.setEnabled(!hasIdentity && generated.getText().isBlank());
        identity.setEnabled(generated.getText().isBlank());
        getContentPane().revalidate();
        contentChanged();
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var n = name.getText().trim();
        var t = DesignForms.comboText(type);
        if (n.isEmpty()) throw new IllegalArgumentException("The column needs a name.");
        if (t.isEmpty()) throw new IllegalArgumentException("Choose a data type.");
        var ident = "none".equals(identity.getSelectedItem()) ? null : (String) identity.getSelectedItem();
        var gen = DesignForms.blankToNull(generated.getText());
        if (gen != null && ident != null) throw new IllegalArgumentException("A column can't be both generated and identity.");
        var def = ident != null || gen != null ? null : DesignForms.blankToNull(defaultExpr.getText());
        if (existing == null) {
            var spec = new ColumnSpec(n, t, notNull.isSelected(), def, ident, gen,
                    DesignForms.blankToNull(collation.getText()), DesignForms.blankToNull(comment.getText()));
            return DdlBuilder.addColumn(table.schema(), table.name(), spec);
        }
        // Edit: comment "" removes it; unchanged text produces no statement.
        var spec = new ColumnSpec(n, t, notNull.isSelected(), def, ident, gen,
                DesignForms.blankToNull(collation.getText()), comment.getText().trim());
        var list = DdlBuilder.alterColumn(table.schema(), table.name(), existing, spec,
                typeChanged() ? DesignForms.blankToNull(using.getText()) : null);
        if (list.isEmpty()) throw new IllegalArgumentException("Nothing has changed yet.");
        return list;
    }
}
