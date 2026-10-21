package com.postgresforall.core.model;

/** One line of output from a long-running database operation (backup, restore, drop...). */
public record OperationEvent(Severity severity, String text, Integer progressCurrent, Integer progressTotal) {

    public enum Severity { INFO, WARN, ERROR, SUCCESS }

    public OperationEvent(Severity severity, String text) {
        this(severity, text, null, null);
    }
}
