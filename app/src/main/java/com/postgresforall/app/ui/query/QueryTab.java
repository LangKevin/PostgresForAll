package com.postgresforall.app.ui.query;

import com.postgresforall.app.ui.FileDialogs;
import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.core.model.QueryResult;
import com.postgresforall.core.model.QueryResult.Message;
import com.postgresforall.core.model.QueryResult.MessageSeverity;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.ConnectionErrors;
import com.postgresforall.core.service.ConnectionLease;
import com.postgresforall.core.service.ConnectionManager;
import com.postgresforall.core.service.DdlPredicates;
import com.postgresforall.core.service.FileTextLoader;
import com.postgresforall.core.service.QueryExecutor;
import com.postgresforall.core.service.ScriptSplitter;
import com.postgresforall.core.service.ServerSession;
import com.postgresforall.core.util.CancelToken;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One query tab: SQL editor over a results area (one grid per result set) and
 * a Messages tab. Bound to a database on one server through a
 * {@link ConnectionLease}, which keeps that database's pool alive while the
 * tab exists. Switch database / server with {@link #setDatabase} /
 * {@link #setServer}.
 */
public final class QueryTab extends JPanel {

    public enum Change { TITLE, STATUS, CONNECTION }

    private static final int RESULT_SET_CAP = 25;

    private volatile ServerSession session;
    private volatile ConnectionLease lease;
    private volatile ConnectionManager manager;
    private volatile QueryExecutor executor;
    private CancelToken cts;
    private boolean disposed;

    private String title = "SQLQuery1.sql";
    private Path filePath;
    private boolean dirty;
    private boolean executing;
    private boolean suppressDirty;
    private String currentDatabase;
    private String statusText = "Ready.";

    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();

    private final RSyntaxTextArea editor = new RSyntaxTextArea(6, 80);
    private final ChipLabel chip = new ChipLabel();
    private final JCheckBox wrapBox = new JCheckBox("Wrap in transaction (BEGIN...COMMIT/ROLLBACK)");
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JPanel resultsHolder = new JPanel(new BorderLayout());
    private final JLabel capBanner = new JLabel();
    private final JTextPane messages = new JTextPane();
    private final JTabbedPane resultTabs = new JTabbedPane();
    private final Timer wrapCheck;

    public QueryTab(ServerSession session, ConnectionLease initialLease) {
        super(new BorderLayout());
        this.session = session;
        this.lease = initialLease;
        this.manager = initialLease.manager();
        this.executor = new QueryExecutor(manager);
        this.currentDatabase = manager.profile().database;

        // ---- top strip --------------------------------------------------
        var strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        strip.add(chip);
        wrapBox.setToolTipText("Wrap the script in BEGIN ... COMMIT / ROLLBACK on error.");
        strip.add(wrapBox);
        var loadBtn = new JButton("Load file...");
        loadBtn.setToolTipText("Replace this tab's contents with a file from disk.");
        loadBtn.addActionListener(e -> loadFile());
        strip.add(loadBtn);
        statusLabel.setForeground(Palette.muted());
        strip.add(statusLabel);
        add(strip, BorderLayout.NORTH);

        // ---- editor ------------------------------------------------------
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        editor.setCodeFoldingEnabled(false);
        editor.setAntiAliasingEnabled(true);
        editor.setTabSize(4);
        editor.setFont(editorFont());
        editor.setText("-- Welcome to PostgresForAll\n-- F5 (or " + Ui.menuKeyText()
                + "Enter) runs the buffer, or just the selected text\n\nSELECT version();\n");
        editor.discardAllEdits();
        var popup = editor.getPopupMenu();
        popup.addSeparator();
        popup.add(item("Load file...", this::loadFile));
        popup.addSeparator();
        popup.add(item("Execute (F5)", () -> execute(null)));
        popup.add(item("Execute selection", () -> {
            var sel = editor.getSelectedText();
            if (sel != null && !sel.isBlank()) execute(sel);
        }));
        var scroll = new RTextScrollPane(editor);
        scroll.setLineNumbersEnabled(true);

        // ---- results -----------------------------------------------------
        capBanner.setOpaque(true);
        capBanner.setBackground(Palette.bannerInfo());
        capBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.bannerInfoBorder()),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        capBanner.setVisible(false);
        var resultsPanel = new JPanel(new BorderLayout(0, 4));
        resultsPanel.add(capBanner, BorderLayout.NORTH);
        resultsPanel.add(resultsHolder, BorderLayout.CENTER);
        messages.setEditable(false);
        messages.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultTabs.addTab("Results", resultsPanel);
        resultTabs.addTab("Messages", new JScrollPane(messages));

        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, resultTabs);
        split.setResizeWeight(1.0 / 3.0);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
        // Editor gets the top third once the split has a real size (a proportional
        // location set before layout is ignored, leaving the editor's preferred height).
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean placed;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (placed || split.getHeight() <= 0) return;
                placed = true;
                split.setDividerLocation(1.0 / 3.0);
            }
        });

        // ---- behaviour ---------------------------------------------------
        wrapCheck = new Timer(300, e -> reevaluateTransactionWrap());
        wrapCheck.setRepeats(false);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { /* attribute changes only */ }
        });
        refreshChip(true, null);
        reevaluateTransactionWrap();
    }

    private static Font editorFont() {
        var name = switch (Platform.current()) {
            case WINDOWS -> "Consolas";
            case MAC -> "Menlo";
            case LINUX -> Font.MONOSPACED;
        };
        return new Font(name, Font.PLAIN, 13);
    }

    private static JMenuItem item(String text, Runnable r) {
        var mi = new JMenuItem(text);
        mi.addActionListener(e -> r.run());
        return mi;
    }

    // ---- Properties ------------------------------------------------------

    public void onChange(Consumer<Change> listener) {
        listeners.add(listener);
    }

    private void fire(Change c) {
        for (var l : listeners) l.accept(c);
    }

    public ServerSession session() {
        return session;
    }

    public ConnectionManager manager() {
        return manager;
    }

    public String currentDatabase() {
        return currentDatabase;
    }

    public String title() {
        return title;
    }

    public void setTitle(String t) {
        title = t;
        fire(Change.TITLE);
    }

    public boolean isDirty() {
        return dirty;
    }

    public Path filePath() {
        return filePath;
    }

    public String statusText() {
        return statusText;
    }

    public boolean isExecuting() {
        return executing;
    }

    /** "5444 / scheduleme" — the server port and database shown in the chip and tab title. */
    public String connectionLabel() {
        return session.server().port() + " / " + currentDatabase;
    }

    public RSyntaxTextArea editor() {
        return editor;
    }

    public void setSqlText(String sql) {
        editor.setText(sql);
        editor.setCaretPosition(0);
    }

    /** Replaces the text without marking the tab dirty (scripted or freshly loaded SQL). */
    public void setInitialSql(String sql) {
        suppressDirty = true;
        try {
            setSqlText(sql);
        } finally {
            suppressDirty = false;
        }
    }

    private void setStatus(String s) {
        statusText = s;
        statusLabel.setText(s);
        fire(Change.STATUS);
    }

    private void onEdited() {
        if (!suppressDirty && !dirty) {
            dirty = true;
            fire(Change.TITLE);
        }
        wrapCheck.restart();
    }

    private void reevaluateTransactionWrap() {
        var sql = editor.getText();
        var nonTransactional = DdlPredicates.containsNonTransactionalStatements(sql);
        var hasCreateDb = DdlPredicates.indexOfCreateDatabaseStatement(ScriptSplitter.split(sql)) >= 0;
        wrapBox.setEnabled(!nonTransactional);
        if (nonTransactional && wrapBox.isSelected()) wrapBox.setSelected(false);
        wrapBox.setToolTipText(nonTransactional
                ? "Disabled — this script contains a statement that Postgres won't allow in a transaction "
                + "(e.g. CREATE/DROP/ALTER DATABASE, VACUUM, REINDEX DATABASE, CREATE INDEX CONCURRENTLY)."
                : hasCreateDb
                ? "Wrap subsequent statements in BEGIN/COMMIT — the CREATE DATABASE itself is always run "
                + "outside a transaction by smart-execute."
                : "Wrap the script in BEGIN ... COMMIT / ROLLBACK on error.");
    }

    private void refreshChip(boolean ok, String error) {
        var m = manager;
        chip.setText("DB: " + connectionLabel());
        if (ok) {
            chip.color = Palette.success();
            chip.setToolTipText("<html>Connected: " + Ui.escapeHtml(m.profile().username + "@" + m.profile().host + ":"
                    + m.profile().port + "/" + currentDatabase) + "<br>PostgreSQL " + m.serverVersion() + "</html>");
        } else {
            chip.color = Palette.danger();
            chip.setToolTipText(error != null ? error : "Connection failed.");
        }
        chip.repaint();
    }

    // ---- Switching -------------------------------------------------------

    /**
     * Switches this tab to another database on the same server. On success the
     * previous lease is released; on failure the tab keeps its database and the
     * chip turns red. Call off the EDT.
     */
    public boolean setDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) return false;
        if (dbName.equals(currentDatabase)) return true;
        return switchTo(session, dbName);
    }

    /** SSMS "Change Connection": point this tab at a database on another connected server. Call off the EDT. */
    public boolean setServer(ServerSession target, String dbName) {
        if (target == session) return setDatabase(dbName);
        return switchTo(target, dbName);
    }

    private boolean switchTo(ServerSession target, String dbName) {
        var factory = target.factory();
        if (factory == null) {
            Ui.edt(() -> setStatus(target.server().address() + " is not connected."));
            return false;
        }
        ConnectionLease newLease;
        try {
            newLease = factory.acquire(dbName);
        } catch (SQLException | RuntimeException ex) {
            var msg = ConnectionErrors.message(ex);
            Ui.edt(() -> {
                refreshChip(false, msg);
                setStatus("Failed to switch to '" + dbName + "': " + msg);
            });
            return false;
        }

        var oldLease = lease;
        var serverChanged = target != session;
        Ui.edt(() -> {
            session = target;
            lease = newLease;
            manager = newLease.manager();
            executor = new QueryExecutor(manager);
            currentDatabase = dbName;
            refreshChip(true, null);
            setStatus(serverChanged ? "Switched to " + target.server().address() + " / '" + dbName + "'."
                    : "Switched to '" + dbName + "'.");
            fire(Change.CONNECTION);
            fire(Change.TITLE);
        });
        try { oldLease.close(); } catch (RuntimeException ignored) { }
        return true;
    }

    // ---- Execution -------------------------------------------------------

    /** F5: runs the selection when there is one, else the whole buffer. Call on the EDT. */
    public void executeSelectionOrAll() {
        var sel = editor.getSelectedText();
        execute(sel == null || sel.isBlank() ? null : sel);
    }

    /** Runs {@code selectionOrNull} (or the whole buffer). Call on the EDT. */
    public void execute(String selectionOrNull) {
        if (executing) return;
        var sql = selectionOrNull == null || selectionOrNull.isBlank() ? editor.getText() : selectionOrNull;
        if (sql.isBlank()) return;

        executing = true;
        resultsHolder.removeAll();
        resultsHolder.revalidate();
        resultsHolder.repaint();
        capBanner.setVisible(false);
        messages.setText("");
        setStatus("Executing...");

        var token = new CancelToken();
        cts = token;
        var exec = executor;
        var wrap = wrapBox.isSelected() && wrapBox.isEnabled();
        Thread.ofVirtual().name("query").start(() -> {
            try {
                var needsSmart = DdlPredicates.indexOfCreateDatabaseStatement(ScriptSplitter.split(sql)) >= 0;
                var result = needsSmart ? exec.smartExecute(sql, wrap, token) : exec.execute(sql, wrap, token);
                Ui.later(() -> showResult(result));
            } catch (Exception ex) {
                Ui.later(() -> {
                    appendMessage(new Message(MessageSeverity.ERROR, ConnectionErrors.message(ex)));
                    setStatus("Failed.");
                    resultTabs.setSelectedIndex(1);
                });
            } finally {
                Ui.later(() -> {
                    executing = false;
                    if (cts == token) cts = null;
                });
            }
        });
    }

    private void showResult(QueryResult result) {
        var sets = result.resultSets();
        var shown = Math.min(sets.size(), RESULT_SET_CAP);
        if (sets.size() > RESULT_SET_CAP) {
            capBanner.setText("Showing " + RESULT_SET_CAP + " of " + sets.size()
                    + " result sets — narrow your selection or run in chunks.");
            capBanner.setVisible(true);
        }

        if (shown > 0) {
            JComponent content;
            if (shown <= 3) {
                var grid = new JPanel(new GridLayout(shown, 1, 0, 6));
                for (var i = 0; i < shown; i++) {
                    var rs = sets.get(i);
                    grid.add(new ResultGrid(i + 1, rs.sourceStatement(), rs.columns(), rs.rows()));
                }
                content = grid;
            } else {
                var stack = new JPanel();
                stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
                for (var i = 0; i < shown; i++) {
                    var rs = sets.get(i);
                    stack.add(new ResultGrid(i + 1, rs.sourceStatement(), rs.columns(), rs.rows()).withMinHeight(240));
                    stack.add(Box.createVerticalStrut(6));
                }
                var sp = new JScrollPane(stack);
                sp.getVerticalScrollBar().setUnitIncrement(24);
                content = sp;
            }
            resultsHolder.add(content, BorderLayout.CENTER);
        }
        resultsHolder.revalidate();
        resultsHolder.repaint();

        for (var m : result.messages()) appendMessage(m);

        var rowTotal = sets.stream().mapToInt(s -> s.rows().size()).sum();
        setStatus(result.hasErrors()
                ? "Completed with errors in " + result.elapsedMs() + " ms."
                : result.statementsExecuted() + " statement(s), " + rowTotal + " row(s) in " + result.elapsedMs() + " ms.");

        if (result.hasErrors()) {
            var firstErr = result.messages().stream().filter(m -> m.severity() == MessageSeverity.ERROR).findFirst();
            // Only a lost database (dropped: 3D000, taken offline: 55000) means the
            // tab's connection itself is broken; a missing table doesn't.
            if (firstErr.isPresent()) {
                var text = firstErr.get().text();
                if (text.startsWith("[3D000]") || text.startsWith("[55000]")) refreshChip(false, text);
            }
        }
        resultTabs.setSelectedIndex(shown == 0 && !result.messages().isEmpty() || result.hasErrors() ? 1 : 0);
    }

    private static final DateTimeFormatter MSG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private void appendMessage(Message m) {
        var color = switch (m.severity()) {
            case ERROR -> Palette.danger();
            case WARNING -> Palette.warning();
            case NOTICE -> Palette.notice();
            case INFO -> messages.getForeground();
        };
        var doc = messages.getStyledDocument();
        try {
            var tag = new SimpleAttributeSet();
            StyleConstants.setBold(tag, true);
            StyleConstants.setForeground(tag, color);
            var body = new SimpleAttributeSet();
            StyleConstants.setForeground(body, m.severity() == MessageSeverity.INFO ? messages.getForeground() : color);
            var sev = m.severity().name().charAt(0) + m.severity().name().substring(1).toLowerCase(Locale.ROOT);
            doc.insertString(doc.getLength(), String.format("%-9s", sev), tag);
            doc.insertString(doc.getLength(), m.text().replace("\n", "\n         ") + "\n", body);
        } catch (BadLocationException ignored) {
            // offsets come from the document
        }
    }

    public void cancel() {
        var token = cts;
        if (token == null) return;
        token.cancel();
        setStatus("Cancelling...");
    }

    // ---- Files -----------------------------------------------------------

    /** Loads a script into this tab. Call off the EDT. */
    public void openFile(Path path) throws IOException {
        var text = FileTextLoader.readText(path);
        Ui.edt(() -> {
            setInitialSql(text);
            filePath = path;
            title = path.getFileName().toString();
            dirty = false;
            fire(Change.TITLE);
        });
    }

    /** Saves to {@code path} (or the current file). Call on the EDT. */
    public void save(Path path) throws IOException {
        var target = path != null ? path : filePath;
        if (target == null) throw new IllegalStateException("No file path.");
        Files.writeString(target, editor.getText(), StandardCharsets.UTF_8);
        filePath = target;
        title = target.getFileName().toString();
        dirty = false;
        fire(Change.TITLE);
    }

    private void loadFile() {
        var path = FileDialogs.open(this, "Load file into this editor", filePath == null ? null : filePath.getParent(),
                "SQL files", "sql");
        if (path == null) return;
        if (!editor.getText().isBlank() && !Ui.confirm(this, "Replace contents?",
                "Replace this tab's current contents with the file?\n\nFile: " + path
                        + "\n\nClick Cancel to keep the current contents.", false)) {
            return;
        }
        Ui.bgQuiet(() -> {
            try {
                openFile(path);
            } catch (Exception ex) {
                Ui.error(this, "Load failed", "Couldn't load file:\n" + ConnectionErrors.message(ex));
            }
        });
    }

    /** Releases the tab's lease (closing the pool if it was the last user). */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        cancel();
        wrapCheck.stop();
        var l = lease;
        Ui.bgQuiet(() -> l.close());
    }

    /** Rounded "DB: 5444 / scheduleme" badge, green when connected, red on failure. */
    private static final class ChipLabel extends JLabel {
        Color color = Palette.success();

        ChipLabel() {
            setForeground(Palette.chipText());
            setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1f));
            setBorder(BorderFactory.createEmptyBorder(2, 9, 2, 9));
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        public Dimension getPreferredSize() {
            var d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(d.height, 20));
        }
    }
}
