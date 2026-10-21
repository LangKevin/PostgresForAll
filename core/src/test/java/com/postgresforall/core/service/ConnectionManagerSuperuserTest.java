package com.postgresforall.core.service;

import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import org.postgresql.util.PSQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The session-identity probe ConnectionManager runs on open, which the login
 * window uses to warn when the "Superuser" preset didn't get a superuser.
 */
class ConnectionManagerSuperuserTest {

    @PostgresAvailable
    void open_reportsCurrentUserAndVersion() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner())) {
            assertThat(mgr.currentUser()).isEqualTo("scheduleme");
            assertThat(mgr.serverVersion()).matches("\\d+\\.\\d+");
        }
    }

    @PostgresAvailable
    void open_superuserFlag_agreesWithCatalog() throws Exception {
        try (var mgr = ConnectionManager.open(TestProfiles.owner());
             var conn = mgr.openConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
            rs.next();
            assertThat(mgr.isSuperuser()).isEqualTo(rs.getBoolean(1));
        }
    }

    @PostgresAvailable
    void open_throwsOnBadPassword() {
        var bad = TestProfiles.owner();
        bad.password = "definitely-the-wrong-password";
        var ex = catchThrowableOfType(PSQLException.class, () -> ConnectionManager.open(bad).close());
        assertThat((Throwable) ex).isNotNull();
        assertThat(ex.getSQLState()).isEqualTo("28P01"); // invalid_password
        assertThat(ConnectionErrors.message(ex)).containsIgnoringCase("password");
    }

    @PostgresAvailable
    void open_doesNotKeepCallersProfileReference() throws Exception {
        var p = TestProfiles.owner();
        try (var mgr = ConnectionManager.open(p)) {
            p.database = "changed";
            assertThat(mgr.profile().database).isNotEqualTo("changed");
        }
    }
}
