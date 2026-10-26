package com.postgresforall.app.ui;

import com.formdev.flatlaf.ui.FlatLineBorder;
import com.postgresforall.app.ui.diag.DiagnosticsDialog;
import com.postgresforall.app.ui.diag.DiagnosticsPanel;
import com.postgresforall.app.ui.theme.Icons;
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
import net.miginfocom.swing.MigLayout;

import javax.swing.ButtonGroup;
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
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.KeyEvent;

/**
 * "Connect to PostgreSQL Server": a brand pane beside the login form, plus a
 * Diagnostics tab. On a successful connect, {@link #connectedManager()} holds
 * the proven login.
 */
public final class LoginDialog extends JDialog {
    private final PasswordStore store;
    private final JToggleButton appUserToggle = new JToggleButton("Application user");
    private final JToggleButton superuserToggle = new JToggleButton("Superuser");
    private final JTextField hostBox = new JTextField(24);
    private final JTextField portBox = new JTextField(24);
    private final JLabel portError = new JLabel(" ");
    private final JTextField dbBox = new JTextField(24);
    private final JTextField userBox = new JTextField(24);
    private final JPasswordField pwBox = new JPasswordField(24);
    private final JComboBox<PgSslMode> sslBox = new JComboBox<>(PgSslMode.values());
    private final JCheckBox rememberBox = new JCheckBox(rememberLabel());
    private final JCheckBox discoverBox = new JCheckBox("Also list the other PostgreSQL servers on this machine");
    private final JLabel statusIcon = new JLabel();
    private final JTextArea status = new JTextArea(2, 32);
    private final JButton testBtn = new JButton("Test Connection");
    private final JButton connectBtn = new JButton("Connect");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel diagHost = new JPanel(new BorderLayout());
    private DiagnosticsPanel diagPanel;
    private boolean suppressRoleChange;
    private boolean busy;
    private volatile ConnectionManager connectedManager;
    private boolean accepted;

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
        com.postgresforall.app.ui.theme.AppIcon.apply(this);

        tabs.addTab("Login", loginTab());
        tabs.addTab("Diagnostics", diagHost);
        tabs.addChangeListener(e -> onTabChanged());
        var content = new JPanel(new BorderLayout());
        content.add(brandPane(), BorderLayout.WEST);
        content.add(tabs, BorderLayout.CENTER);
        setContentPane(content);
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

        // The toggle reflects whichever profile was restored rather than driving
        // it, so a superuser login stays sticky until you switch back.
        suppressRoleChange = true;
        (isSuperuserName(initial.username) ? superuserToggle : appUserToggle).setSelected(true);
        suppressRoleChange = false;
        appUserToggle.addActionListener(e -> onRoleChanged(false));
        superuserToggle.addActionListener(e -> onRoleChanged(true));
        discoverBox.setSelected(AppConfig.current().discoverServers);
        validatePort();

        setMinimumSize(new Dimension(760, 520));
        pack();
        setLocationRelativeTo(null);
    }

    /** Accent-coloured left column: icon, name, tagline. Colours are re-read on paint. */
    private JComponent brandPane() {
        var pane = new JPanel(new MigLayout("insets 28 24 20 24, wrap 1, fill", "[grow]", "[]14[]6[]push[]")) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Palette.brand());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        pane.setOpaque(true);
        pane.setPreferredSize(new Dimension(250, 10));
        pane.add(new JLabel(Icons.tinted("database", 44, Palette::chipText)));
        var name = new JLabel("PostgresForAll");
        name.putClientProperty("FlatLaf.styleClass", "h1");
        name.setForeground(Palette.chipText());
        pane.add(name);
        var tagline = new JTextArea("A fast PostgreSQL client for Windows and macOS");
        tagline.setEditable(false);
        tagline.setFocusable(false);
        tagline.setOpaque(false);
        tagline.setLineWrap(true);
        tagline.setWrapStyleWord(true);
        tagline.setFont(UIManager.getFont("Label.font"));
        tagline.setForeground(Palette.chipText());
        tagline.setBorder(null);
        pane.add(tagline, "growx, wmax 200");
        var footer = new JLabel("v1.0");
        footer.putClientProperty("FlatLaf.styleClass", "small");
        footer.setForeground(Palette.chipText());
        pane.add(footer);
        return pane;
    }

    private JComponent loginTab() {
        var form = new JPanel(new MigLayout("wrap 2, fillx, insets 20 24 16 24, gapy 8", "[right]14[grow,fill]"));

        appUserToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        superuserToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        appUserToggle.setFocusable(false);
        superuserToggle.setFocusable(false);
        var group = new ButtonGroup();
        group.add(appUserToggle);
        group.add(superuserToggle);
        var segmented = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        segmented.setBorder(new FlatLineBorder(new Insets(2, 2, 2, 2), UIManager.getColor("Component.borderColor"), 1, 10));
        segmented.add(appUserToggle);
        segmented.add(superuserToggle);

        hostBox.putClientProperty("JTextField.placeholderText", "localhost");
        portBox.putClientProperty("JTextField.placeholderText", "5432");
        dbBox.putClientProperty("JTextField.placeholderText", "postgres");
        userBox.putClientProperty("JTextField.placeholderText", "postgres");
        pwBox.putClientProperty("JPasswordField.showRevealButton", true);
        portError.putClientProperty("FlatLaf.styleClass", "danger small");
        portError.setVisible(false);
        portBox.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { validatePort(); }
            @Override public void removeUpdate(DocumentEvent e) { validatePort(); }
            @Override public void changedUpdate(DocumentEvent e) { validatePort(); }
        });

        form.add(muted("Connect as"));
        form.add(segmented, "growx 0");
        form.add(muted("Host"));
        form.add(hostBox);
        form.add(muted("Port"));
        form.add(portBox);
        form.add(portError, "skip, gaptop 0, hidemode 3");
        form.add(muted("Database"));
        form.add(dbBox);
        form.add(muted("Username"));
        form.add(userBox);
        form.add(muted("Password"));
        form.add(pwBox);
        form.add(muted("SSL mode"));
        form.add(sslBox);
        form.add(rememberBox, "skip, gaptop 6");
        discoverBox.setToolTipText("Superuser logins only. Each server gets its own entry in Object Explorer.");
        form.add(discoverBox, "skip");

        status.setEditable(false);
        status.setOpaque(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFocusable(false);
        status.setFont(UIManager.getFont("Label.font"));
        status.setForeground(Palette.muted());
        status.setBorder(null);
        statusIcon.setVisible(false);
        var statusRow = new JPanel(new MigLayout("insets 0, fillx", "[]6[grow,fill]"));
        statusRow.add(statusIcon, "top");
        statusRow.add(status);
        form.add(statusRow, "span 2, growx, gaptop 8, hmin 36");

        testBtn.addActionListener(e -> onTest());
        connectBtn.addActionListener(e -> onConnect());
        var cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        var buttons = new JPanel(new MigLayout("insets 0 24 20 24, fillx", "[]push[][]"));
        buttons.add(testBtn);
        if (Platform.isMac()) {
            buttons.add(cancel);
            buttons.add(connectBtn);
        } else {
            buttons.add(connectBtn);
            buttons.add(cancel);
        }

        var root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private static JLabel muted(String text) {
        var l = new JLabel(text);
        l.putClientProperty("FlatLaf.styleClass", "muted");
        return l;
    }

    /** Port must be 1-65535: red outline and a message otherwise, and no connecting. */
    private boolean validatePort() {
        var ok = false;
        try {
            var p = Integer.parseInt(portBox.getText().trim());
            ok = p > 0 && p <= 65535;
        } catch (NumberFormatException ignored) {
            // not a number
        }
        portBox.putClientProperty("JComponent.outline", ok ? null : "error");
        portError.setText(ok ? " " : "Port must be a number from 1 to 65535.");
        portError.setForeground(Palette.danger());
        portError.setVisible(!ok);
        updateButtons();
        return ok;
    }

    private void updateButtons() {
        var valid = !portError.isVisible();
        testBtn.setEnabled(!busy && valid);
        connectBtn.setEnabled(!busy && valid);
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
    private void onRoleChanged(boolean superuser) {
        if (suppressRoleChange) return;
        var cfg = AppConfig.current();
        // A preset is a whole target: also set host / port, so a remembered login
        // for some other server can't send it astray.
        if (superuser) {
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
        var superPreset = superuserToggle.isSelected();
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

    private void setBusy(boolean b) {
        busy = b;
        updateButtons();
        setCursor(b ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? Palette.danger() : Palette.muted());
        statusIcon.setIcon(error ? Icons.tinted("circle-x", Icons.NORMAL, Palette::danger)
                : Icons.tinted("info", Icons.NORMAL, Palette::muted));
        statusIcon.setVisible(!text.isEmpty());
    }
}
