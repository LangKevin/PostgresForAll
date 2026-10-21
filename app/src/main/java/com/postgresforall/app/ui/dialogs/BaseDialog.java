package com.postgresforall.app.ui.dialogs;

import com.postgresforall.app.ui.Palette;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;

/** A modal dialog with a header, optional body text, content and a button row. Esc cancels. */
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
        var root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        var top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        if (header != null) {
            var h = new JLabel(header);
            h.setFont(h.getFont().deriveFont(Font.BOLD, h.getFont().getSize2D() + 3f));
            h.setAlignmentX(LEFT_ALIGNMENT);
            top.add(h);
            top.add(Box.createVerticalStrut(6));
        }
        if (bodyText != null) {
            bodyText.setAlignmentX(LEFT_ALIGNMENT);
            top.add(bodyText);
        }
        root.add(top, BorderLayout.NORTH);
        if (content != null) root.add(content, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    /** A wrapping, selectable-looking text block for explanations. */
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

    protected JButton addButton(String text, Runnable action) {
        var b = new JButton(text);
        b.addActionListener(e -> action.run());
        buttons.add(b);
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
