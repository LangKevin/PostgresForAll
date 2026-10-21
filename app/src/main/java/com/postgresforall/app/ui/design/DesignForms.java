package com.postgresforall.app.ui.design;

import com.formdev.flatlaf.FlatClientProperties;
import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.theme.Fonts;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.app.ui.theme.Theme;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Small form building blocks shared by the design dialogs. */
final class DesignForms {
    private DesignForms() { }

    /** A form panel: right-aligned muted labels, growing fields. */
    static JPanel form() {
        return new JPanel(new MigLayout("insets 0, wrap 2, fillx, gapy 6", "[right]12[grow,fill]"));
    }

    static JLabel label(String text) {
        var l = new JLabel(text);
        l.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        return l;
    }

    static JLabel note(String text) {
        var l = new JLabel(text, Icons.get("info", Icons.SMALL), JLabel.LEFT);
        l.putClientProperty(FlatClientProperties.STYLE_CLASS, "muted");
        return l;
    }

    static JLabel warning(String text) {
        var l = new JLabel(text, Icons.tinted("triangle-alert", Icons.SMALL, Palette::warning), JLabel.LEFT);
        return l;
    }

    static JTextField field(String initial, String placeholder) {
        var f = new JTextField(initial == null ? "" : initial, 28);
        if (placeholder != null) f.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        return f;
    }

    static void onChange(JTextComponent c, Runnable r) {
        c.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }

    static <T> JComboBox<T> combo(T[] items, T selected, Runnable onChange) {
        var c = new JComboBox<>(items);
        if (selected != null) c.setSelectedItem(selected);
        c.addActionListener(e -> onChange.run());
        return c;
    }

    /** An editable combo whose typed text also triggers {@code onChange}. */
    static JComboBox<String> editableCombo(List<String> items, String selected, Runnable onChange) {
        var c = new JComboBox<>(items.toArray(String[]::new));
        c.setEditable(true);
        c.setSelectedItem(selected == null ? "" : selected);
        c.addActionListener(e -> onChange.run());
        if (c.getEditor().getEditorComponent() instanceof JTextComponent t) onChange(t, onChange);
        return c;
    }

    /** The current text of an editable combo (typed or picked). */
    static String comboText(JComboBox<String> c) {
        if (c.getEditor().getEditorComponent() instanceof JTextComponent t && c.isEditable()) return t.getText().trim();
        var v = c.getSelectedItem();
        return v == null ? "" : v.toString().trim();
    }

    static JCheckBox check(String text, boolean selected, Runnable onChange) {
        var b = new JCheckBox(text, selected);
        b.addActionListener(e -> onChange.run());
        return b;
    }

    /** A small themed SQL editor (expressions, WHERE clauses) or a read-only preview. */
    static RSyntaxTextArea sqlArea(int rows, boolean editable) {
        var a = new RSyntaxTextArea(rows, 60);
        a.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        a.setCodeFoldingEnabled(false);
        a.setAntiAliasingEnabled(true);
        var name = Theme.isDark() ? "dark.xml" : "idea.xml";
        try (var in = org.fife.ui.rsyntaxtextarea.Theme.class.getResourceAsStream(
                "/org/fife/ui/rsyntaxtextarea/themes/" + name)) {
            if (in != null) org.fife.ui.rsyntaxtextarea.Theme.load(in).apply(a);
        } catch (IOException ignored) {
            // defaults
        }
        var bg = UIManager.getColor("TextArea.background");
        if (bg != null) a.setBackground(bg);
        a.setFont(Fonts.mono(0));
        a.setEditable(editable);
        a.setHighlightCurrentLine(editable);
        a.setLineWrap(!editable);
        a.setWrapStyleWord(true);
        return a;
    }

    static JComponent scrolled(RSyntaxTextArea a) {
        var sp = new RTextScrollPane(a, false);
        sp.setBorder(BorderFactory.createLineBorder(Palette.border()));
        return sp;
    }

    static String firstLine(String sql) {
        var s = sql.strip();
        var i = s.indexOf('\n');
        s = i < 0 ? s : s.substring(0, i);
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static List<String> splitList(String text) {
        if (text == null || text.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (var p : splitTopLevel(text, ',')) {
            var t = unquote(p.trim());
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Splits on {@code sep} outside parentheses and quotes. */
    static List<String> splitTopLevel(String text, char sep) {
        var parts = new ArrayList<String>();
        var depth = 0;
        var inSingle = false;
        var inDouble = false;
        var cur = new StringBuilder();
        for (var ch : text.toCharArray()) {
            if (ch == '\'' && !inDouble) inSingle = !inSingle;
            else if (ch == '"' && !inSingle) inDouble = !inDouble;
            else if (!inSingle && !inDouble) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == sep && depth == 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(ch);
        }
        if (!cur.isEmpty()) parts.add(cur.toString());
        return parts;
    }

    /** {@code "Order Items"} → Order Items; plain names unchanged. */
    static String unquote(String s) {
        var t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1).replace("\"\"", "\"");
        return t;
    }

    private static final Pattern PLAIN = Pattern.compile("^[a-z_][a-z0-9_$]*$");

    /** Quotes a name only when needed — for prefilled expressions (DdlBuilder does the real SQL). */
    static String quoteIfNeeded(String name) {
        return PLAIN.matcher(name).matches() ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** Text inside the first parentheses after {@code keyword} in a definition, or null. */
    static String parenAfter(String definition, String keyword) {
        if (definition == null) return null;
        var i = definition.toUpperCase(Locale.ROOT).indexOf(keyword.toUpperCase(Locale.ROOT));
        if (i < 0) return null;
        var open = definition.indexOf('(', i + keyword.length());
        if (open < 0) return null;
        var depth = 0;
        for (var j = open; j < definition.length(); j++) {
            var ch = definition.charAt(j);
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return definition.substring(open + 1, j);
        }
        return null;
    }

    // ---- reusable widgets -----------------------------------------------------

    /** Ordered column picker: available-columns combo + Add, list with Up / Down / Remove. */
    static final class OrderedColumns extends JPanel {
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);
        private final JComboBox<String> available;

        OrderedColumns(List<String> columns, List<String> initial, Runnable onChange) {
            super(new MigLayout("insets 0, fillx, gapy 4", "[grow,fill][]"));
            available = new JComboBox<>(columns.toArray(String[]::new));
            var add = iconButton("plus", "Add column", () -> {
                var v = (String) available.getSelectedItem();
                if (v != null && !model.contains(v)) {
                    model.addElement(v);
                    onChange.run();
                }
            });
            add(available, "growx");
            add(add, "wrap");
            for (var c : initial) model.addElement(c);
            list.setVisibleRowCount(4);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            var buttons = new JPanel(new MigLayout("insets 0, wrap 1, gapy 2"));
            buttons.add(iconButton("arrow-up", "Move up", () -> move(-1, onChange)));
            buttons.add(iconButton("arrow-down", "Move down", () -> move(1, onChange)));
            buttons.add(iconButton("x", "Remove", () -> {
                var i = list.getSelectedIndex();
                if (i >= 0) {
                    model.remove(i);
                    onChange.run();
                }
            }));
            add(new JScrollPane(list), "growx, hmin 80");
            add(buttons, "top");
        }

        private void move(int delta, Runnable onChange) {
            var i = list.getSelectedIndex();
            var j = i + delta;
            if (i < 0 || j < 0 || j >= model.size()) return;
            var v = model.remove(i);
            model.add(j, v);
            list.setSelectedIndex(j);
            onChange.run();
        }

        List<String> values() {
            var out = new ArrayList<String>();
            for (var i = 0; i < model.size(); i++) out.add(model.get(i));
            return out;
        }

        void setAvailable(List<String> columns) {
            available.removeAllItems();
            for (var c : columns) available.addItem(c);
        }

        void setValues(List<String> values) {
            model.clear();
            for (var v : values) model.addElement(v);
        }
    }

    /** A wrapping list of checkboxes; selection kept in the given order. */
    static final class CheckList extends JPanel {
        private final List<JCheckBox> boxes = new ArrayList<>();

        CheckList(List<String> items, List<String> selected, Runnable onChange) {
            super(new MigLayout("insets 0, wrap 3, gapy 2"));
            for (var it : items) {
                var b = check(it, selected.contains(it), onChange);
                boxes.add(b);
                add(b);
            }
            if (items.isEmpty()) add(label("(none)"));
        }

        List<String> values() {
            return boxes.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            boxes.forEach(b -> b.setEnabled(enabled));
        }
    }

    /** Fills a name field with a suggestion until the user types their own name. */
    static final class AutoName {
        private final JTextField field;
        private boolean userTyped;
        private boolean setting;

        AutoName(JTextField field, boolean active) {
            this.field = field;
            this.userTyped = !active;
            onChange(field, () -> {
                if (!setting) userTyped = true;
            });
        }

        void suggest(String table, List<String> columns, String suffix) {
            if (userTyped) return;
            var base = table + (columns.isEmpty() ? "" : "_" + String.join("_", columns)) + "_" + suffix;
            base = base.replaceAll("[^A-Za-z0-9_]+", "_").toLowerCase(Locale.ROOT);
            if (base.length() > 63) base = base.substring(0, 63);
            setting = true;
            try {
                field.setText(base);
            } finally {
                setting = false;
            }
        }
    }

    static JButton iconButton(String icon, String tip, Runnable action) {
        var b = new JButton(Icons.get(icon, Icons.SMALL));
        b.setToolTipText(tip);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    static List<String> names(List<com.postgresforall.core.model.TableObjects.ColumnDetail> columns) {
        return columns.stream().map(com.postgresforall.core.model.TableObjects.ColumnDetail::name).toList();
    }

    static String[] actions() {
        return Arrays.asList("NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT").toArray(String[]::new);
    }
}
