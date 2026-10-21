package com.postgresforall.app.ui.diag;

import com.postgresforall.core.platform.Platform;

import java.util.List;

/** Troubleshooting text per topic, worded for the current OS. */
final class HelpContent {
    private HelpContent() { }

    record Section(String heading, List<String> lines) { }

    record Link(String label, String url) { }

    record Content(String title, String summary, List<Section> sections, Link primaryLink) { }

    private static Section s(String heading, String... lines) {
        return new Section(heading, List.of(lines));
    }

    static Content forTopic(HelpTopic topic) {
        var win = Platform.isWindows();
        var mac = Platform.isMac();
        var terminal = win ? "Open a PowerShell window and run:" : "Open a terminal and run:";
        return switch (topic) {
            case DOCKER_NOT_INSTALLED -> new Content(
                    "Docker Desktop not found",
                    "We couldn't find Docker Desktop in the standard install locations. "
                            + "The 'Start Docker Desktop' fix can only launch it from one of these paths.",
                    List.of(
                            win ? s("Where we looked",
                                    "• C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe",
                                    "• %LOCALAPPDATA%\\Programs\\Docker\\Docker\\Docker Desktop.exe")
                                    : mac ? s("Where we looked",
                                    "• /Applications/Docker.app",
                                    "• ~/Applications/Docker.app")
                                    : s("Where we looked",
                                    "• Docker Desktop isn't auto-started on Linux; start the docker service yourself."),
                            s("Common reasons",
                                    "1. Docker Desktop isn't installed on this machine.",
                                    "2. Installed to a custom location (the 'Start' fix won't find it).",
                                    mac ? "3. You use Colima, OrbStack, Rancher Desktop or Podman Desktop — they don't "
                                            + "ship Docker Desktop."
                                            : "3. You use Rancher Desktop, Podman Desktop, or plain WSL-only "
                                            + "Docker — they don't ship Docker Desktop."),
                            s("What to do",
                                    "• If not installed: download and install Docker Desktop from the link below.",
                                    "• If installed elsewhere: start it manually and then click Refresh on this tab.",
                                    "• If using an alternative runtime: start that engine manually. As long as the "
                                            + "'docker' CLI works against it, the Diagnostics tab will work too — "
                                            + "only the auto-start fix won't.",
                                    "• If you've started it but Diagnostics still shows it down, give it 30-60s "
                                            + "to finish initialising, then click Refresh."),
                            s("Verify Docker manually",
                                    terminal,
                                    "    docker version",
                                    "If you get version info from both Client and Server, Docker is up and the "
                                            + "Diagnostics tab will pick it up on the next Refresh.")),
                    new Link("Open docker.com", "https://www.docker.com/products/docker-desktop/"));

            case DOCKER_NOT_RUNNING -> new Content(
                    "Docker daemon not reachable",
                    "The 'docker' command is available but couldn't talk to the Docker engine.",
                    List.of(
                            s("Common reasons",
                                    "1. Docker Desktop hasn't been started yet (or was just started and is "
                                            + "still initialising — takes 20-60s).",
                                    "2. Docker Desktop crashed or hung — try Quit → Start.",
                                    win ? "3. WSL2 backend lost its VM — restart Docker Desktop."
                                            : "3. Docker's VM stopped — restart Docker Desktop."),
                            s("What to do",
                                    "• Click 'Start Docker Desktop' — we'll launch it and wait up to 90s for "
                                            + "the daemon to come up.",
                                    win ? "• If that fails: open Docker Desktop from the Start menu and wait for the "
                                            + "whale icon in the system tray to stop animating."
                                            : "• If that fails: open Docker Desktop from Applications and wait for the "
                                            + "whale icon in the menu bar to stop animating.",
                                    "• Then click Refresh on this tab.")),
                    null);

            case CONTAINER_NOT_FOUND -> new Content(
                    "Container doesn't exist",
                    "This server is configured as a Docker container, Docker is running, but there's no "
                            + "container by that name. (Docker is optional — a native PostgreSQL install works "
                            + "just as well; see the last section.)",
                    List.of(
                            s("Create the container",
                                    "From the ScheduleMe project root, run:",
                                    "    docker compose up -d",
                                    "This creates and starts scheduleme-postgres on port 5444.",
                                    "Then click Refresh on this tab."),
                            s("If you've already done that",
                                    "• Check whether the container was created under a different name:",
                                    "    docker ps -a",
                                    "• If you see it with a different name, set \"DockerContainerName\" (or the "
                                            + "server's \"DockerContainer\" in \"Servers\") in postgresconnect.config.json."),
                            s("Not using Docker?",
                                    "Point \"Host\" / \"Port\" in postgresconnect.config.json at your native "
                                            + "PostgreSQL install (installers default to port 5432) and clear "
                                            + "\"DockerContainerName\". Nothing in PostgresForAll requires Docker.")),
                    null);

            case SERVICE_STOPPED -> win
                    ? new Content(
                    "PostgreSQL Windows service is stopped",
                    "This server is a native PostgreSQL install running as a Windows service, and the "
                            + "service isn't running. Click 'Start service' (Windows asks for admin rights).",
                    List.of(
                            s("Start it yourself",
                                    "• Services app (services.msc) → 'postgresql-x64-NN' → Start, or",
                                    "• from an admin terminal:  net start postgresql-x64-18",
                                    "• Set Startup type to Automatic to have it start with Windows."),
                            s("If it won't start",
                                    "Check the server log in the data folder (e.g. "
                                            + "C:\\Program Files\\PostgreSQL\\18\\data\\log) — common causes are "
                                            + "another program on the port, or a typo in postgresql.conf.")),
                    null)
                    : new Content(
                    "PostgreSQL " + Platform.current().nativeServiceNoun() + " is stopped",
                    "This server is a native PostgreSQL install run by launchd, and it isn't running. "
                            + "Click 'Start service' (system-wide installs ask for an administrator password).",
                    List.of(
                            s("Start it yourself",
                                    "• Homebrew:  brew services start postgresql@NN",
                                    "• EDB installer:  sudo launchctl kickstart system/com.edb.launchd.postgresql-NN",
                                    "• Postgres.app: open Postgres.app and click Start."),
                            s("If it won't start",
                                    "Check the server log — Homebrew: /opt/homebrew/var/log/postgresql@NN.log "
                                            + "(/usr/local/var/log on Intel Macs); EDB: /Library/PostgreSQL/NN/data/log. "
                                            + "Common causes are another program on the port, or a typo in postgresql.conf.")),
                    null);

            case CONTAINER_STOPPED -> new Content(
                    "Container exists but isn't running",
                    "Click 'Start container' to bring it back up — or use the Container Actions buttons "
                            + "below for finer control (Start / Stop / Restart / View logs).",
                    List.of(s("If it won't start",
                            "1. Click 'View logs' below to see what Postgres said on the last boot.",
                            "2. Common causes: port 5444 already in use, corrupted data volume, "
                                    + "out-of-disk on the Docker VM.")),
                    null);

            case TCP_CLOSED -> new Content(
                    "TCP connection refused",
                    "Nothing is listening on the configured host and port. The fix on this row picks "
                            + "the best recovery action based on what runs the server — a native service, a "
                            + "Docker container, or neither.",
                    List.of(
                            win ? s("Native install (Windows service)",
                                    "1. If the Service row says 'stopped' → click Start service.",
                                    "2. Check the port: the EDB installer defaults to 5432; postgresql.conf has the real value.")
                                    : s("Native install (Homebrew / EDB / Postgres.app)",
                                    "1. If the Service row says 'stopped' → click Start service.",
                                    "2. Check the port: installers default to 5432; postgresql.conf has the real value."),
                            s("Docker container",
                                    "1. If the Docker row is also red → start Docker first.",
                                    "2. If the Container row says 'not found' → create it via docker compose.",
                                    "3. If the Container row says 'exited' → click Start container.",
                                    "4. If the Container is 'running' but TCP is still closed → Postgres may "
                                            + "still be initialising (wait 5-10s) or the wrong port is mapped."),
                            s("Remote / other",
                                    "Start the server where it runs, check firewalls, and confirm Host / Port.")),
                    null);

            case AUTH_FAILED -> new Content(
                    "Authentication failed",
                    "TCP works and Postgres is up, but the username or password is wrong.",
                    List.of(s("What to do",
                            "• Click 'Edit credentials' to jump back to the Login tab.",
                            "• The default login comes from \"Username\" / \"Password\" in "
                                    + "postgresconnect.config.json.",
                            "• If you've rotated the password, look it up in your password manager "
                                    + "or your local .env.")),
                    null);

            case QUERY_FAILED -> new Content(
                    "Query failed",
                    "Connection and auth succeeded but a basic SELECT failed. Rare — usually means "
                            + "your role lost a permission mid-session, or the server is overloaded.",
                    List.of(s("What to try",
                            "• Click Retry — it may be transient.",
                            "• If repeated: reconnect (close and re-open the app).",
                            "• If still failing: check pg_stat_activity for long-running queries that "
                                    + "may be blocking yours.")),
                    null);

            case OUT_OF_MEMORY -> win
                    ? new Content(
                    "Container killed for out-of-memory",
                    "Docker killed the Postgres container because it exceeded its memory limit. "
                            + "On Windows this is governed by the WSL2 memory cap.",
                    List.of(
                            s("Quick fix",
                                    "1. Right-click the Docker whale tray icon → Settings.",
                                    "2. Resources → Advanced → drag the Memory slider higher (≥ 4 GB recommended "
                                            + "for Postgres).",
                                    "3. Apply & Restart.",
                                    "4. Click Refresh on the Diagnostics tab."),
                            s("If you can't raise the cap",
                                    "• Close other memory-heavy apps (browsers, IDEs) before launching Docker.",
                                    "• In your postgresql.conf, lower shared_buffers and work_mem.",
                                    "• If using WSL2, you can override the global cap in %USERPROFILE%\\.wslconfig:",
                                    "      [wsl2]",
                                    "      memory=4GB")),
                    null)
                    : new Content(
                    "Container killed for out-of-memory",
                    "Docker killed the Postgres container because it exceeded its memory limit. "
                            + "On a Mac this is the memory given to Docker Desktop's VM.",
                    List.of(
                            s("Quick fix",
                                    "1. Docker Desktop → Settings (gear icon).",
                                    "2. Resources → drag the Memory slider higher (≥ 4 GB recommended for Postgres).",
                                    "3. Apply & Restart.",
                                    "4. Click Refresh on the Diagnostics tab."),
                            s("If you can't raise the cap",
                                    "• Close other memory-heavy apps (browsers, IDEs) before launching Docker.",
                                    "• In your postgresql.conf, lower shared_buffers and work_mem.")),
                    null);

            case CONNECTION_LIMIT -> new Content(
                    "Connection limit reached",
                    "Postgres ran out of connection slots. Either kill idle sessions or raise "
                            + "max_connections in postgresql.conf.",
                    List.of(
                            s("Find idle sessions",
                                    "Open a new query tab and run:",
                                    "    SELECT pid, usename, state, query_start,",
                                    "           now() - state_change AS idle_for, left(query, 60) AS last_query",
                                    "    FROM   pg_stat_activity",
                                    "    WHERE  state = 'idle'",
                                    "    ORDER  BY state_change;"),
                            s("Terminate a session by PID",
                                    "    SELECT pg_terminate_backend(<pid>);",
                                    "Only terminate sessions you recognise — killing the wrong PID can disrupt "
                                            + "active work."),
                            s("Raise the cap (server-wide)",
                                    "• Edit postgresql.conf: max_connections = 300 (default is 100).",
                                    "• Restart Postgres for the change to take effect.",
                                    "• Increases shared memory usage — see Postgres docs for sizing.")),
                    null);

            case GENERAL -> new Content(
                    "Diagnostics troubleshooting",
                    "Each red row in Postgres Health has its own Fix button that knows what to do. "
                            + "Pick the topic that matches the row you're stuck on.",
                    List.of(s("All topics",
                            "• TCP connection refused",
                            "• PostgreSQL " + Platform.current().nativeServiceNoun() + " stopped",
                            "• Docker daemon not running / Docker Desktop not installed (Docker servers only)",
                            "• Container not found / stopped (Docker servers only)",
                            "• Authentication failed",
                            "• Query failed",
                            "Click the help icon next to the banner to jump to the right topic.")),
                    null);
        };
    }
}
