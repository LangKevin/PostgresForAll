package com.postgresforall.app.ui.diag;

import com.postgresforall.app.ui.dialogs.BaseDialog;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DiscoveredServer;

import java.awt.Component;
import java.awt.Dimension;
import java.util.function.Supplier;

/** Modal "Postgres Health" window; "Retry connection" closes it with true. */
public final class DiagnosticsDialog extends BaseDialog {
    private final DiagnosticsPanel panel;

    private DiagnosticsDialog(Component owner, Supplier<ConnectionProfile> profileProvider, DiscoveredServer server) {
        super(owner, "PostgreSQL Diagnostics");
        panel = new DiagnosticsPanel(profileProvider, server);
        build(null, null, panel);
        addButton("Retry connection", this::accept);
        addButton("Close", this::cancel);
        setResizable(true);
        setPreferredSize(new Dimension(760, 560));
    }

    @Override
    public void dispose() {
        panel.dispose();
        super.dispose();
    }

    /** Modal; the panel probes on open. Returns true for "Retry connection". Call on the EDT. */
    public static boolean show(Component owner, Supplier<ConnectionProfile> profileProvider, DiscoveredServer serverOrNull) {
        var dlg = new DiagnosticsDialog(owner, profileProvider, serverOrNull);
        dlg.panel.refresh();
        return dlg.showDialog();
    }
}
