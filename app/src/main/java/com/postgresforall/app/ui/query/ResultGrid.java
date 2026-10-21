package com.postgresforall.app.ui.query;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.core.model.QueryResult.Column;
import com.postgresforall.core.service.CellText;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One result set: a title strip and a sortable, copyable grid. */
public final class ResultGrid extends JPanel {
    private final List<Column> columns;
    private final List<Object[]> rows;
    private final JTable table;
    private final TableRowSorter<Model> sorter;

    public ResultGrid(int index, String statement, List<Column> columns, List<Object[]> rows) {
        super(new BorderLayout());
        this.columns = columns;
        this.rows = rows;
        setBorder(BorderFactory.createLineBorder(new java.awt.Color(221, 221, 221)));

        var strip = new JPanel(new BorderLayout(12, 0));
        strip.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        var title = new JLabel("Result set " + index + " — " + truncate(statement, 80));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        var count = new JLabel(String.format("%,d row(s)", rows.size()));
        count.setForeground(Palette.DIM_GRAY);
        strip.add(title, BorderLayout.CENTER);
        strip.add(count, BorderLayout.EAST);
        add(strip, BorderLayout.NORTH);

        var model = new Model();
        table = new JTable(model);
        sorter = new TableRowSorter<>(model);
        for (var c = 0; c < columns.size(); c++) sorter.setComparator(c, CELL_ORDER);
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowGrid(true);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(true);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        sizeColumns();

        table.getActionMap().put("copy", action(this::copySelection));
        table.getInputMap().put(Ui.menuKey(KeyEvent.VK_C), "copy");
        table.getInputMap().put(Ui.menuKey(KeyEvent.VK_C, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "copyRow");
        table.getActionMap().put("copyRow", action(this::copyRows));

        table.setComponentPopupMenu(gridMenu());
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeHeaderMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeHeaderMenu(e); }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public JTable table() {
        return table;
    }

    public List<Object[]> rows() {
        return rows;
    }

    // ---- Model / rendering ----------------------------------------------

    final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.size(); }
        @Override public String getColumnName(int c) { return columns.get(c).name(); }
        @Override public Class<?> getColumnClass(int c) { return Object.class; }
        @Override public Object getValueAt(int r, int c) {
            var row = rows.get(r);
            return c < row.length ? row[c] : null;
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(t, value == null ? "NULL" : CellText.of(value), sel, focus, row, col);
            setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (value == null) {
                setFont(getFont().deriveFont(Font.ITALIC));
                if (!sel) setForeground(Palette.GRAY);
            } else if (!sel) {
                setForeground(t.getForeground());
            }
            return this;
        }
    }

    /** Nulls first; same-kind values by their natural order; numbers numerically; else as text. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final Comparator<Object> CELL_ORDER = (a, b) -> {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            try {
                return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString()));
            } catch (NumberFormatException ex) { // NaN / Infinity
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
        }
        if (a instanceof Comparable ca && a.getClass() == b.getClass()) return ca.compareTo(b);
        return CellText.of(a).compareToIgnoreCase(CellText.of(b));
    };

    private void sizeColumns() {
        var fm = table.getFontMetrics(table.getFont());
        var headerFm = table.getTableHeader().getFontMetrics(table.getTableHeader().getFont());
        var sample = Math.min(rows.size(), 300);
        for (var c = 0; c < columns.size(); c++) {
            var w = headerFm.stringWidth(columns.get(c).name()) + 28;
            for (var r = 0; r < sample; r++) {
                var row = rows.get(r);
                var v = c < row.length ? row[c] : null;
                var text = v == null ? "NULL" : CellText.of(v);
                if (text.length() > 120) text = text.substring(0, 120);
                w = Math.max(w, fm.stringWidth(text) + 16);
            }
            table.getColumnModel().getColumn(c).setPreferredWidth(Math.max(40, Math.min(600, w)));
        }
    }

    // ---- Copy -------------------------------------------------------------

    private void copySelection() {
        copy(false, false);
    }

    private void copyRows() {
        copy(true, false);
    }

    private void copyWithHeaders() {
        copy(false, true);
    }

    /** Selected cells as tab-separated text (whole rows when {@code fullRows}). */
    private void copy(boolean fullRows, boolean headers) {
        var selRows = table.getSelectedRows();
        if (selRows.length == 0) return;
        int[] selCols;
        if (fullRows || table.getRowSelectionAllowed() && !table.getColumnSelectionAllowed()) {
            selCols = new int[table.getColumnCount()];
            for (var i = 0; i < selCols.length; i++) selCols[i] = i;
        } else {
            selCols = table.getSelectedColumns();
            if (selCols.length == 0) return;
        }
        var sb = new StringBuilder();
        if (headers) {
            var hs = new ArrayList<String>();
            for (var vc : selCols) hs.add(table.getColumnName(vc));
            sb.append(String.join("\t", hs)).append('\n');
        }
        for (var vr : selRows) {
            var cells = new ArrayList<String>();
            for (var vc : selCols) {
                var v = table.getValueAt(vr, vc);
                cells.add(v == null ? "NULL" : CellText.of(v));
            }
            sb.append(String.join("\t", cells)).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    // ---- Menus ------------------------------------------------------------

    private JPopupMenu gridMenu() {
        var m = new JPopupMenu();
        var copy = new JMenuItem("Copy");
        copy.setFont(copy.getFont().deriveFont(Font.BOLD));
        copy.setAccelerator(Ui.menuKey(KeyEvent.VK_C));
        copy.addActionListener(e -> copySelection());
        var copyRow = new JMenuItem("Copy row");
        copyRow.setAccelerator(Ui.menuKey(KeyEvent.VK_C, java.awt.event.InputEvent.SHIFT_DOWN_MASK));
        copyRow.addActionListener(e -> copyRows());
        var copyHeaders = new JMenuItem("Copy with headers");
        copyHeaders.addActionListener(e -> copyWithHeaders());
        var cellMode = new JMenuItem("Select cell");
        cellMode.addActionListener(e -> table.setCellSelectionEnabled(true));
        var rowMode = new JMenuItem("Select row");
        rowMode.addActionListener(e -> {
            table.setColumnSelectionAllowed(false);
            table.setRowSelectionAllowed(true);
        });
        var all = new JMenuItem("Select all");
        all.setAccelerator(Ui.menuKey(KeyEvent.VK_A));
        all.addActionListener(e -> table.selectAll());
        m.add(copy);
        m.add(copyRow);
        m.add(copyHeaders);
        m.addSeparator();
        m.add(cellMode);
        m.add(rowMode);
        m.add(all);
        return m;
    }

    private void maybeHeaderMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        var viewCol = table.columnAtPoint(e.getPoint());
        if (viewCol < 0) return;
        var modelCol = table.convertColumnIndexToModel(viewCol);
        var m = new JPopupMenu();
        var asc = new JMenuItem("Sort ascending");
        asc.addActionListener(a -> sorter.setSortKeys(List.of(new RowSorter.SortKey(modelCol, SortOrder.ASCENDING))));
        var desc = new JMenuItem("Sort descending");
        desc.addActionListener(a -> sorter.setSortKeys(List.of(new RowSorter.SortKey(modelCol, SortOrder.DESCENDING))));
        var clear = new JMenuItem("Clear sort");
        clear.addActionListener(a -> sorter.setSortKeys(null));
        var find = new JMenuItem("Find in column…");
        find.addActionListener(a -> new ColumnFindDialog(SwingUtilities.getWindowAncestor(this), this, modelCol).setVisible(true));
        m.add(asc);
        m.add(desc);
        m.add(clear);
        m.addSeparator();
        m.add(find);
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    /** Selects and scrolls to the model row / column. */
    void reveal(int modelRow, int modelCol) {
        var vr = table.convertRowIndexToView(modelRow);
        var vc = table.convertColumnIndexToView(modelCol);
        if (vr < 0 || vc < 0) return;
        table.changeSelection(vr, vc, false, false);
        table.scrollRectToVisible(table.getCellRect(vr, vc, true));
        table.requestFocusInWindow();
    }

    String columnName(int modelCol) {
        return columns.get(modelCol).name();
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        };
    }

    static String truncate(String text, int max) {
        var single = text.replace("\r", " ").replace("\n", " ").trim().replaceAll(" {2,}", " ");
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    /** For callers that want the grid to keep a minimum visible height. */
    public JComponent withMinHeight(int h) {
        setMinimumSize(new java.awt.Dimension(100, h));
        setPreferredSize(new java.awt.Dimension(400, h));
        return this;
    }
}
