package com.postgresforall.app.ui.explorer;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.explorer.ExplorerNode.Kind;
import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.SchemaNodes.DatabaseInfo;
import com.postgresforall.core.model.SchemaNodes.SchemaInfo;
import com.postgresforall.core.model.SchemaNodes.TableInfo;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.ConnectionErrors;
import com.postgresforall.core.service.ConnectionLease;
import com.postgresforall.core.service.ConnectionManager;
import com.postgresforall.core.model.TableObjects.ConstraintType;
import com.postgresforall.core.service.DatabaseOperations;
import com.postgresforall.core.service.SchemaInspector;
import com.postgresforall.core.service.TableInspector;
import com.postgresforall.core.service.ServerRegistry;
import com.postgresforall.core.service.ServerSession;
import com.postgresforall.core.service.ServerSession.State;

import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Backs the Object Explorer tree. Two layouts:
 * <ul>
 *   <li>Per server: one root per server in the {@link ServerRegistry}, like SSMS's
 *       registered servers.</li>
 *   <li>Combined (AppConfig.combineServerDatabases): a single root for the login
 *       ("home") server whose Databases folder lists every server's databases,
 *       grouped by server and tagged with host:port. A server not logged into yet
 *       is a "double-click to connect" entry in that folder.</li>
 * </ul>
 * Every node carries its server, so actions on a node reach the right server
 * in either layout.
 *
 * <p>Server-wide queries (database list, roles) run on that server's primary
 * connection. Everything below a database node runs on a connection to that
 * database, because pg_class / pg_namespace are per-database catalogs.
 *
 * <p>Each browsed database gets a {@link ConnectionLease} on first expansion and
 * keeps it until its server's node is rebuilt or the explorer is closed.
 *
 * <p>Threading: tree structure is only touched on the EDT; loaders and server
 * events run on background threads (server events one at a time, in order).
 */
public final class ObjectExplorer implements AutoCloseable {

    /**
     * Opens a lease for a database on a server, prompting for alternate
     * credentials if the login role can't reach it. Null when the user cancels.
     * Called off the EDT.
     */
    @FunctionalInterface
    public interface Acquirer {
        ConnectionLease acquire(ServerSession session, String dbName) throws Exception;
    }

    /** Lease + inspector backing everything under one database node. */
    private record DatabaseContext(ConnectionLease lease, SchemaInspector inspector) { }

    /** Per-server state: primary inspector plus the database leases opened while browsing. */
    private static final class ServerTree {
        final ServerSession session;
        final ConnectionManager manager;
        final SchemaInspector inspector;
        final Map<String, DatabaseContext> contexts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final ReentrantLock lock = new ReentrantLock();

        ServerTree(ServerSession session, ConnectionManager manager) {
            this.session = session;
            this.manager = manager;
            this.inspector = new SchemaInspector(manager);
        }
    }

    private final ServerRegistry registry;
    private final Acquirer acquire;
    private final boolean combined;
    private final ServerSession home;
    private final Map<ServerSession, ServerTree> trees = new ConcurrentHashMap<>();
    private final ExecutorService events = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final ExplorerNode root = new ExplorerNode(Kind.ROOT, "Servers", null, null, null, null);
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final ExplorerRenderer renderer = new ExplorerRenderer();
    private int hoverRow = -1;
    private String filter = "";
    /** Draws a full-width hover band behind the row under the mouse. */
    private final JTree tree = new JTree(model) {
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (hoverRow >= 0 && !isRowSelected(hoverRow)) {
                var r = getRowBounds(hoverRow);
                if (r != null) {
                    g.setColor(Palette.hoverRow());
                    g.fillRect(0, r.y, getWidth(), r.height);
                }
            }
            super.paintComponent(g);
        }
    };
    private final Consumer<ServerSession> stateListener = s -> events.submit(() -> onSessionStateChanged(s));
    private final Consumer<ServerSession> infoListener = s -> events.submit(() -> onSessionInfoChanged(s));
    private final Consumer<ServerSession> addedListener = s -> events.submit(() -> onSessionAdded(s));
    private volatile ExplorerNode combinedFolder;
    private volatile boolean closed;

    public ObjectExplorer(ServerRegistry registry, Acquirer acquire, boolean combineDatabases) {
        this.registry = registry;
        this.acquire = acquire;
        this.combined = combineDatabases;
        var sessions = registry.sessions();
        this.home = sessions.isEmpty() ? null : sessions.get(0);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(renderer);
        tree.setOpaque(false);
        tree.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                var row = tree.getClosestRowForLocation(e.getX(), e.getY());
                var r = row >= 0 ? tree.getRowBounds(row) : null;
                setHover(r != null && e.getY() >= r.y && e.getY() < r.y + r.height ? row : -1);
            }
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                setHover(-1);
            }
        });
        tree.setToggleClickCount(0); // double-click is an action, not a toggle
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                if (e.getPath().getLastPathComponent() instanceof ExplorerNode n) startLoad(n);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
                // nothing to do
            }
        });

        for (var s : sessions) addServer(s);
        registry.onSessionAdded(addedListener);
    }

    public JTree tree() {
        return tree;
    }

    private void setHover(int row) {
        if (row == hoverRow) return;
        hoverRow = row;
        tree.repaint();
    }

    // ---- Filter (search box) ---------------------------------------------

    /**
     * Highlights loaded nodes whose name contains {@code text} (others dim),
     * expands down to each match and selects the first. Empty clears it.
     * Unloaded branches aren't searched — expand them first. Call on the EDT.
     */
    public void setFilter(String text) {
        filter = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        renderer.setFilter(filter);
        // Matches render bold, so cached row widths are stale: re-measure loaded nodes.
        var all = root.preorderEnumeration();
        while (all.hasMoreElements()) {
            if (all.nextElement() instanceof ExplorerNode n && n != root) model.nodeChanged(n);
        }
        if (!filter.isEmpty()) {
            var hits = matches();
            for (var n : hits.subList(0, Math.min(200, hits.size()))) {
                var parent = (ExplorerNode) n.getParent();
                if (parent != null) tree.expandPath(pathOf(parent));
            }
            if (!hits.isEmpty()) select(hits.get(0));
        }
        tree.repaint();
    }

    /** Selects the next match after the current selection, wrapping. Returns false when nothing matches. */
    public boolean nextMatch() {
        var hits = matches();
        if (hits.isEmpty()) return false;
        var current = selectedNode();
        var i = current == null ? -1 : hits.indexOf(current);
        select(hits.get((i + 1) % hits.size()));
        return true;
    }

    private java.util.List<ExplorerNode> matches() {
        var hits = new ArrayList<ExplorerNode>();
        if (filter.isEmpty()) return hits;
        var e = root.preorderEnumeration();
        while (e.hasMoreElements()) {
            if (e.nextElement() instanceof ExplorerNode n && n != root && ExplorerRenderer.matches(n, filter)) hits.add(n);
        }
        return hits;
    }

    private void select(ExplorerNode n) {
        var path = pathOf(n);
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    /** Collapses everything below the top-level servers. Call on the EDT. */
    public void collapseAll() {
        for (var row = tree.getRowCount() - 1; row >= 0; row--) {
            var p = tree.getPathForRow(row);
            if (p != null && p.getPathCount() > 2) tree.collapseRow(row);
        }
    }

    public ExplorerNode selectedNode() {
        var p = tree.getSelectionPath();
        return p != null && p.getLastPathComponent() instanceof ExplorerNode n ? n : null;
    }

    /** True when {@code session} lives inside the home server's Databases folder. */
    private boolean isGrouped(ServerSession session) {
        return combined && session != home;
    }

    // ---- Session events (events thread) ----------------------------------

    private void onSessionAdded(ServerSession session) {
        if (closed) return;
        Ui.edt(() -> addServer(session));
        if (isGrouped(session)) refreshGroup(session);
    }

    private void addServer(ServerSession session) {
        if (!isGrouped(session)) {
            var node = buildServerNode(session);
            model.insertNodeInto(node, root, root.getChildCount());
            if (root.getChildCount() == 1) model.nodeStructureChanged(root);
            showNode(node);
        }
        session.onStateChanged(stateListener);
        session.onInfoChanged(infoListener);
    }

    /** A label / hint arrived from discovery: retitle the node, keep its expanded children. */
    private void onSessionInfoChanged(ServerSession session) {
        if (closed) return;
        if (isGrouped(session)) {
            // A connected server's databases stay as they are; only its "connect" entry shows the label.
            if (session.state() != State.CONNECTED) refreshGroup(session);
            return;
        }
        Ui.edt(() -> {
            var node = rootFor(session);
            if (node != null) {
                node.detail = describeServer(session);
                model.nodeChanged(node);
            }
        });
    }

    private void onSessionStateChanged(ServerSession session) {
        if (closed) return;
        // Connecting is transient; the node is rebuilt when it settles.
        if (session.state() == State.CONNECTING) {
            Ui.edt(() -> {
                var node = isGrouped(session) ? groupEntryFor(session) : rootFor(session);
                if (node != null) {
                    node.detail = describeServer(session);
                    model.nodeChanged(node);
                }
            });
            return;
        }
        rebuildServer(session);
    }

    private ExplorerNode rootFor(ServerSession session) {
        for (var i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i) instanceof ExplorerNode n && n.server == session) return n;
        }
        return null;
    }

    private ExplorerNode groupEntryFor(ServerSession session) {
        var folder = combinedFolder;
        if (folder == null) return null;
        for (var i = 0; i < folder.getChildCount(); i++) {
            if (folder.getChildAt(i) instanceof ExplorerNode n && n.server == session && n.kind == Kind.SERVER) return n;
        }
        return null;
    }

    // ---- Inspectors ------------------------------------------------------

    /** Inspector for server-wide queries (databases, roles); null when not connected. */
    public SchemaInspector inspectorFor(ServerSession session) {
        var t = treeFor(session);
        return t == null ? null : t.inspector;
    }

    /**
     * Inspector bound to {@code dbName} on {@code session}, opening a lease the
     * first time. Null name, or the login database, gives the primary inspector.
     * Null result: the database couldn't be opened. Call off the EDT.
     */
    public SchemaInspector inspectorFor(ServerSession session, String dbName) throws Exception {
        var t = treeFor(session);
        if (t == null) return null;
        var login = session.login();
        if (dbName == null || dbName.isBlank() || (login != null && dbName.equalsIgnoreCase(login.database))) {
            return t.inspector;
        }
        var ctx = getOrOpenContext(t, dbName);
        return ctx == null ? null : ctx.inspector();
    }

    /**
     * Connection pool for {@code dbName} on {@code session} (the login database
     * when null), opening a lease the first time. Null when it can't be opened.
     * Call off the EDT.
     */
    public ConnectionManager managerFor(ServerSession session, String dbName) throws Exception {
        var t = treeFor(session);
        if (t == null) return null;
        if (dbName == null || dbName.isBlank()) {
            var login = session.login();
            if (login == null) return t.manager;
            dbName = login.database;
        }
        var ctx = getOrOpenContext(t, dbName);
        return ctx == null ? null : ctx.lease().manager();
    }

    /**
     * Selects {@code schema.table} in the same database as {@code near}, loading
     * the schema's children first if needed. False when the schema isn't shown.
     * Call on the EDT.
     */
    public boolean revealTable(ExplorerNode near, String schema, String table) {
        var db = near;
        while (db != null && db.kind != Kind.DATABASE) db = db.getParent() instanceof ExplorerNode p ? p : null;
        if (db == null) return false;
        ExplorerNode schemaNode = null;
        for (var folder : children(db)) {
            if (folder.kind != Kind.SCHEMAS_FOLDER) continue;
            for (var sn : children(folder)) if (sn.kind == Kind.SCHEMA && sn.name.equals(schema)) schemaNode = sn;
        }
        if (schemaNode == null) return false;
        var sn = schemaNode;
        Runnable select = () -> {
            for (var folder : children(sn)) {
                if (folder.kind != Kind.TABLES_FOLDER) continue;
                for (var tn : children(folder)) {
                    if (tn.kind == Kind.TABLE && tn.name.equals(table)) {
                        var path = pathOf(tn);
                        tree.setSelectionPath(path);
                        tree.scrollPathToVisible(path);
                    }
                }
            }
        };
        if (sn.loaded) {
            select.run();
        } else {
            afterLoad.put(sn, select);
            startLoad(sn);
        }
        return true;
    }

    private static List<ExplorerNode> children(ExplorerNode n) {
        var out = new ArrayList<ExplorerNode>();
        for (var i = 0; i < n.getChildCount(); i++) {
            if (n.getChildAt(i) instanceof ExplorerNode c) out.add(c);
        }
        return out;
    }

    private ServerTree treeFor(ServerSession session) {
        var mgr = session.manager();
        if (closed || mgr == null) return null;
        return trees.compute(session, (k, existing) ->
                existing != null && existing.manager == mgr ? existing : new ServerTree(session, mgr));
    }

    // ---- Rebuild ---------------------------------------------------------

    /**
     * Drops everything browsed on one server (releasing its database leases)
     * and rebuilds its node from the session's current state. Call off the EDT.
     */
    public void rebuildServer(ServerSession session) {
        if (closed) return;
        releaseTree(session);
        if (isGrouped(session)) {
            refreshGroup(session);
            return;
        }
        Ui.edt(() -> {
            if (combined) combinedFolder = null; // the home node (and its folder) is replaced below
            var old = rootFor(session);
            var fresh = buildServerNode(session);
            if (old != null) {
                var index = root.getIndex(old);
                model.removeNodeFromParent(old);
                model.insertNodeInto(fresh, root, index);
            } else {
                model.insertNodeInto(fresh, root, root.getChildCount());
            }
            showNode(fresh);
        });
    }

    private void releaseTree(ServerSession session) {
        var t = trees.remove(session);
        if (t == null) return;
        t.lock.lock();
        try {
            for (var ctx : t.contexts.values()) {
                try { ctx.lease().close(); } catch (RuntimeException ignored) { }
            }
            t.contexts.clear();
        } finally {
            t.lock.unlock();
        }
    }

    /** Reloads one node's children (context menu Refresh). Call on the EDT. */
    public void refreshNode(ExplorerNode node) {
        if (node.loader == null) return;
        node.loaded = false;
        node.loading = false;
        startLoad(node);
        tree.expandPath(pathOf(node));
    }

    // ---- Node building ---------------------------------------------------

    /** The grey text after a server's address: label plus version / state. */
    private static String describeServer(ServerSession session) {
        var label = session.server().label() == null ? "" : session.server().label() + " — ";
        var mgr = session.manager();
        return switch (session.state()) {
            case CONNECTED -> mgr == null ? label + "connected"
                    : label + "PostgreSQL " + mgr.serverVersion() + " ("
                    + (mgr.isSuperuser() ? mgr.profile().username + ", superuser" : mgr.profile().username) + ")";
            case CONNECTING -> label + "connecting…";
            case FAILED -> label + "connect failed — double-click to retry";
            case DISCONNECTED -> label + "not connected — double-click to connect";
        };
    }

    private ExplorerNode buildServerNode(ServerSession session) {
        var s = session.server();
        var node = new ExplorerNode(Kind.SERVER, s.address(), describeServer(session), null, null, session);
        switch (session.state()) {
            case CONNECTED -> {
                if (session.manager() != null) {
                    node.withLoader(n -> loadServerChildren(session));
                    node.expandOnShow = true;
                } else {
                    node.loaded = true;
                }
            }
            case FAILED -> {
                node.foreground = Palette.danger();
                node.toolTip = session.lastError();
                node.loaded = true;
            }
            default -> {
                node.foreground = Palette.subtle();
                node.toolTip = Boolean.FALSE.equals(s.serviceRunning())
                        ? Platform.current().nativeServiceNoun() + " '" + s.service() + "' is stopped."
                        : null;
                node.loaded = true;
            }
        }
        return node;
    }

    private DatabaseContext getOrOpenContext(ServerTree t, String dbName) throws Exception {
        if (closed) return null;
        t.lock.lock();
        try {
            var existing = t.contexts.get(dbName);
            if (existing != null) return existing;
            // The factory ref-counts by database name, so asking for the login
            // database here just bumps the count on the session's primary lease.
            var lease = acquire.acquire(t.session, dbName);
            if (lease == null) return null;
            var ctx = new DatabaseContext(lease, new SchemaInspector(lease.manager()));
            t.contexts.put(dbName, ctx);
            return ctx;
        } finally {
            t.lock.unlock();
        }
    }

    private List<ExplorerNode> loadServerChildren(ServerSession session) throws Exception {
        var t = treeFor(session);
        if (t == null) throw new IllegalStateException("Server is not connected.");
        var dbFolder = ExplorerNode.folder(Kind.DATABASES_FOLDER, "Databases", null, session);
        if (combined) {
            // One sub-folder per server (this one first, then the rest in registry
            // order), so the same database name on two servers can't be confused.
            combinedFolder = dbFolder;
            var all = registry.sessions();
            dbFolder.addAll(buildGroup(session));
            for (var other : all) {
                if (isGrouped(other)) dbFolder.addAll(buildGroup(other));
            }
            dbFolder.name = "Databases (all " + all.size() + " servers)";
            dbFolder.expandOnShow = true;
        } else {
            dbFolder.addAll(buildDatabaseNodes(t));
        }

        var security = ExplorerNode.folder(Kind.SECURITY_FOLDER, "Security", null, session);
        var roles = new ExplorerNode(Kind.ROLES_FOLDER, "Roles", null, null, null, session)
                .withLoader(n -> loadRoles(t));
        security.add(roles);
        return List.of(dbFolder, security);
    }

    private List<ExplorerNode> buildDatabaseNodes(ServerTree t) throws Exception {
        var session = t.session;
        var login = session.login();
        var nodes = new ArrayList<ExplorerNode>();
        for (var d : visibleInTree(t.inspector.getDatabases())) {
            var current = login != null && d.name().equals(login.database);
            var tag = current ? "  (connected)" : d.allowConnections() ? "" : "  (offline)";
            var node = new ExplorerNode(Kind.DATABASE, d.name() + tag, "owner: " + d.owner(), d, d.name(), session);
            if (d.allowConnections()) {
                // Every connectable database is expandable — expanding opens a lease on demand.
                node.withLoader(n -> loadDatabaseChildren(n, t));
            } else {
                node.foreground = Palette.disabled();
                node.loaded = true;
            }
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * The databases the tree shows for a server: 'postgres' is only the
     * maintenance database, so when the server has real databases it's hidden
     * (still reachable from the connection menu). Config: HideMaintenanceDatabase.
     */
    public static List<DatabaseInfo> visibleInTree(List<DatabaseInfo> dbs) {
        if (AppConfig.current().hideMaintenanceDatabase && dbs.size() > 1) {
            return dbs.stream().filter(d -> !d.name().equals("postgres")).toList();
        }
        return dbs;
    }

    /**
     * One server's entry in the combined Databases folder: a server node holding
     * its databases when connected, otherwise a single entry to double-click.
     */
    private List<ExplorerNode> buildGroup(ServerSession session) {
        var t = session.state() == State.CONNECTED ? treeFor(session) : null;
        if (t != null) {
            try {
                var group = new ExplorerNode(Kind.SERVER, session.server().address(), describeServer(session),
                        null, null, session);
                group.addAll(buildDatabaseNodes(t));
                group.loaded = true;
                // The login server's databases open straight away; others stay folded.
                group.expandOnShow = session == home;
                return List.of(group);
            } catch (Exception ex) {
                var n = new ExplorerNode(Kind.SERVER, session.server().address(),
                        "couldn't list databases: " + ConnectionErrors.message(ex), null, null, session);
                n.loaded = true;
                n.foreground = Palette.danger();
                return List.of(n);
            }
        }
        return List.of(buildServerNode(session));
    }

    /** Replaces one server's slice of the combined folder, keeping the others (and their expansion) intact. */
    private void refreshGroup(ServerSession session) {
        var folder = combinedFolder;
        if (folder == null) return;
        var fresh = buildGroup(session);
        Ui.edt(() -> {
            if (folder != combinedFolder) return; // home rebuilt meanwhile
            var order = registry.sessions();
            var rank = order.indexOf(session);
            for (var i = folder.getChildCount() - 1; i >= 0; i--) {
                if (folder.getChildAt(i) instanceof ExplorerNode n && n.server == session) {
                    if (folder.getRoot() == root) model.removeNodeFromParent(n);
                    else folder.remove(i);
                }
            }
            var at = 0;
            for (var i = 0; i < folder.getChildCount(); i++) {
                if (folder.getChildAt(i) instanceof ExplorerNode n && n.server != null
                        && order.indexOf(n.server) < rank) at = i + 1;
            }
            for (var n : fresh) {
                if (folder.getRoot() == root) model.insertNodeInto(n, folder, at++);
                else folder.insert(n, at++);
            }
            folder.name = "Databases (all " + order.size() + " servers)";
            if (folder.getRoot() == root) model.nodeChanged(folder);
        });
    }

    private List<ExplorerNode> loadDatabaseChildren(ExplorerNode db, ServerTree t) throws Exception {
        var info = (DatabaseInfo) db.payload;
        var ctx = getOrOpenContext(t, info.name());
        if (ctx == null) {
            // Cancelled credential prompt, or no CONNECT privilege. Refresh retries.
            return List.of(ExplorerNode.placeholder("(no access — right-click Refresh to retry)", Palette.subtle()));
        }
        var session = t.session;
        var schemasFolder = ExplorerNode.folder(Kind.SCHEMAS_FOLDER, "Schemas", info.name(), session);
        for (var s : ctx.inspector().getSchemas()) {
            schemasFolder.add(new ExplorerNode(Kind.SCHEMA, s.name(), "owner: " + s.owner(), s, info.name(), session)
                    .withLoader(n -> loadSchemaChildren(n, ctx, info.name(), session)));
        }
        return List.of(schemasFolder);
    }

    private List<ExplorerNode> loadSchemaChildren(ExplorerNode schemaNode, DatabaseContext ctx, String dbName,
                                                  ServerSession session) throws Exception {
        var schema = (SchemaInfo) schemaNode.payload;

        var tables = ctx.inspector().getTables(schema.name());
        var tablesFolder = ExplorerNode.folder(Kind.TABLES_FOLDER, "Tables (" + tables.size() + ")", dbName, session);
        for (var tb : tables) {
            tablesFolder.add(new ExplorerNode(Kind.TABLE, tb.name(), tb.kind(), tb, dbName, session)
                    .withLoader(n -> loadTableChildren(n, ctx, dbName, session)));
        }

        var views = ctx.inspector().getViews(schema.name());
        var viewsFolder = ExplorerNode.folder(Kind.VIEWS_FOLDER, "Views (" + views.size() + ")", dbName, session);
        for (var v : views) {
            var n = new ExplorerNode(Kind.VIEW, v.name(), null, v, dbName, session);
            n.loaded = true;
            viewsFolder.add(n);
        }

        var fns = ctx.inspector().getFunctions(schema.name());
        var fnFolder = ExplorerNode.folder(Kind.FUNCTIONS_FOLDER, "Functions (" + fns.size() + ")", dbName, session);
        for (var f : fns) {
            var n = new ExplorerNode(Kind.FUNCTION, f.name(), "(" + f.arguments() + ") → " + f.returnType(), f, dbName,
                    session);
            n.loaded = true;
            fnFolder.add(n);
        }
        return List.of(tablesFolder, viewsFolder, fnFolder);
    }

    /** Run once a node's next load lands (see {@link #revealTable}). */
    private final Map<ExplorerNode, Runnable> afterLoad = new ConcurrentHashMap<>();

    /** Folder to open after the next reload of a table (set by {@link #refreshTable}). */
    private final Map<ExplorerNode, Kind> reopenAfterReload = new ConcurrentHashMap<>();

    /**
     * Reloads one table's folders (after a structure change) and opens the folder
     * of the kind that changed. Call on the EDT.
     */
    public void refreshTable(ExplorerNode tableNode, Kind folderToOpen) {
        if (folderToOpen != null) {
            reopenAfterReload.put(tableNode, folderToOpen);
            afterLoad.put(tableNode, () -> {
                for (var f : children(tableNode)) {
                    if (f.kind != folderToOpen) continue;
                    var kids = children(f);
                    tree.scrollPathToVisible(pathOf(kids.isEmpty() ? f : kids.getLast()));
                    tree.scrollPathToVisible(pathOf(f));
                }
            });
        }
        refreshNode(tableNode);
    }

    /** A table's structure: columns, keys, constraints, indexes, triggers, inbound foreign keys. */
    private List<ExplorerNode> loadTableChildren(ExplorerNode tableNode, DatabaseContext ctx, String dbName,
                                                 ServerSession session) throws Exception {
        var tb = (TableInfo) tableNode.payload;
        var ti = new TableInspector(ctx.lease().manager());
        var s = tb.schema();
        var t = tb.name();
        var open = reopenAfterReload.remove(tableNode);

        var columns = ti.columns(s, t);
        var colFolder = tableFolder(Kind.COLUMNS_FOLDER, "Columns", columns.size(), tb, dbName, session, open);
        for (var c : columns) {
            var detail = new StringBuilder(c.dataType());
            if (!c.nullable()) detail.append(" NOT NULL");
            if (c.identity() != null) detail.append(" · identity");
            if (c.generated() != null) detail.append(" · generated");
            leaf(colFolder, Kind.COLUMN, c.name(), detail.toString(), c, dbName, session, null);
        }

        var constraints = ti.constraints(s, t);
        var keys = constraints.stream().filter(c -> c.type() == ConstraintType.PRIMARY_KEY || c.type() == ConstraintType.FOREIGN_KEY).toList();
        var others = constraints.stream().filter(c -> c.type() != ConstraintType.PRIMARY_KEY && c.type() != ConstraintType.FOREIGN_KEY).toList();
        var keyFolder = tableFolder(Kind.KEYS_FOLDER, "Keys", keys.size(), tb, dbName, session, open);
        for (var c : keys) {
            var pk = c.type() == ConstraintType.PRIMARY_KEY;
            var detail = pk ? "PRIMARY KEY (" + String.join(", ", c.columns()) + ")"
                    : "(" + String.join(", ", c.columns()) + ") → " + c.refSchema() + "." + c.refTable()
                    + "(" + String.join(", ", c.refColumns()) + ")"
                    + (!"NO ACTION".equals(c.onDelete()) ? " · ON DELETE " + c.onDelete() : "");
            leaf(keyFolder, pk ? Kind.PRIMARY_KEY : Kind.FOREIGN_KEY, c.name(),
                    detail + (c.validated() ? "" : " · NOT VALID"), c, dbName, session,
                    c.validated() ? null : Palette.disabled());
        }
        var conFolder = tableFolder(Kind.CONSTRAINTS_FOLDER, "Constraints", others.size(), tb, dbName, session, open);
        for (var c : others) {
            var kind = switch (c.type()) {
                case UNIQUE -> Kind.UNIQUE;
                case CHECK -> Kind.CHECK;
                default -> Kind.EXCLUSION;
            };
            leaf(conFolder, kind, c.name(), c.definition() + (c.validated() ? "" : " · NOT VALID"), c, dbName, session,
                    c.validated() ? null : Palette.disabled());
        }

        var indexes = ti.indexes(s, t);
        var ixFolder = tableFolder(Kind.INDEXES_FOLDER, "Indexes", indexes.size(), tb, dbName, session, open);
        for (var ix : indexes) {
            var detail = new StringBuilder(ix.method());
            if (ix.primary()) detail.append(" · primary");
            else if (ix.unique()) detail.append(" · unique");
            detail.append(" · (").append(String.join(", ", ix.keys())).append(")");
            if (ix.predicate() != null) detail.append(" WHERE ").append(ix.predicate());
            detail.append(" · ").append(DatabaseOperations.formatBytes(ix.sizeBytes()))
                    .append(" · ").append(ix.scans()).append(" scans");
            if (!ix.valid()) detail.append(" · INVALID");
            leaf(ixFolder, Kind.INDEX, ix.name(), detail.toString(), ix, dbName, session,
                    ix.valid() ? null : Palette.danger());
        }

        var triggers = ti.triggers(s, t);
        var trFolder = tableFolder(Kind.TRIGGERS_FOLDER, "Triggers", triggers.size(), tb, dbName, session, open);
        for (var tr : triggers) {
            var detail = tr.timing() + " " + String.join(" OR ", tr.events()) + " · FOR EACH " + tr.level()
                    + " · " + tr.function() + (tr.enabled() ? "" : " · disabled");
            leaf(trFolder, Kind.TRIGGER, tr.name(), detail, tr, dbName, session, tr.enabled() ? null : Palette.disabled());
        }

        var refs = ti.referencedBy(s, t);
        var refFolder = tableFolder(Kind.REFERENCED_BY_FOLDER, "Referenced by", refs.size(), tb, dbName, session, open);
        for (var r : refs) {
            leaf(refFolder, Kind.REFERENCING_FK, r.schema() + "." + r.table(),
                    r.constraintName() + " (" + String.join(", ", r.columns()) + ")", r, dbName, session, null);
        }
        return List.of(colFolder, keyFolder, conFolder, ixFolder, trFolder, refFolder);
    }

    private static ExplorerNode tableFolder(Kind kind, String label, int count, TableInfo table, String dbName,
                                            ServerSession session, Kind open) {
        // The folder's payload is its table, so "New …" actions on the folder know the target.
        var f = new ExplorerNode(kind, label + " (" + count + ")", null, table, dbName, session);
        f.loaded = true;
        f.expandOnShow = kind == open;
        return f;
    }

    private static void leaf(ExplorerNode parent, Kind kind, String name, String detail, Object payload, String dbName,
                             ServerSession session, java.awt.Color color) {
        var n = new ExplorerNode(kind, name, detail, payload, dbName, session);
        n.loaded = true;
        n.foreground = color;
        parent.add(n);
    }

    private List<ExplorerNode> loadRoles(ServerTree t) throws Exception {
        var nodes = new ArrayList<ExplorerNode>();
        for (var r : t.inspector.getRoles()) {
            var attrs = new ArrayList<String>();
            if (r.isSuperuser()) attrs.add("superuser");
            if (r.canLogin()) attrs.add("login");
            if (r.canCreateRole()) attrs.add("createrole");
            if (r.canCreateDb()) attrs.add("createdb");
            var n = new ExplorerNode(Kind.ROLE, r.name(), String.join(", ", attrs), r, null, t.session);
            n.loaded = true;
            nodes.add(n);
        }
        return nodes;
    }

    // ---- Lazy loading (EDT) ----------------------------------------------

    private void startLoad(ExplorerNode node) {
        if (node.loaded || node.loading || node.loader == null || closed) return;
        node.loading = true;
        if (node.getChildCount() != 1 || !(node.getChildAt(0) instanceof ExplorerNode only) || only.kind != Kind.LOADING) {
            node.removeAllChildren();
            node.add(ExplorerNode.loadingRow());
            if (node.getRoot() == root) model.nodeStructureChanged(node);
        }
        var loader = node.loader;
        Thread.ofVirtual().name("explorer-load").start(() -> {
            List<ExplorerNode> kids;
            try {
                kids = loader.load(node);
            } catch (Exception ex) {
                kids = List.of(ExplorerNode.placeholder("(error) " + ConnectionErrors.message(ex), Palette.danger()));
            }
            var result = kids;
            Ui.later(() -> {
                node.loading = false;
                node.loaded = true;
                node.removeAllChildren();
                for (var k : result) node.add(k);
                if (node.getRoot() != root) return; // replaced while loading
                model.nodeStructureChanged(node);
                tree.expandPath(pathOf(node));
                expandRequested(result);
                var then = afterLoad.remove(node);
                if (then != null) then.run();
            });
        });
    }

    /** Expands freshly loaded nodes (and their descendants) that asked to be shown open. */
    private void expandRequested(List<ExplorerNode> nodes) {
        for (var n : nodes) {
            if (!n.expandOnShow) continue;
            tree.expandPath(pathOf(n));
            var kids = new ArrayList<ExplorerNode>();
            for (var i = 0; i < n.getChildCount(); i++) kids.add((ExplorerNode) n.getChildAt(i));
            expandRequested(kids);
        }
    }

    /** Puts a freshly inserted node on screen, expanding it if it asked to be. */
    private void showNode(ExplorerNode node) {
        var path = pathOf(node);
        tree.expandPath(new TreePath(root));
        if (node.expandOnShow) tree.expandPath(path);
    }

    public TreePath pathOf(ExplorerNode node) {
        return new TreePath(model.getPathToRoot(node));
    }

    public DefaultTreeModel model() {
        return model;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        registry.removeSessionAddedListener(addedListener);
        for (var s : registry.sessions()) {
            s.removeStateListener(stateListener);
            s.removeInfoListener(infoListener);
        }
        for (var s : List.copyOf(trees.keySet())) releaseTree(s);
        events.shutdownNow();
    }
}
