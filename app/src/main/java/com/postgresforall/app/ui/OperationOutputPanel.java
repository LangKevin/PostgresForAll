package com.postgresforall.app.ui;

import com.postgresforall.app.ui.theme.Fonts;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.OperationEvent.Severity;
import com.postgresforall.core.util.CancelToken;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The "Output" panel at the bottom of the main window: a header bar (collapse
 * chevron, title, status, inline progress, Cancel / Clear) over a streaming
 * log of backup / restore / drop etc. and the session's totals. Collapsing
 * hides everything but the header; the main window decides how much room that
 * leaves (see {@link #onToggleCollapse} / {@link #headerHeight}).
 */
public final class OperationOutputPanel extends JPanel {
    private static final int MAX_LINES = 10_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JButton chevron = new JButton();
    private final JLabel title = new JLabel("Output");
    private final JLabel statusLine = new JLabel("Idle.");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel progressText = new JLabel("");
    private final JPanel progressBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private final JTextPane output = new JTextPane();
    private final JScrollPane body;
    private final JLabel statsLine = new JLabel("No operations yet this session.");
    private final JButton cancelBtn = new JButton(Icons.get("square", Icons.SMALL));
    private final JPanel header;
    private final Map<Severity, Icon> severityIcons = new EnumMap<>(Severity.class);

    private final List<Runnable> toggleListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, Boolean>> finishedListeners = new CopyOnWriteArrayList<>();

    private CancelToken cts;
    private boolean running;
    private boolean collapsed;
    private String currentTitle = "";
    private LocalDateTime startedAt;
    private int lineCount;
    private int progressTotal;

    private int opsRun;
    private int opsSucceeded;
    private int opsFailed;
    private int totalInfo;
    private int totalWarn;
    private int totalErrors;
    private Duration totalElapsed = Duration.ZERO;

    /** A running operation: its cancellation token and the sink for its events (any thread). */
    public record Operation(CancelToken token, Consumer<OperationEvent> emit) { }

    public OperationOutputPanel() {
        super(new BorderLayout());

        severityIcons.put(Severity.INFO, Icons.tinted("info", Icons.SMALL, Palette::subtle));
        severityIcons.put(Severity.WARN, Icons.tinted("triangle-alert", Icons.SMALL, Palette::warning));
        severityIcons.put(Severity.ERROR, Icons.tinted("circle-x", Icons.SMALL, Palette::danger));
        severityIcons.put(Severity.SUCCESS, Icons.tinted("circle-check", Icons.SMALL, Palette::success));

        // ---- header bar --------------------------------------------------
        header = new JPanel(new BorderLayout(8, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Palette.headerStrip());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Palette.border());
                g.fillRect(0, 0, getWidth(), 1);
                if (!collapsed) g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 6));

        chevron.putClientProperty("JButton.buttonType", "toolBarButton");
        chevron.setFocusable(false);
        chevron.addActionListener(e -> {
            for (var r : toggleListeners) r.run();
        });
        title.putClientProperty("FlatLaf.styleClass", "semibold");
        statusLine.putClientProperty("FlatLaf.styleClass", "small muted");
        var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(chevron);
        left.add(new JLabel(Icons.get("terminal", Icons.SMALL)));
        left.add(title);
        left.add(Box.createHorizontalStrut(6));
        left.add(statusLine);
        header.add(left, BorderLayout.CENTER);

        progress.setPreferredSize(new Dimension(160, 6));
        progressText.putClientProperty("FlatLaf.styleClass", "small muted");
        progressBox.setOpaque(false);
        progressBox.add(progressText);
        progressBox.add(progress);
        progressBox.setVisible(false);

        cancelBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        cancelBtn.setToolTipText("Cancel the running operation");
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> cancel());
        var clearBtn = new JButton(Icons.get("trash-2", Icons.SMALL));
        clearBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        clearBtn.setToolTipText("Clear the output");
        clearBtn.addActionListener(e -> clear());
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(progressBox);
        right.add(cancelBtn);
        right.add(clearBtn);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ---- body ----------------------------------------------------------
        output.setEditable(false);
        output.setFont(Fonts.mono(-1));
        output.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        body = new JScrollPane(output);
        body.setBorder(BorderFactory.createEmptyBorder());
        add(body, BorderLayout.CENTER);

        statsLine.putClientProperty("FlatLaf.styleClass", "small muted");
        statsLine.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.border()),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        add(statsLine, BorderLayout.SOUTH);

        updateChevron();
    }

    // ---- Collapse ------------------------------------------------------------

    /** Called on the EDT when the user clicks the collapse / expand chevron. */
    public void onToggleCollapse(Runnable r) {
        toggleListeners.add(r);
    }

    /** Shows only the header bar (true) or the full panel (false). Call on the EDT. */
    public void setCollapsed(boolean c) {
        collapsed = c;
        body.setVisible(!c);
        statsLine.setVisible(!c);
        updateChevron();
        revalidate();
        repaint();
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** Height of the header bar — the panel's height when collapsed. */
    public int headerHeight() {
        return header.getPreferredSize().height;
    }

    /** Called on the EDT when an operation ends: its title and whether it succeeded. */
    public void onFinished(BiConsumer<String, Boolean> r) {
        finishedListeners.add(r);
    }

    private void updateChevron() {
        chevron.setIcon(Icons.get(collapsed ? "chevron-up" : "chevron-down", Icons.SMALL));
        chevron.setToolTipText(collapsed ? "Show output" : "Hide output");
    }

    // ---- Operations ----------------------------------------------------------

    /**
     * Starts tracking a long-running operation. Call from any thread; returns the
     * token to pass to the worker and the event sink to report through.
     */
    public Operation begin(String opTitle) {
        var token = new CancelToken();
        Ui.edt(() -> {
            cts = token;
            running = true;
            currentTitle = opTitle;
            title.setText("Output · running");
            statusLine.setText(opTitle + "…");
            startedAt = LocalDateTime.now();
            progressTotal = 0;
            progress.setIndeterminate(true);
            progress.setValue(0);
            progressText.setText("");
            progressBox.setVisible(true);
            cancelBtn.setEnabled(true);
            opsRun++;
            recomputeStats();
            appendLine(new OperationEvent(Severity.INFO, "── " + opTitle + " ──"));
        });
        return new Operation(token, evt -> Ui.later(() -> onEvent(evt)));
    }

    /** Ends the current operation (any thread). */
    public void end(boolean success) {
        Ui.later(() -> {
            running = false;
            title.setText("Output");
            var elapsed = startedAt == null ? Duration.ZERO : Duration.between(startedAt, LocalDateTime.now());
            statusLine.setText(String.format(success ? "%s — done in %.1fs." : "%s — failed after %.1fs.",
                    currentTitle, elapsed.toMillis() / 1000.0));
            progress.setIndeterminate(false);
            if (success && progressTotal > 0) progress.setValue(progressTotal);
            progressBox.setVisible(false);
            cancelBtn.setEnabled(false);
            if (success) opsSucceeded++;
            else opsFailed++;
            totalElapsed = totalElapsed.plus(elapsed);
            recomputeStats();
            for (var l : finishedListeners) l.accept(currentTitle, success);
        });
    }

    private void onEvent(OperationEvent evt) {
        appendLine(evt);
        if (evt.progressTotal() != null && evt.progressTotal() > 0) {
            progress.setIndeterminate(false);
            progressTotal = evt.progressTotal();
            progress.setMaximum(progressTotal);
        }
        if (evt.progressCurrent() != null) {
            progress.setValue(evt.progressCurrent());
            if (progressTotal > 0) {
                progressText.setText(String.format("%d / %d (%.0f%%)", evt.progressCurrent(), progressTotal,
                        100.0 * evt.progressCurrent() / progressTotal));
            }
        }
    }

    private void cancel() {
        if (cts == null || !running) return;
        statusLine.setText("Cancelling…");
        cts.cancel();
    }

    private void clear() {
        output.setText("");
        lineCount = 0;
        if (!running) {
            statusLine.setText("Cleared.");
            progress.setValue(0);
            progressText.setText("");
        }
    }

    private void appendLine(OperationEvent evt) {
        var color = switch (evt.severity()) {
            case ERROR -> Palette.danger();
            case WARN -> Palette.warning();
            case SUCCESS -> Palette.success();
            case INFO -> output.getForeground();
        };
        var doc = output.getStyledDocument();
        try {
            var icon = new SimpleAttributeSet();
            StyleConstants.setIcon(icon, severityIcons.get(evt.severity()));
            doc.insertString(doc.getLength(), " ", icon);
            doc.insertString(doc.getLength(), "  " + STAMP.format(LocalDateTime.now()) + "  ", attrs(Palette.subtle(), false));
            doc.insertString(doc.getLength(), evt.text() + "\n", attrs(color, evt.severity() == Severity.SUCCESS));
            lineCount++;
            if (lineCount > MAX_LINES) {
                var first = doc.getDefaultRootElement().getElement(0);
                doc.remove(0, first.getEndOffset());
                lineCount--;
            }
        } catch (BadLocationException ignored) {
            // unreachable: offsets come from the document
        }
        output.setCaretPosition(doc.getLength());

        switch (evt.severity()) {
            case ERROR -> totalErrors++;
            case WARN -> totalWarn++;
            default -> totalInfo++;
        }
        recomputeStats();
    }

    private static SimpleAttributeSet attrs(Color c, boolean bold) {
        var a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        StyleConstants.setBold(a, bold);
        return a;
    }

    private void recomputeStats() {
        statsLine.setText(String.format(
                "Session: %d op(s) · %d OK · %d failed     Lines: %d info · %d warn · %d error     Time: %.1fs",
                opsRun, opsSucceeded, opsFailed, totalInfo, totalWarn, totalErrors, totalElapsed.toMillis() / 1000.0));
    }
}
