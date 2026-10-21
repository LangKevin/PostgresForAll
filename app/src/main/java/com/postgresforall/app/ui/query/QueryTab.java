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
import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.ui.theme.Fonts;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.app.ui.theme.Theme;
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
import java.awt.Component;
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
    private final javax.swing.JToggleButton wrapBox = new javax.swing.JToggleButton("Transaction",
            Icons.get("shield", Icons.SMALL));
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JPanel resultsHolder = new JPanel(new BorderLayout());
    private final JLabel capBanner = new JLabel();
    private final javax.swing.DefaultListModel<Message> messageModel = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<Message> messages = new javax.swing.JList<>(messageModel);
    private final Runnable themeListener = this::applyEditorTheme;
    private RTextScrollPane editorScroll;
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
        var strip = new JPanel(new BorderLayout(10, 0));
        strip.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 10));
        var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(chip);
        wrapBox.setToolTipText("Wrap the script in BEGIN ... COMMIT / ROLLBACK on error.");
        wrapBox.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        wrapBox.setFocusable(false);
        left.add(wrapBox);
        var loadBtn = new JButton(Icons.get("folder-open", Icons.SMALL));
        loadBtn.setToolTipText("Load a file into this tab (replaces its contents)");
        loadBtn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        loadBtn.setFocusable(false);
        loadBtn.addActionListener(e -> loadFile());
        left.add(loadBtn);
        strip.add(left, BorderLayout.WEST);
        statusLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        statusLabel.setHorizontalAlignment(JLabel.RIGHT);
        strip.add(statusLabel, BorderLayout.CENTER);
        add(strip, BorderLayout.NORTH);

        // ---- editor ------------------------------------------------------
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        editor.setCodeFoldingEnabled(false);
        editor.setAntiAliasingEnabled(true);
        editor.setTabSize(4);
        editor.setBracketMatchingEnabled(true);
        editor.setPaintMatchedBracketPair(true);
        editor.setText("-- Welcome to PostgresForAll\n-- F5 (or " + Ui.menuKeyText()
                + "Enter) runs the buffer, or just the selected text\n\n-- SELECT version();\n");
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
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Palette.border()));
        editorScroll = scroll;
        applyEditorTheme();
        Theme.onChange(themeListener);

        // ---- results -----------------------------------------------------
        capBanner.setOpaque(true);
        capBanner.setIcon(Icons.get("info", Icons.SMALL));
        capBanner.setBackground(Palette.bannerInfo());
        capBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.bannerInfoBorder()),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        capBanner.setVisible(false);
        var resultsPanel = new JPanel(new BorderLayout(0, 4));
        resultsPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        resultsPanel.add(capBanner, BorderLayout.NORTH);
        resultsPanel.add(resultsHolder, BorderLayout.CENTER);
        showEmptyState("play", "Run a query (F5 / " + Ui.menuKeyText() + "Enter) to see results here");
        messages.setCellRenderer(new MessageRenderer());
        messages.setFocusable(true);
        var messagesScroll = new JScrollPane(messages);
        messagesScroll.setBorder(BorderFactory.createEmptyBorder());
        resultTabs.addTab("Results", Icons.get("table", Icons.SMALL), resultsPanel);
        resultTabs.addTab("Messages", Icons.get("bell", Icons.SMALL), messagesScroll);

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

    /** RSyntaxTextArea colours for the current light / dark theme, on the theme's own background. */
    private void applyEditorTheme() {
        var name = Theme.isDark() ? "dark.xml" : "idea.xml";
        try (var in = org.fife.ui.rsyntaxtextarea.Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/" + name)) {
            if (in != null) org.fife.ui.rsyntaxtextarea.Theme.load(in).apply(editor);
        } catch (IOException ignored) {
            // keep the defaults
        }
        var bg = javax.swing.UIManager.getColor("TextArea.background");
        if (bg != null) editor.setBackground(bg);
        editor.setFont(Fonts.mono(1));
        if (editorScroll != null) {
            var gutter = editorScroll.getGutter();
            gutter.setBackground(bg);
            gutter.setBorderColor(Palette.border());
            gutter.setLineNumberColor(Palette.subtle());
            gutter.setLineNumberFont(Fonts.mono(0));
            editorScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Palette.border()));
        }
    }

    /** A centred icon + hint where results will appear. */
    private void showEmptyState(String icon, String text) {
        var label = new JLabel(text, Icons.get(icon, Icons.LARGE), JLabel.CENTER);
        label.setHorizontalTextPosition(JLabel.CENTER);
        label.setVerticalTextPosition(JLabel.BOTTOM);
        label.setIconTextGap(10);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        resultsHolder.removeAll();
        resultsHolder.add(label, BorderLayout.CENTER);
        resultsHolder.revalidate();
        resultsHolder.repaint();
    }

    /** All messages as text (severity + text per line), e.g. for tests and copying. */
    public String messagesText() {
        var sb = new StringBuilder();
        for (var i = 0; i < messageModel.size(); i++) {
            var m = messageModel.get(i);
            sb.append(m.severity()).append(": ").append(m.text()).append('\n');
        }
        return sb.toString();
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
        chip.setText(connectionLabel());
        chip.ok = ok;
        if (ok) {
            chip.setToolTipText("<html>Connected: " + Ui.escapeHtml(m.profile().username + "@" + m.profile().host + ":"
                    + m.profile().port + "/" + currentDatabase) + "<br>PostgreSQL " + m.serverVersion() + "</html>");
        } else {
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

    /** Runs the whole script wrapped in BEGIN / COMMIT (when the script allows it). Call on the EDT. */
    public void executeAllInTransaction() {
        if (wrapBox.isEnabled()) wrapBox.setSelected(true);
        execute(null);
    }

    /** Runs {@code selectionOrNull} (or the whole buffer). Call on the EDT. */
    public void execute(String selectionOrNull) {
        if (executing) return;
        var sql = selectionOrNull == null || selectionOrNull.isBlank() ? editor.getText() : selectionOrNull;
        if (sql.isBlank()) return;

        executing = true;
        showEmptyState("loader", "Running…");
        capBanner.setVisible(false);
        messageModel.clear();
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

        resultsHolder.removeAll();
        if (shown == 0) {
            showEmptyState(result.hasErrors() ? "circle-alert" : "circle-check", result.hasErrors()
                    ? "No results — the script stopped on an error (see Messages)"
                    : "No result sets — " + result.statementsExecuted() + " statement(s) ran (see Messages)");
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
        messageModel.addElement(m);
    }

    /** Severity icon, muted time, text (multi-line DETAIL / HINT kept) in the severity colour. */
    private static final class MessageRenderer extends JPanel implements javax.swing.ListCellRenderer<Message> {
        private final JLabel icon = new JLabel();
        private final JLabel time = new JLabel();
        private final javax.swing.JTextArea text = new javax.swing.JTextArea();

        MessageRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            left.setOpaque(false);
            left.add(icon);
            left.add(time);
            add(left, BorderLayout.WEST);
            text.setOpaque(false);
            text.setEditable(false);
            text.setBorder(null);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(javax.swing.JList<? extends Message> list, Message m, int index,
                                                      boolean sel, boolean focus) {
            var color = switch (m.severity()) {
                case ERROR -> Palette.danger();
                case WARNING -> Palette.warning();
                case NOTICE -> Palette.notice();
                case INFO -> list.getForeground();
            };
            icon.setIcon(switch (m.severity()) {
                case ERROR -> Icons.tinted("circle-x", Icons.SMALL, Palette::danger);
                case WARNING -> Icons.tinted("triangle-alert", Icons.SMALL, Palette::warning);
                case NOTICE -> Icons.tinted("bell", Icons.SMALL, Palette::notice);
                case INFO -> Icons.get("info", Icons.SMALL);
            });
            time.setText(MSG_TIME.format(m.timestamp()));
            time.setFont(Fonts.mono(-1));
            time.setForeground(sel ? list.getSelectionForeground() : Palette.subtle());
            text.setText(m.text());
            text.setFont(Fonts.mono(0));
            text.setForeground(sel ? list.getSelectionForeground() : color);
            setOpaque(true);
            setBackground(sel ? list.getSelectionBackground() : list.getBackground());
            return this;
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
        Theme.removeOnChange(themeListener);
        var l = lease;
        Ui.bgQuiet(() -> l.close());
    }

    /** Rounded status badge: tinted pill with a green / red dot and "5444 / scheduleme". */
    private static final class ChipLabel extends JLabel {
        boolean ok = true;

        ChipLabel() {
            setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() - 1f));
            setBorder(BorderFactory.createEmptyBorder(3, 22, 3, 10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var h = getHeight();
            g2.setColor(ok ? Palette.bannerSuccess() : Palette.bannerError());
            g2.fillRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
            g2.setColor(ok ? Palette.bannerSuccessBorder() : Palette.bannerErrorBorder());
            g2.drawRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
            g2.setColor(ok ? Palette.success() : Palette.danger());
            var d = 8;
            g2.fillOval(9, (h - d) / 2, d, d);
            g2.dispose();
            setForeground(javax.swing.UIManager.getColor("Label.foreground"));
            super.paintComponent(g);
        }

        @Override
        public Dimension getPreferredSize() {
            var d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(d.height, 22));
        }
    }
}
