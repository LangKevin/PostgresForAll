package com.postgresforall.app.ui.dialogs;

import com.postgresforall.app.ui.Palette;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.theme.Icons;
import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DatabaseCredential;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.PgSslMode;
import com.postgresforall.core.model.ServerKey;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.ConnectionErrors;
import com.postgresforall.core.service.ConnectionManager;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;

/**
 * Two modes, both verified with a throwaway connection before returning:
 * <ul>
 *   <li>Database credentials: alternate credentials for one database on a
 *       server — to query a database owned by another role without logging out.</li>
 *   <li>Server login: the login for a whole server in the Object Explorer
 *       (database editable), see {@link #forServer}.</li>
 * </ul>
 */
public final class DatabaseCredentialsDialog extends BaseDialog {
    private final String database;
    private final String host;
    private final int port;
    private final boolean serverMode;
    private final JTextField dbBox = new JTextField(24);
    private final JTextField userBox = new JTextField(24);
    private final JPasswordField pwBox = new JPasswordField(24);
    private final JComboBox<PgSslMode> sslBox = new JComboBox<>(PgSslMode.values());
    private final JCheckBox rememberBox = new JCheckBox(rememberLabel());
    private final JTextArea status = wrappingText("", 40);
    private final JLabel statusIcon = new JLabel();
    private final JButton testBtn;
    private final JButton saveBtn;
    private boolean busy;

    private ConnectionProfile resultProfile = new ConnectionProfile();
    private DatabaseCredential result = new DatabaseCredential();
    private boolean remember;

    static String rememberLabel() {
        return switch (Platform.current()) {
            case WINDOWS -> "Remember password (DPAPI, this Windows user only)";
            case MAC -> "Remember password (in your login Keychain)";
            case LINUX -> "Remember password (in your keyring)";
        };
    }

    /** Database-credentials mode. */
    public DatabaseCredentialsDialog(Component owner, String database, String host, int port,
                                     DatabaseCredential existing) {
        super(owner, "Database credentials");
        this.database = database;
        this.host = host;
        this.port = port;
        this.serverMode = false;

        dbBox.setText(database);
        dbBox.setEditable(false);
        userBox.setText(existing != null ? existing.username : database);
        pwBox.setText(existing != null ? existing.password : "");
        sslBox.setSelectedItem(existing != null ? existing.sslMode : PgSslMode.PREFER);

        build(Icons.tinted("database", 24, Palette::accent), "Credentials for '" + database + "'",
                wrappingText("This database is on " + host + ":" + port + " but is owned by a different role than "
                        + "your login. Enter credentials with access to it — they'll be used for query "
                        + "tabs targeting this database only.", 48),
                form());
        saveBtn = addPrimaryButton("Save", this::onSave);
        testBtn = addButton("Test", this::onTest);
        addButton("Cancel", this::cancel);
        finish();
    }

    /**
     * Server-login mode: "Connect to localhost:5434". A superuser login shows
     * every database on that server.
     */
    public static DatabaseCredentialsDialog forServer(Component owner, DiscoveredServer server,
                                                      ConnectionProfile suggested, String lastError) {
        return new DatabaseCredentialsDialog(owner, server, suggested, lastError);
    }

    private DatabaseCredentialsDialog(Component owner, DiscoveredServer server, ConnectionProfile suggested,
                                      String lastError) {
        super(owner, "Connect to server");
        this.serverMode = true;
        this.database = suggested.database;
        this.host = server.host();
        this.port = server.port();

        dbBox.setText(suggested.database);
        userBox.setText(suggested.username);
        pwBox.setText(suggested.password);
        sslBox.setSelectedItem(suggested.sslMode);

        build(Icons.tinted("server", 24, Palette::accent), "Connect to " + server.address(),
                wrappingText((server.label() == null ? "" : server.label() + ". ")
                        + "Log in to this server to browse and query it. A superuser (e.g. 'postgres') "
                        + "sees every database on the server.", 48),
                form());
        saveBtn = addPrimaryButton("Connect", this::onSave);
        testBtn = addButton("Test", this::onTest);
        addButton("Cancel", this::cancel);
        if (lastError != null && !lastError.isBlank()) setStatus(lastError, true);
        finish();
    }

    private JPanel form() {
        var p = new JPanel(new MigLayout("insets 0, wrap 2, fillx, gapy 8", "[right]12[grow,fill]"));
        String[] labels = {"Database", "Username", "Password", "SSL mode"};
        Component[] fields = {dbBox, userBox, pwBox, sslBox};
        for (var i = 0; i < labels.length; i++) {
            var l = new JLabel(labels[i]);
            l.putClientProperty("FlatLaf.styleClass", "muted");
            p.add(l);
            p.add(fields[i]);
        }
        pwBox.putClientProperty("JPasswordField.showRevealButton", true);
        p.add(rememberBox, "skip, gaptop 4");
        statusIcon.setVisible(false);
        var statusRow = new JPanel(new MigLayout("insets 0, fillx", "[]6[grow,fill]"));
        statusRow.add(statusIcon, "top");
        statusRow.add(status);
        p.add(statusRow, "span 2, gaptop 6");
        return p;
    }

    private void finish() {
        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateButtons(); }
            @Override public void removeUpdate(DocumentEvent e) { updateButtons(); }
            @Override public void changedUpdate(DocumentEvent e) { updateButtons(); }
        };
        dbBox.getDocument().addDocumentListener(dl);
        userBox.getDocument().addDocumentListener(dl);
        updateButtons();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                if (pwBox.getPassword().length == 0) pwBox.requestFocusInWindow();
                else {
                    userBox.requestFocusInWindow();
                    userBox.selectAll();
                }
            }
        });
    }

    public ConnectionProfile resultProfile() {
        return resultProfile;
    }

    public DatabaseCredential result() {
        return result;
    }

    public boolean remember() {
        return remember;
    }

    private String targetDatabase() {
        return serverMode ? dbBox.getText().trim() : database;
    }

    private void updateButtons() {
        var ok = !userBox.getText().isBlank() && !targetDatabase().isBlank();
        testBtn.setEnabled(ok && !busy);
        saveBtn.setEnabled(ok && !busy);
    }

    private DatabaseCredential buildCredential() {
        var c = new DatabaseCredential();
        c.server = ServerKey.of(host, port);
        c.database = targetDatabase();
        c.username = userBox.getText().trim();
        c.password = new String(pwBox.getPassword());
        c.sslMode = (PgSslMode) sslBox.getSelectedItem();
        return c;
    }

    private ConnectionProfile buildProfile() {
        var cred = buildCredential();
        var p = new ConnectionProfile();
        p.host = host;
        p.port = port;
        p.database = cred.database;
        p.username = cred.username;
        p.password = cred.password;
        p.sslMode = cred.sslMode;
        return p;
    }

    private void onTest() {
        var profile = buildProfile();
        setBusy(true);
        setStatus("Testing connection...", false);
        Ui.bgQuiet(() -> {
            try (var mgr = ConnectionManager.open(profile)) {
                Ui.edt(() -> setStatus("Connected to '" + profile.database + "'. PostgreSQL " + mgr.serverVersion()
                        + (mgr.isSuperuser() ? " (superuser)." : "."), false));
            } catch (Exception ex) {
                Ui.edt(() -> setStatus(ConnectionErrors.message(ex), true));
            } finally {
                Ui.edt(() -> setBusy(false));
            }
        });
    }

    private void onSave() {
        var profile = buildProfile();
        var cred = buildCredential();
        var rememberChoice = rememberBox.isSelected();
        setBusy(true);
        setStatus("Verifying credentials...", false);
        Ui.bgQuiet(() -> {
            try (var ignored = ConnectionManager.open(profile)) {
                Ui.edt(() -> {
                    result = cred;
                    resultProfile = profile;
                    remember = rememberChoice;
                    accept();
                });
            } catch (Exception ex) {
                Ui.edt(() -> {
                    setStatus(serverMode ? "Couldn't connect: " + ConnectionErrors.message(ex)
                            : "Won't save — connection failed: " + ConnectionErrors.message(ex), true);
                    setBusy(false);
                });
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        setCursor(b ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR) : null);
        updateButtons();
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? Palette.danger() : Palette.muted());
        statusIcon.setIcon(error ? Icons.tinted("circle-x", Icons.NORMAL, Palette::danger)
                : Icons.tinted("info", Icons.NORMAL, Palette::muted));
        statusIcon.setVisible(!text.isEmpty());
        pack();
    }
}
