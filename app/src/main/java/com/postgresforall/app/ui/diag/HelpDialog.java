package com.postgresforall.app.ui.diag;

import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.dialogs.BaseDialog;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/** Troubleshooting help for one diagnostics topic. */
public final class HelpDialog extends BaseDialog {

    private HelpDialog(Component owner, HelpTopic topic) {
        super(owner, "Help");
        var content = HelpContent.forTopic(topic);

        var body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        for (var section : content.sections()) {
            var h = new JLabel(section.heading());
            h.setFont(h.getFont().deriveFont(Font.BOLD));
            h.setAlignmentX(LEFT_ALIGNMENT);
            body.add(Box.createVerticalStrut(12));
            body.add(h);
            body.add(Box.createVerticalStrut(4));
            for (var line : section.lines()) {
                var t = wrappingText(line, 60);
                t.setForeground(h.getForeground());
                if (line.startsWith("    ")) t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, t.getFont().getSize()));
                t.setAlignmentX(LEFT_ALIGNMENT);
                body.add(t);
                body.add(Box.createVerticalStrut(2));
            }
        }
        var scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(600, 380));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        build(content.title(), wrappingText(content.summary(), 60), scroll);
        if (content.primaryLink() != null) {
            var link = content.primaryLink();
            addButton(link.label(), () -> {
                if (!Ui.browse(link.url())) Ui.warn(this, "Open link", "Couldn't open " + link.url());
            });
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
