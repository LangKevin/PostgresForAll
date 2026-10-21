package com.postgresforall.app.ui.diag;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.dialogs.BaseDialog;
import com.postgresforall.core.platform.PortOwners;
import com.postgresforall.core.platform.PortOwners.Owner;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.stream.Collectors;

/** What's listening on a port, with an option to kill it. */
public final class PortOwnerDialog extends BaseDialog {
    private final int port;
    private final JPanel list = new JPanel();
    private final JButton killBtn;
    private List<Owner> owners = List.of();

    private PortOwnerDialog(Component owner, int port) {
        super(owner, "Port Owner");
        this.port = port;
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        var scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(520, 200));
        var center = new JPanel(new BorderLayout(0, 6));
        var title = new JLabel("Owners");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        center.add(title, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);

        build("What's using port " + port + "?",
                wrappingText("Postgres can't bind because another process owns this port. "
                        + "Identify it below — kill it only if you're sure.", 56),
                center);
        killBtn = addButton("Kill process", this::onKill);
        killBtn.setForeground(Palette.danger());
        addButton("Re-check", this::refresh);
        var close = addButton("Close", this::cancel);
        getRootPane().setDefaultButton(close);
        setResizable(true);
        refresh();
    }

    public static void show(Component owner, int port) {
        Ui.edt(() -> new PortOwnerDialog(owner, port).showDialog());
    }

    private void refresh() {
        killBtn.setEnabled(false);
        list.removeAll();
        list.add(new JLabel("Looking..."));
        list.revalidate();
        Ui.bgQuiet(() -> {
            var found = PortOwners.find(port, null);
            Ui.edt(() -> render(found));
        });
    }

    private void render(List<Owner> found) {
        owners = found;
        list.removeAll();
        if (found.isEmpty()) {
            var empty = new JLabel("Nothing is currently using this port.");
            empty.setForeground(Palette.muted());
            list.add(empty);
        }
        for (var o : found) {
            var row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
            row.setBorder(BorderFactory.createEmptyBorder(4, 4, 6, 4));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.add(new JLabel("<html><b>PID:</b> " + o.pid() + "  <font color='#bbbbbb'>•</font>  <b>"
                    + Ui.escapeHtml(o.processName()) + "</b></html>"));
            if (!o.detail().isBlank()) {
                var d = new JLabel(o.detail());
                d.setForeground(Palette.subtle());
                row.add(d);
            }
            list.add(row);
            list.add(Box.createVerticalStrut(2));
        }
        killBtn.setEnabled(!found.isEmpty());
        list.revalidate();
        list.repaint();
    }

    private void onKill() {
        if (owners.isEmpty()) return;
        var names = owners.stream().map(o -> o.processName() + " (PID " + o.pid() + ")")
                .collect(Collectors.joining(", "));
        if (!Ui.confirm(this, "Confirm kill", "Kill the following process(es)?\n\n" + names + "\n\n"
                + "This is a force-kill and the process won't have a chance to save state.", true)) {
            return;
        }
        for (var o : owners) {
            var handle = ProcessHandle.of(o.pid());
            if (handle.isEmpty()) continue;
            try {
                if (!handle.get().destroyForcibly()) {
                    Ui.error(this, "Kill failed", "Couldn't kill PID " + o.pid() + " (not permitted?).");
                }
            } catch (RuntimeException ex) {
                Ui.error(this, "Kill failed", "Couldn't kill PID " + o.pid() + ": " + ex.getMessage());
            }
        }
        refresh();
    }
}
