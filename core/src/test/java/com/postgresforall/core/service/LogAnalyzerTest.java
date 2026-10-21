package com.postgresforall.core.service;

import com.postgresforall.core.model.LogIssue;
import com.postgresforall.core.model.LogIssue.FixAction;
import com.postgresforall.core.model.LogIssue.Severity;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LogAnalyzerTest {

    private static LogIssue single(List<LogIssue> issues, FixAction id) {
        var matches = issues.stream().filter(i -> i.fixAction() == id).toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    @Test
    void emptyLogs_noIssues() {
        assertThat(LogAnalyzer.analyze("")).isEmpty();
        assertThat(LogAnalyzer.analyze("   \n  \n")).isEmpty();
    }

    @Test
    void portConflict_detected_fromBindError() {
        var log = """
                2026-05-29 08:21:01 LOG: starting PostgreSQL 16.14
                2026-05-29 08:21:02 LOG: listening on IPv6 address "::"
                2026-05-29 08:21:02 FATAL: could not bind IPv4 address "0.0.0.0": Address already in use
                2026-05-29 08:21:02 HINT: Is another postmaster already running?
                """;
        assertThat(single(LogAnalyzer.analyze(log), FixAction.FIND_PORT_OWNER).severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void oom_detected_fromKilledMarker() {
        var log = """
                2026-05-29 08:21:01 LOG: server process (PID 1234) was terminated by signal 9: Killed
                Killed process 1234 (postgres)
                """;
        assertThat(LogAnalyzer.analyze(log)).anyMatch(i -> i.fixAction() == FixAction.OPEN_DOCKER_MEMORY_SETTINGS);
    }

    @Test
    void diskFull_detected_fromNoSpaceLeft() {
        assertThat(LogAnalyzer.analyze("ERROR: could not extend file \"base/16384/2619\": No space left on device"))
                .anyMatch(i -> i.fixAction() == FixAction.PRUNE_DOCKER);
    }

    @Test
    void versionMismatch_detected() {
        assertThat(LogAnalyzer.analyze("FATAL: database files are incompatible with server"))
                .anyMatch(i -> i.fixAction() == FixAction.RECREATE_CONTAINER);
    }

    @Test
    void corruption_detected_fromPanic() {
        var issues = LogAnalyzer.analyze("PANIC: invalid record length at 0/14E1E48: expected at least 24, got 0");
        assertThat(single(issues, FixAction.OPEN_BACKUP_GUIDE).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void connectionLimit_detected() {
        assertThat(LogAnalyzer.analyze("FATAL: sorry, too many clients already"))
                .anyMatch(i -> i.fixAction() == FixAction.SHOW_IDLE_SESSIONS);
    }

    @Test
    void healthyBoot_detectedAlone() {
        var log = """
                2026-05-29 08:21:01 LOG: starting PostgreSQL 16.14
                2026-05-29 08:21:02 LOG: listening on IPv6 address "::"
                2026-05-29 08:21:03 LOG: database system is ready to accept connections
                """;
        var issues = LogAnalyzer.analyze(log);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).fixAction()).isEqualTo(FixAction.HEALTHY_BOOT);
        assertThat(issues.get(0).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void unknownFatal_fallsThroughToCatchAll() {
        var issues = LogAnalyzer.analyze("2026-05-29 08:21:02 FATAL: undocumented condition with no playbook");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).fixAction()).isEqualTo(FixAction.SEARCH_ERROR);
        assertThat(issues.get(0).severity()).isEqualTo(Severity.UNKNOWN);
    }

    @Test
    void specificPattern_suppressesCatchAll_forSameLine() {
        var issues = LogAnalyzer.analyze("FATAL: could not bind IPv4 address: Address already in use");
        assertThat(issues).anyMatch(i -> i.fixAction() == FixAction.FIND_PORT_OWNER);
        assertThat(issues).noneMatch(i -> i.fixAction() == FixAction.SEARCH_ERROR);
    }

    @Test
    void multipleMatches_dedupeWithCount() {
        var log = String.join("\n", Collections.nCopies(5, "FATAL: could not bind IPv4 address: Address already in use"));
        var port = single(LogAnalyzer.analyze(log), FixAction.FIND_PORT_OWNER);
        assertThat(port.matchCount()).isEqualTo(5);
        assertThat(port.matchedLines()).hasSize(5);
    }

    @Test
    void moreThanFiveMatches_keepsOnlyLastFive() {
        var log = IntStream.range(0, 10).mapToObj(i -> "FATAL: bind: address already in use (" + i + ")")
                .collect(Collectors.joining("\n"));
        var port = single(LogAnalyzer.analyze(log), FixAction.FIND_PORT_OWNER);
        assertThat(port.matchCount()).isEqualTo(10);
        assertThat(port.matchedLines()).hasSize(5);
        assertThat(port.matchedLines().get(4)).contains("(9)");
        assertThat(port.matchedLines().get(0)).contains("(5)");
    }

    @Test
    void issues_sortedBySeverity_criticalFirst() {
        var log = """
                FATAL: sorry, too many clients already
                PANIC: invalid record length at 0/14E1E48
                FATAL: could not bind: Address already in use
                """;
        var issues = LogAnalyzer.analyze(log);
        assertThat(issues.get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(issues.get(0).fixAction()).isEqualTo(FixAction.OPEN_BACKUP_GUIDE);
    }

    @Test
    void healthyBoot_doesNotSuppressLaterErrors() {
        var log = """
                LOG: database system is ready to accept connections
                FATAL: could not bind: address already in use
                """;
        var issues = LogAnalyzer.analyze(log);
        assertThat(issues).anyMatch(i -> i.fixAction() == FixAction.HEALTHY_BOOT);
        assertThat(issues).anyMatch(i -> i.fixAction() == FixAction.FIND_PORT_OWNER);
    }

    @Test
    void authFailures_detected_asWarning() {
        var issues = LogAnalyzer.analyze("FATAL: password authentication failed for user \"scheduleme\"");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(issues.get(0).fixAction()).isEqualTo(FixAction.NONE);
    }

    @Test
    void crlfLineEndings_areTrimmed() {
        var issues = LogAnalyzer.analyze("LOG: x\r\nFATAL: sorry, too many clients already\r\n");
        var limit = single(issues, FixAction.SHOW_IDLE_SESSIONS);
        assertThat(limit.matchedLines().get(0)).doesNotEndWith("\r");
    }
}
