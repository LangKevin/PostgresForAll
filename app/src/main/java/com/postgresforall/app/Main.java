package com.postgresforall.app;

import com.postgresforall.app.cli.CheckCommand;
import com.postgresforall.app.ui.LoginDialog;
import com.postgresforall.app.ui.MainFrame;
import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.theme.AppIcon;
import com.postgresforall.app.ui.theme.Theme;
import com.postgresforall.core.config.AppConfig;
import com.postgresforall.core.model.DiscoveredServer;
import com.postgresforall.core.model.ServerSource;
import com.postgresforall.core.platform.Platform;
import com.postgresforall.core.service.DatabaseCredentialStore;
import com.postgresforall.core.service.PasswordStore;
import com.postgresforall.core.service.ServerRegistry;

import javax.swing.SwingUtilities;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point. {@code --check} runs the console health check; anything else
 * starts the GUI: login dialog, then the main window.
 */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("--check")) {
            var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            out.println();
            System.exit(CheckCommand.run(args, out));
            return;
        }

        if (Platform.isMac()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "PostgresForAll");
            System.setProperty("apple.awt.application.appearance", "system");
        }
        System.setProperty("flatlaf.uiScale.allowScaleDown", "true");

        SwingUtilities.invokeLater(Main::startGui);
    }

    private static void startGui() {
        // Dark by default; View > Appearance switches to light or follow-system (and is remembered).
        var appearance = AppSettings.load().appearance;
        Theme.init(appearance == null || appearance.isBlank() ? Theme.Mode.DARK : Theme.Mode.parse(appearance));
        AppIcon.applyToTaskbar();

        var passwords = new PasswordStore();
        var login = new LoginDialog(passwords);
        if (!login.showDialog() || login.connectedManager() == null) {
            System.exit(0);
            return;
        }

        var manager = login.connectedManager();
        var loginProfile = manager.profile().copy();
        // SSMS-style: a superuser login also lists every other Postgres server on
        // this machine. Non-superusers see only the server they signed in to.
        var discover = manager.isSuperuser() && AppConfig.current().discoverServers && login.discoverServersRequested();
        // The login dialog's manager proved the credentials; close it — the
        // login server's session opens its own pools.
        Ui.bgQuiet(manager::close);

        var registry = new ServerRegistry(new DatabaseCredentialStore());
        var primary = registry.addOrUpdate(DiscoveredServer.of(loginProfile.host, loginProfile.port, ServerSource.LOGIN));
        var main = new MainFrame(registry, primary, loginProfile, discover, passwords);
        main.setVisible(true);
    }
}
