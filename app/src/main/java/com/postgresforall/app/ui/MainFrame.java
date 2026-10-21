package com.postgresforall.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.AppSettings;
import com.postgresforall.app.ui.diag.DiagnosticsDialog;
import com.postgresforall.app.ui.diag.HelpDialog;
import com.postgresforall.app.ui.diag.HelpTopic;
import com.postgresforall.app.ui.dialogs.ConfirmTypeDialog;
import com.postgresforall.app.ui.dialogs.DatabaseCredentialsDialog;
import com.postgresforall.app.ui.dialogs.TextInputDialog;
import com.postgresforall.app.ui.explorer.ExplorerNode;
import com.postgresforall.app.ui.explorer.ObjectExplorer;
import com.postgresforall.app.ui.query.QueryTab;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.app.ui.theme.Theme;
import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.OperationEvent.Severity;
import com.postgresforall.core.model.SchemaNodes.DatabaseInfo;
import com.postgresforall.core.model.SchemaNodes.TableInfo;
import com.postgresforall.core.model.ServerSource;
import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.ConnectionErrors;
import com.postgresforall.core.service.ConnectionErrors.Kind;
import com.postgresforall.core.service.ConnectionLease;
import com.postgresforall.core.service.DatabaseOperations;
import com.postgresforall.core.service.PasswordStore;
import com.postgresforall.core.service.ServerDiscovery;
import com.postgresforall.core.service.ServerRegistry;
import com.postgresforall.core.service.ServerSession;
import com.postgresforall.core.service.ServerSession.State;
import com.postgresforall.core.util.CancelToken;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The shell: Object Explorer on the left, query tabs on the right, DB
 * Operations Output at the bottom, menus / toolbar / status bar around them.
 *
 * <p>Event handlers that do I/O run their bodies off the EDT ({@link Ui#bg});
 * Swing state is only touched through {@link Ui#edt} / {@link Ui#call}.
 */
public final class MainFrame extends JFrame {
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServerRegistry registry;
    private final ServerSession primary;
    private final ConnectionProfile primaryLogin;
    private final boolean discoverServers;
    private final PasswordStore passwordStore;
    private final AppSettings settings = AppSettings.load();
    private final OperationOutputPanel output = new OperationOutputPanel();
    private final List<QueryTab> tabs = new ArrayList<>();
    private final Timer healthTimer;
    private volatile ObjectExplorer explorer;

    private final JTabbedPane docTabs = new JTabbedPane();
    private final JPanel explorerHost = new JPanel(new BorderLayout());
    private final JButton connectionPill = new JButton();
    private final JTextField explorerFilter = new JTextField();
    private final JLabel caretStatus = new JLabel();
    private final JLabel healthDot = new JLabel("●");
    private final JLabel connectionStatus = new JLabel();
    private final JLabel serverVersionStatus = new JLabel();
    private final JLabel activeDocStatus = new JLabel();
    private final JSplitPane outerSplit;
    private final JCheckBoxMenuItem showOutputItem = new JCheckBoxMenuItem("Output Panel", true);
    private int expandedOutputDivider = -1;
    private volatile LocalDateTime lastHealthOk;
    private int untitledCounter;
    private boolean closing;

    /**
     * @param primary         the login window's server; connected when the window opens
     * @param discoverServers scan this machine for other Postgres servers (superuser
     *                        logins, when the config allows). Other servers (configured or found) are only
     *                        listed for superuser logins.
     */
    public MainFrame(ServerRegistry registry, ServerSession primary, ConnectionProfile primaryLogin,
                     boolean discoverServers, PasswordStore passwordStore) {
        super("PostgresForAll");
        this.registry = registry;
        this.primary = primary;
        this.primaryLogin = primaryLogin;
        this.discoverServers = discoverServers;
        this.passwordStore = passwordStore;

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setJMenuBar(buildMenuBar());

        var top = new JPanel(new BorderLayout());
        top.add(buildToolBar(), BorderLayout.CENTER);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.border()));
        if (Platform.isMac()) {
            // One toolbar row beside the traffic lights, like Xcode / TablePlus.
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        }

        docTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        docTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK,
                (java.util.function.IntConsumer) this::closeTabAt);
        docTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        docTabs.putClientProperty(FlatClientProperties.TABBED_PANE_SCROLL_BUTTONS_PLACEMENT, FlatClientProperties.TABBED_PANE_PLACEMENT_TRAILING);
        docTabs.addChangeListener(e -> syncToActiveTab());

        var explorerPanel = new JPanel(new BorderLayout());
        explorerPanel.add(buildExplorerHeader(), BorderLayout.NORTH);
        var connecting = new JLabel("Connecting…", Icons.get("loader"), JLabel.LEFT);
        connecting.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        connecting.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        explorerHost.add(connecting, BorderLayout.NORTH);
        explorerPanel.add(explorerHost, BorderLayout.CENTER);

        var innerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorerPanel, docTabs);
        innerSplit.setDividerLocation(320);
        innerSplit.setContinuousLayout(true);
        outerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, innerSplit, output);
        outerSplit.setResizeWeight(1.0);
        outerSplit.setContinuousLayout(true);
        output.onToggleCollapse(() -> setOutputCollapsed(!output.isCollapsed()));
        // A long operation that finishes while the output is out of sight gets a toast.
        output.onFinished((title, ok) -> {
            if (!output.isShowing() || output.isCollapsed()) Toast.show(this, ok ? "Finished" : "Failed", title, ok);
        });

        var content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(outerSplit, BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(content);

        healthTimer = new Timer(30_000, e -> probeHealth());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                outerSplit.setDividerLocation(Math.max(200, outerSplit.getHeight() - 200));
                // Output starts folded to its header; it opens itself when an operation runs.
                Ui.later(() -> setOutputCollapsed(true));
                Ui.bg(MainFrame.this, MainFrame.this::onOpened);
            }

            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler((ev, response) -> {
                if (requestClose()) response.performQuit();
                else response.cancelQuit();
            });
        }

        setSize(1280, 800);
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH);
    }

    // ---- Startup / shutdown ------------------------------------------------

    private void onOpened() throws Exception {
        try {
            // Credentials saved before multi-server support were entered against the login server.
            registry.credentials().adoptUnscoped(primary.key());
            primary.connect(primaryLogin);

            Ui.edt(() -> {
                explorer = new ObjectExplorer(registry, this::acquireWithCredentialPrompt,
                        AppConfig.current().combineServerDatabases);
                wireExplorerTree(explorer);
                explorerHost.removeAll();
                var treeScroll = new JScrollPane(explorer.tree());
                treeScroll.setBorder(BorderFactory.createEmptyBorder());
                explorerHost.add(treeScroll, BorderLayout.CENTER);
                explorerHost.revalidate();
            });

            for (var s : registry.sessions()) watchSession(s);
            registry.onSessionAdded(s -> {
                watchSession(s);
                Ui.later(this::syncToActiveTab);
            });

            Ui.edt(this::syncToActiveTab);
            newQueryDocument(null, null, primary, null);
            probeHealth();
            Ui.edt(healthTimer::start);

            discoverServersNow();
        } catch (Exception ex) {
            Ui.error(this, "PostgresForAll", "Startup failed: " + ConnectionErrors.message(ex));
            Ui.edt(this::shutdown);
        }
    }

    private void watchSession(ServerSession s) {
        s.onStateChanged(this::onSessionStateChanged);
        s.onInfoChanged(x -> Ui.later(this::syncToActiveTab));
    }

    /** Asks about unsaved tabs; true when the app is closing. */
    private boolean requestClose() {
        if (closing) return true;
        var dirty = tabs.stream().filter(QueryTab::isDirty).count();
        if (dirty > 0 && !Ui.confirm(this, "Quit",
                dirty + " query tab(s) have unsaved changes, which will be lost.\n\nQuit anyway?", true)) {
            return false;
        }
        shutdown();
        return true;
    }

    private void shutdown() {
        closing = true;
        healthTimer.stop();
        for (var t : List.copyOf(tabs)) t.dispose();
        tabs.clear();
        var ex = explorer;
        // Releases the per-database leases the explorer opened, then disconnects every server.
        var done = Thread.ofVirtual().start(() -> {
            if (ex != null) {
                try { ex.close(); } catch (RuntimeException ignored) { }
            }
            try { registry.close(); } catch (RuntimeException ignored) { }
        });
        try {
            done.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        dispose();
        System.exit(0);
    }

    // ---- Servers -----------------------------------------------------------

    /**
     * Lists the configured servers and — for superuser logins — every other
     * Postgres server on this machine, then connects the ones with a remembered
     * login. Runs after the window is usable. Call off the EDT.
     */
    private void discoverServersNow() {
        // Only a superuser sees other servers (configured or discovered) and gets
        // silently connected to them with saved logins. Anyone else sees just the
        // server they signed in to; File > Connect to Server still works with credentials.
        var mgr = primary.manager();
        if (mgr == null || !mgr.isSuperuser()) {
            setActiveStatus("Signed in as " + (mgr == null ? "a non-superuser" : "'" + mgr.currentUser() + "'")
                    + " (not a superuser): only this server and the databases you can connect to are shown.");
            return;
        }
        if (discoverServers) setActiveStatus("Looking for other PostgreSQL servers on this machine…");
        try {
            var servers = new ServerDiscovery().discover(primaryLogin, discoverServers, CancelToken.NONE);
            for (var s : servers) registry.addOrUpdate(s);
            registry.connectSilently(passwordStore);

            var all = registry.sessions();
            var others = all.size() - 1;
            if (others > 0) {
                var waiting = all.stream().filter(s -> s.state() != State.CONNECTED).count();
                setActiveStatus(others + " other server(s) listed"
                        + (waiting > 0 ? " — double-click a grey one to log in." : "."));
            } else if (discoverServers) {
                setActiveStatus("No other PostgreSQL servers found on this machine.");
            }
        } catch (Exception ex) {
            setActiveStatus("Server discovery failed: " + ConnectionErrors.message(ex));
        }
    }

    private void onSessionStateChanged(ServerSession s) {
        Ui.later(() -> {
            syncToActiveTab();
        });
    }

    /**
     * Makes sure {@code session} is connected: silently with a remembered login
     * when there is one, otherwise by asking. False if the user cancels. Off the EDT.
     */
    private boolean ensureConnected(ServerSession session) {
        if (session.state() == State.CONNECTED) return true;

        var login = session.suggestLogin(passwordStore);
        if (login.password != null && !login.password.isEmpty()) {
            try {
                session.connect(login);
                return true;
            } catch (Exception ignored) {
                // fall through to the prompt, which shows lastError
            }
        }

        var dlg = Ui.call(() -> {
            var d = DatabaseCredentialsDialog.forServer(this, session.server(), login, session.lastError());
            return d.showDialog() ? d : null;
        });
        if (dlg == null) return false;

        try {
            session.connect(dlg.resultProfile());
        } catch (Exception ex) {
            Ui.warn(this, "Connect", "Couldn't connect to " + session.server().address() + ":\n"
                    + ConnectionErrors.message(ex));
            return false;
        }
        passwordStore.save(dlg.resultProfile(), dlg.remember(), session != primary);
        return true;
    }

    /** Disconnects a server after closing its query tabs (they'd hold pools the disconnect tears down). Off the EDT. */
    private void disconnectServer(ServerSession session) {
        var serverTabs = Ui.call(() -> tabs.stream().filter(t -> t.session() == session).toList());
        if (!serverTabs.isEmpty()) {
            var dirty = serverTabs.stream().filter(QueryTab::isDirty).count();
            var msg = "Disconnecting " + session.server().address() + " closes its " + serverTabs.size() + " query tab(s)."
                    + (dirty > 0 ? "\n\n" + dirty + " of them have unsaved changes, which will be lost." : "")
                    + "\n\nContinue?";
            if (!Ui.confirm(this, "Disconnect", msg, dirty > 0)) return;
            Ui.edt(() -> serverTabs.forEach(this::removeTab));
        }
        session.disconnect();
    }

    private void onConnectServer() {
        var dlg = new TextInputDialog(this, "Connect to server", "Connect to server",
                "Host and port of the PostgreSQL server, e.g. localhost:5432 or db.example.com:5432.", "localhost:5432")
                .validator(v -> parseHostPort(v) != null ? null : "Use host:port, e.g. localhost:5432.");
        if (!dlg.showDialog()) return;
        var hp = parseHostPort(dlg.value());
        if (hp == null) return;
        Ui.bg(this, () -> {
            var session = registry.addOrUpdate(DiscoveredServer.of(hp.host, hp.port, ServerSource.CONFIG));
            ensureConnected(session);
        });
    }

    private record HostPort(String host, int port) { }

    private static HostPort parseHostPort(String text) {
        var t = text.trim();
        var colon = t.lastIndexOf(':');
        if (colon <= 0) return null;
        try {
            var port = Integer.parseInt(t.substring(colon + 1));
            return port > 0 && port <= 65535 ? new HostPort(t.substring(0, colon), port) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---- Health / status bar ----------------------------------------------

    /** The server the active tab runs against, or the login server. */
    private ServerSession activeSession() {
        var tab = activeTab();
        return tab != null ? tab.session() : primary;
    }

    private void updateConnectionStatus() {
        var tab = activeTab();
        var session = activeSession();
        if (tab != null && session.state() == State.CONNECTED) {
            connectionStatus.setText("Connected: " + tab.manager().profile().displayName());
            serverVersionStatus.setText("PostgreSQL " + tab.manager().serverVersion());
        } else if (session.manager() != null) {
            connectionStatus.setText("Connected: " + session.manager().profile().displayName());
            serverVersionStatus.setText("PostgreSQL " + session.manager().serverVersion());
        } else {
            connectionStatus.setText("Not connected: " + session.server().address());
            serverVersionStatus.setText("");
        }
    }

    private void probeHealth() {
        var info = Ui.call(() -> {
            var s = activeSession();
            var tab = activeTab();
            return new Object[]{s, tab != null ? tab.manager() : s.manager()};
        });
        var session = (ServerSession) info[0];
        var manager = (com.postgresforall.core.service.ConnectionManager) info[1];
        Ui.bgQuiet(() -> {
            if (manager == null || session.state() != State.CONNECTED) {
                Ui.edt(() -> {
                    healthDot.setForeground(Palette.disabled());
                    healthDot.setToolTipText(html(session.server().address() + " is not connected.\nClick to open diagnostics."));
                });
                return;
            }
            var t0 = System.nanoTime();
            try (var conn = manager.openConnection(); var st = conn.createStatement()) {
                st.execute("SELECT 1");
                var ms = (System.nanoTime() - t0) / 1e6;
                lastHealthOk = LocalDateTime.now();
                Ui.edt(() -> {
                    healthDot.setForeground(Palette.success());
                    healthDot.setToolTipText(html(String.format("%s healthy — %,.0f ms%nLast OK: %s%nClick to open diagnostics.",
                            session.server().address(), ms, HMS.format(lastHealthOk))));
                });
            } catch (Exception ex) {
                var last = lastHealthOk;
                Ui.edt(() -> {
                    healthDot.setForeground(Palette.danger());
                    healthDot.setToolTipText(html(session.server().address() + " connection failed: "
                            + ConnectionErrors.message(ex) + "\n"
                            + (last == null ? "Never confirmed OK." : "Last OK: " + HMS.format(last))
                            + "\nClick to open diagnostics."));
                });
            }
        });
    }

    private static String html(String text) {
        return "<html>" + Ui.escapeHtml(text).replace("\n", "<br>") + "</html>";
    }

    private void showDiagnostics(ServerSession session) {
        var login = session.login();
        var profile = login != null ? login : session.suggestLogin(passwordStore);
        DiagnosticsDialog.show(this, () -> profile, session.server());
        probeHealth();
    }

    private void setActiveStatus(String text) {
        Ui.later(() -> activeDocStatus.setText(text));
    }

    // ---- Documents ---------------------------------------------------------

    private record Target(ServerSession session, String database) { }

    /**
     * Where a new query tab points when the caller didn't say — SSMS style: the
     * server / database selected in Object Explorer, else the active tab's, else
     * the login server's login database.
     */
    private Target defaultQueryTarget() {
        var node = explorer == null ? null : explorer.selectedNode();
        if (node != null && node.server != null) return new Target(node.server, node.databaseName);
        var tab = activeTab();
        if (tab != null) return new Target(tab.session(), tab.currentDatabase());
        return new Target(primary, null);
    }

    /** Opens a query tab. Off the EDT. Null when cancelled or the connection failed. */
    private QueryTab newQueryDocument(String initialSql, Path filePath, ServerSession server, String targetDatabase)
            throws Exception {
        var target = server != null ? new Target(server, targetDatabase) : Ui.call(this::defaultQueryTarget);
        var session = target.session();
        if (!ensureConnected(session)) return null;
        var login = session.login();
        if (login == null) return null;
        var dbName = target.database() == null || target.database().isBlank()
                ? defaultDatabase(session, login) : target.database();

        var lease = acquireWithCredentialPrompt(session, dbName);
        if (lease == null) return null;

        var tab = Ui.call(() -> {
            var t = new QueryTab(session, lease);
            if (initialSql != null) t.setInitialSql(initialSql);
            if (filePath == null) t.setTitle("SQLQuery" + (++untitledCounter) + ".sql");
            t.onChange(change -> Ui.later(() -> onTabChanged(t, change)));
            t.editor().addCaretListener(e -> { if (t == activeTab()) updateCaretStatus(); });
            tabs.add(t);
            docTabs.addTab(composeTabTitle(t), t);
            docTabs.setSelectedComponent(t);
            syncToActiveTab();
            return t;
        });
        if (filePath != null) tab.openFile(filePath);
        Ui.edt(() -> tab.editor().requestFocusInWindow());
        return tab;
    }

    /**
     * Database for a new tab when only a server was chosen: the login database,
     * unless the tree hides it (the 'postgres' maintenance database) and the
     * server shows exactly one database. Then that one, which is what the user
     * sees under the server. Off the EDT.
     */
    private String defaultDatabase(ServerSession session, ConnectionProfile login) {
        var ex = explorer;
        var inspector = ex == null ? null : ex.inspectorFor(session);
        if (inspector == null) return login.database;
        try {
            var visible = ObjectExplorer.visibleInTree(inspector.getDatabases()).stream()
                    .filter(DatabaseInfo::allowConnections).map(DatabaseInfo::name).toList();
            if (!visible.contains(login.database) && visible.size() == 1) return visible.get(0);
        } catch (Exception ignored) {
            // fall back to the login database
        }
        return login.database;
    }

    private void onTabChanged(QueryTab tab, QueryTab.Change change) {
        var index = docTabs.indexOfComponent(tab);
        if (index >= 0) docTabs.setTitleAt(index, composeTabTitle(tab));
        if (change == QueryTab.Change.STATUS) activeDocStatus.setText(tab.statusText());
        if (change == QueryTab.Change.CONNECTION && tab == activeTab()) syncToActiveTab();
    }

    private static String composeTabTitle(QueryTab tab) {
        return tab.title() + " — " + tab.connectionLabel() + (tab.isDirty() ? " *" : "");
    }

    private void closeTabAt(int index) {
        if (!(docTabs.getComponentAt(index) instanceof QueryTab tab)) return;
        if (tab.isDirty() && !Ui.confirm(this, "Close tab",
                "'" + tab.title() + "' has unsaved changes. Close it anyway?", true)) {
            return;
        }
        removeTab(tab);
    }

    private void removeTab(QueryTab tab) {
        tabs.remove(tab);
        docTabs.remove(tab);
        tab.dispose();
        syncToActiveTab();
    }

    private QueryTab activeTab() {
        return docTabs.getSelectedComponent() instanceof QueryTab t ? t : null;
    }

    // ---- Per-database credentials -----------------------------------------

    /**
     * A lease for {@code dbName} on {@code session}. When the login role is
     * refused (permission denied / auth failed), offers to enter credentials for
     * that database and retries once. Null if the user cancels or it still fails.
     * Off the EDT.
     */
    private ConnectionLease acquireWithCredentialPrompt(ServerSession session, String dbName) {
        var factory = session.factory();
        if (factory == null) {
            Ui.warn(this, "New Query", session.server().address() + " is not connected.");
            return null;
        }
        try {
            return factory.acquire(dbName);
        } catch (Exception ex) {
            var kind = ConnectionErrors.classify(ex);
            if (kind != Kind.PERMISSION_DENIED && kind != Kind.AUTH_FAILED) {
                Ui.warn(this, "New Query", "Couldn't open a connection to '" + dbName + "' on "
                        + session.server().address() + ":\n" + ConnectionErrors.message(ex));
                return null;
            }
            if (!promptForDatabaseCredentials(session, dbName)) return null;
            try {
                return factory.acquire(dbName);
            } catch (Exception retryEx) {
                Ui.warn(this, "New Query", "Still couldn't connect to '" + dbName + "':\n"
                        + ConnectionErrors.message(retryEx));
                return null;
            }
        }
    }

    /**
     * Shows the credential dialog for a database and, on success, records the
     * credentials so that server's factory uses them for that database. Off the EDT.
     */
    private boolean promptForDatabaseCredentials(ServerSession session, String dbName) {
        var existing = registry.credentials().find(session.key(), dbName);
        var dlg = Ui.call(() -> {
            var d = new DatabaseCredentialsDialog(this, dbName, session.server().host(), session.server().port(), existing);
            return d.showDialog() ? d : null;
        });
        if (dlg == null) return false;
        registry.credentials().set(dlg.result(), dlg.remember());
        return true;
    }

    // ---- Connection pill (server › database) --------------------------------

    /** The active tab changed (or its connection did): refresh the pill and the status bar. EDT. */
    private void syncToActiveTab() {
        updateConnectionStatus();
        updatePill();
        updateCaretStatus();
        var tab = activeTab();
        if (tab != null) activeDocStatus.setText(tab.statusText());
    }

    private void updatePill() {
        var tab = activeTab();
        var session = activeSession();
        var connected = session.state() == State.CONNECTED;
        var db = tab != null ? tab.currentDatabase() : session.login() != null ? session.login().database : "—";
        connectionPill.setText(session.server().address() + "  ›  " + db);
        connectionPill.setIcon(new DotIcon(connected ? Palette::success : Palette::disabled));
        connectionPill.setToolTipText("<html><b>" + Ui.escapeHtml(session.server().displayName()) + "</b><br>Database: "
                + Ui.escapeHtml(db) + "<br>Click to switch this tab to another server or database</html>");
    }

    /** Server › database menu for the active tab: every connected server and its databases. */
    private void showConnectionMenu() {
        var ex = explorer;
        if (ex == null) return;
        var tab = activeTab();
        connectionPill.setEnabled(false);
        Ui.bgQuiet(() -> {
            var servers = new java.util.LinkedHashMap<ServerSession, List<DatabaseInfo>>();
            for (var s : registry.sessions()) {
                if (s.state() != State.CONNECTED) continue;
                var inspector = ex.inspectorFor(s);
                List<DatabaseInfo> dbs;
                try {
                    dbs = inspector == null ? List.of() : inspector.getDatabases();
                } catch (Exception e) {
                    dbs = List.of();
                }
                servers.put(s, dbs.stream().sorted(Comparator.comparing((DatabaseInfo d) -> d.allowConnections() ? 0 : 1)
                        .thenComparing(DatabaseInfo::name, String.CASE_INSENSITIVE_ORDER)).toList());
            }
            Ui.edt(() -> {
                connectionPill.setEnabled(true);
                var menu = new JPopupMenu();
                for (var entry : servers.entrySet()) {
                    var s = entry.getKey();
                    var sub = new JMenu(s.server().displayName());
                    sub.setIcon(Icons.tinted("server", Icons.SMALL, Palette::success));
                    if (tab != null && s == tab.session()) sub.setFont(sub.getFont().deriveFont(Font.BOLD));
                    for (var d : entry.getValue()) {
                        var current = tab != null && s == tab.session() && d.name().equals(tab.currentDatabase());
                        var item = new JMenuItem(d.allowConnections() ? d.name() : d.name() + "  (offline)",
                                current ? Icons.tinted("circle-check", Icons.SMALL, Palette::accent)
                                        : d.allowConnections() ? Icons.get("database", Icons.SMALL)
                                        : Icons.tinted("lock", Icons.SMALL, Palette::disabled));
                        if (current) item.setFont(item.getFont().deriveFont(Font.BOLD));
                        item.addActionListener(e -> switchActiveTab(s, d.name(), d.allowConnections()));
                        sub.add(item);
                    }
                    if (entry.getValue().isEmpty()) {
                        var none = new JMenuItem("(couldn't list databases)");
                        none.setEnabled(false);
                        sub.add(none);
                    }
                    menu.add(sub);
                }
                if (servers.isEmpty()) {
                    var none = new JMenuItem("No connected servers");
                    none.setEnabled(false);
                    menu.add(none);
                }
                menu.addSeparator();
                menu.add(menuItem("Connect to Server…", null, this::onConnectServer)).setIcon(Icons.get("plug", Icons.SMALL));
                menu.show(connectionPill, 0, connectionPill.getHeight());
            });
        });
    }

    /**
     * SSMS "Change Connection": points the active tab at {@code dbName} on
     * {@code target}, bringing an offline database online first (after asking) and
     * offering alternate credentials when the login role is refused.
     */
    private void switchActiveTab(ServerSession target, String dbName, boolean allowConnections) {
        var tab = activeTab();
        if (tab == null || (target == tab.session() && dbName.equals(tab.currentDatabase()))) return;
        Ui.bg(this, () -> {
            if (!allowConnections) {
                if (!Ui.yesNo(this, "Database offline", "Database '" + dbName
                        + "' is currently offline (ALLOW_CONNECTIONS = false).\n\nBring it online and switch to it?", false)) {
                    return;
                }
                try {
                    DatabaseOperations.bringOnlineUsingProfile(target.login(), dbName);
                } catch (Exception ex) {
                    Ui.error(this, "Bring online failed", "Couldn't bring '" + dbName + "' online: "
                            + ConnectionErrors.message(ex));
                    return;
                }
                refreshTree(target);
            }
            var ok = tab.setServer(target, dbName);
            // Likely a permission / auth failure (a database owned by another role):
            // offer alternate credentials and retry once.
            if (!ok && promptForDatabaseCredentials(target, dbName)) ok = tab.setServer(target, dbName);
            if (!ok) Ui.error(this, "Switch failed", "Couldn't switch to '" + dbName + "' on "
                    + target.server().address() + ".\n" + tab.statusText());
            Ui.edt(this::syncToActiveTab);
        });
    }

    /** A small filled circle in a theme colour (pill status dot). */
    private record DotIcon(java.util.function.Supplier<Color> color) implements javax.swing.Icon {
        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            var g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color.get());
            g2.fillOval(x + 1, y + 1, 8, 8);
            g2.dispose();
        }

        @Override public int getIconWidth() { return 10; }
        @Override public int getIconHeight() { return 10; }
    }

    // ---- Menu / toolbar ----------------------------------------------------

    private JMenuBar buildMenuBar() {
        var bar = new JMenuBar();
        var mac = Platform.isMac();

        var file = new JMenu("File");
        file.add(menuItem("New Query", Ui.menuKey(KeyEvent.VK_N), this::onNewQuery));
        file.add(menuItem("Connect to Server...", null, this::onConnectServer));
        file.add(menuItem("Open Script...", Ui.menuKey(KeyEvent.VK_O), this::onOpenScript));
        file.add(menuItem("Save", Ui.menuKey(KeyEvent.VK_S), this::onSave));
        file.add(menuItem("Save As...", Ui.menuKey(KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK), this::onSaveAs));
        if (!mac) {
            file.addSeparator();
            file.add(menuItem("Exit", null, this::requestClose));
        }
        bar.add(file);

        var query = new JMenu("Query");
        var exec = menuItem("Execute", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), this::onExecute);
        query.add(exec);
        // Second accelerator: Cmd/Ctrl+Enter (F5 needs Fn on Mac laptops).
        getRootPane().registerKeyboardAction(e -> onExecute(), Ui.menuKey(KeyEvent.VK_ENTER),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        var cancelKey = mac ? Ui.menuKey(KeyEvent.VK_PERIOD) : KeyStroke.getKeyStroke(KeyEvent.VK_PAUSE, InputEvent.ALT_DOWN_MASK);
        query.add(menuItem("Cancel", cancelKey, this::onCancel));
        bar.add(query);

        var view = new JMenu("View");
        view.add(menuItem("Refresh Object Explorer (and find servers)", KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.SHIFT_DOWN_MASK), this::onRefreshTree));
        showOutputItem.addActionListener(e -> setOutputCollapsed(!showOutputItem.isSelected()));
        view.add(showOutputItem);
        view.addSeparator();
        var appearance = new JMenu("Appearance");
        var group = new javax.swing.ButtonGroup();
        for (var m : Theme.Mode.values()) {
            var item = new javax.swing.JRadioButtonMenuItem(m.label, Icons.get(switch (m) {
                case SYSTEM -> "monitor";
                case LIGHT -> "sun";
                case DARK -> "moon";
            }, Icons.SMALL), Theme.mode() == m);
            item.addActionListener(e -> {
                Theme.setMode(m);
                settings.appearance = m.name().toLowerCase(java.util.Locale.ROOT);
                settings.save();
            });
            group.add(item);
            appearance.add(item);
        }
        view.add(appearance);
        bar.add(view);

        var window = new JMenu("Window");
        window.add(menuItem("Minimize", mac ? Ui.menuKey(KeyEvent.VK_M) : null, () -> setExtendedState(getExtendedState() | ICONIFIED)));
        window.add(menuItem(mac ? "Zoom" : "Maximize / Restore", null, () ->
                setExtendedState((getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH ? NORMAL : MAXIMIZED_BOTH)));
        window.addSeparator();
        window.add(menuItem("Close Window", mac ? Ui.menuKey(KeyEvent.VK_W, InputEvent.SHIFT_DOWN_MASK) : null, this::requestClose));
        bar.add(window);

        var help = new JMenu("Help");
        help.add(menuItem("Diagnostics…", null, () -> showDiagnostics(activeSession())));
        help.add(menuItem("Troubleshooting…", null, () -> HelpDialog.show(this, HelpTopic.GENERAL)));
        help.addSeparator();
        help.add(menuItem("Edit Settings File…", null, this::onEditSettings));
        help.add(menuItem("Open Data Folder", null, () -> {
            try {
                Files.createDirectories(AppPaths.dataDir());
            } catch (IOException ignored) {
                // open reports the failure
            }
            if (!Ui.open(AppPaths.dataDir())) Ui.info(this, "Data folder", AppPaths.dataDir().toString());
        }));
        bar.add(help);
        return bar;
    }

    private static JMenuItem menuItem(String text, KeyStroke key, Runnable action) {
        var mi = new JMenuItem(text);
        if (key != null) mi.setAccelerator(key);
        mi.addActionListener(e -> action.run());
        return mi;
    }

    private JToolBar buildToolBar() {
        var tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        if (Platform.isMac()) {
            // Keeps the toolbar clear of the traffic-light buttons (and adapts in full screen).
            var placeholder = new JPanel();
            placeholder.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "mac");
            tb.add(placeholder);
        }
        var mod = Ui.menuKeyText();
        tb.add(iconButton("file-plus", "New query (" + mod + "N)", this::onNewQuery));
        tb.add(iconButton("folder-open", "Open script… (" + mod + "O)", this::onOpenScript));
        tb.add(iconButton("save", "Save (" + mod + "S)", this::onSave));
        tb.addSeparator();

        // Split "Run ▾" button: Run (selection or all), plus options.
        var run = new JButton("Run", Icons.tinted("play", Icons.NORMAL, Palette::success));
        run.setToolTipText("Run the selection, or the whole script (F5 / " + mod + "Enter)");
        run.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        run.setFocusable(false);
        run.addActionListener(e -> onExecute());
        tb.add(run);
        var runMore = iconButton("chevron-down", "More ways to run", null);
        runMore.addActionListener(e -> {
            var m = new JPopupMenu();
            m.add(menuItem("Run selection or all", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), this::onExecute));
            m.add(menuItem("Run whole script", null, () -> { var t = activeTab(); if (t != null) t.execute(null); }));
            m.add(menuItem("Run whole script in a transaction", null, () -> { var t = activeTab(); if (t != null) t.executeAllInTransaction(); }));
            m.show(runMore, 0, runMore.getHeight());
        });
        tb.add(runMore);
        tb.add(iconButton("square", "Stop the running query (" + (Platform.isMac() ? "⌘." : "Alt+Break") + ")", this::onCancel));
        tb.addSeparator();
        tb.add(iconButton("refresh-cw", "Refresh Object Explorer and find servers (Shift+F5)", this::onRefreshTree));
        tb.add(iconButton("activity", "Diagnostics for this server", () -> showDiagnostics(activeSession())));
        tb.add(Box.createHorizontalGlue());

        connectionPill.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; borderWidth: 1; focusWidth: 0; innerFocusWidth: 0; margin: 3,12,3,10");
        connectionPill.setHorizontalTextPosition(JButton.RIGHT);
        connectionPill.setIconTextGap(8);
        connectionPill.setFocusable(false);
        connectionPill.addActionListener(e -> showConnectionMenu());
        tb.add(connectionPill);
        var chevron = iconButton("chevron-down", "Switch server or database", this::showConnectionMenu);
        tb.add(chevron);
        return tb;
    }

    /** Icon-only toolbar button with a tooltip (null action = caller wires it). */
    private static JButton iconButton(String icon, String tip, Runnable action) {
        var b = new JButton(Icons.get(icon));
        b.setToolTipText(tip);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setFocusable(false);
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    /** "Object Explorer" title with refresh / collapse / connect buttons, and the filter box. */
    private JComponent buildExplorerHeader() {
        var title = new JLabel("Object Explorer");
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "sectionTitle");
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(smallIconButton("plug", "Connect to server…", this::onConnectServer));
        buttons.add(smallIconButton("refresh-cw", "Refresh and find servers", this::onRefreshTree));
        buttons.add(smallIconButton("chevrons-down-up", "Collapse all", () -> { var x = explorer; if (x != null) x.collapseAll(); }));
        var row = new JPanel(new BorderLayout());
        row.add(title, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);

        explorerFilter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter loaded objects");
        explorerFilter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", Icons.SMALL));
        explorerFilter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        explorerFilter.setToolTipText("Highlights matching loaded objects; Enter jumps to the next match");
        explorerFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void changed() { var x = explorer; if (x != null) x.setFilter(explorerFilter.getText()); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        });
        explorerFilter.addActionListener(e -> { var x = explorer; if (x != null) x.nextMatch(); });

        var header = new JPanel(new BorderLayout(0, 6));
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 6));
        header.add(row, BorderLayout.NORTH);
        header.add(explorerFilter, BorderLayout.CENTER);
        return header;
    }

    private static JButton smallIconButton(String icon, String tip, Runnable action) {
        var b = new JButton(Icons.get(icon, Icons.SMALL));
        b.setToolTipText(tip);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JComponent buildStatusBar() {
        var bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.border()),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        healthDot.setFont(healthDot.getFont().deriveFont(15f));
        healthDot.setForeground(Palette.disabled());
        healthDot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        healthDot.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showDiagnostics(activeSession());
            }
        });
        left.add(healthDot);
        connectionStatus.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        left.add(connectionStatus);
        var sep = new JLabel("·");
        sep.putClientProperty(FlatClientProperties.STYLE_CLASS, "subtle");
        left.add(sep);
        serverVersionStatus.putClientProperty(FlatClientProperties.STYLE_CLASS, "subtle");
        left.add(serverVersionStatus);
        bar.add(left, BorderLayout.WEST);
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        activeDocStatus.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        caretStatus.putClientProperty(FlatClientProperties.STYLE_CLASS, "subtle");
        right.add(activeDocStatus);
        right.add(caretStatus);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /** Folds the output panel down to its header bar, or opens it again. EDT. */
    private void setOutputCollapsed(boolean collapsed) {
        if (collapsed == output.isCollapsed()) return;
        if (collapsed) {
            expandedOutputDivider = outerSplit.getDividerLocation();
            output.setCollapsed(true);
            output.setMinimumSize(new Dimension(0, output.headerHeight()));
            outerSplit.setDividerLocation(outerSplit.getHeight() - outerSplit.getDividerSize() - output.headerHeight());
        } else {
            output.setCollapsed(false);
            var fallback = Math.max(200, outerSplit.getHeight() - 200);
            outerSplit.setDividerLocation(expandedOutputDivider > 0 && expandedOutputDivider < outerSplit.getHeight() - 60
                    ? expandedOutputDivider : fallback);
        }
        showOutputItem.setSelected(!collapsed);
        outerSplit.revalidate();
    }

    private void showOutput() {
        Ui.later(() -> setOutputCollapsed(false));
    }

    private void updateCaretStatus() {
        var tab = activeTab();
        if (tab == null) {
            caretStatus.setText("");
            return;
        }
        var ed = tab.editor();
        var line = ed.getCaretLineNumber() + 1;
        var col = ed.getCaretOffsetFromLineStart() + 1;
        caretStatus.setText("Ln " + line + ", Col " + col);
    }

    private void onNewQuery() {
        Ui.bg(this, () -> newQueryDocument(null, null, null, null));
    }

    private void onOpenScript() {
        var path = FileDialogs.open(this, "Open SQL Script", null, "SQL files", "sql");
        if (path != null) Ui.bg(this, () -> newQueryDocument(null, path, null, null));
    }

    private void onSave() {
        var tab = activeTab();
        if (tab == null) return;
        if (tab.filePath() == null) {
            onSaveAs();
            return;
        }
        try {
            tab.save(null);
        } catch (IOException ex) {
            Ui.error(this, "Save failed", ConnectionErrors.message(ex));
        }
    }

    private void onSaveAs() {
        var tab = activeTab();
        if (tab == null) return;
        var dir = tab.filePath() != null ? tab.filePath().getParent() : null;
        var path = FileDialogs.save(this, "Save SQL Script", dir, tab.title().replace("*", "").trim(), "SQL files", "sql");
        if (path == null) return;
        try {
            tab.save(path);
        } catch (IOException ex) {
            Ui.error(this, "Save failed", ConnectionErrors.message(ex));
        }
    }

    private void onExecute() {
        var tab = activeTab();
        if (tab != null) tab.executeSelectionOrAll();
    }

    private void onCancel() {
        var tab = activeTab();
        if (tab != null) tab.cancel();
    }

    private void onRefreshTree() {
        Ui.bg(this, () -> {
            refreshTree(null);
            discoverServersNow();
        });
    }

    /** Opens the config file in the default editor, creating a user copy from the bundled defaults first. */
    private void onEditSettings() {
        var loaded = AppConfig.loadedFrom();
        Path target;
        if (loaded.isPresent()) {
            target = loaded.get();
        } else {
            target = AppPaths.dataDir().resolve(AppConfig.FILE_NAME);
            try (var in = AppConfig.class.getResourceAsStream("/" + AppConfig.FILE_NAME)) {
                Files.createDirectories(target.getParent());
                if (in != null) Files.copy(in, target);
                else AppConfig.current().save(target);
            } catch (IOException ex) {
                Ui.error(this, "Settings", "Couldn't create " + target + ":\n" + ex.getMessage());
                return;
            }
        }
        if (!Ui.open(target)) {
            Ui.info(this, "Settings", "Edit this file, then restart PostgresForAll:\n\n" + target);
        } else {
            setActiveStatus("Editing " + target + " — restart PostgresForAll to apply changes.");
        }
    }

    // ---- Object Explorer ---------------------------------------------------

    private void wireExplorerTree(ObjectExplorer ex) {
        var tree = ex.tree();
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectUnder(e);
                maybeContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeContextMenu(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) onTreeDoubleClick();
            }

            private void selectUnder(MouseEvent e) {
                var path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null && (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger())) tree.setSelectionPath(path);
            }

            private void maybeContextMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                var path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                tree.setSelectionPath(path);
                var menu = buildContextMenu(ex.selectedNode());
                if (menu != null) menu.show(tree, e.getX(), e.getY());
            }
        });
        tree.addTreeSelectionListener(e -> {
            // nothing to sync: New Query reads the selection when it runs
        });
    }

    private ExplorerNode selectedNode() {
        var ex = explorer;
        return ex == null ? null : ex.selectedNode();
    }

    private record DbSelection(ServerSession session, DatabaseInfo db) { }

    private DbSelection selectedDatabase() {
        var n = selectedNode();
        return n != null && n.payload instanceof DatabaseInfo db && n.server != null ? new DbSelection(n.server, db) : null;
    }

    private void onTreeDoubleClick() {
        var node = selectedNode();
        if (node == null) return;
        if (node.kind == ExplorerNode.Kind.SERVER && node.server != null && node.server.state() != State.CONNECTED) {
            var s = node.server;
            Ui.bg(this, () -> ensureConnected(s));
            return;
        }
        if (node.kind == ExplorerNode.Kind.TABLE && node.payload instanceof TableInfo t && node.server != null) {
            // Script against the node's own server and database, not the login one.
            var server = node.server;
            var db = node.databaseName;
            Ui.bg(this, () -> {
                var inspector = explorer.inspectorFor(server, db);
                if (inspector == null) return;
                var sql = inspector.scriptTableSelect(t.schema(), t.name(), 1000);
                var tab = newQueryDocument(sql, null, server, db);
                if (tab != null) Ui.edt(() -> tab.execute(null));
            });
        }
    }

    private void scriptTable(boolean create) {
        var node = selectedNode();
        if (node == null || !(node.payload instanceof TableInfo t) || node.server == null) return;
        var server = node.server;
        var db = node.databaseName;
        Ui.bg(this, () -> {
            var inspector = explorer.inspectorFor(server, db);
            if (inspector == null) return;
            var sql = create ? inspector.scriptTableCreate(t.schema(), t.name())
                    : inspector.scriptTableSelect(t.schema(), t.name(), 1000);
            newQueryDocument(sql, null, server, db);
        });
    }

    /** Kind-aware context menu. Slow bits (offline list, client tools check) fill in after it opens. */
    private JPopupMenu buildContextMenu(ExplorerNode node) {
        if (node == null) return null;
        var menu = new JPopupMenu();

        if (node.kind == ExplorerNode.Kind.SERVER && node.server != null && node.server.state() == State.CONNECTED) {
            var server = node.server;
            menu.add(menuItem("New Query", null, () -> Ui.bg(this, () -> newQueryDocument(null, null, server, null))));
            menu.add(newQueryOnSubmenu(server));
            menu.add(menuItem("Refresh", null, () -> Ui.bg(this, () -> refreshTree(server))));
            menu.add(menuItem("Disconnect", null, () -> Ui.bg(this, () -> disconnectServer(server))));
            menu.addSeparator();
            menu.add(menuItem("Create a DB from script…", null, () -> onCreateDbFromScript(server)));
            var restoreNew = menuItem("Restore as new database…", null, () -> onRestoreAsNew(server));
            menu.add(restoreNew);
            menu.add(bringOnlineSubmenu(server));
            menu.addSeparator();
            menu.add(menuItem("Diagnostics…", null, () -> showDiagnostics(server)));
            disableWithoutClientTools(server, restoreNew);
        } else if (node.kind == ExplorerNode.Kind.SERVER && node.server != null) {
            var server = node.server;
            menu.add(menuItem("Connect…", null, () -> Ui.bg(this, () -> ensureConnected(server))));
            menu.add(menuItem("Diagnostics…", null, () -> showDiagnostics(server)));
        } else if (node.kind == ExplorerNode.Kind.DATABASE && node.server != null) {
            var server = node.server;
            menu.add(menuItem("New Query", null, this::onNewQueryOnDatabase));
            menu.add(menuItem("Set credentials…", null, () -> {
                var sel = selectedDatabase();
                if (sel == null) return;
                Ui.bg(this, () -> {
                    if (promptForDatabaseCredentials(sel.session(), sel.db().name())) {
                        setActiveStatus("Saved credentials for '" + sel.db().name() + "' on "
                                + sel.session().server().address() + ".");
                    }
                });
            }));
            menu.addSeparator();
            var backup = menuItem("Backup database…", null, this::onBackupDatabase);
            var restore = menuItem("Restore database…", null, this::onRestoreDatabase);
            menu.add(backup);
            menu.add(restore);
            menu.addSeparator();
            menu.add(menuItem("Refresh", null, () -> explorer.refreshNode(node)));
            menu.addSeparator();
            if (!(node.payload instanceof DatabaseInfo info) || info.allowConnections()) {
                menu.add(menuItem("Take database offline…", null, this::onTakeOffline));
            } else {
                var online = menuItem("Bring database online", null, () -> {
                    var sel = selectedDatabase();
                    if (sel != null) Ui.bg(this, () -> bringOnlineByName(sel.session(), sel.db().name()));
                });
                online.setForeground(Palette.success());
                menu.add(online);
            }
            var delete = menuItem("Delete database…", null, this::onDeleteDatabase);
            delete.setForeground(Palette.danger());
            menu.add(delete);
            if (server != primary) {
                // In the combined list other servers have no node of their own.
                menu.addSeparator();
                menu.add(menuItem("Disconnect " + server.server().address(), null,
                        () -> Ui.bg(this, () -> disconnectServer(server))));
            }
            disableWithoutClientTools(server, backup, restore);
        } else if (node.kind == ExplorerNode.Kind.TABLE) {
            menu.add(menuItem("Script as SELECT (LIMIT 1000)", null, () -> scriptTable(false)));
            menu.add(menuItem("Script as CREATE", null, () -> scriptTable(true)));
            menu.addSeparator();
            menu.add(menuItem("Refresh", null, () -> explorer.refreshNode(node)));
        } else {
            var refresh = menuItem("Refresh", null, () -> explorer.refreshNode(node));
            refresh.setEnabled(node.loader != null);
            menu.add(refresh);
        }
        return menu;
    }

    /**
     * Backup / restore run the host's pg_dump / pg_restore. Grey those items out,
     * with the reason as a tooltip, when no usable client tools exist.
     */
    private void disableWithoutClientTools(ServerSession server, JMenuItem... items) {
        var ops = server.operations();
        if (ops == null) return;
        Ui.bgQuiet(() -> {
            var reason = ops.clientToolsProblem();
            if (reason == null) return;
            Ui.edt(() -> {
                for (var mi : items) {
                    mi.setEnabled(false);
                    mi.setToolTipText("<html><body style='width:320px'>" + Ui.escapeHtml(reason) + "</body></html>");
                }
            });
        });
    }

    /** "New Query on database ▸": one entry per database on the server, so the target is explicit. */
    private JMenu newQueryOnSubmenu(ServerSession session) {
        var sub = new JMenu("New Query on database");
        sub.setIcon(Icons.get("database", Icons.SMALL));
        var loading = new JMenuItem("Loading...");
        loading.setEnabled(false);
        sub.add(loading);
        Ui.bgQuiet(() -> {
            var ex = explorer;
            var inspector = ex == null ? null : ex.inspectorFor(session);
            var dbs = inspector == null ? List.<DatabaseInfo>of() : inspector.getDatabases();
            Ui.edt(() -> {
                sub.removeAll();
                for (var d : dbs) {
                    var item = menuItem(d.allowConnections() ? d.name() : d.name() + "  (offline)", null,
                            () -> Ui.bg(this, () -> newQueryDocument(null, null, session, d.name())));
                    item.setEnabled(d.allowConnections());
                    sub.add(item);
                }
                if (dbs.isEmpty()) {
                    var none = new JMenuItem("(couldn't list databases)");
                    none.setEnabled(false);
                    sub.add(none);
                }
                if (sub.isPopupMenuVisible()) {
                    sub.setPopupMenuVisible(false);
                    sub.setPopupMenuVisible(true);
                }
            });
        });
        return sub;
    }

    /**
     * "Bring database online ▶" on the server menu. Queried live, so it reflects
     * current state whether or not the Databases folder was expanded.
     */
    private JMenu bringOnlineSubmenu(ServerSession session) {
        var sub = new JMenu("Bring database online");
        var loading = new JMenuItem("Loading...");
        loading.setEnabled(false);
        sub.add(loading);
        Ui.bgQuiet(() -> {
            List<DatabaseInfo> dbs;
            try {
                var inspector = explorer.inspectorFor(session);
                if (inspector == null) throw new IllegalStateException("server not connected");
                dbs = inspector.getDatabases();
            } catch (Exception ex) {
                Ui.edt(() -> {
                    sub.removeAll();
                    var err = new JMenuItem("(query failed: " + ConnectionErrors.message(ex) + ")");
                    err.setEnabled(false);
                    sub.add(err);
                });
                return;
            }
            var offline = dbs.stream().filter(d -> !d.allowConnections()).toList();
            Ui.edt(() -> {
                sub.removeAll();
                if (offline.isEmpty()) {
                    sub.setText("Bring database online (no offline databases)");
                    sub.setEnabled(false);
                    return;
                }
                sub.setText("Bring database online (" + offline.size() + " offline)");
                for (var d : offline) {
                    var item = menuItem(d.name(), null, () -> Ui.bg(this, () -> bringOnlineByName(session, d.name())));
                    item.setForeground(Palette.success());
                    sub.add(item);
                }
                // The popup was sized before the items arrived.
                if (sub.isPopupMenuVisible()) {
                    sub.setPopupMenuVisible(false);
                    sub.setPopupMenuVisible(true);
                }
            });
        });
        return sub;
    }

    private void onNewQueryOnDatabase() {
        var sel = selectedDatabase();
        if (sel == null) return;
        Ui.bg(this, () -> {
            if (!sel.db().allowConnections()) {
                if (!Ui.yesNo(this, "Database offline", "Database '" + sel.db().name()
                        + "' is offline (ALLOW_CONNECTIONS = false).\n\n"
                        + "Bring it online now and open a new query tab against it?", false)) {
                    return;
                }
                try {
                    DatabaseOperations.bringOnlineUsingProfile(sel.session().login(), sel.db().name());
                } catch (Exception ex) {
                    Ui.error(this, "Bring online failed", "Couldn't bring '" + sel.db().name() + "' online: "
                            + ConnectionErrors.message(ex));
                    return;
                }
                refreshTree(sel.session());
            }
            newQueryDocument(null, null, sel.session(), sel.db().name());
        });
    }

    // ---- Database operations ----------------------------------------------

    private void onBackupDatabase() {
        var sel = selectedDatabase();
        if (sel == null || sel.session().operations() == null) return;
        var ops = sel.session().operations();
        var db = sel.db();

        var stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now());
        var path = FileDialogs.save(this, "Backup database", AppSettings.folderOr(settings.lastBackupFolder),
                db.name() + "-" + stamp + ".dump", "PostgreSQL custom dump", "dump");
        if (path == null) return;
        settings.lastBackupFolder = path.getParent().toString();
        settings.save();

        showOutput();
        var op = output.begin("Backup '" + db.name() + "' (" + sel.session().server().address() + ")");
        Ui.bgQuiet(() -> {
            var ok = false;
            try {
                ok = ops.backup(db.name(), path.toString(), op.emit(), op.token());
            } catch (Exception ex) {
                op.emit().accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
            } finally {
                output.end(ok);
            }
        });
    }

    private void onRestoreDatabase() {
        var sel = selectedDatabase();
        if (sel == null || sel.session().operations() == null) return;
        var ops = sel.session().operations();
        var db = sel.db();

        var path = FileDialogs.open(this, "Restore database",
                AppSettings.folderOr(settings.lastRestoreFolder, settings.lastBackupFolder),
                "PostgreSQL dump", "dump", "sql", "backup");
        if (path == null) return;
        settings.lastRestoreFolder = path.getParent().toString();
        settings.save();

        Ui.bg(this, () -> {
            // Fingerprint the target so we can warn loudly if it has data.
            Ui.busy(this, true);
            DatabaseOperations.DatabaseFingerprint fp;
            try {
                fp = ops.getDatabaseFingerprint(db.name());
            } finally {
                Ui.busy(this, false);
            }

            var where = db.name() + "   on " + sel.session().server().address();
            String body;
            if (fp.tables() == 0) {
                body = "Restore from:\n  " + path + "\n\nInto database:\n  " + where + "   (empty — 0 tables)\n\n"
                        + "pg_restore will populate this database from the dump. Continue?";
            } else {
                body = "⚠  WARNING — destructive operation\n\n"
                        + "Restore from:\n  " + path + "\n\n"
                        + "Into database:\n  " + where + "\n"
                        + "    currently contains: " + fp.tables() + " table(s)\n"
                        + String.format("    ~%,d rows · %s%n%n", fp.estimatedRows(), DatabaseOperations.formatBytes(fp.sizeBytes()))
                        + "pg_restore is invoked with --clean --if-exists. Every object listed "
                        + "in the dump will REPLACE the matching object in this database. Tables "
                        + "and rows present here but NOT in the dump will remain (the restore "
                        + "merges rather than wipes).\n\n"
                        + "If '" + db.name() + "' is the wrong target, consider 'Restore as new database…' "
                        + "on the server node instead.\n\nContinue?";
            }
            if (!Ui.confirm(this, "Confirm restore", body, fp.tables() > 0)) return;

            showOutput();
            var op = output.begin("Restore '" + db.name() + "' (" + sel.session().server().address() + ")");
            var ok = false;
            try {
                ok = ops.restore(db.name(), path.toString(), op.emit(), op.token());
            } catch (Exception ex) {
                op.emit().accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
            } finally {
                output.end(ok);
                if (ok) refreshTree(sel.session());
            }
        });
    }

    private void onCreateDbFromScript(ServerSession session) {
        var login = session.login();
        if (login == null) return;
        var template = "-- Create a new PostgreSQL database.\n"
                + "-- Edit the name and options below, then press F5 to run.\n"
                + "-- The CREATE DATABASE statement must run by itself (no transaction).\n"
                + "\n"
                + "CREATE DATABASE my_new_db\n"
                + "    WITH OWNER     = " + login.username + "\n"
                + "         ENCODING   = 'UTF8'\n"
                + "         TEMPLATE   = template0\n"
                + "         CONNECTION LIMIT = -1;\n"
                + "\n"
                + "-- Optional: GRANT access to other roles\n"
                + "-- GRANT ALL PRIVILEGES ON DATABASE my_new_db TO some_role;\n"
                + "\n"
                + "-- Optional: COMMENT on the database for documentation\n"
                + "-- COMMENT ON DATABASE my_new_db IS 'What this database is for';\n";
        Ui.bg(this, () -> newQueryDocument(template, null, session, null));
    }

    private static final Pattern DUMP_SUFFIX = Pattern.compile("[-_]\\d{4,}.*$");

    private void onRestoreAsNew(ServerSession session) {
        var ops = session.operations();
        var login = session.login();
        if (ops == null || login == null) return;

        var path = FileDialogs.open(this, "Pick a dump file to restore as a new database",
                AppSettings.folderOr(settings.lastRestoreFolder, settings.lastBackupFolder),
                "PostgreSQL dump", "dump", "sql", "backup");
        if (path == null) return;
        settings.lastRestoreFolder = path.getParent().toString();
        settings.save();

        // Suggest a name from the file: "scheduleme-20260613-1122.dump" → "scheduleme"
        var fileName = path.getFileName().toString();
        var stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        var suggested = DUMP_SUFFIX.matcher(stem).replaceAll("").toLowerCase(Locale.ROOT);
        if (suggested.isBlank() || suggested.length() > 63) suggested = "restored_db";

        var nameDlg = new TextInputDialog(this, "New database name", "Restore as new database",
                "Choose a name for the new database on " + session.server().address() + ". The dump '" + fileName
                        + "' will be restored into it. The name must be a valid PostgreSQL identifier.", suggested)
                .validator(TextInputDialog::validateIdentifier);
        if (!nameDlg.showDialog()) return;
        var newDb = nameDlg.value().trim();

        Ui.bg(this, () -> {
            // Refuse if it already exists.
            var inspector = explorer.inspectorFor(session);
            var existing = inspector == null ? List.<DatabaseInfo>of() : inspector.getDatabases();
            if (existing.stream().anyMatch(d -> d.name().equals(newDb))) {
                Ui.warn(this, "Name already in use", "A database named '" + newDb + "' already exists. Pick a "
                        + "different name, or use 'Restore database…' on that database to overwrite it.");
                return;
            }

            showOutput();
            var op = output.begin("Restore as new '" + newDb + "' (" + session.server().address() + ")");
            var ok = false;
            try {
                // Create the database owned by the current login, then restore into it.
                ok = ops.createDatabase(newDb, login.username, op.emit(), op.token())
                        && ops.restore(newDb, path.toString(), op.emit(), op.token());
            } catch (Exception ex) {
                op.emit().accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
                ok = false;
            } finally {
                output.end(ok);
                if (ok) refreshTree(session);
            }
        });
    }

    /** Off the EDT. */
    private void bringOnlineByName(ServerSession session, String dbName) {
        var ops = session.operations();
        if (ops == null) return;
        showOutput();
        var op = output.begin("Bring '" + dbName + "' online");
        var ok = false;
        try {
            ok = ops.bringOnline(dbName, op.emit(), op.token());
        } catch (Exception ex) {
            op.emit().accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
        } finally {
            output.end(ok);
            if (ok) refreshTree(session);
        }
    }

    private void onTakeOffline() {
        var sel = selectedDatabase();
        if (sel == null || sel.session().operations() == null) return;
        var ops = sel.session().operations();
        var db = sel.db();

        Ui.bg(this, () -> {
            Ui.busy(this, true);
            int active;
            try {
                active = ops.countActiveSessions(db.name());
            } finally {
                Ui.busy(this, false);
            }

            if (active <= 0) {
                // No connections (or count failed) — a single confirm prompt.
                var msg = active == 0
                        ? "Take database '" + db.name() + "' OFFLINE?\n\n"
                        + "No active connections found. New connections will be blocked (ALLOW_CONNECTIONS false).\n\n"
                        + "Bring online later via:\n  ALTER DATABASE \"" + db.name() + "\" WITH ALLOW_CONNECTIONS true"
                        : "Take database '" + db.name() + "' OFFLINE?\n\n"
                        + "(Couldn't count active sessions — connection check failed.)\n"
                        + "New connections will be blocked; any existing sessions terminated.";
                if (!Ui.confirm(this, "Confirm take offline", msg, true)) return;
            } else {
                // Active connections present — a separate Yes / No about closing them.
                var prompt = "Database '" + db.name() + "' has " + active + " active connection(s).\n\n"
                        + "Close all of them and take the database offline?\n\n"
                        + "Yes  → terminate the connections and block new ones\n"
                        + "No   → abort, no changes made";
                if (!Ui.yesNo(this, "Active connections found", prompt, true)) {
                    showOutput();
                    var op = output.begin("Take '" + db.name() + "' offline");
                    op.emit().accept(new OperationEvent(Severity.WARN, "Aborted by user — '" + db.name() + "' has "
                            + active + " active session(s) and the user declined to close them."));
                    output.end(false);
                    return;
                }
            }

            showOutput();
            var op = output.begin("Take '" + db.name() + "' offline");
            var ok = false;
            try {
                ok = ops.takeOffline(db.name(), op.emit(), op.token());
            } catch (Exception ex) {
                op.emit().accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
            } finally {
                output.end(ok);
                if (ok) refreshTree(sel.session());
            }
        });
    }

    private void onDeleteDatabase() {
        var sel = selectedDatabase();
        if (sel == null || sel.session().operations() == null) return;
        var ops = sel.session().operations();
        var db = sel.db();

        var confirm = new ConfirmTypeDialog(this, "Drop database '" + db.name() + "'",
                "This will PERMANENTLY drop the database '" + db.name() + "' on " + sel.session().server().address()
                        + " and ALL of its data.\n\n"
                        + "All current connections to '" + db.name() + "' will be terminated (WITH FORCE).\n\n"
                        + "Type the database name below to confirm:", db.name());
        if (!confirm.showDialog()) return;

        showOutput();
        var op = output.begin("Drop '" + db.name() + "' (" + sel.session().server().address() + ")");
        Ui.bgQuiet(() -> {
            var ok = false;
            try {
                ok = ops.dropDatabase(db.name(), op.emit(), op.token());
            } catch (Exception ex) {
                op.emit().accept(new OperationEvent(Severity.ERROR, ConnectionErrors.message(ex)));
            } finally {
                output.end(ok);
                if (ok) refreshTree(sel.session());
            }
        });
    }

    /**
     * Rebuilds one server's node (or every server's), releasing the database
     * leases browsed under the old nodes, then reloads the database dropdown.
     * Off the EDT.
     */
    private void refreshTree(ServerSession session) {
        var ex = explorer;
        if (ex == null) return;
        for (var s : session == null ? registry.sessions() : List.of(session)) ex.rebuildServer(s);
        Ui.edt(this::syncToActiveTab);
    }
}
