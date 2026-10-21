package com.postgresforall.app.ui.explorer;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;

import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Component;

/** Bold name plus a dimmed detail, coloured per node state; no folder/leaf icons. */
final class ExplorerRenderer extends DefaultTreeCellRenderer {

    ExplorerRenderer() {
        setLeafIcon(null);
        setOpenIcon(null);
        setClosedIcon(null);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                  boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
        if (!(value instanceof ExplorerNode n)) return this;

        var fg = sel ? UIManager.getColor("Tree.selectionForeground") : n.foreground;
        var nameColor = fg == null ? "" : " color='" + Palette.hex(fg) + "'";
        var detailColor = sel ? nameColor : " color='" + Palette.hex(Palette.subtle()) + "'";
        var italic = n.kind == ExplorerNode.Kind.LOADING || n.kind == ExplorerNode.Kind.PLACEHOLDER;
        var name = Ui.escapeHtml(n.name);
        var sb = new StringBuilder("<html><span style='white-space:nowrap'>");
        sb.append("<font").append(nameColor).append(">")
                .append(italic ? "<i>" + name + "</i>" : "<b>" + name + "</b>").append("</font>");
        if (n.detail != null && !n.detail.isEmpty()) {
            sb.append("&nbsp;&nbsp;<font").append(detailColor).append(">").append(Ui.escapeHtml(n.detail)).append("</font>");
        }
        sb.append("</span></html>");
        setText(sb.toString());
        setToolTipText(n.toolTip);
        setIcon(null);
        return this;
    }
}
