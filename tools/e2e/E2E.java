import com.postgresforall.app.ui.explorer.ExplorerNode;
import com.postgresforall.app.ui.query.QueryTab;
import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.PasswordStore;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * End-to-end GUI test: drives the real app through login, queries, Object
 * Explorer browsing, table scripting and diagnostics, asserting along the way
 * and saving a PNG of each step. Exit code 0 = pass.
 *
 * <p>Run against the app's classpath (Java source-file mode):
 * <pre>java -cp "app/build/install/PostgresForAll/lib/*" tools/e2e/E2E.java &lt;screenshot dir&gt;</pre>
 * It logs in with the config's "Application user" preset (needs a password in the
 * config) and expects the ScheduleMe schema (a users table) in that database.
 */
public class E2E {
    static String out;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        out = a.length > 0 ? a[0] : "e2e-shots";
        new File(out).mkdirs();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("UNCAUGHT in " + t.getName() + ": " + e);
            e.printStackTrace();
        });
        try {
            run();
        } catch (Throwable ex) {
            ex.printStackTrace();
            failures.add("aborted: " + ex);
            shotAll("zz-failure");
        }
        if (failures.isEmpty()) {
            System.out.println("E2E PASSED");
            Runtime.getRuntime().halt(0);
        }
        System.out.println("E2E FAILED:");
        failures.forEach(f -> System.out.println("  - " + f));
        Runtime.getRuntime().halt(1);
    }

    static void run() throws Exception {
        var cfg = AppConfig.current();
        var themeArg = System.getProperty("pfa.theme", "system");
        System.out.println("theme: " + themeArg);
        System.out.println("config: " + AppConfig.resolvePath() + " → " + cfg.host + ":" + cfg.port + "/" + cfg.database);
        new Thread(() -> com.postgresforall.app.Main.main(new String[0]), "app-main").start();

        // ---- Login ---------------------------------------------------------
        Window login = waitWindow(w -> w instanceof JDialog d && String.valueOf(d.getTitle()).startsWith("Connect to PostgreSQL") && d.isShowing(), 60000);
        edt(() -> {
            // Superuser then Application user: applies the config's app-user preset (host, port, user, password).
            find(login, AbstractButton.class, b -> "Superuser".equals(b.getText())).doClick();
            find(login, AbstractButton.class, b -> "Application user".equals(b.getText())).doClick();
        });
        Thread.sleep(500);
        shot(login, "01-login");
        edt(() -> find(login, JButton.class, b -> b.getText().equals("Connect")).doClick());

        Window main = waitWindow(w -> w instanceof JFrame f && "PostgresForAll".equals(f.getTitle()) && f.isShowing(), 60000);
        QueryTab tab = waitFor("first query tab", () -> findOrNull(main, QueryTab.class, t -> true), 60000);
        Thread.sleep(4000);
        shot(main, "02-main");
        if (themeArg.equals("dark")) check("dark theme active", com.postgresforall.app.ui.theme.Theme.isDark());
        if (themeArg.equals("light")) check("light theme active", !com.postgresforall.app.ui.theme.Theme.isDark());

        // ---- Remembered password went to the OS store ------------------------
        var profiles = AppPaths.dataDir().resolve("profiles.json");
        check("profiles.json written", Files.isRegularFile(profiles));
        if (Files.isRegularFile(profiles)) {
            var json = Files.readString(profiles);
            if (Platform.isMac()) check("password kept in Keychain (file holds a keychain: reference)", json.contains("\"keychain:"));
            if (Platform.isWindows()) check("password DPAPI-encrypted (not plain text)", !json.contains("\"" + cfg.password + "\""));
            var restored = new PasswordStore().findMostRecent();
            check("saved login reads back with its password", restored != null && cfg.password.equals(restored.password));
        }

        // ---- Queries ---------------------------------------------------------
        runSql(tab, "SELECT version();");
        check("version query: no errors", tab.statusText().contains("1 statement(s), 1 row(s)"));
        shot(main, "03-version");

        runSql(tab, """
                SELECT 1 AS one, 'two' AS two, NULL AS nothing, now() AS ts, 3.14::numeric AS pi, '{1,2}'::int[] AS arr, '{"a":1}'::jsonb AS j;
                DO $$ BEGIN RAISE NOTICE 'hello from a notice'; RAISE WARNING 'and a warning'; END $$;
                SELECT generate_series(1, 500) AS n, md5(random()::text) AS h;
                SELECT * FROM table_that_does_not_exist;
                """);
        shot(main, "04-messages");
        var messages = Ui.call(tab::messagesText);
        check("notice captured", messages.contains("hello from a notice"));
        check("warning captured", messages.contains("and a warning"));
        check("error reported with SQLSTATE", messages.contains("[42P01]"));
        edt(() -> find(tab, JTabbedPane.class, t -> t.getTabCount() == 2 && "Results".equals(t.getTitleAt(0))).setSelectedIndex(0));
        Thread.sleep(400);
        var grids = Ui.call(() -> count(tab, JTable.class));
        check("two result grids shown (got " + grids + ")", grids == 2);
        shot(main, "05-results");

        // ---- Object Explorer -------------------------------------------------
        JTree tree = find(main, JTree.class, t -> true);
        expand(tree, n -> n.name.startsWith("Databases"));
        if (cfg.combineServerDatabases) expand(tree, n -> n.kind == ExplorerNode.Kind.SERVER && n.name.equals("localhost:" + cfg.port) && n.getLevel() > 1);
        expand(tree, n -> n.kind == ExplorerNode.Kind.DATABASE && n.name.startsWith(cfg.database));
        expand(tree, n -> n.name.equals("Schemas"));
        expand(tree, n -> n.kind == ExplorerNode.Kind.SCHEMA && n.name.equals("public"));
        expand(tree, n -> n.name.startsWith("Tables ("));
        shot(main, "06-tree");
        var users = waitFor("users table node", () -> node(tree, n -> n.kind == ExplorerNode.Kind.TABLE && n.name.equals("users")), 15000);

        // Double-click a table: scripts SELECT ... LIMIT 1000 into a new tab and runs it.
        click(tree, users, 2, MouseEvent.BUTTON1);
        QueryTab scripted = waitFor("scripted tab", () -> findOrNull(main, QueryTab.class, t -> t != tab), 20000);
        waitFor("scripted query done", () -> !scripted.isExecuting() && scripted.statusText().contains("row(s)") ? true : null, 20000);
        check("table script ran", scripted.editor().getText().contains("FROM \"public\".\"users\""));
        shot(main, "07-table-script");

        // ---- Table structure: add an index through the dialog, then drop it --------
        tableDesign(main, tree, tab);

        // ---- Table menu: Design ▸ submenu and Properties --------------------------------
        var usersNode = waitFor("users table node", () -> node(tree, n -> n.kind == ExplorerNode.Kind.TABLE && n.name.equals("users")), 15000);
        contextMenu(tree, usersNode);
        var topLevel = Ui.call(() -> {
            var names = new ArrayList<String>();
            for (var c : findPopup().getComponents()) if (c instanceof JMenuItem m) names.add(m.getText());
            return names;
        });
        check("table menu: Design submenu + Properties, new items not at top level (" + topLevel + ")",
                topLevel.contains("Design") && topLevel.contains("Properties") && !topLevel.contains("New index…"));
        clickMenu("Properties");
        Window props = waitWindow(w -> w instanceof JDialog d && d.isShowing()
                && String.valueOf(d.getTitle()).startsWith("Table Properties"), 20000);
        Thread.sleep(400);
        var ptree = Ui.call(() -> find(props, com.postgresforall.app.ui.PropertyTreeTable.class, t -> true));
        var before = Ui.call(ptree::visibleRows);
        check("properties tree drills into Columns → id", Ui.call(() -> ptree.expandRow("Columns") && ptree.expandRow("id")));
        var after = Ui.call(ptree::visibleRows);
        check("expanding added detail rows (" + before + " → " + after + ")", after > before + 5);
        check("properties text has owner and sizes", Ui.call(ptree::asText).contains("Owner: ") && Ui.call(ptree::asText).contains("Total size: "));
        Thread.sleep(300);
        shot(props, "18a-table-properties");
        edt(() -> find(props, JButton.class, b -> "Close".equals(b.getText())).doClick());

        // ---- Relationship diagram for users ------------------------------------------
        // The schema was refreshed above: find the users node again.
        var usersNow = waitFor("users table node", () -> node(tree, n -> n.kind == ExplorerNode.Kind.TABLE && n.name.equals("users")), 15000);
        contextMenu(tree, usersNow);
        clickMenu("View relationship diagram");
        var diagram = waitFor("diagram tab", () -> findOrNull(main,
                com.postgresforall.app.ui.diagram.RelationshipDiagramTab.class, Component::isShowing), 20000);
        var graph = waitFor("diagram loaded", () -> Ui.call(() -> diagram.canvas().graph()), 30000);
        check("diagram shows users and linked tables (" + graph.tables().size() + " tables, "
                + graph.relationships().size() + " FKs)", graph.tables().size() > 1 && !graph.relationships().isEmpty());
        check("every table has a box", Ui.call(() -> graph.tables().stream().allMatch(t -> diagram.canvas().boxOf(t.ref()) != null)));
        Thread.sleep(800);
        shot(main, "18-relationship-diagram");
        // Re-centre on a smaller table (what a double-click on its box does).
        edt(() -> diagram.recenter(new com.postgresforall.core.model.TableObjects.TableRef("public", "tasks")));
        waitFor("re-centred diagram", () -> Ui.call(() -> {
            var g = diagram.canvas().graph();
            return g != null && g.focus().name().equals("tasks") ? g : null;
        }), 30000);
        check("diagram re-centred on tasks", Ui.call(() -> diagram.title().endsWith("tasks")));
        Thread.sleep(800);
        shot(main, "19-relationship-diagram-tasks");
        edt(() -> ((JTabbedPane) scripted.getParent()).setSelectedComponent(scripted));

        // ---- Explorer filter -------------------------------------------------------
        edt(() -> find(main, JTextField.class, f -> "Filter loaded objects".equals(
                f.getClientProperty("JTextField.placeholderText"))).setText("tasks"));
        Thread.sleep(500);
        var picked = Ui.call(() -> {
            var p = tree.getSelectionPath();
            return p == null ? "" : ((ExplorerNode) p.getLastPathComponent()).name;
        });
        check("explorer filter selects the match (got '" + picked + "')", picked.equals("tasks"));
        shot(main, "08-explorer-filter");
        edt(() -> find(main, JTextField.class, f -> "Filter loaded objects".equals(
                f.getClientProperty("JTextField.placeholderText"))).setText(""));

        // ---- Connection pill: switch the scripted tab to 'postgres' and back ----------
        edt(() -> find(main, JButton.class, b -> b.getText() != null && b.getText().contains("›")).doClick());
        var item = waitFor("database menu item", () -> Ui.call(() -> {
            for (var w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                var pm = findOrNull(w instanceof RootPaneContainer r ? r.getRootPane() : (Container) w, JPopupMenu.class, x -> x.isShowing());
                if (pm == null) continue;
                for (var c : pm.getComponents()) {
                    if (c instanceof JMenu m && m.getText().startsWith("localhost:" + cfg.port)) {
                        for (var mc : m.getMenuComponents()) {
                            if (mc instanceof JMenuItem mi && "postgres".equals(mi.getText())) return mi;
                        }
                    }
                }
            }
            return null;
        }), 20000);
        shot(main, "09-connection-menu");
        edt(item::doClick);
        waitFor("tab switched to postgres", () -> "postgres".equals(scripted.currentDatabase()) ? true : null, 20000);
        runSql(scripted, "SELECT current_database();");
        check("pill switched the tab to postgres", scripted.statusText().contains("1 row(s)"));
        var pillText = Ui.call(() -> find(main, JButton.class, b -> b.getText() != null && b.getText().contains("›")).getText());
        check("pill shows the new database (" + pillText + ")", pillText.endsWith("postgres"));
        shot(main, "10-pill-switched");

        // ---- Live theme switch -------------------------------------------------------
        var wasDark = com.postgresforall.app.ui.theme.Theme.isDark();
        edt(() -> com.postgresforall.app.ui.theme.Theme.setMode(wasDark
                ? com.postgresforall.app.ui.theme.Theme.Mode.LIGHT : com.postgresforall.app.ui.theme.Theme.Mode.DARK));
        Thread.sleep(1500);
        check("live theme switch flipped the theme", com.postgresforall.app.ui.theme.Theme.isDark() != wasDark);
        shot(main, "11-live-theme-switch");
        edt(() -> com.postgresforall.app.ui.theme.Theme.setMode(wasDark
                ? com.postgresforall.app.ui.theme.Theme.Mode.DARK : com.postgresforall.app.ui.theme.Theme.Mode.LIGHT));
        Thread.sleep(1200);

        // ---- Diagnostics -------------------------------------------------------
        edt(() -> {
            var dot = find(main, JLabel.class, l -> "●".equals(l.getText()));
            var e = new MouseEvent(dot, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false);
            SwingUtilities.invokeLater(() -> { for (var l : dot.getMouseListeners()) l.mouseClicked(e); });
        });
        Window diag = waitWindow(w -> w instanceof JDialog d && d.isShowing() && String.valueOf(d.getTitle()).toLowerCase().contains("diagnostic"), 20000);
        waitFor("diagnostics finished", () -> Ui.call(() -> findOrNull(diag, JLabel.class, l -> "All green.".equals(l.getText())) != null
                || findOrNull(diag, JLabel.class, l -> l.getText() != null && l.getText().startsWith("Some checks failed")) != null) ? true : null, 30000);
        shot(diag, "12-diagnostics");
        var allGreen = Ui.call(() -> findOrNull(diag, JLabel.class, l -> "All green.".equals(l.getText())) != null);
        check("diagnostics all green", allGreen);
        var hostLine = Ui.call(() -> labels(diag));
        System.out.println("diagnostics labels: " + hostLine);
        if (Platform.isMac()) check("diagnostics found the launchd service", hostLine.contains("Service 'homebrew.mxcl.postgresql"));
        edt(() -> find(diag, JButton.class, b -> b.getText().startsWith("View logs")).doClick());
        Thread.sleep(4000);
        shot(diag, "13-diagnostics-logs");
        edt(diag::dispose);
    }

    /** Right-click CRUD on a throwaway table: New index → Apply → node appears → Drop. Cleans up after itself. */
    static void tableDesign(Window main, JTree tree, QueryTab tab) throws Exception {
        var t = "pfa_e2e_design";
        runSql(tab, "DROP TABLE IF EXISTS public." + t + "; CREATE TABLE public." + t + " (id int PRIMARY KEY, name text);");
        check("throwaway table created", !Ui.call(tab::messagesText).contains("ERROR"));
        try {
            var schema = node(tree, n -> n.kind == ExplorerNode.Kind.SCHEMA && n.name.equals("public"));
            contextMenu(tree, schema);
            clickMenu("Refresh");
            var table = waitFor("new table node", () -> node(tree, n -> n.kind == ExplorerNode.Kind.TABLE && n.name.equals(t)), 20000);
            expand(tree, n -> n == table);
            var cols = waitFor("columns folder", () -> node(tree, n -> n.kind == ExplorerNode.Kind.COLUMNS_FOLDER && n.getParent() == table), 10000);
            check("columns folder counts 2 (" + cols.name + ")", cols.name.equals("Columns (2)"));
            check("primary key listed under Keys", node(tree, n -> n.kind == ExplorerNode.Kind.PRIMARY_KEY && n.getParent().getParent() == table) != null);

            contextMenu(tree, table);
            shot(Ui.call(E2E::findPopup), "14-table-menu");
            clickMenu("New index…");
            Window dlg = waitWindow(w -> w instanceof JDialog d && d.isShowing() && "New index".equals(d.getTitle()), 20000);
            edt(() -> find(dlg, AbstractButton.class, b -> "Add column as a key".equals(b.getToolTipText())).doClick());
            Thread.sleep(500);
            shot(dlg, "15-new-index-dialog");
            edt(() -> find(dlg, JButton.class, b -> "Apply".equals(b.getText())).doClick());

            var idx = waitFor("new index node", () -> node(tree, n -> n.kind == ExplorerNode.Kind.INDEX
                    && n.getParent().getParent() != null && ((ExplorerNode) n.getParent().getParent()).name.equals(t)
                    && !n.name.endsWith("_pkey")), 30000);
            check("index created (" + idx.name + ")", idx.name.contains(t));
            Thread.sleep(500);
            shot(main, "16-index-created");

            contextMenu(tree, idx);
            clickMenu("Drop index…");
            Window drop = waitWindow(w -> w instanceof JDialog d && d.isShowing() && "Drop index".equals(d.getTitle()), 20000);
            edt(() -> find(drop, JTextField.class, f -> idx.name.equals(f.getClientProperty("JTextField.placeholderText"))).setText(idx.name));
            Thread.sleep(300);
            shot(drop, "17-drop-index-dialog");
            edt(() -> find(drop, JButton.class, b -> "Drop".equals(b.getText())).doClick());
            waitFor("index folder back to 1", () -> node(tree, n -> n.kind == ExplorerNode.Kind.INDEXES_FOLDER
                    && n.getParent() != null && ((ExplorerNode) n.getParent()).name.equals(t) && n.name.equals("Indexes (1)")), 30000);
            check("index dropped", true);
        } finally {
            runSql(tab, "DROP TABLE IF EXISTS public." + t + ";");
        }
    }

    /** Opens the tree's context menu on {@code n}, the way a right-click does on this OS. */
    static void contextMenu(JTree tree, ExplorerNode n) throws Exception {
        edt(() -> {
            var path = new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n));
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            var r = tree.getPathBounds(path);
            for (var id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED}) {
                var e = new MouseEvent(tree, id, System.currentTimeMillis(), 0, r.x + 5, r.y + 5, 1, true, MouseEvent.BUTTON3);
                for (var l : tree.getMouseListeners()) {
                    if (id == MouseEvent.MOUSE_PRESSED) l.mousePressed(e); else l.mouseReleased(e);
                }
                if (findPopup() != null) break; // macOS opens on press, Windows on release
            }
        });
        waitFor("context menu", () -> Ui.call(E2E::findPopup), 10000);
    }

    static JPopupMenu findPopup() {
        for (var w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            var pm = findOrNull(w instanceof RootPaneContainer r ? r.getRootPane() : (Container) w, JPopupMenu.class, Component::isShowing);
            if (pm != null) return pm;
        }
        return null;
    }

    /** Finds an enabled item by text, looking inside submenus (e.g. Design ▸) too. */
    static JMenuItem menuItemIn(Component[] items, String text) {
        for (var c : items) {
            if (c instanceof JMenu sub) {
                var found = menuItemIn(sub.getMenuComponents(), text);
                if (found != null) return found;
            } else if (c instanceof JMenuItem m && text.equals(m.getText()) && m.isEnabled()) {
                return m;
            }
        }
        return null;
    }

    static void clickMenu(String text) throws Exception {
        var mi = waitFor("menu item '" + text + "'", () -> Ui.call(() -> {
            var pm = findPopup();
            if (pm == null) return null;
            return menuItemIn(pm.getComponents(), text);
        }), 10000);
        edt(() -> {
            mi.doClick();
            var pm = findPopup();
            if (pm != null) pm.setVisible(false);
        });
    }

    // ---- helpers -----------------------------------------------------------

    static final class Ui {
        static <T> T call(java.util.function.Supplier<T> s) {
            var ref = new AtomicReference<T>();
            edt(() -> ref.set(s.get()));
            return ref.get();
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures.add(what);
    }

    static void runSql(QueryTab tab, String sql) throws Exception {
        edt(() -> tab.setSqlText(sql));
        edt(() -> tab.execute(null));
        Thread.sleep(300);
        waitFor("query to finish", () -> tab.isExecuting() ? null : true, 60000);
        Thread.sleep(800);
    }

    static String text(JTextPane p) {
        return Ui.call(p::getText);
    }

    static String labels(Container c) {
        var sb = new StringBuilder();
        var stack = new ArrayList<Component>(List.of(c));
        while (!stack.isEmpty()) {
            var x = stack.remove(0);
            if (x instanceof JLabel l && l.getText() != null) sb.append(l.getText()).append(" | ");
            if (x instanceof Container k) stack.addAll(List.of(k.getComponents()));
        }
        return sb.toString();
    }

    static int count(Container root, Class<?> type) {
        var n = 0;
        var stack = new ArrayList<Component>(List.of(root));
        while (!stack.isEmpty()) {
            var c = stack.remove(0);
            if (type.isInstance(c) && c.isShowing()) n++;
            if (c instanceof Container k) stack.addAll(List.of(k.getComponents()));
        }
        return n;
    }

    static void expand(JTree tree, Predicate<ExplorerNode> p) throws Exception {
        var n = waitFor("tree node", () -> node(tree, x -> x.kind != ExplorerNode.Kind.LOADING && p.test(x)), 30000);
        edt(() -> tree.expandPath(new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n))));
        waitFor("node '" + n.name + "' to load", () -> n.loaded && !(n.getChildCount() == 1
                && ((ExplorerNode) n.getChildAt(0)).kind == ExplorerNode.Kind.LOADING) ? true : null, 30000);
        Thread.sleep(300);
    }

    static void click(JTree tree, ExplorerNode n, int clicks, int button) {
        edt(() -> {
            var path = new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n));
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            var r = tree.getPathBounds(path);
            var e = new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    r.x + 5, r.y + 5, clicks, false, button);
            for (var l : tree.getMouseListeners()) l.mouseClicked(e);
        });
    }

    static ExplorerNode node(JTree tree, Predicate<ExplorerNode> p) {
        return Ui.call(() -> {
            var all = new ArrayList<ExplorerNode>();
            var e = ((ExplorerNode) tree.getModel().getRoot()).depthFirstEnumeration();
            while (e.hasMoreElements()) all.add((ExplorerNode) e.nextElement());
            all.sort(Comparator.comparingInt(ExplorerNode::getLevel));
            return all.stream().filter(p).findFirst().orElse(null);
        });
    }

    interface Sup<T> { T get() throws Exception; }

    static <T> T waitFor(String what, Sup<T> s, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            T v = s.get();
            if (v != null) return v;
            Thread.sleep(200);
        }
        throw new IllegalStateException("timed out waiting for " + what);
    }

    static Window waitWindow(Predicate<Window> p, long ms) throws Exception {
        return waitFor("window", () -> {
            for (var w : Window.getWindows()) if (p.test(w)) return w;
            return null;
        }, ms);
    }

    static void edt(Runnable r) {
        try {
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeAndWait(r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Component> T findOrNull(Container root, Class<T> type, Predicate<T> p) {
        var stack = new ArrayList<Component>(List.of(root));
        while (!stack.isEmpty()) {
            var c = stack.remove(0);
            if (type.isInstance(c) && p.test((T) c)) return (T) c;
            if (c instanceof Container k) stack.addAll(List.of(k.getComponents()));
        }
        return null;
    }

    static <T extends Component> T find(Container root, Class<T> type, Predicate<T> p) {
        var r = findOrNull(root, type, p);
        if (r == null) throw new IllegalStateException("not found: " + type.getSimpleName());
        return r;
    }

    static void shot(Component c, String name) {
        try {
            var ref = new AtomicReference<BufferedImage>();
            edt(() -> {
                var img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), BufferedImage.TYPE_INT_RGB);
                var g = img.createGraphics();
                c.printAll(g);
                g.dispose();
                ref.set(img);
            });
            ImageIO.write(ref.get(), "png", new File(out, name + ".png"));
            System.out.println("  shot " + name);
        } catch (Exception ex) {
            System.out.println("  (screenshot " + name + " failed: " + ex + ")");
        }
    }

    static void shotAll(String prefix) {
        var i = 0;
        for (var w : Window.getWindows()) if (w.isShowing()) shot(w, prefix + "-" + (i++));
    }
}
