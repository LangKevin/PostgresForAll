package com.postgresforall.app.ui.dialogs;

import com.postgresforall.app.ui.Palette;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.util.function.Function;
import java.util.regex.Pattern;

/** One-line text prompt with an optional validator (red outline + message when invalid). */
public final class TextInputDialog extends BaseDialog {
    private final JTextField entry;
    private final JTextArea body;
    private final JLabel error = new JLabel(" ");
    private final JButton ok;
    private Function<String, String> validator;

    public TextInputDialog(Component owner, String title, String header, String bodyText, String initial) {
        super(owner, title);
        body = wrappingText(bodyText, 44);
        entry = new JTextField(initial, 30);
        entry.selectAll();
        error.putClientProperty("FlatLaf.styleClass", "danger small");
        var content = new JPanel(new MigLayout("insets 0, fillx, gapy 4", "[grow,fill]"));
        content.add(entry, "wrap");
        content.add(error);
        build(header, body, content);
        ok = addPrimaryButton("OK", this::accept);
        addButton("Cancel", this::cancel);
        entry.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { revalidateNow(); }
            @Override public void removeUpdate(DocumentEvent e) { revalidateNow(); }
            @Override public void changedUpdate(DocumentEvent e) { revalidateNow(); }
        });
        revalidateNow();
    }

    /** Optional validator: returns null when the input is valid, else an error message. */
    public TextInputDialog validator(Function<String, String> v) {
        validator = v;
        revalidateNow();
        return this;
    }

    public String value() {
        return entry.getText();
    }

    private void revalidateNow() {
        var msg = validator == null ? null : validator.apply(entry.getText());
        if (msg == null) {
            ok.setEnabled(!entry.getText().isBlank());
            entry.putClientProperty("JComponent.outline", null);
            error.setText(" ");
        } else {
            ok.setEnabled(false);
            entry.putClientProperty("JComponent.outline", "error");
            error.setText(msg);
            error.setForeground(Palette.danger());
        }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /** Validator for SQL identifiers (no quoting / no whitespace / safe chars). */
    public static String validateIdentifier(String s) {
        if (s == null || s.isBlank()) return "Name can't be empty.";
        if (s.length() > 63) return "Name must be 63 characters or fewer.";
        if (!IDENTIFIER.matcher(s).matches()) {
            return "Start with a letter or underscore; letters, digits and underscores only.";
        }
        return null;
    }
}
