package com.postgresforall.app.ui.diag;

import com.postgresforall.app.ui.Ui;
import com.postgresforall.app.ui.dialogs.ConfirmTypeDialog;
import com.postgresforall.core.model.LogIssue.FixAction;
import com.postgresforall.core.platform.AppPaths;
import com.postgresforall.core.platform.DefaultProcessRunner;
import com.postgresforall.core.service.DockerProbe;

import java.awt.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Concrete fix actions for log issues. Each returns a label + action, or null
 * when the issue has no auto-fix. Docker-only fixes (prune, recreate, memory)
 * are offered only for a containerised server. Actions run on the EDT and push
 * slow work to background threads.
 */
final class LogFixActions {
    record Fix(String label, Runnable action) { }

    private final Component owner;
    private final DockerProbe docker;
    private final int expectedPort;
    private final Runnable refreshLogs;

    /** @param docker the server's container, or null when it isn't one */
    LogFixActions(Component owner, DockerProbe docker, int expectedPort, Runnable refreshLogs) {
        this.owner = owner;
        this.docker = docker;
        this.expectedPort = expectedPort;
        this.refreshLogs = refreshLogs;
    }

    static Path guidePath() {
        return AppPaths.home().resolve("Downloads").resolve("ScheduleMe-DB-Connection-Guide.md");
    }

    Fix forAction(FixAction id, List<String> matchedLines) {
        var dockerOnly = id == FixAction.OPEN_DOCKER_MEMORY_SETTINGS || id == FixAction.PRUNE_DOCKER
                || id == FixAction.RECREATE_CONTAINER;
        if (dockerOnly && docker == null) return null;
        return switch (id) {
            case FIND_PORT_OWNER -> new Fix("Find port owner", () -> {
                PortOwnerDialog.show(owner, expectedPort);
                refreshLogs.run();
            });
            case OPEN_DOCKER_MEMORY_SETTINGS -> new Fix("Open memory help",
                    () -> HelpDialog.show(owner, HelpTopic.OUT_OF_MEMORY));
            case PRUNE_DOCKER -> new Fix("Prune Docker", this::pruneDocker);
            case RECREATE_CONTAINER -> new Fix("Recreate (data loss)", this::recreateContainer);
            case OPEN_BACKUP_GUIDE -> new Fix("Open backup guide", this::openBackupGuide);
            case SHOW_IDLE_SESSIONS -> new Fix("Open session view",
                    () -> HelpDialog.show(owner, HelpTopic.CONNECTION_LIMIT));
            case SEARCH_ERROR -> new Fix("Search this error",
                    () -> searchOnline(matchedLines.isEmpty() ? null : matchedLines.get(matchedLines.size() - 1)));
            default -> null;
        };
    }

    private void pruneDocker() {
        if (!Ui.confirm(owner, "Prune Docker", "Run 'docker system prune -f'?\n\n"
                + "This removes:\n"
                + "  • all stopped containers\n"
                + "  • all dangling images (untagged)\n"
                + "  • all unused networks\n"
                + "  • the build cache\n\n"
                + "It does NOT touch running containers or named volumes.", false)) {
            return;
        }
        Ui.bgQuiet(() -> {
            var r = new DefaultProcessRunner().run("docker", List.of("system", "prune", "-f"), Duration.ofSeconds(60));
            if (r.exitCode() == 0) Ui.info(owner, "Prune Docker", "Docker prune complete:\n\n" + r.stdOut().trim());
            else Ui.error(owner, "Prune Docker", "Docker prune failed:\n\n" + r.stdErr().trim());
            Ui.later(refreshLogs);
        });
    }

    private void recreateContainer() {
        var name = docker.containerName();
        var ok = new ConfirmTypeDialog(owner, "Recreate " + name,
                "This will STOP and DELETE the container '" + name + "' and its data volume. "
                        + "All data inside will be lost.\n\n"
                        + "After this you must re-run 'docker compose up -d' from your "
                        + "ScheduleMe repo to recreate it.\n\n"
                        + "Type DELETE below to confirm:", "DELETE").showDialog();
        if (!ok) return;
        Ui.bgQuiet(() -> {
            var runner = new DefaultProcessRunner();
            // Stop, then remove (with its anonymous volumes).
            runner.run("docker", List.of("stop", name), Duration.ofSeconds(30));
            var rm = runner.run("docker", List.of("rm", "-v", name), Duration.ofSeconds(30));
            if (rm.exitCode() == 0) {
                Ui.info(owner, "Recreate container", "Container removed (and -v cleaned its anonymous volumes).\n\n"
                        + "Now run 'docker compose up -d' from your ScheduleMe repo to recreate it.");
            } else {
                Ui.error(owner, "Recreate container", "docker rm failed:\n\n" + rm.stdErr().trim());
            }
            Ui.later(refreshLogs);
        });
    }

    private void openBackupGuide() {
        var path = guidePath();
        if (Files.isRegularFile(path) && Ui.open(path)) return;
        Ui.info(owner, "Backup procedure",
                "To back up a database, right-click it in Object Explorer → \"Backup database…\".\n\n"
                        + "That runs pg_dump on this machine against the server over TCP, so it works "
                        + "for native installs and containers alike. From a terminal the equivalent is:\n\n"
                        + "  pg_dump -h localhost -p <port> -U <user> -d <db> --format=custom --file=backup.dump");
    }

    private static void searchOnline(String line) {
        if (line == null || line.isBlank()) return;
        // Strip leading timestamps & PIDs for a cleaner query.
        var cleaned = line.replaceFirst("^\\S+\\s+\\S+\\s+", "");
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        Ui.browse("https://www.google.com/search?q=" + URLEncoder.encode(cleaned, StandardCharsets.UTF_8));
    }
}
