package com.postgresforall.app.ui.diag;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.dialogs.BaseDialog;
import com.postgresforall.app.ui.theme.Fonts;
import com.postgresforall.app.ui.theme.Icons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;

/** Troubleshooting help for one diagnostics topic. */
public final class HelpDialog extends BaseDialog {

    private HelpDialog(Component owner, HelpTopic topic) {
        super(owner, "Help");
        var content = HelpContent.forTopic(topic);

        var body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 12));
        for (var section : content.sections()) {
            var h = new JLabel(section.heading());
            h.putClientProperty("FlatLaf.styleClass", "sectionTitle");
            h.setAlignmentX(LEFT_ALIGNMENT);
            body.add(Box.createVerticalStrut(14));
            body.add(h);
            body.add(Box.createVerticalStrut(6));
            for (var line : section.lines()) {
                if (line.startsWith("    ")) {
                    // A command or code sample: monospaced, in a soft rounded box.
                    var code = new RoundedPanel(new BorderLayout())
                            .fill(Palette::headerStrip).outline(Palette::border);
                    code.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
                    var t = new JLabel(line.strip());
                    t.setFont(Fonts.mono(-1));
                    code.add(t, BorderLayout.CENTER);
                    code.setAlignmentX(LEFT_ALIGNMENT);
                    code.setMaximumSize(new Dimension(Integer.MAX_VALUE, code.getPreferredSize().height));
                    body.add(code);
                } else {
                    var t = wrappingText(line, 60);
                    t.setForeground(h.getForeground());
                    t.setBorder(BorderFactory.createEmptyBorder(0, line.startsWith("•") ? 4 : 0, 0, 0));
                    t.setAlignmentX(LEFT_ALIGNMENT);
                    body.add(t);
                }
                body.add(Box.createVerticalStrut(3));
            }
        }
        var scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(620, 400));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        var title = content.title();
        var summary = wrappingText(content.summary(), 60);
        summary.putClientProperty("FlatLaf.styleClass", "muted");
        build(title, summary, scroll);
        if (content.primaryLink() != null) {
            var link = content.primaryLink();
            var b = addButton(link.label(), () -> {
                if (!Ui.browse(link.url())) Ui.warn(this, "Open link", "Couldn't open " + link.url());
            });
            b.setIcon(Icons.get("download"));
        }
        var close = addButton("Close", this::cancel);
        getRootPane().setDefaultButton(close);
        setResizable(true);
    }

    /** Shows the topic modally. Safe from any thread. */
    public static void show(Component owner, HelpTopic topic) {
        Ui.edt(() -> new HelpDialog(owner, topic).showDialog());
    }
}
