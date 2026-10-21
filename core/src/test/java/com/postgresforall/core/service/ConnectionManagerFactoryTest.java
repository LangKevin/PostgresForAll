package com.postgresforall.core.service;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.DatabaseCredential;
import com.postgresforall.core.model.PgSslMode;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;
import org.postgresql.util.PSQLException;

import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ConnectionManagerFactoryTest {

    private static ConnectionProfile owner() {
        return TestProfiles.owner();
    }

    private static String currentDatabase(ConnectionManager manager) throws SQLException {
        try (var conn = manager.openConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT current_database()")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @PostgresAvailable
    void acquire_opensManagerForTargetDatabase() throws Exception {
        try (var factory = new ConnectionManagerFactory(owner(), null);
             var lease = factory.acquire("postgres")) {
            assertThat(currentDatabase(lease.manager())).isEqualTo("postgres");
        }
    }

    @PostgresAvailable
    void acquire_reusesSameManager_forSameDbName() throws Exception {
        try (var factory = new ConnectionManagerFactory(owner(), null)) {
            var lease1 = factory.acquire("postgres");
            var lease2 = factory.acquire("POSTGRES"); // names are case-insensitive keys
            assertThat(lease2.manager()).isSameAs(lease1.manager());
            assertThat(factory.openPoolCount()).isEqualTo(1);
            lease1.close();
            assertThat(factory.openPoolCount()).isEqualTo(1);
            lease2.close();
            assertThat(factory.openPoolCount()).isZero();
        }
    }

    @PostgresAvailable
    void acquire_differentDbNames_yieldDifferentManagers() throws Exception {
        try (var factory = new ConnectionManagerFactory(owner(), null);
             var lease1 = factory.acquire("postgres");
             var lease2 = factory.acquire("scheduleme")) {
            assertThat(lease2.manager()).isNotSameAs(lease1.manager());
            assertThat(lease1.manager().profile().database).isEqualTo("postgres");
            assertThat(lease2.manager().profile().database).isEqualTo("scheduleme");
        }
    }

    @PostgresAvailable
    void close_onLastRelease_closesManager() throws Exception {
        var factory = new ConnectionManagerFactory(owner(), null);
        var lease = factory.acquire("postgres");
        var mgr = lease.manager();

        lease.close();
        lease.close(); // idempotent
        // After the last lease drops, the manager's pool is closed.
        assertThatThrownBy(() -> {
            try (var ignored = mgr.openConnection()) {
                // unreachable
            }
        }).isInstanceOf(SQLException.class);
        factory.close();
    }

    @PostgresAvailable
    void acquire_throwsForMissingDatabase() {
        try (var factory = new ConnectionManagerFactory(owner(), null)) {
            var ex = catchThrowableOfType(PSQLException.class,
                    () -> factory.acquire("definitely_not_a_db_" + UUID.randomUUID().toString().substring(0, 8)));
            assertThat((Throwable) ex).isNotNull();
            assertThat(ex.getSQLState()).isEqualTo("3D000");
            assertThat(ConnectionErrors.classify(ex)).isEqualTo(ConnectionErrors.Kind.DATABASE_MISSING);
            assertThat(factory.openPoolCount()).isZero();
        }
    }

    @PostgresAvailable
    void acquire_appliesCredentialOverride() {
        // Base credentials are valid, but the override for 'postgres' uses a wrong
        // password. If the override is applied, the connect must fail with an auth error.
        Map<String, DatabaseCredential> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        var c = new DatabaseCredential();
        c.database = "postgres";
        c.username = "scheduleme";
        c.password = "definitely-the-wrong-password";
        c.sslMode = PgSslMode.PREFER;
        map.put(c.database, c);

        try (var factory = new ConnectionManagerFactory(owner(), map::get)) {
            var ex = catchThrowableOfType(PSQLException.class, () -> factory.acquire("postgres"));
            assertThat((Throwable) ex).isNotNull();
            assertThat(ex.getSQLState()).isEqualTo("28P01"); // invalid_password
            assertThat(ConnectionErrors.classify(ex)).isEqualTo(ConnectionErrors.Kind.AUTH_FAILED);
        }
    }

    @PostgresAvailable
    void acquire_withProvider_fallsBackToBase_whenNoOverride() throws Exception {
        try (var factory = new ConnectionManagerFactory(owner(), db -> null);
             var lease = factory.acquire("scheduleme")) {
            assertThat(lease.manager().profile().database).isEqualTo("scheduleme");
            assertThat(lease.manager().profile().username).isEqualTo("scheduleme");
        }
    }

    /** Browsing database B while connected to A must read B's catalogs, not A's. */
    @PostgresAvailable
    void acquire_eachLease_readsItsOwnCatalog() throws Exception {
        try (var factory = new ConnectionManagerFactory(owner(), null);
             var a = factory.acquire("scheduleme");
             var b = factory.acquire("postgres")) {
            assertThat(currentDatabase(a.manager())).isEqualTo("scheduleme");
            assertThat(currentDatabase(b.manager())).isEqualTo("postgres");
        }
    }

    /** Releasing one database lease must not tear down the others. */
    @PostgresAvailable
    void releasingOneLease_leavesOtherDatabasesUsable() throws Exception {
        try (var factory = new ConnectionManagerFactory(owner(), null);
             var keep = factory.acquire("scheduleme")) {
            var drop = factory.acquire("postgres");
            drop.close();
            assertThat(currentDatabase(keep.manager())).isEqualTo("scheduleme");
        }
    }

    @PostgresAvailable
    void closedFactory_refusesNewLeases_andToleratesLateRelease() throws Exception {
        var factory = new ConnectionManagerFactory(owner(), null);
        var lease = factory.acquire("postgres");
        factory.close();
        lease.close(); // a lease outliving the factory has nothing to release
        assertThatThrownBy(() -> factory.acquire("postgres")).isInstanceOf(IllegalStateException.class);
    }
}
