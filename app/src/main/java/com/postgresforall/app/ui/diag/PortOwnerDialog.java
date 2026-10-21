package com.postgresforall.app.ui.diag;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.dialogs.BaseDialog;
import com.postgresforall.app.ui.theme.Icons;
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
        list.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        var holder = new JPanel(new BorderLayout());
        holder.add(list, BorderLayout.NORTH);
        var scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(540, 210));
        var center = new JPanel(new BorderLayout(0, 8));
        var title = new JLabel("Owners");
        title.putClientProperty("FlatLaf.styleClass", "sectionTitle");
        center.add(title, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);

        var body = wrappingText("Postgres can't bind because another process owns this port. "
                + "Identify it below — kill it only if you're sure.", 56);
        body.putClientProperty("FlatLaf.styleClass", "muted");
        build("What's using port " + port + "?", body, center);
        killBtn = addButton("Kill process", this::onKill);
        killBtn.setIcon(Icons.get("power", Icons.SMALL));
        killBtn.putClientProperty("FlatLaf.styleClass", "danger");
        var recheck = addButton("Re-check", this::refresh);
        recheck.setIcon(Icons.get("refresh-cw", Icons.SMALL));
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
        var looking = new JLabel("Looking...", Icons.get("loader", Icons.SMALL), JLabel.LEADING);
        looking.putClientProperty("FlatLaf.styleClass", "muted");
        list.add(looking);
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
            var empty = new JLabel("Nothing is currently using this port.",
                    Icons.tinted("circle-check", Icons.NORMAL, Palette::success), JLabel.LEADING);
            empty.putClientProperty("FlatLaf.styleClass", "muted");
            list.add(empty);
        }
        for (var o : found) {
            var card = new RoundedPanel(new BorderLayout(10, 0))
                    .fill(RoundedPanel::panelBackground).outline(Palette::border);
            card.setAlignmentX(LEFT_ALIGNMENT);
            card.add(new JLabel(Icons.get("terminal", Icons.LARGE)), BorderLayout.WEST);
            var text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            var name = new JLabel(o.processName());
            name.putClientProperty("FlatLaf.styleClass", "semibold");
            var pid = new JLabel("PID " + o.pid());
            pid.putClientProperty("FlatLaf.styleClass", "small muted");
            text.add(name);
            text.add(pid);
            if (!o.detail().isBlank()) {
                var d = new JLabel(o.detail());
                d.putClientProperty("FlatLaf.styleClass", "small subtle");
                d.setToolTipText(o.detail());
                text.add(d);
            }
            card.add(text, BorderLayout.CENTER);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            list.add(card);
            list.add(Box.createVerticalStrut(6));
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
