package com.postgresforall.app.ui.query;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.core.service.GridSearch;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;

/** Non-modal "Find in column" over one result grid; Enter finds the next match, wrapping. */
final class ColumnFindDialog extends JDialog {
    private final ResultGrid grid;
    private final int column;
    private final JTextField entry = new JTextField(24);
    private final JCheckBox matchNulls = new JCheckBox("Match nulls as 'NULL'");
    private final JLabel status = new JLabel(" ");
    private int cursorRow = -1;

    ColumnFindDialog(Window owner, ResultGrid grid, int column) {
        super(owner, "Find in column", ModalityType.MODELESS);
        this.grid = grid;
        this.column = column;

        var header = new JLabel(String.format("Find in column '%s' (%,d rows)", grid.columnName(column), grid.rows().size()));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        status.setForeground(Palette.DIM_GRAY);

        var center = new JPanel(new BorderLayout(0, 6));
        center.add(entry, BorderLayout.NORTH);
        center.add(matchNulls, BorderLayout.CENTER);
        center.add(status, BorderLayout.SOUTH);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var find = new JButton("Find next");
        find.addActionListener(e -> doFind());
        var close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(find);
        buttons.add(close);

        var root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        root.add(header, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(find);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    private void doFind() {
        var needle = entry.getText();
        var idx = GridSearch.findNext(grid.rows(), column, needle, cursorRow, true, matchNulls.isSelected());
        if (idx < 0) {
            status.setText("No match for \"" + needle.trim() + "\" in '" + grid.columnName(column) + "'.");
            return;
        }
        cursorRow = idx;
        status.setText(String.format("Row %,d of %,d", idx + 1, grid.rows().size()));
        grid.reveal(idx, column);
        entry.requestFocusInWindow();
    }
}
