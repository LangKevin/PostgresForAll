import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.postgresforall.app.ui.explorer.ExplorerNode;
import com.postgresforall.app.ui.query.QueryTab;
import com.postgresforall.core.config.AppConfig;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Records a captioned, voice-free demo video of the real app.
 *
 * <p>A director thread drives the app through scripted scenes (moving a drawn
 * cursor, clicking, typing); a recorder thread renders the app's windows
 * offscreen at 1.5x into 1920x1080 frames, adds the cursor and captions, and
 * pipes them into ffmpeg (must be on PATH).
 *
 * <pre>
 * ./gradlew :app:installDist
 * java -cp "app/build/install/PostgresForAll/lib/*" tools/demo/Demo.java demo/PostgresForAll-demo.mp4
 * </pre>
 * Logs in with the config's "Application user" preset against the ScheduleMe
 * database; reads only (the backup goes to a temp file).
 */
public class Demo {
    // ---- video geometry -------------------------------------------------------
    static final int STAGE_W = 1280, STAGE_H = 720;      // app window size (logical px)
    static final double SCALE = 1.5;                       // → 1920 x 1080
    static final int W = (int) (STAGE_W * SCALE), H = (int) (STAGE_H * SCALE);
    static final int FPS = 12;

    // ---- shared state (written by the director, read by the recorder on the EDT) -------
    static volatile Window anchor;                 // login dialog, then main frame
    static volatile double cx = STAGE_W / 2.0, cy = STAGE_H / 2.0; // cursor, stage coords
    static volatile long clickAt = -1;
    static volatile String caption = "";
    static volatile long captionAt = 0;
    static volatile String chapter = "";
    static volatile String cardTitle = null, cardSubtitle = null;
    static volatile double cardAlpha = 0;
    static volatile boolean showCursor = false;
    static volatile boolean running = true;

    public static void main(String[] a) throws Exception {
        var out = new File(a.length > 0 ? a[0] : "demo/PostgresForAll-demo.mp4").getAbsoluteFile();
        out.getParentFile().mkdirs();
        System.out.println("config: " + AppConfig.resolvePath());

        var ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", W + "x" + H, "-r", String.valueOf(FPS), "-i", "-",
                "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", out.getPath())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        var recorder = new Thread(() -> record(new BufferedOutputStream(ffmpeg.getOutputStream(), 1 << 22)), "recorder");

        var started = System.currentTimeMillis();
        try {
            // Title card first (recorder shows it over an empty stage).
            card("PostgresForAll", "A modern PostgreSQL client for Windows and macOS", 1);
            recorder.start();
            hold(5500);
            new Thread(() -> com.postgresforall.app.Main.main(new String[0]), "app").start();
            var login = waitWindow(w -> w instanceof JDialog d && d.getTitle().startsWith("Connect to PostgreSQL") && d.isShowing(), 60000);
            anchor = login;
            fadeCard(0, 700);
            script(login);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            running = false;
            recorder.join(10000);
            ffmpeg.waitFor();
            System.out.printf("video: %s (%.0f s recorded)%n", out, (System.currentTimeMillis() - started) / 1000.0);
            Runtime.getRuntime().halt(0);
        }
    }

    // =========================================================================
    //  The script
    // =========================================================================

    static void script(Window login) throws Exception {
        showCursor = true;
        var cfg = AppConfig.current();

        // ---- 1. Sign in ------------------------------------------------------------
        chapter("1 / 10  ·  Sign in");
        say("Connect to any PostgreSQL server: native installs, Docker containers or remote hosts");
        hold(4500);
        var superuser = find(login, AbstractButton.class, b -> "Superuser".equals(b.getText()));
        var appUser = find(login, AbstractButton.class, b -> "Application user".equals(b.getText()));
        say("Presets fill in your application login, or a superuser account");
        moveTo(superuser);
        click(superuser::doClick);
        hold(2500);
        moveTo(appUser);
        click(appUser::doClick);
        hold(2000);
        say("Passwords can be remembered securely: Windows DPAPI or the macOS Keychain");
        var remember = find(login, JCheckBox.class, c -> c.getText().startsWith("Remember password"));
        moveTo(remember);
        hold(3500);
        say("Test Connection checks the login before you commit to it");
        var test = find(login, JButton.class, b -> "Test Connection".equals(b.getText()));
        moveTo(test);
        clickLater(test::doClick);
        hold(4000);
        say("Connect, and the workspace opens");
        var connect = find(login, JButton.class, b -> "Connect".equals(b.getText()));
        moveTo(connect);
        clickLater(connect::doClick);

        var main = (JFrame) waitWindow(w -> w instanceof JFrame f && "PostgresForAll".equals(f.getTitle()) && f.isShowing(), 60000);
        edt(() -> {
            main.setExtendedState(Frame.NORMAL);
            main.setBounds(40, 24, STAGE_W, STAGE_H);
            main.validate();
        });
        anchor = main;
        var tab = waitFor(() -> findOrNull(main, QueryTab.class, t -> true), 60000);
        JTree tree = waitFor(() -> findOrNull(main, JTree.class, t -> true), 60000);
        waitFor(() -> node(tree, n -> n.name.startsWith("Databases")), 30000);
        hold(3500);

        // ---- 2. Workspace tour ------------------------------------------------------
        chapter("2 / 10  ·  The workspace");
        say("Object Explorer on the left, query tabs on the right, in a dark theme by default");
        moveTo(tree, 120, 40);
        hold(4500);
        say("Every PostgreSQL server on this machine is found and listed automatically");
        var dbFolder = node(tree, n -> n.name.startsWith("Databases"));
        moveTo(tree, nodeCenter(tree, dbFolder));
        hold(4500);
        say("The connection pill shows where the active tab runs. Click it to switch");
        var pill = find(main, JButton.class, b -> b.getText() != null && b.getText().contains("›"));
        moveTo(pill);
        hold(4500);
        say("The status bar shows connection health, server version and cursor position");
        var dot = find(main, JLabel.class, l -> "●".equals(l.getText()));
        moveTo(dot);
        hold(4500);

        // ---- 3. Browse ----------------------------------------------------------------
        chapter("3 / 10  ·  Browse your databases");
        say("Browse servers, databases, schemas, tables, views, functions and roles");
        expand(tree, n -> n.kind == ExplorerNode.Kind.SERVER && n.name.equals("localhost:" + cfg.port) && n.getLevel() > 1);
        expand(tree, n -> n.kind == ExplorerNode.Kind.DATABASE && n.name.startsWith(cfg.database));
        expand(tree, n -> n.name.equals("Schemas"));
        expand(tree, n -> n.kind == ExplorerNode.Kind.SCHEMA && n.name.equals("public"));
        expand(tree, n -> n.name.startsWith("Tables ("));
        hold(2500);
        say("Columns show their types, NOT NULL and primary keys");
        expand(tree, n -> n.kind == ExplorerNode.Kind.TABLE && n.name.equals("users"));
        expand(tree, n -> n.name.startsWith("Columns (") && ((ExplorerNode) n.getParent()).name.equals("users"));
        hold(4000);
        say("Filter the tree as you type");
        var filter = find(main, JTextField.class, f -> "Filter loaded objects".equals(f.getClientProperty("JTextField.placeholderText")));
        moveTo(filter);
        click(() -> { });
        typeField(filter, "task");
        hold(4000);
        edt(() -> filter.setText(""));
        edt(() -> collapseTo(tree, n -> n.name.startsWith("Columns (")));
        hold(1500);

        // ---- 4. Write and run SQL -----------------------------------------------------------
        chapter("4 / 10  ·  Write and run SQL");
        say("The editor has syntax highlighting, bracket matching and line numbers");
        moveTo(tab.editor(), 300, 60);
        edt(() -> tab.setSqlText(""));
        typeEditor(tab, """
                -- How busy is each user?
                SELECT u.email,
                       count(e.id)       AS events,
                       min(e.start_time) AS first_event
                FROM users u
                LEFT JOIN events e ON e.user_id = u.id
                GROUP BY u.email
                ORDER BY events DESC
                LIMIT 25;
                """);
        hold(1500);
        say("Run with F5 or Ctrl+Enter: just the selection, or the whole script");
        var run = find(main, JButton.class, b -> "Run".equals(b.getText()));
        moveTo(run);
        click(run::doClick);
        waitFor(() -> tab.isExecuting() ? null : true, 30000);
        hold(3000);
        say("Results land in a fast grid with zebra rows, NULL markers and column-type tooltips");
        var grid = waitFor(() -> findOrNull(tab, JTable.class, JComponent::isShowing), 10000);
        moveTo(grid, 200, 60);
        hold(1200);
        moveTo(grid, 200, 120);
        hold(1500);
        say("Click a column header to sort");
        var header = grid.getTableHeader();
        var colRect = Ui.call(() -> header.getHeaderRect(1));
        moveTo(header, (int) colRect.getCenterX(), (int) colRect.getCenterY());
        click(() -> clickHeader(header, colRect));
        hold(2500);
        click(() -> clickHeader(header, colRect));
        hold(2500);
        say("Copy everything as TSV, CSV or JSON, or export straight to a CSV file");
        var copyBtn = find(tab, JButton.class, b -> "Copy all rows as…".equals(b.getToolTipText()));
        moveTo(copyBtn);
        click(copyBtn::doClick);
        hold(4000);
        closeMenus();
        hold(800);

        // ---- 5. Scripts and messages -------------------------------------------------------------
        chapter("5 / 10  ·  Scripts and messages");
        say("Run whole scripts: every result set, NOTICE and WARNING is captured");
        moveTo(tab.editor(), 300, 60);
        edt(() -> tab.setSqlText(""));
        typeEditor(tab, """
                SELECT count(*) AS users FROM users;
                DO $$ BEGIN
                  RAISE NOTICE 'There are % users', (SELECT count(*) FROM users);
                  RAISE WARNING 'Just a friendly warning';
                END $$;
                SELECT * FROM table_that_does_not_exist;
                """);
        moveTo(run);
        click(run::doClick);
        waitFor(() -> tab.isExecuting() ? null : true, 30000);
        hold(2500);
        say("Errors stop the run and show the SQLSTATE, with any detail and hint");
        var msgs = find(tab, JList.class, l -> true);
        moveTo(msgs, 300, 50);
        hold(5000);

        // ---- 6. Script a table -------------------------------------------------------------------------
        chapter("6 / 10  ·  Script a table");
        say("Double-click a table to script SELECT … LIMIT 1000 into a new tab and run it");
        var users = node(tree, n -> n.kind == ExplorerNode.Kind.TABLE && n.name.equals("users"));
        moveTo(tree, nodeCenter(tree, users));
        click(() -> doubleClickNode(tree, users));
        var scripted = waitFor(() -> findOrNull(main, QueryTab.class, t -> t != tab && t.isShowing()), 20000);
        waitFor(() -> !scripted.isExecuting() && scripted.statusText().contains("row(s)") ? true : null, 20000);
        hold(5500);

        // ---- 7. Switch database --------------------------------------------------------------------------
        chapter("7 / 10  ·  Switch connection");
        say("Point a tab at another database, or another server, in two clicks");
        moveTo(pill);
        clickLater(pill::doClick);
        var popup = waitFor(() -> Ui.call(() -> visiblePopup()), 15000);
        hold(1200);
        var serverMenu = Ui.call(() -> {
            for (var c : popup.getComponents()) if (c instanceof JMenu m && m.getText().startsWith("localhost:" + cfg.port)) return m;
            return null;
        });
        moveTo(serverMenu);
        edt(() -> MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{popup, serverMenu, serverMenu.getPopupMenu()}));
        hold(1500);
        var pgItem = Ui.call(() -> {
            for (var c : serverMenu.getMenuComponents()) if (c instanceof JMenuItem mi && "postgres".equals(mi.getText())) return mi;
            return null;
        });
        moveTo(pgItem);
        click(pgItem::doClick);
        closeMenus();
        waitFor(() -> "postgres".equals(scripted.currentDatabase()) ? true : null, 20000);
        hold(1500);
        edt(() -> scripted.setSqlText("SELECT datname, pg_size_pretty(pg_database_size(datname)) AS size\nFROM pg_database\nWHERE NOT datistemplate\nORDER BY 1;"));
        moveTo(run);
        click(run::doClick);
        waitFor(() -> scripted.isExecuting() ? null : true, 30000);
        hold(4500);

        // ---- 8. Backup ---------------------------------------------------------------------------------------
        chapter("8 / 10  ·  Backup and restore");
        say("Back up and restore with pg_dump / pg_restore, with live progress in the Output panel");
        var db = node(tree, n -> n.kind == ExplorerNode.Kind.DATABASE && n.name.startsWith(cfg.database));
        moveTo(tree, nodeCenter(tree, db));
        click(() -> rightClickNode(tree, db));
        var ctx = waitFor(() -> Ui.call(() -> visiblePopup()), 10000);
        hold(1800);
        var backupItem = Ui.call(() -> {
            for (var c : ctx.getComponents()) if (c instanceof JMenuItem mi && mi.getText().startsWith("Backup database")) return mi;
            return null;
        });
        moveTo(backupItem);
        clickLater(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            backupItem.doClick();
        });
        var chooserDialog = waitWindow(w -> w instanceof JDialog d && d.isShowing() && findOrNull(d, JFileChooser.class, x -> true) != null, 15000);
        hold(2500);
        var chooser = find(chooserDialog, JFileChooser.class, x -> true);
        var target = new File(System.getProperty("java.io.tmpdir"), "scheduleme-demo-backup.dump");
        target.delete();
        edt(() -> chooser.setSelectedFile(target));
        hold(1200);
        edt(chooser::approveSelection);
        hold(6500);
        target.delete();

        // ---- 9. Diagnostics -------------------------------------------------------------------------------------------
        chapter("9 / 10  ·  Diagnostics");
        say("Diagnostics walks through each step of the connection and offers one-click fixes");
        moveTo(dot);
        clickLater(() -> {
            var e = new MouseEvent(dot, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, 4, 1, false);
            for (var l : dot.getMouseListeners()) l.mouseClicked(e);
        });
        var diag = waitWindow(w -> w instanceof JDialog d && d.isShowing() && d.getTitle().toLowerCase().contains("diagnostic"), 20000);
        waitFor(() -> Ui.call(() -> findOrNull(diag, JLabel.class, l -> "All green.".equals(l.getText())) != null) ? true : null, 30000);
        hold(4500);
        say("It reads the server log and flags known problems, with a fix for each");
        var logs = find(diag, JButton.class, b -> b.getText() != null && b.getText().startsWith("View logs"));
        moveTo(logs);
        click(logs::doClick);
        hold(6000);
        var close = find(diag, JButton.class, b -> "Close".equals(b.getText()));
        moveTo(close);
        click(close::doClick);
        hold(1200);

        // ---- 10. Appearance ---------------------------------------------------------------------------------------------
        chapter("10 / 10  ·  Appearance");
        say("Dark by default. Switch to light, or follow your system, from View › Appearance");
        var bar = main.getJMenuBar();
        var view = Ui.call(() -> {
            for (var i = 0; i < bar.getMenuCount(); i++) if ("View".equals(bar.getMenu(i).getText())) return bar.getMenu(i);
            return null;
        });
        moveTo(view);
        edt(() -> MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{bar, view, view.getPopupMenu()}));
        hold(1200);
        var appearance = Ui.call(() -> {
            for (var c : view.getMenuComponents()) if (c instanceof JMenu m && "Appearance".equals(m.getText())) return m;
            return null;
        });
        moveTo(appearance);
        edt(() -> MenuSelectionManager.defaultManager().setSelectedPath(
                new MenuElement[]{bar, view, view.getPopupMenu(), appearance, appearance.getPopupMenu()}));
        hold(1500);
        var light = Ui.call(() -> {
            for (var c : appearance.getMenuComponents()) if (c instanceof JMenuItem mi && "Light".equals(mi.getText())) return mi;
            return null;
        });
        moveTo(light);
        click(light::doClick);
        closeMenus();
        hold(5500);
        say("…and back to dark");
        edt(() -> com.postgresforall.app.ui.theme.Theme.setMode(com.postgresforall.app.ui.theme.Theme.Mode.DARK));
        hold(3500);

        // ---- Closing card -------------------------------------------------------------------------------------------------
        say("");
        chapter("");
        showCursor = false;
        card("PostgresForAll", "Java 21  ·  Swing + FlatLaf  ·  Windows and macOS", 0);
        fadeCard(1, 900);
        hold(6000);
    }

    // =========================================================================
    //  Director helpers (called off the EDT)
    // =========================================================================

    static void say(String text) {
        caption = text;
        captionAt = System.currentTimeMillis();
    }

    static void chapter(String c) {
        chapter = c;
    }

    static void card(String title, String subtitle, double alpha) {
        cardTitle = title;
        cardSubtitle = subtitle;
        cardAlpha = alpha;
    }

    static void fadeCard(double to, long ms) throws InterruptedException {
        var from = cardAlpha;
        var t0 = System.currentTimeMillis();
        while (true) {
            var f = Math.min(1, (System.currentTimeMillis() - t0) / (double) ms);
            cardAlpha = from + (to - from) * f;
            if (f >= 1) break;
            Thread.sleep(16);
        }
    }

    /** Pauses scale with this so the whole video lands near five minutes. */
    static final double PACE = 1.42;

    static void hold(long ms) throws InterruptedException {
        Thread.sleep((long) (ms * PACE));
    }

    /** Closes any open menu / popup (programmatic menu clicks don't dismiss them). */
    static void closeMenus() {
        edt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            var p = visiblePopup();
            for (var i = 0; p != null && i < 5; i++) {
                p.setVisible(false);
                p = visiblePopup();
            }
        });
    }

    /** Smoothly moves the cursor to a point on {@code c}, sending hover events on the way. */
    static void moveTo(Component c, int x, int y) throws InterruptedException {
        var target = Ui.call(() -> stagePoint(c, new Point(x, y)));
        if (target == null) return;
        var sx = cx;
        var sy = cy;
        var dist = Math.hypot(target.x - sx, target.y - sy);
        var ms = (long) Math.max(350, Math.min(1100, dist * 1.4));
        var t0 = System.currentTimeMillis();
        while (true) {
            var f = Math.min(1, (System.currentTimeMillis() - t0) / (double) ms);
            var e = f < 0.5 ? 4 * f * f * f : 1 - Math.pow(-2 * f + 2, 3) / 2; // ease in-out
            cx = sx + (target.x - sx) * e;
            cy = sy + (target.y - sy) * e;
            hover();
            if (f >= 1) break;
            Thread.sleep(16);
        }
        Thread.sleep(250);
    }

    static void moveTo(Component c) throws InterruptedException {
        var d = Ui.call(c::getSize);
        moveTo(c, d.width / 2, d.height / 2);
    }

    static void moveTo(JTree tree, Point p) throws InterruptedException {
        moveTo(tree, p.x, p.y);
    }

    static void click(Runnable action) throws InterruptedException {
        clickAt = System.currentTimeMillis();
        Thread.sleep(150);
        edt(action);
        Thread.sleep(250);
    }

    /** For actions that open a modal dialog (they block until it closes). */
    static void clickLater(Runnable action) throws InterruptedException {
        clickAt = System.currentTimeMillis();
        Thread.sleep(150);
        SwingUtilities.invokeLater(action);
        Thread.sleep(400);
    }

    /** Dispatches a mouse-moved event to whatever is under the cursor (hover effects). */
    static void hover() {
        SwingUtilities.invokeLater(() -> {
            var w = windowAtCursor();
            if (w == null) return;
            var o = origin(w);
            var p = new Point((int) (cx - o.x), (int) (cy - o.y));
            var root = w instanceof RootPaneContainer r ? r.getRootPane() : w;
            var rp = SwingUtilities.convertPoint(w, p, root);
            var deepest = SwingUtilities.getDeepestComponentAt(root, rp.x, rp.y);
            if (deepest == null) return;
            var dp = SwingUtilities.convertPoint(root, rp, deepest);
            deepest.dispatchEvent(new MouseEvent(deepest, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                    dp.x, dp.y, 0, false));
        });
    }

    static Window windowAtCursor() {
        Window best = null;
        for (var w : Window.getWindows()) {
            if (!w.isShowing() || !inScene(w)) continue;
            var o = origin(w);
            if (cx >= o.x && cy >= o.y && cx < o.x + w.getWidth() && cy < o.y + w.getHeight()) best = w;
        }
        return best;
    }

    static void typeEditor(QueryTab tab, String text) throws InterruptedException {
        for (var ch : text.toCharArray()) {
            edt(() -> {
                var ed = tab.editor();
                try {
                    ed.getDocument().insertString(ed.getCaretPosition(), String.valueOf(ch), null);
                } catch (Exception ignored) {
                    // offsets come from the caret
                }
            });
            Thread.sleep(ch == '\n' ? 160 : 40);
        }
    }

    static void typeField(JTextField f, String text) throws InterruptedException {
        for (var ch : text.toCharArray()) {
            edt(() -> f.setText(f.getText() + ch));
            Thread.sleep(260);
        }
    }

    static void expand(JTree tree, Predicate<ExplorerNode> p) throws Exception {
        var n = waitFor(() -> node(tree, x -> x.kind != ExplorerNode.Kind.LOADING && p.test(x)), 30000);
        moveTo(tree, nodeCenter(tree, n));
        click(() -> {
            var path = new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n));
            tree.setSelectionPath(path);
            tree.expandPath(path);
        });
        waitFor(() -> n.loaded && !(n.getChildCount() == 1
                && ((ExplorerNode) n.getChildAt(0)).kind == ExplorerNode.Kind.LOADING) ? true : null, 30000);
        Thread.sleep(700);
    }

    static void collapseTo(JTree tree, Predicate<ExplorerNode> p) {
        var n = node(tree, p);
        if (n != null) tree.collapsePath(new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n)));
    }

    static Point nodeCenter(JTree tree, ExplorerNode n) {
        return Ui.call(() -> {
            var path = new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n));
            tree.scrollPathToVisible(path);
            var r = tree.getPathBounds(path);
            return r == null ? new Point(60, 20) : new Point(r.x + Math.min(r.width / 2, 70), r.y + r.height / 2);
        });
    }

    static void doubleClickNode(JTree tree, ExplorerNode n) {
        var path = new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n));
        tree.setSelectionPath(path);
        var r = tree.getPathBounds(path);
        var e = new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                r.x + 8, r.y + r.height / 2, 2, false, MouseEvent.BUTTON1);
        for (var l : tree.getMouseListeners()) l.mouseClicked(e);
    }

    static void rightClickNode(JTree tree, ExplorerNode n) {
        var path = new TreePath(((DefaultTreeModel) tree.getModel()).getPathToRoot(n));
        tree.setSelectionPath(path);
        var r = tree.getPathBounds(path);
        var e = new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON3_DOWN_MASK,
                r.x + 30, r.y + r.height / 2, 1, true, MouseEvent.BUTTON3);
        for (var l : tree.getMouseListeners()) l.mousePressed(e);
    }

    static void clickHeader(JTableHeader header, Rectangle r) {
        var x = (int) r.getCenterX();
        var y = (int) r.getCenterY();
        var t = System.currentTimeMillis();
        header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_PRESSED, t, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_RELEASED, t, 0, x, y, 1, false, MouseEvent.BUTTON1));
        header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_CLICKED, t, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    static JPopupMenu visiblePopup() {
        for (var w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            var c = w instanceof RootPaneContainer r ? r.getRootPane() : (Container) w;
            var pm = findOrNull(c, JPopupMenu.class, Component::isShowing);
            if (pm != null) return pm;
        }
        return null;
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

    // =========================================================================
    //  Recorder
    // =========================================================================

    static void record(OutputStream ffmpeg) {
        var frame = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        var bytes = ((DataBufferByte) frame.getRaster().getDataBuffer()).getData();
        var frameMs = 1000.0 / FPS;
        var t0 = System.currentTimeMillis();
        long written = 0;
        try {
            while (running) {
                edt(() -> render(frame));
                var due = (long) ((System.currentTimeMillis() - t0) / frameMs) + 1;
                // Keep wall-clock time: repeat this frame for any slots rendering fell behind on.
                do {
                    ffmpeg.write(bytes);
                    written++;
                } while (written < due);
                var next = t0 + (long) (written * frameMs);
                var sleep = next - System.currentTimeMillis();
                if (sleep > 0) Thread.sleep(sleep);
            }
            ffmpeg.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        System.out.println("frames: " + written);
    }

    static boolean inScene(Window w) {
        var a = anchor;
        if (a == null) return false;
        if (w == a) return true;
        // Dialogs / popups / toasts of the app; skip the login dialog once it's gone.
        return w.isShowing() && (w.getOwner() != null || w instanceof JDialog || w instanceof JWindow);
    }

    /** Where window {@code w} sits on the stage (logical px). */
    static Point origin(Window w) {
        var a = anchor;
        // The login dialog is centred on the stage; the main window fills it.
        var ao = a instanceof JDialog
                ? new Point((STAGE_W - a.getWidth()) / 2, (STAGE_H - a.getHeight()) / 2)
                : new Point(0, 0);
        if (w == a) return ao;
        try {
            var wl = w.getLocationOnScreen();
            var al = a.getLocationOnScreen();
            return new Point(ao.x + wl.x - al.x, ao.y + wl.y - al.y);
        } catch (IllegalComponentStateException ex) {
            return ao;
        }
    }

    static Point stagePoint(Component c, Point p) {
        var w = SwingUtilities.getWindowAncestor(c);
        if (c instanceof Window cw) w = cw;
        if (w == null || !c.isShowing()) return null;
        var inWindow = SwingUtilities.convertPoint(c, p, w);
        var o = origin(w);
        return new Point(o.x + inWindow.x, o.y + inWindow.y);
    }

    static Font font(float size, boolean bold) {
        var base = UIManager.getFont("defaultFont");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return base.deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
    }

    static void render(BufferedImage frame) {
        var g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Stage backdrop
        g.setPaint(new GradientPaint(0, 0, new Color(0x0F1720), W, H, new Color(0x1B2B3D)));
        g.fillRect(0, 0, W, H);

        var a = anchor;
        if (a != null && a.isShowing()) {
            for (var w : Window.getWindows()) {
                if (!w.isShowing() || !inScene(w)) continue;
                var o = origin(w);
                var wg = (Graphics2D) g.create();
                wg.scale(SCALE, SCALE);
                wg.translate(o.x, o.y);
                if (w != a || a instanceof JDialog) {
                    // soft shadow under floating windows
                    wg.setColor(new Color(0, 0, 0, 90));
                    wg.fill(new RoundRectangle2D.Double(-2, 4, w.getWidth() + 4, w.getHeight() + 4, 12, 12));
                }
                wg.setClip(0, 0, w.getWidth(), w.getHeight());
                try {
                    w.printAll(wg);
                } catch (RuntimeException ignored) {
                    // a window closing mid-paint
                }
                wg.dispose();
            }
        }

        // Cursor + click ripple
        if (showCursor && cardAlpha < 0.5) {
            var x = cx * SCALE;
            var y = cy * SCALE;
            var since = System.currentTimeMillis() - clickAt;
            if (since >= 0 && since < 450) {
                var f = since / 450.0;
                var r = 10 + 28 * f;
                g.setColor(new Color(108, 160, 216, (int) (200 * (1 - f))));
                g.setStroke(new BasicStroke(4f));
                g.draw(new java.awt.geom.Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            }
            var arrow = new Path2D.Double();
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 27);
            arrow.lineTo(7, 21);
            arrow.lineTo(12, 32);
            arrow.lineTo(17, 30);
            arrow.lineTo(12, 19);
            arrow.lineTo(21, 19);
            arrow.closePath();
            var cg = (Graphics2D) g.create();
            cg.translate(x, y);
            cg.setColor(new Color(0, 0, 0, 70));
            cg.translate(2, 3);
            cg.fill(arrow);
            cg.translate(-2, -3);
            cg.setColor(Color.WHITE);
            cg.fill(arrow);
            cg.setColor(Color.BLACK);
            cg.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            cg.draw(arrow);
            cg.dispose();
        }

        // Chapter tag (top-left)
        var ch = chapter;
        if (ch != null && !ch.isEmpty() && cardAlpha < 0.5) {
            g.setFont(font(22f, true));
            var fm = g.getFontMetrics();
            var w = fm.stringWidth(ch) + 36;
            var tx = (W - w) / 2;
            g.setColor(new Color(51, 103, 145, 240));
            g.fill(new RoundRectangle2D.Double(tx, 8, w, 42, 42, 42));
            g.setColor(Color.WHITE);
            g.drawString(ch, tx + 18, 8 + 29);
        }

        // Caption (bottom)
        var text = caption;
        if (text != null && !text.isEmpty() && cardAlpha < 0.5) {
            var fade = Math.min(1, (System.currentTimeMillis() - captionAt) / 300.0);
            g.setFont(font(34f, true));
            var fm = g.getFontMetrics();
            var lines = wrap(text, fm, 1500);
            var lineH = fm.getHeight();
            var boxW = lines.stream().mapToInt(fm::stringWidth).max().orElse(0) + 70;
            var boxH = lineH * lines.size() + 34;
            var bx = (W - boxW) / 2;
            var by = H - boxH - 44;
            var cg = (Graphics2D) g.create();
            cg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) fade));
            cg.setColor(new Color(10, 14, 20, 215));
            cg.fill(new RoundRectangle2D.Double(bx, by, boxW, boxH, 26, 26));
            cg.setColor(new Color(108, 160, 216));
            cg.fill(new RoundRectangle2D.Double(bx, by, 10, boxH, 10, 10));
            cg.setColor(Color.WHITE);
            for (var i = 0; i < lines.size(); i++) {
                var lw = fm.stringWidth(lines.get(i));
                cg.drawString(lines.get(i), (W - lw) / 2 + 5, by + 17 + fm.getAscent() + i * lineH);
            }
            cg.dispose();
        }

        // Title card
        if (cardAlpha > 0 && cardTitle != null) {
            var cg = (Graphics2D) g.create();
            cg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, cardAlpha)));
            cg.setPaint(new GradientPaint(0, 0, new Color(0x0F1720), W, H, new Color(0x223A55)));
            cg.fillRect(0, 0, W, H);
            try {
                var icon = new FlatSVGIcon("icons/database.svg", 120, 120);
                icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> new Color(0x6CA0D8)));
                icon.paintIcon(null, cg, (W - 120) / 2, H / 2 - 230);
            } catch (RuntimeException ignored) {
                // icon is decoration only
            }
            cg.setColor(Color.WHITE);
            cg.setFont(font(96f, true));
            var fm = cg.getFontMetrics();
            cg.drawString(cardTitle, (W - fm.stringWidth(cardTitle)) / 2, H / 2 + 20);
            cg.setColor(new Color(0xB8C7D9));
            cg.setFont(font(40f, false));
            fm = cg.getFontMetrics();
            cg.drawString(cardSubtitle, (W - fm.stringWidth(cardSubtitle)) / 2, H / 2 + 95);
            cg.dispose();
        }
        g.dispose();
    }

    static List<String> wrap(String text, FontMetrics fm, int max) {
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        for (var word : text.split(" ")) {
            if (line.length() > 0 && fm.stringWidth(line + " " + word) > max) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    // =========================================================================
    //  Swing utilities
    // =========================================================================

    static final class Ui {
        static <T> T call(java.util.function.Supplier<T> s) {
            var ref = new AtomicReference<T>();
            edt(() -> ref.set(s.get()));
            return ref.get();
        }
    }

    interface Sup<T> { T get() throws Exception; }

    static <T> T waitFor(Sup<T> s, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            T v = s.get();
            if (v != null) return v;
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out");
    }

    static Window waitWindow(Predicate<Window> p, long ms) throws Exception {
        return waitFor(() -> Ui.call(() -> {
            for (var w : Window.getWindows()) if (p.test(w)) return w;
            return null;
        }), ms);
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
        var r = Ui.call(() -> findOrNull(root, type, p));
        if (r == null) throw new IllegalStateException("not found: " + type.getSimpleName());
        return r;
    }
}
