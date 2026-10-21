package com.postgresforall.app.ui.dialogs;

import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.ui.PropertyTreeTable;
import com.postgresforall.app.ui.Toast;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.TableObjects.PropertyNode;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * Table properties as an expandable tree: sections (General, Storage, Structure,
 * Activity, Your access) whose rows drill down into columns, indexes,
 * constraints, triggers, inbound foreign keys, the owner role, sizes, policies
 * and grants.
 */
public final class TablePropertiesDialog extends BaseDialog {
    private final PropertyTreeTable tree = new PropertyTreeTable();

    public TablePropertiesDialog(Component owner, String qualifiedName, List<PropertyNode> groups) {
        super(owner, "Table Properties — " + qualifiedName);
        tree.setRoots(groups);

        var hint = new JLabel("Rows with an arrow expand for more detail.");
        hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        var bar = new JPanel(new MigLayout("insets 0, gapx 4", "[]push[][]"));
        bar.add(hint);
        bar.add(link("Expand all", tree::expandAll));
        bar.add(link("Collapse all", tree::collapseAll));

        var scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(680, 520));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        var content = new JPanel(new BorderLayout(0, 6));
        content.add(bar, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        build(Icons.get("table", Icons.LARGE), qualifiedName, null, content);
        addPrimaryButton("Close", this::accept);
        addButton("Copy all", () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(tree.asText()), null);
            Toast.show(this, "Copied", "Table properties copied to the clipboard.", true);
        });
        setResizable(true);
        setMinimumSize(new Dimension(520, 420));
    }

    private static JButton link(String text, Runnable action) {
        var b = new JButton(text);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        b.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** The tree, for tests. */
    public PropertyTreeTable tree() {
        return tree;
    }
}
