package com.postgresforall.app.ui;

import com.postgresforall.app.ui.diag.DiagnosticsDialog;
import com.postgresforall.app.ui.diag.DiagnosticsPanel;
import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.PgSslMode;
import com.postgresforall.core.model.ServerKey;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.ConnectionErrors;
import com.postgresforall.core.service.ConnectionErrors.Kind;
import com.postgresforall.core.service.ConnectionManager;
import com.postgresforall.core.service.DatabaseOperations;
import com.postgresforall.core.service.PasswordStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;

/**
 * "Connect to PostgreSQL Server": login form plus a Diagnostics tab. On a
 * successful connect, {@link #connectedManager()} holds the proven login.
 */
public final class LoginDialog extends JDialog {
    private final PasswordStore store;
    private final JComboBox<RoleOption> roleBox = new JComboBox<>();
    private final JTextField hostBox = new JTextField(24);
    private final JTextField portBox = new JTextField(24);
    private final JTextField dbBox = new JTextField(24);
    private final JTextField userBox = new JTextField(24);
    private final JPasswordField pwBox = new JPasswordField(24);
    private final JComboBox<PgSslMode> sslBox = new JComboBox<>(PgSslMode.values());
    private final JCheckBox rememberBox = new JCheckBox(rememberLabel());
    private final JCheckBox discoverBox = new JCheckBox("Also list the other PostgreSQL servers on this machine");
    private final JTextArea status = new JTextArea(2, 40);
    private final JButton testBtn = new JButton("Test Connection");
    private final JButton connectBtn = new JButton("Connect");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel diagHost = new JPanel(new BorderLayout());
    private DiagnosticsPanel diagPanel;
    private boolean suppressRoleChange;
    private volatile ConnectionManager connectedManager;
    private boolean accepted;

    /** A "Connect as" preset. Username and database stay editable after picking one. */
    private record RoleOption(String label, boolean superuser) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final RoleOption APP_USER = new RoleOption("Application user", false);
    private static final RoleOption SUPERUSER = new RoleOption("Superuser", true);

    private static String rememberLabel() {
        return switch (Platform.current()) {
            case WINDOWS -> "Remember password (DPAPI, this Windows user only)";
            case MAC -> "Remember password (in your login Keychain)";
            case LINUX -> "Remember password (in your keyring)";
        };
    }

    public LoginDialog(PasswordStore store) {
        super((java.awt.Frame) null, "Connect to PostgreSQL Server", ModalityType.APPLICATION_MODAL);
        this.store = store;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        tabs.addTab("Login", loginTab());
        tabs.addTab("Diagnostics", diagHost);
        tabs.addChangeListener(e -> onTabChanged());
        setContentPane(tabs);
        getRootPane().setDefaultButton(connectBtn);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        var initial = store.findMostRecent();
        if (initial == null) initial = ConnectionProfile.fromConfig();
        hostBox.setText(initial.host);
        portBox.setText(String.valueOf(initial.port));
        dbBox.setText(initial.database);
        userBox.setText(initial.username);
        pwBox.setText(initial.password);
        sslBox.setSelectedItem(initial.sslMode);
        rememberBox.setSelected(initial.password != null && !initial.password.isEmpty());

        // The combo reflects whichever profile was restored rather than driving
        // it, so a superuser login stays sticky until you switch back.
        suppressRoleChange = true;
        roleBox.addItem(APP_USER);
        roleBox.addItem(SUPERUSER);
        roleBox.setSelectedItem(isSuperuserName(initial.username) ? SUPERUSER : APP_USER);
        suppressRoleChange = false;
        roleBox.addActionListener(e -> onRoleChanged());
        discoverBox.setSelected(AppConfig.current().discoverServers);

        setMinimumSize(new Dimension(520, 520));
        pack();
        setLocationRelativeTo(null);
    }

    private JComponent loginTab() {
        var root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        var title = new JLabel("PostgresForAll");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 7f));
        root.add(title, BorderLayout.NORTH);

        var form = new JPanel(new GridBagLayout());
        var c = new GridBagConstraints();
        c.insets = new Insets(5, 0, 5, 8);
        c.anchor = GridBagConstraints.WEST;
        String[] labels = {"Connect as:", "Host:", "Port:", "Database:", "Username:", "Password:", "SSL Mode:"};
        JComponent[] fields = {roleBox, hostBox, portBox, dbBox, userBox, pwBox, sslBox};
        for (var i = 0; i < labels.length; i++) {
            c.gridx = 0;
            c.gridy = i;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            form.add(new JLabel(labels[i]), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(fields[i], c);
        }
        c.gridx = 1;
        c.gridy = labels.length;
        c.insets = new Insets(10, 0, 0, 8);
        form.add(rememberBox, c);
        c.gridy++;
        c.insets = new Insets(4, 0, 0, 8);
        discoverBox.setToolTipText("Superuser logins only. Each server gets its own entry in Object Explorer.");
        form.add(discoverBox, c);
        c.gridy++;
        c.weighty = 1;
        form.add(new JPanel(), c);

        var center = new JPanel(new BorderLayout(0, 8));
        center.add(form, BorderLayout.CENTER);
        status.setEditable(false);
        status.setOpaque(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFocusable(false);
        status.setFont(UIManager.getFont("Label.font"));
        status.setForeground(Palette.muted());
        center.add(status, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        testBtn.addActionListener(e -> onTest());
        connectBtn.addActionListener(e -> onConnect());
        var cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        buttons.add(testBtn);
        buttons.add(connectBtn);
        buttons.add(cancel);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    /** Shows the dialog; true when a connection was made. Call on the EDT. */
    public boolean showDialog() {
        setVisible(true);
        if (diagPanel != null) diagPanel.dispose();
        return accepted;
    }

    public ConnectionManager connectedManager() {
        return connectedManager;
    }

    /** Whether the user asked to list the machine's other servers (applies to superusers). */
    public boolean discoverServersRequested() {
        return discoverBox.isSelected();
    }

    private static boolean isSuperuserName(String username) {
        return username != null && username.equalsIgnoreCase(AppConfig.current().superuserUsername);
    }

    /**
     * Applies the picked preset. The password never carries across roles —
     * switching to Superuser always re-asks; switching back restores the
     * application-user defaults from the config.
     */
    private void onRoleChanged() {
        if (suppressRoleChange || !(roleBox.getSelectedItem() instanceof RoleOption role)) return;
        var cfg = AppConfig.current();
        // A preset is a whole target: also set host / port, so a remembered login
        // for some other server can't send it astray.
        if (role.superuser()) {
            hostBox.setText(cfg.effectiveSuperuserHost());
            portBox.setText(String.valueOf(cfg.effectiveSuperuserPort()));
            userBox.setText(cfg.superuserUsername);
            dbBox.setText(cfg.superuserDatabase);
            pwBox.setText("");
            rememberBox.setSelected(false);
            setStatus("Enter the password for '" + cfg.superuserUsername + "'. Username and database stay editable.", false);
        } else {
            hostBox.setText(cfg.host);
            portBox.setText(String.valueOf(cfg.port));
            userBox.setText(cfg.username);
            dbBox.setText(cfg.database);
            pwBox.setText(cfg.password);
            rememberBox.setSelected(!cfg.password.isEmpty());
            setStatus("", false);
        }
        pwBox.requestFocusInWindow();
    }

    private void onTabChanged() {
        if (tabs.getSelectedIndex() != 1 || diagPanel != null) return;
        diagPanel = new DiagnosticsPanel(() -> {
            var p = buildProfile(false);
            return p != null ? p : ConnectionProfile.fromConfig();
        }, null);
        diagPanel.onEditCredentials(() -> {
            tabs.setSelectedIndex(0);
            pwBox.requestFocusInWindow();
            pwBox.selectAll();
        });
        diagHost.add(diagPanel, BorderLayout.CENTER);
        diagHost.revalidate();
        diagPanel.refresh();
    }

    private ConnectionProfile buildProfile(boolean report) {
        int port;
        try {
            port = Integer.parseInt(portBox.getText().trim());
        } catch (NumberFormatException ex) {
            if (report) setStatus("Port must be a number.", true);
            return null;
        }
        var p = new ConnectionProfile();
        p.host = hostBox.getText().trim();
        p.port = port;
        p.database = dbBox.getText().trim();
        p.username = userBox.getText().trim();
        p.password = new String(pwBox.getPassword());
        p.sslMode = sslBox.getSelectedItem() instanceof PgSslMode m ? m : PgSslMode.PREFER;
        return p;
    }

    private void onTest() {
        var profile = buildProfile(true);
        if (profile == null) return;
        setBusy(true);
        setStatus("Testing connection...", false);
        Ui.bgQuiet(() -> {
            try (var mgr = ConnectionManager.open(profile)) {
                Ui.edt(() -> setStatus("Connected. PostgreSQL " + mgr.serverVersion() + ".", false));
            } catch (Exception ex) {
                handleConnectionException(profile, ex);
            } finally {
                Ui.edt(() -> setBusy(false));
            }
        });
    }

    private void onConnect() {
        var profile = buildProfile(true);
        if (profile == null) return;
        var remember = rememberBox.isSelected();
        var superPreset = roleBox.getSelectedItem() == SUPERUSER;
        setBusy(true);
        setStatus("Connecting...", false);
        Ui.bgQuiet(() -> {
            try {
                connectAndClose(profile, remember, superPreset);
            } catch (Exception ex) {
                connectedManager = null;
                if (handleConnectionException(profile, ex)) {
                    // retry once after a successful recovery (server started / DB brought online)
                    try {
                        connectAndClose(profile, remember, superPreset);
                    } catch (Exception retryEx) {
                        Ui.edt(() -> setStatus(ConnectionErrors.message(retryEx), true));
                    }
                }
            } finally {
                Ui.edt(() -> setBusy(false));
            }
        });
    }

    /**
     * Opens the connection, saves the profile, warns when the Superuser preset
     * was picked but the role isn't one, then closes. Failures propagate to the
     * caller's recovery path. Runs off the EDT.
     */
    private void connectAndClose(ConnectionProfile profile, boolean remember, boolean superPreset) throws Exception {
        var mgr = ConnectionManager.open(profile);
        connectedManager = mgr;
        store.save(profile, remember);

        if (superPreset && !mgr.isSuperuser()) {
            // Connect anyway — PostgreSQL is the authority on what a role can do —
            // but say so, since the extra reach was the point of the preset.
            Ui.warn(this, "Not a superuser",
                    "Connected as '" + mgr.currentUser() + "', but that role is not a superuser.\n\n"
                            + "Databases it lacks CONNECT privilege on will still ask for separate "
                            + "credentials when you expand them in the Object Explorer.");
        }
        Ui.edt(() -> {
            accepted = true;
            dispose();
        });
    }

    /**
     * Shows the failure and offers recovery: diagnostics for an unreachable local
     * server, bring-online for an offline database. True when the caller should
     * retry. Runs off the EDT.
     */
    private boolean handleConnectionException(ConnectionProfile profile, Exception ex) {
        var kind = ConnectionErrors.classify(ex);
        var msg = ConnectionErrors.message(ex);
        Ui.edt(() -> setStatus(msg, true));

        if (kind == Kind.UNREACHABLE && ServerKey.isLoopback(profile.host)) {
            return Ui.call(() -> DiagnosticsDialog.show(this, () -> profile, null));
        }

        if (kind == Kind.DATABASE_OFFLINE) {
            var ask = Ui.yesNo(this, "Database offline",
                    "Database '" + profile.database + "' is currently offline (ALLOW_CONNECTIONS = false).\n\n"
                            + "Bring it back online and retry connecting?\n\n"
                            + "Yes  → run ALTER DATABASE ... WITH ALLOW_CONNECTIONS true via the 'postgres' "
                            + "maintenance database using these credentials, then reconnect\n"
                            + "No   → leave it offline", false);
            if (!ask) return false;
            Ui.edt(() -> setStatus("Bringing '" + profile.database + "' back online...", false));
            try {
                DatabaseOperations.bringOnlineUsingProfile(profile, profile.database);
                Ui.edt(() -> setStatus("'" + profile.database + "' is online. Retrying connect...", false));
                return true;
            } catch (Exception bex) {
                Ui.edt(() -> setStatus("Could not bring '" + profile.database + "' online: "
                        + ConnectionErrors.message(bex), true));
                return false;
            }
        }
        return false;
    }

    private void setBusy(boolean busy) {
        testBtn.setEnabled(!busy);
        connectBtn.setEnabled(!busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? Palette.danger() : Palette.muted());
    }
}
