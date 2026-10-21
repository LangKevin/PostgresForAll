package com.postgresforall.core.service;

import com.postgresforall.core.model.LogIssue;
import com.postgresforall.core.model.LogIssue.FixAction;
import com.postgresforall.core.model.LogIssue.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Groups recognisable problems in Postgres log output, each mapped to a fix action. */
public final class LogAnalyzer {
    private LogAnalyzer() { }

    /** @param claims specific patterns claim a line so the catch-all skips it */
    private record Rule(FixAction id, Severity severity, String title, String description, Pattern regex,
                        boolean claims) { }

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static final List<Rule> RULES = List.of(
            new Rule(FixAction.FIND_PORT_OWNER, Severity.ERROR,
                    "Port already in use",
                    "Postgres couldn't bind its listen socket because another process "
                            + "already owns the port. The Fix button will identify the owning process.",
                    ci("(address already in use|bind:\\s*address already in use|port is already allocated)"),
                    true),

            new Rule(FixAction.OPEN_DOCKER_MEMORY_SETTINGS, Severity.ERROR,
                    "Container killed for out-of-memory",
                    "Docker killed the container because it exceeded its memory limit. "
                            + "Raise the memory limit in Docker Desktop → Settings → Resources.",
                    ci("(out of memory|OOMKilled|Killed process \\d+ \\(postgres\\))"),
                    true),

            new Rule(FixAction.PRUNE_DOCKER, Severity.ERROR,
                    "Disk full",
                    "Postgres couldn't extend a file because the disk is full. Pruning "
                            + "stopped containers and dangling images typically frees enough.",
                    ci("(No space left on device|could not extend file|disk full)"),
                    true),

            new Rule(FixAction.RECREATE_CONTAINER, Severity.ERROR,
                    "Data files incompatible with Postgres version",
                    "The data volume was initialised by a different Postgres major version. "
                            + "Recreating the container will drop all data — make sure you have a backup.",
                    ci("(database files are incompatible with server|incompatible with this version)"),
                    true),

            new Rule(FixAction.OPEN_BACKUP_GUIDE, Severity.CRITICAL,
                    "Data corruption detected",
                    "Postgres reported corruption in the WAL or a relation. Recovery "
                            + "usually requires restoring from backup.",
                    ci("(PANIC:\\s*invalid record length|PANIC:\\s*WAL contains|corrupt:\\s*invalid|invalid checkpoint record)"),
                    true),

            new Rule(FixAction.SHOW_IDLE_SESSIONS, Severity.WARNING,
                    "Connection limit reached",
                    "Postgres ran out of connection slots. Close idle sessions or "
                            + "increase max_connections.",
                    ci("(too many clients already|connection slots are reserved)"),
                    true),

            new Rule(FixAction.NONE, Severity.WARNING,
                    "Auth failures recorded",
                    "One or more clients tried to authenticate with wrong credentials. "
                            + "Usually harmless; check if a connected app has stale credentials.",
                    ci("password authentication failed for user"),
                    true),

            new Rule(FixAction.HEALTHY_BOOT, Severity.INFO,
                    "Postgres last booted cleanly",
                    "The most recent startup reached 'ready to accept connections' without errors.",
                    ci("database system is ready to accept connections"),
                    false), // doesn't suppress anything

            // Catch-all for unrecognised FATAL/PANIC lines. Runs last; only fires for
            // lines no specific pattern matched.
            new Rule(FixAction.SEARCH_ERROR, Severity.UNKNOWN,
                    "Unrecognised fatal/panic",
                    "Postgres logged a FATAL or PANIC that doesn't match a known pattern. "
                            + "Click Search to look it up online.",
                    ci("\\b(FATAL|PANIC):\\s*\\S"),
                    false));

    public static List<LogIssue> analyze(String rawLogs) {
        if (rawLogs == null || rawLogs.isBlank()) return List.of();

        var lines = rawLogs.split("\n", -1);
        var kept = new ArrayList<String>();
        for (var l : lines) {
            var t = l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
            if (!t.isBlank()) kept.add(t);
        }

        var claimed = new boolean[kept.size()];
        var grouped = new LinkedHashMap<FixAction, Bucket>();

        for (var rule : RULES) {
            for (var li = 0; li < kept.size(); li++) {
                if (claimed[li]) continue;
                var line = kept.get(li);
                if (!rule.regex().matcher(line).find()) continue;
                if (rule.claims()) claimed[li] = true;
                grouped.computeIfAbsent(rule.id(), k -> new Bucket(rule)).lines.add(line);
            }
        }

        return grouped.values().stream()
                .map(b -> new LogIssue(b.rule.severity(), b.rule.title(), b.rule.description(), b.rule.id(),
                        List.copyOf(b.lines.subList(Math.max(0, b.lines.size() - 5), b.lines.size())),
                        b.lines.size()))
                .sorted(Comparator.comparingInt(i -> rank(i.severity())))
                .toList();
    }

    private static final class Bucket {
        final Rule rule;
        final List<String> lines = new ArrayList<>();

        Bucket(Rule rule) {
            this.rule = rule;
        }
    }

    private static int rank(Severity s) {
        return switch (s) {
            case CRITICAL -> 0;
            case ERROR -> 1;
            case WARNING -> 2;
            case UNKNOWN -> 3;
            case INFO -> 4;
        };
    }
}
