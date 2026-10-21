package com.postgresforall.app.ui.dialogs;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.core.platform.Platform;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;

/**
 * A modal dialog: optional icon + header, muted body text, content, and a
 * button row in the OS's order (macOS: primary rightmost; Windows / Linux:
 * primary first). Esc cancels.
 */
public abstract class BaseDialog extends JDialog {
    private boolean accepted;
    protected final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

    protected BaseDialog(Component owner, String title) {
        super(owner == null ? null : owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner),
                title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> cancel(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /** Lays out header, body text, content and buttons. Call once from the subclass constructor. */
    protected void build(String header, JComponent bodyText, JComponent content) {
        build(null, header, bodyText, content);
    }

    /** As {@link #build(String, JComponent, JComponent)}, with an icon left of the header. */
    protected void build(Icon icon, String header, JComponent bodyText, JComponent content) {
        var root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(20, 20, 16, 20));

        if (header != null || bodyText != null) {
            var top = new JPanel(new MigLayout("insets 0, gapy 6, fillx", icon != null ? "[]10[grow,fill]" : "[grow,fill]"));
            if (header != null) {
                var h = new JLabel(header);
                h.putClientProperty("FlatLaf.styleClass", "h3");
                if (icon != null) top.add(new JLabel(icon), "top, spany 2");
                top.add(h, "wrap");
            } else if (icon != null) {
                top.add(new JLabel(icon), "top");
            }
            if (bodyText != null) top.add(bodyText, icon != null && header != null ? "" : "wrap");
            root.add(top, BorderLayout.NORTH);
        }
        if (content != null) root.add(content, BorderLayout.CENTER);
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    /** A wrapping text block for explanations, in the muted colour. */
    public static JTextArea wrappingText(String text, int columns) {
        var t = new JTextArea(text);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setColumns(columns);
        t.setFont(UIManager.getFont("Label.font"));
        t.setForeground(Palette.muted());
        t.setBorder(null);
        return t;
    }

    /**
     * Adds a button. The first one added is the primary action: on macOS buttons
     * are laid out right-to-left so it ends up rightmost; elsewhere it stays first.
     */
    protected JButton addButton(String text, Runnable action) {
        var b = new JButton(text);
        b.addActionListener(e -> action.run());
        if (Platform.isMac()) buttons.add(b, 0);
        else buttons.add(b);
        return b;
    }

    /** Adds the primary (default, Enter) button. Add it before the secondary ones. */
    protected JButton addPrimaryButton(String text, Runnable action) {
        var b = addButton(text, action);
        getRootPane().setDefaultButton(b);
        return b;
    }

    protected void accept() {
        accepted = true;
        dispose();
    }

    protected void cancel() {
        accepted = false;
        dispose();
    }

    /** Shows the dialog modally; true when it was accepted. Call on the EDT. */
    public boolean showDialog() {
        pack();
        setLocationRelativeTo(getOwner());
        setVisible(true);
        return accepted;
    }
}
