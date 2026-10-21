package com.postgresforall.app.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.TableObjects.PropertyNode;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A two-column property grid (Property | Value) whose rows expand like a tree:
 * click the chevron or double-click a row, or use → / ← / Enter. Top-level
 * rows are section headers and start expanded. Ctrl/⌘ + C copies the selected rows.
 */
public final class PropertyTreeTable extends JTable {
    private static final int INDENT = 18;
    private static final int CHEVRON = 12;

    private record Row(PropertyNode node, int depth, Row parent) { }

    private final List<Row> rows = new ArrayList<>();
    private final Map<PropertyNode, Boolean> expanded = new IdentityHashMap<>();
    private List<PropertyNode> roots = List.of();

    private final AbstractTableModel model = new AbstractTableModel() {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int c) { return c == 0 ? "Property" : "Value"; }
        @Override public Object getValueAt(int r, int c) {
            var n = rows.get(r).node();
            return c == 0 ? n.name() : n.value() == null ? "" : n.value();
        }
    };

    public PropertyTreeTable() {
        setModel(model);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setShowVerticalLines(false);
        setFillsViewportHeight(true);
        setRowHeight(Math.max(getRowHeight(), 24));
        getTableHeader().setReorderingAllowed(false);
        getColumnModel().getColumn(0).setPreferredWidth(260);
        getColumnModel().getColumn(1).setPreferredWidth(380);
        getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        getColumnModel().getColumn(1).setCellRenderer(new ValueRenderer());

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                var r = rowAtPoint(e.getPoint());
                if (r < 0 || columnAtPoint(e.getPoint()) != 0) return;
                var row = rows.get(r);
                var x = e.getX() - getCellRect(r, 0, false).x;
                var chevronStart = 4 + row.depth() * INDENT;
                if (row.node().hasChildren() && x >= chevronStart - 2 && x <= chevronStart + CHEVRON + 4) toggle(r);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                var r = rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && r >= 0 && rows.get(r).node().hasChildren()) toggle(r);
            }
        });

        bind(KeyEvent.VK_RIGHT, 0, "pfa-expand", this::expandOrDescend);
        bind(KeyEvent.VK_LEFT, 0, "pfa-collapse", this::collapseOrAscend);
        bind(KeyEvent.VK_ENTER, 0, "pfa-toggle", () -> {
            var r = getSelectedRow();
            if (r >= 0 && rows.get(r).node().hasChildren()) toggle(r);
        });
        bind(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(), "pfa-copy", this::copySelection);
    }

    private void bind(int key, int modifiers, String name, Runnable action) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, modifiers), name);
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, modifiers), name);
        getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /** Shows {@code nodes} as the top level; top-level rows start expanded, everything below collapsed. */
    public void setRoots(List<PropertyNode> nodes) {
        roots = List.copyOf(nodes);
        expanded.clear();
        for (var n : roots) expanded.put(n, true);
        rebuild();
    }

    public void expandAll() {
        walk(roots, n -> expanded.put(n, true));
        rebuild();
    }

    /** Collapses everything below the section headers. */
    public void collapseAll() {
        expanded.clear();
        for (var n : roots) expanded.put(n, true);
        rebuild();
    }

    /** Visible row count (for tests). */
    public int visibleRows() {
        return rows.size();
    }

    /** Expands the first row named {@code name} that has children, if visible. True if found. */
    public boolean expandRow(String name) {
        for (var i = 0; i < rows.size(); i++) {
            var n = rows.get(i).node();
            if (n.name().equals(name) && n.hasChildren()) {
                if (!isExpanded(n)) toggle(i);
                return true;
            }
        }
        return false;
    }

    private static void walk(List<PropertyNode> nodes, java.util.function.Consumer<PropertyNode> f) {
        for (var n : nodes) {
            f.accept(n);
            if (n.hasChildren()) walk(n.children(), f);
        }
    }

    private boolean isExpanded(PropertyNode n) {
        return Boolean.TRUE.equals(expanded.get(n));
    }

    private void rebuild() {
        var selected = getSelectedRow() >= 0 && getSelectedRow() < rows.size() ? rows.get(getSelectedRow()).node() : null;
        rows.clear();
        for (var n : roots) add(n, 0, null);
        model.fireTableDataChanged();
        if (selected != null) {
            for (var i = 0; i < rows.size(); i++) {
                if (rows.get(i).node() == selected) {
                    setRowSelectionInterval(i, i);
                    scrollRectToVisible(getCellRect(i, 0, true));
                    break;
                }
            }
        }
    }

    private void add(PropertyNode n, int depth, Row parent) {
        var row = new Row(n, depth, parent);
        rows.add(row);
        if (n.hasChildren() && isExpanded(n)) {
            for (var c : n.children()) add(c, depth + 1, row);
        }
    }

    private void toggle(int r) {
        var n = rows.get(r).node();
        setRowSelectionInterval(r, r);
        expanded.put(n, !isExpanded(n));
        rebuild();
        // Bring the newly shown children into view.
        if (isExpanded(n)) {
            var last = r + countVisibleBelow(n);
            scrollRectToVisible(getCellRect(Math.min(last, rows.size() - 1), 0, true));
            scrollRectToVisible(getCellRect(r, 0, true));
        }
    }

    private int countVisibleBelow(PropertyNode n) {
        if (!n.hasChildren() || !isExpanded(n)) return 0;
        var count = 0;
        for (var c : n.children()) count += 1 + countVisibleBelow(c);
        return count;
    }

    private void expandOrDescend() {
        var r = getSelectedRow();
        if (r < 0) return;
        var n = rows.get(r).node();
        if (!n.hasChildren()) return;
        if (!isExpanded(n)) toggle(r);
        else if (r + 1 < rows.size()) setRowSelectionInterval(r + 1, r + 1);
    }

    private void collapseOrAscend() {
        var r = getSelectedRow();
        if (r < 0) return;
        var row = rows.get(r);
        if (row.node().hasChildren() && isExpanded(row.node()) && row.depth() > 0) {
            toggle(r);
            return;
        }
        if (row.parent() != null) {
            var p = rows.indexOf(row.parent());
            if (p >= 0) {
                setRowSelectionInterval(p, p);
                scrollRectToVisible(getCellRect(p, 0, true));
            }
        }
    }

    private void copySelection() {
        var sb = new StringBuilder();
        for (var r : getSelectedRows()) {
            var row = rows.get(r);
            sb.append("  ".repeat(row.depth())).append(row.node().name());
            if (row.node().value() != null) sb.append(": ").append(row.node().value());
            sb.append('\n');
        }
        if (!sb.isEmpty()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    /** The whole tree as indented text, expanded or not. */
    public String asText() {
        var sb = new StringBuilder();
        appendText(sb, roots, 0);
        return sb.toString();
    }

    private static void appendText(StringBuilder sb, List<PropertyNode> nodes, int depth) {
        for (var n : nodes) {
            if (depth == 0 && !sb.isEmpty()) sb.append('\n');
            sb.append("  ".repeat(depth)).append(depth == 0 ? "[" + n.name() + "]" : n.name());
            if (n.value() != null) sb.append(": ").append(n.value());
            sb.append('\n');
            if (n.hasChildren()) appendText(sb, n.children(), depth + 1);
        }
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        var r = rowAtPoint(e.getPoint());
        if (r < 0) return null;
        var n = rows.get(r).node();
        if (columnAtPoint(e.getPoint()) == 1 && n.value() != null && n.value().length() > 40) {
            return "<html><body style='width:420px'>" + Ui.escapeHtml(n.value()) + "</body></html>";
        }
        if (n.hasChildren()) return isExpanded(n) ? "Click the arrow to collapse" : "Click the arrow (or double-click) for details";
        return null;
    }

    // ---- Rendering -------------------------------------------------------

    private static Icon iconFor(PropertyNode n) {
        if (n.icon() == null) return null;
        return switch (n.icon()) {
            case "column" -> Icons.get("columns-3", Icons.SMALL);
            case "key" -> Icons.tinted("key-round", Icons.SMALL, Palette::caution);
            case "fk" -> Icons.tinted("link", Icons.SMALL, Palette::notice);
            case "index" -> Icons.get("list-ordered", Icons.SMALL);
            case "constraint" -> Icons.get("list-checks", Icons.SMALL);
            case "trigger" -> Icons.tinted("zap", Icons.SMALL, Palette::caution);
            case "role" -> Icons.get("user", Icons.SMALL);
            case "policy" -> Icons.get("shield", Icons.SMALL);
            default -> null;
        };
    }

    /** Indent + chevron + optional kind icon, painted as one icon so the label stays a plain JLabel. */
    private record TreeIcon(int depth, FlatSVGIcon chevron, Icon kind) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            var cx = x + depth * INDENT;
            if (chevron != null) chevron.paintIcon(c, g, cx, y + (getIconHeight() - CHEVRON) / 2);
            if (kind != null) kind.paintIcon(c, g, cx + CHEVRON + 4, y + (getIconHeight() - kind.getIconHeight()) / 2);
        }

        @Override
        public int getIconWidth() {
            return depth * INDENT + CHEVRON + 4 + (kind != null ? kind.getIconWidth() : 0);
        }

        @Override
        public int getIconHeight() {
            return Math.max(CHEVRON, kind != null ? kind.getIconHeight() : 0);
        }
    }

    private final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, value, sel, focus, r, c);
            var row = rows.get(r);
            var n = row.node();
            var chevron = n.hasChildren()
                    ? Icons.get(isExpanded(n) ? "chevron-down" : "chevron-right", CHEVRON) : null;
            setIcon(new TreeIcon(row.depth(), chevron, iconFor(n)));
            setIconTextGap(6);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 4));
            var header = row.depth() == 0;
            setFont(t.getFont().deriveFont(header ? Font.BOLD : Font.PLAIN));
            if (!sel) {
                setBackground(header ? Palette.headerStrip() : t.getBackground());
                setForeground(row.depth() >= 2 ? Palette.muted() : t.getForeground());
            }
            return this;
        }
    }

    private final class ValueRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, value, sel, focus, r, c);
            var row = rows.get(r);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 4));
            setFont(t.getFont());
            if (!sel) {
                setBackground(row.depth() == 0 ? Palette.headerStrip() : t.getBackground());
                // A row that expands shows a summary: keep it quieter than real values.
                setForeground(row.node().hasChildren() ? Palette.muted() : UIManager.getColor("Table.foreground"));
            }
            return this;
        }
    }
}
