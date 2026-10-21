package com.postgresforall.app.ui.design;

import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlSpecs.IndexElement;
import com.postgresforall.core.model.DdlSpecs.IndexSpec;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.model.TableObjects.ColumnDetail;
import com.postgresforall.core.model.TableObjects.IndexDetail;
import com.postgresforall.core.model.TableObjects.TableRef;
import com.postgresforall.core.service.DdlBuilder;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
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

/** Create or edit an index. Editing drops and recreates it (Postgres can't alter index keys in place). */
public final class IndexDialog extends ChangeDialog {

    private static final String[] METHODS = {"btree", "hash", "gin", "gist", "brin", "spgist"};
    private static final String[] NULLS = {"default", "NULLS FIRST", "NULLS LAST"};

    private final TableRef table;
    private final List<String> columnNames;
    private final IndexDetail existing;
    private final boolean largeTable;

    private final JTextField name;
    private final DesignForms.AutoName autoName;
    private final JComboBox<String> method;
    private final JCheckBox unique;
    private final DefaultTableModel keys = new DefaultTableModel(new Object[]{"Column or expression", "Descending", "Nulls"}, 0) {
        @Override
        public Class<?> getColumnClass(int c) {
            return c == 1 ? Boolean.class : String.class;
        }
    };
    private final JTable keyTable = new JTable(keys);
    private final DesignForms.CheckList include;
    private final RSyntaxTextArea where = DesignForms.sqlArea(2, true);
    private final JTextField fillfactor;
    private final JCheckBox concurrently;
    private final JLabel lockWarning;

    public static IndexDialog create(Component owner, TableRef table, List<ColumnDetail> columns, boolean largeTable) {
        return new IndexDialog(owner, table, columns, null, largeTable);
    }

    public static IndexDialog edit(Component owner, TableRef table, List<ColumnDetail> columns, IndexDetail existing,
                                   boolean largeTable) {
        return new IndexDialog(owner, table, columns, existing, largeTable);
    }

    private IndexDialog(Component owner, TableRef table, List<ColumnDetail> columns, IndexDetail existing, boolean largeTable) {
        super(owner, existing == null ? "New index" : "Edit index",
                existing == null ? "New index on " + table : "Edit index " + existing.name(),
                existing == null ? "Speeds up lookups and sorts on the chosen columns."
                        : "Postgres can't change an index in place, so this drops it and creates it again.");
        this.table = table;
        this.columnNames = DesignForms.names(columns);
        this.existing = existing;
        this.largeTable = largeTable;
        setHeaderIcon(Icons.get("zap", Icons.LARGE));

        Runnable changed = this::onChanged;
        name = DesignForms.field(existing == null ? "" : existing.name(), "index name");
        autoName = new DesignForms.AutoName(name, existing == null);
        method = DesignForms.combo(METHODS, existing == null ? "btree" : existing.method(), changed);
        unique = DesignForms.check("Unique (no two rows may have the same key)", existing != null && existing.unique(), changed);

        var initialInclude = existing == null ? List.<String>of()
                : DesignForms.splitList(DesignForms.parenAfter(existing.definition(), " INCLUDE "));
        include = new DesignForms.CheckList(columnNames, initialInclude, changed);
        if (existing != null && existing.predicate() != null) where.setText(existing.predicate());
        DesignForms.onChange(where, changed);
        fillfactor = DesignForms.field(existing == null ? "" : parseFillfactor(existing.definition()), "default");
        DesignForms.onChange(fillfactor, changed);
        concurrently = DesignForms.check("Build without blocking writes (CONCURRENTLY)", largeTable, changed);
        lockWarning = DesignForms.warning("This table is large: a normal build blocks inserts, updates and deletes until it finishes.");

        // ---- key table ----
        if (existing != null) {
            for (var k : existing.keys()) keys.addRow(parseKey(k));
        }
        keyTable.setRowHeight(Math.max(keyTable.getRowHeight(), 24));
        keyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keyTable.setPreferredScrollableViewportSize(new Dimension(420, 4 * keyTable.getRowHeight()));
        var exprEditor = new JComboBox<>(columnNames.toArray(String[]::new));
        exprEditor.setEditable(true);
        keyTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(exprEditor));
        keyTable.getColumnModel().getColumn(1).setMaxWidth(100);
        keyTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(NULLS)));
        keyTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        keyTable.getColumnModel().getColumn(2).setMaxWidth(140);
        keys.addTableModelListener(e -> onChanged());

        var addCol = new JComboBox<>(columnNames.toArray(String[]::new));
        var keyButtons = new JPanel(new MigLayout("insets 0, gapx 2", "[grow,fill][][][][]"));
        keyButtons.add(addCol);
        keyButtons.add(DesignForms.iconButton("plus", "Add column as a key", () -> {
            var v = (String) addCol.getSelectedItem();
            if (v != null) keys.addRow(new Object[]{v, false, NULLS[0]});
        }));
        keyButtons.add(DesignForms.iconButton("arrow-up", "Move up", () -> moveKey(-1)));
        keyButtons.add(DesignForms.iconButton("arrow-down", "Move down", () -> moveKey(1)));
        keyButtons.add(DesignForms.iconButton("x", "Remove key", () -> {
            stopEditing();
            var i = keyTable.getSelectedRow();
            if (i >= 0) keys.removeRow(i);
        }));
        var keyPanel = new JPanel(new MigLayout("insets 0, fillx, wrap 1, gapy 4", "[grow,fill]"));
        keyPanel.add(keyButtons);
        keyPanel.add(new JScrollPane(keyTable), "hmin 90");
        keyPanel.add(DesignForms.note("Type an expression such as lower(email) in the first column for an expression index."));

        var form = DesignForms.form();
        form.add(DesignForms.label("Name"));
        form.add(name);
        form.add(DesignForms.label("Method"));
        form.add(method, "split 2, growx 0");
        form.add(unique, "gapleft 12");
        form.add(DesignForms.label("Keys"), "top, gaptop 4");
        form.add(keyPanel);
        form.add(DesignForms.label("Include"), "top");
        form.add(include);
        form.add(DesignForms.label("Where"), "top, gaptop 4");
        form.add(DesignForms.scrolled(where), "hmin 44");
        form.add(DesignForms.label("Fill factor"));
        form.add(fillfactor, "growx 0, w 80!");
        form.add(new JLabel());
        form.add(concurrently);
        form.add(new JLabel());
        form.add(lockWarning);
        setForm(form);
        onChanged();
    }

    private void onChanged() {
        var m = (String) method.getSelectedItem();
        var btree = "btree".equals(m);
        unique.setEnabled(btree);
        include.setEnabled(btree || "gist".equals(m) || "spgist".equals(m));
        lockWarning.setVisible(largeTable && !concurrently.isSelected());
        if (existing == null) {
            var cols = new ArrayList<String>();
            for (var e : elements()) if (!e.expression()) cols.add(e.columnOrExpression());
            autoName.suggest(table.name(), cols, "idx");
        }
        contentChanged();
    }

    @Override
    protected List<DdlStatement> buildStatements() {
        var elems = elements();
        if (elems.isEmpty()) throw new IllegalArgumentException("Add at least one key column or expression.");
        var n = name.getText().trim();
        if (existing != null && n.isEmpty()) throw new IllegalArgumentException("The index needs a name.");
        if (existing != null && existing.constraintName() != null) {
            throw new IllegalArgumentException("This index backs constraint " + existing.constraintName()
                    + ". Edit the constraint instead.");
        }
        var m = (String) method.getSelectedItem();
        var btree = "btree".equals(m);
        Integer ff = null;
        var ffText = fillfactor.getText().trim();
        if (!ffText.isEmpty()) {
            try {
                ff = Integer.parseInt(ffText);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Fill factor must be a whole number between 10 and 100.");
            }
            if (ff < 10 || ff > 100) throw new IllegalArgumentException("Fill factor must be between 10 and 100.");
        }
        var inc = include.isEnabled() ? include.values() : List.<String>of();
        var conc = concurrently.isSelected();
        var spec = new IndexSpec(table.schema(), table.name(), n.isEmpty() ? null : n, elems, m,
                btree && unique.isSelected(), inc, DesignForms.blankToNull(where.getText()), ff, conc);
        var out = new ArrayList<DdlStatement>();
        if (existing != null) out.add(DdlBuilder.dropIndex(table.schema(), table.name(), existing.name(), conc, false));
        out.add(DdlBuilder.createIndex(spec));
        return out;
    }

    private List<IndexElement> elements() {
        var out = new ArrayList<IndexElement>();
        for (var r = 0; r < keys.getRowCount(); r++) {
            var v = keys.getValueAt(r, 0);
            var text = v == null ? "" : v.toString().trim();
            if (text.isEmpty()) continue;
            var desc = Boolean.TRUE.equals(keys.getValueAt(r, 1));
            var nulls = String.valueOf(keys.getValueAt(r, 2));
            Boolean nullsFirst = nulls.equals(NULLS[1]) ? Boolean.TRUE : nulls.equals(NULLS[2]) ? Boolean.FALSE : null;
            var column = columnNames.contains(DesignForms.unquote(text));
            out.add(new IndexElement(column ? DesignForms.unquote(text) : text, !column, desc, nullsFirst));
        }
        return out;
    }

    private void moveKey(int delta) {
        stopEditing();
        var i = keyTable.getSelectedRow();
        var j = i + delta;
        if (i < 0 || j < 0 || j >= keys.getRowCount()) return;
        keys.moveRow(i, i, j);
        keyTable.setRowSelectionInterval(j, j);
    }

    private void stopEditing() {
        if (keyTable.isEditing()) keyTable.getCellEditor().stopCellEditing();
    }

    private static final Pattern NULLS_SUFFIX = Pattern.compile("(?i)\\s+NULLS\\s+(FIRST|LAST)$");
    private static final Pattern DIR_SUFFIX = Pattern.compile("(?i)\\s+(ASC|DESC)$");

    /** "lower(email) DESC NULLS LAST" → [expr, desc, nulls]. */
    private static Object[] parseKey(String key) {
        var k = key.trim();
        var nulls = NULLS[0];
        var m = NULLS_SUFFIX.matcher(k);
        if (m.find()) {
            nulls = m.group(1).equalsIgnoreCase("FIRST") ? NULLS[1] : NULLS[2];
            k = k.substring(0, m.start());
        }
        var desc = false;
        var d = DIR_SUFFIX.matcher(k);
        if (d.find()) {
            desc = d.group(1).equalsIgnoreCase("DESC");
            k = k.substring(0, d.start());
        }
        return new Object[]{k, desc, nulls};
    }

    private static String parseFillfactor(String definition) {
        if (definition == null) return "";
        var m = Pattern.compile("(?i)fillfactor\\s*=\\s*'?(\\d+)'?").matcher(definition.toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) : "";
    }
}
