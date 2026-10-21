package com.postgresforall.app.ui.design;

import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.DdlStatement;
import com.postgresforall.core.platform.Platform;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Base for every structure-change dialog: header, a form, a live SQL preview of
 * exactly what will run, the lock each statement takes, and Apply / Open in
 * query tab / Cancel. Nothing runs here: the caller acts on {@link #showDialog()}.
 */
public abstract class ChangeDialog extends JDialog {

    public enum Choice { APPLY, OPEN_IN_TAB, CANCEL }

    private Choice choice = Choice.CANCEL;
    private List<DdlStatement> statements = List.of();

    private final JLabel headerIcon = new JLabel(Icons.get("wrench", Icons.LARGE));
    private final JPanel formHost = new JPanel(new BorderLayout());
    private final RSyntaxTextArea preview = DesignForms.sqlArea(7, false);
    private final JPanel locks = new JPanel(new MigLayout("insets 0, gapy 2, fillx", "[grow,fill]"));
    private final JLabel error = new JLabel();
    private final JButton apply = new JButton("Apply");
    private final JButton openInTab = new JButton("Open in query tab");
    private final JButton cancel = new JButton("Cancel");

    protected ChangeDialog(Component owner, String title, String header, String subtitle) {
        super(owner == null ? null : owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner),
                title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> close(Choice.CANCEL),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        // ---- header ---------------------------------------------------------
        var top = new JPanel(new MigLayout("insets 0, gapy 4, fillx", "[]10[grow,fill]"));
        var h = new JLabel(header);
        h.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
        top.add(headerIcon, "top, spany 2");
        top.add(h, "wrap");
        if (subtitle != null && !subtitle.isBlank()) {
            var sub = new JLabel(subtitle);
            sub.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
            top.add(sub, "wrap");
        }

        // ---- preview + locks + validation ----------------------------------------
        var previewTitle = new JLabel("SQL preview");
        previewTitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "sectionTitle");
        var previewScroll = new RTextScrollPane(preview, false);
        previewScroll.setBorder(BorderFactory.createLineBorder(Palette.border()));
        error.setIcon(Icons.tinted("circle-alert", Icons.SMALL, Palette::danger));
        error.putClientProperty(FlatClientProperties.STYLE_CLASS, "danger");
        error.setVisible(false);

        var bottom = new JPanel(new MigLayout("insets 0, gapy 6, fill", "[grow,fill]", "[][grow,fill][][]"));
        bottom.add(previewTitle, "wrap");
        bottom.add(previewScroll, "wrap, hmin 110");
        bottom.add(locks, "wrap");
        bottom.add(error, "wrap");

        // ---- buttons (macOS: primary rightmost) -----------------------------------
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        apply.addActionListener(e -> {
            if (apply.isEnabled()) close(Choice.APPLY);
        });
        openInTab.addActionListener(e -> {
            if (openInTab.isEnabled()) close(Choice.OPEN_IN_TAB);
        });
        cancel.addActionListener(e -> close(Choice.CANCEL));
        openInTab.setToolTipText("Put this SQL in a new query tab instead of running it");
        if (Platform.isMac()) {
            buttons.add(cancel);
            buttons.add(openInTab);
            buttons.add(apply);
        } else {
            buttons.add(apply);
            buttons.add(openInTab);
            buttons.add(cancel);
        }
        getRootPane().setDefaultButton(apply);

        var center = new JPanel(new MigLayout("insets 0, gapy 12, fill", "[grow,fill]", "[][grow,fill]"));
        center.add(formHost, "wrap");
        center.add(bottom, "grow");

        var root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 14, 20));
        root.add(top, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    /** Builds the statements from the current form. Throw IllegalArgumentException(message) when incomplete. */
    protected abstract List<DdlStatement> buildStatements();

    protected void setForm(JComponent form) {
        formHost.removeAll();
        formHost.add(form, BorderLayout.CENTER);
        formHost.revalidate();
    }

    protected void setHeaderIcon(Icon icon) {
        headerIcon.setIcon(icon);
    }

    /** The Apply button, for dialogs that relabel / restyle it (e.g. "Drop"). */
    protected JButton applyButton() {
        return apply;
    }

    /** Rebuilds the preview from the form. Call from every field listener. */
    protected void contentChanged() {
        List<DdlStatement> built;
        try {
            built = buildStatements();
        } catch (IllegalArgumentException ex) {
            showInvalid(ex.getMessage());
            return;
        } catch (UnsupportedOperationException ex) {
            showInvalid("SQL generation for this change isn't available yet.");
            return;
        } catch (RuntimeException ex) {
            showInvalid(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return;
        }
        statements = List.copyOf(built);
        preview.setText(statements.stream().map(DdlStatement::sql).collect(Collectors.joining("\n\n")));
        preview.setCaretPosition(0);
        renderLocks(statements);
        error.setVisible(false);
        var ok = !statements.isEmpty();
        apply.setEnabled(ok && canApply());
        openInTab.setEnabled(ok);
    }

    /**
     * Extra gate on Apply that still shows the preview (e.g. a drop's type-the-name
     * confirmation). Re-evaluated on every {@link #contentChanged()}.
     */
    protected boolean canApply() {
        return true;
    }

    private void showInvalid(String message) {
        statements = List.of();
        preview.setText("-- " + (message == null ? "Incomplete" : message.replace("\n", "\n-- ")));
        renderLocks(List.of());
        error.setText(message);
        error.setVisible(message != null && !message.isBlank());
        apply.setEnabled(false);
        openInTab.setEnabled(false);
    }

    private void renderLocks(List<DdlStatement> list) {
        locks.removeAll();
        for (var s : list) {
            var heavy = s.lock() == DdlStatement.Lock.ACCESS_EXCLUSIVE || s.lock() == DdlStatement.Lock.EXCLUSIVE;
            var l = new JLabel(s.lockDescription(), heavy
                    ? Icons.tinted("triangle-alert", Icons.SMALL, Palette::warning)
                    : Icons.get("info", Icons.SMALL), JLabel.LEFT);
            l.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
            locks.add(l, "wrap");
            if (!s.transactional()) {
                var n = new JLabel("Runs outside a transaction: " + DesignForms.firstLine(s.sql()),
                        Icons.get("info", Icons.SMALL), JLabel.LEFT);
                n.putClientProperty(FlatClientProperties.STYLE_CLASS, "subtle");
                locks.add(n, "wrap");
            }
        }
        locks.revalidate();
        locks.repaint();
    }

    /** Modal; returns the user's choice. Call on the EDT. */
    public Choice showDialog() {
        contentChanged();
        pack();
        var w = Math.max(720, Math.min(getWidth(), 960));
        var h = Math.max(560, Math.min(getHeight(), 800));
        setSize(w, h);
        setMinimumSize(new Dimension(600, 440));
        setLocationRelativeTo(getOwner());
        setVisible(true);
        return choice;
    }

    /** The last successfully built statements (valid after showDialog returns). */
    public List<DdlStatement> statements() {
        return statements;
    }

    private void close(Choice c) {
        choice = c;
        dispose();
    }
}
