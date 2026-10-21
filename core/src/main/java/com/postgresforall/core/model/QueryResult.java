package com.postgresforall.core.model;

import java.time.LocalDateTime;
import java.util.List;

/** Everything one script execution produced. */
public record QueryResult(List<ResultTable> resultSets, List<Message> messages, long elapsedMs,
                          int statementsExecuted) {

    public boolean hasErrors() {
        return messages.stream().anyMatch(m -> m.severity() == MessageSeverity.ERROR);
    }

    public enum MessageSeverity { INFO, NOTICE, WARNING, ERROR }

    public record Message(MessageSeverity severity, String text, Integer statementIndex, LocalDateTime timestamp) {
        public Message(MessageSeverity severity, String text) {
            this(severity, text, null, LocalDateTime.now());
        }

        public Message(MessageSeverity severity, String text, Integer statementIndex) {
            this(severity, text, statementIndex, LocalDateTime.now());
        }
    }

    public record Column(String name, String dataType) { }

    /** One result set: columns, rows (cell values as returned by JDBC, normalised), source statement. */
    public record ResultTable(List<Column> columns, List<Object[]> rows, Integer rowsAffected,
                              String sourceStatement, int statementIndex) { }
}
