package com.postgresforall.app.ui.explorer;

import com.postgresforall.core.service.ServerSession;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Color;
import java.util.List;

/**
 * One Object Explorer row. A node with a {@link #loader} is expandable; the
 * loader runs once (off the EDT) on first expansion and supplies the children.
 * To refresh, clear the children and reset {@link #loaded}.
 */
public final class ExplorerNode extends DefaultMutableTreeNode {

    public enum Kind {
        ROOT, SERVER, DATABASES_FOLDER, DATABASE, SCHEMAS_FOLDER, SCHEMA,
        TABLES_FOLDER, TABLE, COLUMNS_FOLDER, COLUMN,
        VIEWS_FOLDER, VIEW, FUNCTIONS_FOLDER, FUNCTION,
        SECURITY_FOLDER, ROLES_FOLDER, ROLE,
        LOADING, PLACEHOLDER
    }

    /** Builds a node's children. Runs off the EDT; may block on queries or dialogs. */
    @FunctionalInterface
    public interface Loader {
        List<ExplorerNode> load(ExplorerNode node) throws Exception;
    }

    public final Kind kind;
    public volatile String name;
    public volatile String detail;
    public final Object payload;
    /**
     * Database this node lives in, set on every node under a database so
     * scripting and New Query target it. Null on server-wide nodes.
     */
    public final String databaseName;
    /** Server this node belongs to, set on the server node and everything under it. */
    public final ServerSession server;
    public volatile Color foreground;
    public volatile String toolTip;
    public volatile Loader loader;
    public volatile boolean loaded;
    volatile boolean loading;
    /** Expand as soon as it's in the tree (connected server nodes). */
    boolean expandOnShow;

    public ExplorerNode(Kind kind, String name, String detail, Object payload, String databaseName,
                        ServerSession server) {
        this.kind = kind;
        this.name = name;
        this.detail = detail;
        this.payload = payload;
        this.databaseName = databaseName;
        this.server = server;
    }

    public static ExplorerNode folder(Kind kind, String name, String databaseName, ServerSession server) {
        var n = new ExplorerNode(kind, name, null, null, databaseName, server);
        n.loaded = true;
        return n;
    }

    public static ExplorerNode loadingRow() {
        return new ExplorerNode(Kind.LOADING, "Loading...", null, null, null, null);
    }

    public static ExplorerNode placeholder(String text, Color color) {
        var n = new ExplorerNode(Kind.PLACEHOLDER, text, null, null, null, null);
        n.foreground = color;
        return n;
    }

    /** Gives the node an expander until its loader runs. */
    public ExplorerNode withLoader(Loader l) {
        loader = l;
        loaded = false;
        removeAllChildren();
        add(loadingRow());
        return this;
    }

    public ExplorerNode addAll(List<ExplorerNode> kids) {
        for (var k : kids) add(k);
        return this;
    }

    @Override
    public boolean isLeaf() {
        return getChildCount() == 0;
    }

    @Override
    public String toString() {
        return detail == null ? name : name + "  " + detail;
    }
}
