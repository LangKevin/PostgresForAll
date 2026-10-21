package com.postgresforall.app.ui.dialogs;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.theme.Icons;

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
        entry.putClientProperty("JTextField.placeholderText", expected);
        var text = wrappingText(body, 48);
        build(Icons.tinted("triangle-alert", 24, Palette::danger), header, text, entry);
        JButton confirm = addButton("Confirm", this::accept);
        confirm.putClientProperty("FlatLaf.styleClass", "danger");
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
