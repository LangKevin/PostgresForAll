package com.postgresforall.core.service;

import com.postgresforall.core.model.OperationEvent;
import com.postgresforall.core.model.OperationEvent.Severity;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import com.postgresforall.core.util.CancelToken;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Backup → restore through the host's pg_dump / pg_restore against the
 * configured live server — no Docker involved. Skipped when the server is
 * unreachable or no usable client tools are installed.
 */
class BackupRestoreRoundTripTest {
    @TempDir
    Path dir;

    private static String text(List<OperationEvent> events) {
        return events.stream().map(OperationEvent::text).collect(Collectors.joining("\n"));
    }

    @PostgresAvailable
    void backupThenRestoreAsNew_copiesRows() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var ops = new DatabaseOperations(mgr);
            assumeTrue(ops.getClientTools(CancelToken.NONE) != null, "no pg_dump / pg_restore on this machine");
            assumeTrue(ops.clientToolsProblem() == null, ops.clientToolsProblem());

            var tag = TestProfiles.tag();
            var source = "rt_src_" + tag;
            var target = "rt_dst_" + tag;
            var file = dir.resolve(source + ".dump").toString();
            var events = new CopyOnWriteArrayList<OperationEvent>();

            try {
                assertThat(ops.createDatabase(source, null, events::add, CancelToken.NONE)).as(text(events)).isTrue();
                try (var conn = TestProfiles.owner().withDatabase(source).open(); var st = conn.createStatement()) {
                    st.execute("CREATE TABLE t(id int); INSERT INTO t SELECT generate_series(1, 42);");
                }

                assertThat(ops.backup(source, file, events::add, CancelToken.NONE)).as(text(events)).isTrue();
                assertThat(Files.size(Path.of(file))).isPositive();
                assertThat(events).anyMatch(e -> e.progressCurrent() != null);

                var fp = ops.getDatabaseFingerprint(source);
                assertThat(fp.exists()).isTrue();
                assertThat(fp.tables()).isEqualTo(1);

                assertThat(ops.createDatabase(target, null, events::add, CancelToken.NONE)).as(text(events)).isTrue();
                assertThat(ops.getDatabaseFingerprint(target).tables()).isZero();
                assertThat(ops.restore(target, file, events::add, CancelToken.NONE)).as(text(events)).isTrue();

                try (var conn = TestProfiles.owner().withDatabase(target).open(); var st = conn.createStatement();
                     var rs = st.executeQuery("SELECT count(*) FROM t")) {
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(42L);
                }
                assertThat(events).noneMatch(e -> e.text().toLowerCase(Locale.ROOT).contains("docker"));
                assertThat(events).noneMatch(e -> e.text().contains(TestProfiles.owner().password)
                        && !TestProfiles.owner().password.isEmpty());
            } finally {
                ops.dropDatabase(source, e -> { }, CancelToken.NONE);
                ops.dropDatabase(target, e -> { }, CancelToken.NONE);
            }
            assertThat(ops.getDatabaseFingerprint(source).exists()).isFalse();
        }
    }

    @PostgresAvailable
    void restore_missingFile_failsWithoutRunningTools() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var ops = new DatabaseOperations(mgr, null, null, ct -> {
                throw new AssertionError("tools must not be located for a missing file");
            });
            var events = new CopyOnWriteArrayList<OperationEvent>();
            assertThat(ops.restore("whatever", dir.resolve("nope.dump").toString(), events::add, CancelToken.NONE))
                    .isFalse();
            assertThat(events).anyMatch(e -> e.severity() == Severity.ERROR && e.text().startsWith("Backup file not found"));
        }
    }

    @PostgresAvailable
    void backup_withoutClientTools_explainsHowToInstall() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var ops = new DatabaseOperations(mgr, null, null, ct -> null);
            var events = new CopyOnWriteArrayList<OperationEvent>();
            assertThat(ops.backup("scheduleme", dir.resolve("x.dump").toString(), events::add, CancelToken.NONE))
                    .isFalse();
            assertThat(events).anyMatch(e -> e.severity() == Severity.ERROR && e.text().contains("PgBinPath"));
            assertThat(ops.clientToolsProblem()).contains("weren't found");
        }
    }

    @PostgresAvailable
    void backup_withToolsOlderThanServer_refuses() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            var ops = new DatabaseOperations(mgr, null, null, ct -> new PgClientTools("/old", 9));
            var events = new CopyOnWriteArrayList<OperationEvent>();
            assertThat(ops.backup("scheduleme", dir.resolve("x.dump").toString(), events::add, CancelToken.NONE))
                    .isFalse();
            assertThat(events).anyMatch(e -> e.severity() == Severity.ERROR && e.text().contains("version 9"));
            assertThat(ops.clientToolsProblem()).contains("older than");
        }
    }
}
