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
        System.out.println("config: " + AppConfig.resolvePath() + " → " + cfg.host + ":" + cfg.port + "/" + cfg.database);
        new Thread(() -> com.postgresforall.app.Main.main(new String[0]), "app-main").start();

        // ---- Login ---------------------------------------------------------
        Window login = waitWindow(w -> w instanceof JDialog d && d.getTitle().startsWith("Connect to PostgreSQL") && d.isShowing(), 60000);
        edt(() -> {
            JComboBox<?> role = find(login, JComboBox.class, c -> c.getItemCount() == 2 && "Application user".equals(c.getItemAt(0).toString()));
            role.setSelectedIndex(1);
            role.setSelectedIndex(0); // applies the config's app-user preset (host, port, user, password)
        });
        Thread.sleep(500);
        shot(login, "01-login");
        edt(() -> find(login, JButton.class, b -> b.getText().equals("Connect")).doClick());

        Window main = waitWindow(w -> w instanceof JFrame f && "PostgresForAll".equals(f.getTitle()) && f.isShowing(), 60000);
        QueryTab tab = waitFor("first query tab", () -> findOrNull(main, QueryTab.class, t -> true), 60000);
        Thread.sleep(4000);
        shot(main, "02-main");

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
        var messages = text(find(tab, JTextPane.class, p -> true));
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

        // ---- Diagnostics -------------------------------------------------------
        edt(() -> {
            var dot = find(main, JLabel.class, l -> "●".equals(l.getText()));
            var e = new MouseEvent(dot, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false);
            SwingUtilities.invokeLater(() -> { for (var l : dot.getMouseListeners()) l.mouseClicked(e); });
        });
        Window diag = waitWindow(w -> w instanceof JDialog d && d.isShowing() && d.getTitle().toLowerCase().contains("diagnostic"), 20000);
        waitFor("diagnostics finished", () -> Ui.call(() -> findOrNull(diag, JLabel.class, l -> "All green.".equals(l.getText())) != null
                || findOrNull(diag, JLabel.class, l -> l.getText() != null && l.getText().startsWith("Some checks failed")) != null) ? true : null, 30000);
        shot(diag, "08-diagnostics");
        var allGreen = Ui.call(() -> findOrNull(diag, JLabel.class, l -> "All green.".equals(l.getText())) != null);
        check("diagnostics all green", allGreen);
        var hostLine = Ui.call(() -> labels(diag));
        System.out.println("diagnostics labels: " + hostLine);
        if (Platform.isMac()) check("diagnostics found the launchd service", hostLine.contains("Service 'homebrew.mxcl.postgresql"));
        edt(() -> find(diag, JButton.class, b -> b.getText().startsWith("View logs")).doClick());
        Thread.sleep(4000);
        shot(diag, "09-diagnostics-logs");
        edt(diag::dispose);
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
