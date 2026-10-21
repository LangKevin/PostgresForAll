package com.postgresforall.app.ui.dialogs;

import com.postgresforall.app.ui.Palette;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;

/** Destructive-action confirmation: the user must type {@code expected} exactly. */
public final class ConfirmTypeDialog extends BaseDialog {
    public ConfirmTypeDialog(Component owner, String header, String body, String expected) {
        super(owner, "Confirm");
        var entry = new JTextField(24);
        var text = wrappingText(body, 48);
        build(header, text, entry);
        JButton confirm = addButton("Confirm", this::accept);
        confirm.setForeground(Palette.danger());
        confirm.setEnabled(false);
        addButton("Cancel", this::cancel);
        entry.getDocument().addDocumentListener(new DocumentListener() {
            private void check() { confirm.setEnabled(entry.getText().equals(expected)); }
            @Override public void insertUpdate(DocumentEvent e) { check(); }
            @Override public void removeUpdate(DocumentEvent e) { check(); }
            @Override public void changedUpdate(DocumentEvent e) { check(); }
        });
        entry.addActionListener(e -> { if (confirm.isEnabled()) accept(); });
    }
}
