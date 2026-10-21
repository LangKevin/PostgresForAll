package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlSpecs.ExclusionElement;
import com.postgresforall.core.model.DdlSpecs.ExclusionSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.ConstraintDetail;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.service.DdlBuilder;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Create or edit an EXCLUDE constraint (e.g. no overlapping bookings per room). */
public final class ExclusionDialog extends ChangeDialog {

    private static final String[] METHODS = {"gist", "spgist", "btree", "hash"};
    private static final String[] OPERATORS = {"=", "&&", "<>", "-|-", "@>", "<@"};

    private final TableRef table;
    private final ConstraintDetail existing;
    private final JTextField name;
    private final DesignForms.AutoName autoName;
    private final JComboBox<String> method;
    private final DefaultTableModel elements = new DefaultTableModel(new Object[]{"Column or expression", "Operator"}, 0);
    private final JTable elementTable = new JTable(elements);
    private final RSyntaxTextArea where = DesignForms.sqlArea(2, true);

    public static ExclusionDialog create(Component owner, TableRef table, List<ColumnDetail> columns) {
        return new ExclusionDialog(owner, table, columns, null);
    }

    public static ExclusionDialog edit(Component owner, TableRef table, List<ColumnDetail> columns, ConstraintDetail existing) {
        return new ExclusionDialog(owner, table, columns, existing);
    }

    private ExclusionDialog(Component owner, TableRef table, List<ColumnDetail> columns, ConstraintDetail existing) {
        super(owner, existing == null ? "New exclusion constraint" : "Edit exclusion constraint",
                existing == null ? "New exclusion constraint on " + table : "Edit exclusion constraint " + existing.name(),
                existing == null ? "No two rows may match on every element (for example: same room and overlapping time)."
                        : "Postgres can't change an exclusion constraint in place, so this drops it and adds it again.");
        this.table = table;
        this.existing = existing;
        setHeaderIcon(Icons.get("ban", Icons.LARGE));

        Runnable changed = this::onChanged;
        var names = DesignForms.names(columns);
        var def = existing == null ? null : existing.definition();
        name = DesignForms.field(existing == null ? "" : existing.name(), "constraint name");
        autoName = new DesignForms.AutoName(name, existing == null);
        method = DesignForms.combo(METHODS, def == null ? "gist" : parseMethod(def), changed);
        if (def != null) {
            var inner = DesignForms.parenAfter(def, "EXCLUDE");
            if (inner != null) {
                for (var part : DesignForms.splitTopLevel(inner, ',')) {
                    var p = part.trim();
                    var at = p.toUpperCase(Locale.ROOT).lastIndexOf(" WITH ");
                    if (at > 0) elements.addRow(new Object[]{p.substring(0, at).trim(), p.substring(at + 6).trim()});
                }
            }
            var w = parseWhere(def);
            if (w != null) where.setText(w);
        }
        DesignForms.onChange(where, changed);

        elementTable.setRowHeight(Math.max(elementTable.getRowHeight(), 24));
        elementTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        elementTable.setPreferredScrollableViewportSize(new Dimension(420, 4 * elementTable.getRowHeight()));
        var exprEditor = new JComboBox<>(names.toArray(String[]::new));
        exprEditor.setEditable(true);
        elementTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(exprEditor));
        var opEditor = new JComboBox<>(OPERATORS);
        opEditor.setEditable(true);
        elementTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(opEditor));
        elementTable.getColumnModel().getColumn(1).setMaxWidth(120);
        elements.addTableModelListener(e -> onChanged());

        var addCol = new JComboBox<>(names.toArray(String[]::new));
        var bar = new JPanel(new MigLayout("insets 0, gapx 2", "[grow,fill][][]"));
        bar.add(addCol);
        bar.add(DesignForms.iconButton("plus", "Add element", () -> {
            var v = (String) addCol.getSelectedItem();
            if (v != null) elements.addRow(new Object[]{v, "="});
        }));
        bar.add(DesignForms.iconButton("x", "Remove element", () -> {
            if (elementTable.isEditing()) elementTable.getCellEditor().stopCellEditing();
            var i = elementTable.getSelectedRow();
            if (i >= 0) elements.removeRow(i);
        }));
        var elemPanel = new JPanel(new MigLayout("insets 0, fillx, wrap 1, gapy 4", "[grow,fill]"));
        elemPanel.add(bar);
        elemPanel.add(new JScrollPane(elementTable), "hmin 90");
        elemPanel.add(DesignForms.note("Mixing = on plain columns with && on ranges in gist needs the btree_gist extension."));

        var form = DesignForms.form();
        form.add(DesignForms.label("Name"));
        form.add(name);
        form.add(DesignForms.label("Method"));
        form.add(method, "growx 0");
        form.add(DesignForms.label("Elements"), "top, gaptop 4");
        form.add(elemPanel);
        form.add(DesignForms.label("Where"), "top, gaptop 4");
        form.add(DesignForms.scrolled(where), "hmin 44");
        form.add(new JLabel());
        form.add(DesignForms.note("Building the constraint's index blocks writes to the table until it finishes."));
        setForm(form);
        onChanged();
    }

    private void onChanged() {
        if (existing == null) autoName.suggest(table.name(), List.of(), "excl");
        contentChanged();
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var n = name.getText().trim();
        if (n.isEmpty()) throw new IllegalArgumentException("The exclusion constraint needs a name.");
        var elems = new ArrayList<ExclusionElement>();
        for (var r = 0; r < elements.getRowCount(); r++) {
            var expr = text(elements.getValueAt(r, 0));
            var op = text(elements.getValueAt(r, 1));
            if (expr.isEmpty()) continue;
            if (op.isEmpty()) throw new IllegalArgumentException("Choose an operator for " + expr + ".");
            elems.add(new ExclusionElement(expr, op));
        }
        if (elems.isEmpty()) throw new IllegalArgumentException("Add at least one element.");
        var out = new ArrayList<DdlStatement>();
        if (existing != null) out.add(DdlBuilder.dropConstraint(table.schema(), table.name(), existing.name(), false));
        out.add(DdlBuilder.addExclusion(new ExclusionSpec(table.schema(), table.name(), n,
                (String) method.getSelectedItem(), elems, DesignForms.blankToNull(where.getText()))));
        return out;
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String parseMethod(String def) {
        var m = Pattern.compile("(?i)EXCLUDE\\s+USING\\s+(\\w+)").matcher(def);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "gist";
    }

    /** The WHERE predicate after the element list, or null. */
    private static String parseWhere(String def) {
        var i = def.toUpperCase(Locale.ROOT).lastIndexOf(" WHERE ");
        if (i < 0) return null;
        var w = DesignForms.parenAfter(def.substring(i), "WHERE");
        return w == null ? null : w.trim();
    }
}
