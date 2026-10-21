package com.postgresforall.core.model;

import java.util.List;

/** A class of problem found in server logs, with the lines that matched. */
public record LogIssue(Severity severity, String title, String description, FixAction fixAction,
                       List<String> matchedLines, int matchCount) {

    public enum Severity { INFO, WARNING, ERROR, CRITICAL, UNKNOWN }

    /** Stable identifier for a class of log issue; the UI maps these to concrete fix actions. */
    public enum FixAction {
        NONE,
        HEALTHY_BOOT,
        FIND_PORT_OWNER,
        OPEN_DOCKER_MEMORY_SETTINGS,
        PRUNE_DOCKER,
        RECREATE_CONTAINER,
        OPEN_BACKUP_GUIDE,
        SHOW_IDLE_SESSIONS,
        SEARCH_ERROR
    }
}
