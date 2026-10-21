package com.postgresforall.app.ui;

import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.OperationEvent.Severity;
import com.postgresforall.core.util.CancelToken;

import javax.swing.BorderFactory;
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
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * "DB Operations Output": streaming log of backup / restore / drop etc., with
 * a progress bar while one runs, Cancel / Clear, and session totals.
 */
public final class OperationOutputPanel extends JPanel {
    private static final int MAX_LINES = 10_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JLabel statusLine = new JLabel("Idle.");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel progressText = new JLabel("");
    private final JPanel progressRow = new JPanel(new BorderLayout(8, 0));
    private final JTextPane output = new JTextPane();
    private final JLabel statsLine = new JLabel("No operations yet this session.");
    private final JButton cancelBtn = new JButton("Cancel");

    private CancelToken cts;
    private boolean running;
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
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        statusLine.setFont(statusLine.getFont().deriveFont(Font.BOLD));
        var header = new JPanel(new BorderLayout());
        header.add(statusLine, BorderLayout.CENTER);
        var btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> cancel());
        var clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clear());
        btns.add(cancelBtn);
        btns.add(clearBtn);
        header.add(btns, BorderLayout.EAST);

        progress.setPreferredSize(new java.awt.Dimension(100, 14));
        progressText.setForeground(Palette.DIM_GRAY);
        progressRow.add(progress, BorderLayout.CENTER);
        progressRow.add(progressText, BorderLayout.EAST);
        progressRow.setVisible(false);

        var top = new JPanel(new BorderLayout(0, 6));
        top.add(header, BorderLayout.NORTH);
        top.add(progressRow, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(output), BorderLayout.CENTER);

        statsLine.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        statsLine.setForeground(Palette.DIM_GRAY);
        statsLine.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        add(statsLine, BorderLayout.SOUTH);
    }

    /**
     * Starts tracking a long-running operation. Call from any thread; returns
     * the token to pass to the worker and the event sink to report through.
     */
    public Operation begin(String title) {
        var token = new CancelToken();
        Ui.edt(() -> {
            cts = token;
            running = true;
            statusLine.setText(title + " running...");
            startedAt = LocalDateTime.now();
            progressTotal = 0;
            progress.setIndeterminate(true);
            progress.setValue(0);
            progressText.setText("");
            progressRow.setVisible(true);
            cancelBtn.setEnabled(true);
            opsRun++;
            recomputeStats();
            appendLine(new OperationEvent(Severity.INFO, "── " + title + " ──"));
        });
        return new Operation(token, evt -> Ui.later(() -> onEvent(evt)));
    }

    /** Ends the current operation (any thread). */
    public void end(boolean success) {
        Ui.later(() -> {
            running = false;
            var elapsed = startedAt == null ? Duration.ZERO : Duration.between(startedAt, LocalDateTime.now());
            statusLine.setText(String.format(success ? "Done in %.1fs." : "Failed after %.1fs.",
                    elapsed.toMillis() / 1000.0));
            progress.setIndeterminate(false);
            if (success && progressTotal > 0) progress.setValue(progressTotal);
            progressRow.setVisible(false);
            cancelBtn.setEnabled(false);
            if (success) opsSucceeded++;
            else opsFailed++;
            totalElapsed = totalElapsed.plus(elapsed);
            recomputeStats();
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
        statusLine.setText("Cancelling...");
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
            case ERROR -> Palette.FIREBRICK;
            case WARN -> Palette.DARK_ORANGE;
            case SUCCESS -> Palette.FOREST_GREEN;
            case INFO -> getForeground();
        };
        var tag = switch (evt.severity()) {
            case ERROR -> "ERROR";
            case WARN -> "WARN ";
            case SUCCESS -> "OK   ";
            case INFO -> "INFO ";
        };
        var doc = output.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), STAMP.format(LocalDateTime.now()) + "  ", attrs(Palette.GRAY, false));
            doc.insertString(doc.getLength(), tag + "  ", attrs(color, true));
            doc.insertString(doc.getLength(), evt.text() + "\n", attrs(color, false));
            lineCount++;
            if (lineCount > MAX_LINES) {
                var root = doc.getDefaultRootElement();
                var first = root.getElement(0);
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
                "Session: %d op(s) · %d OK · %d failed   |   Lines: %d info · %d warn · %d error   |   Cumulative time: %.1fs",
                opsRun, opsSucceeded, opsFailed, totalInfo, totalWarn, totalErrors, totalElapsed.toMillis() / 1000.0));
    }
}
