package com.postgresforall.core.service;

import com.postgresforall.core.model.SchemaNodes.DatabaseInfo;
import com.postgresforall.core.testsupport.PostgresAvailable;
import com.postgresforall.core.testsupport.TestProfiles;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only a superuser sees every database; other roles see just the databases they
 * may CONNECT to. Uses a throwaway role and database, dropped afterwards.
 */
class DatabaseVisibilityTest {

    @PostgresAvailable
    void nonSuperuser_seesOnlyConnectableDatabases_superuserSeesAll() throws Exception {
        var tag = TestProfiles.tag();
        var role = "pfa_limited_" + tag;
        var hidden = "pfa_hidden_" + tag;
        var password = "pw-" + tag;
        var admin = TestProfiles.owner();

        try (var conn = admin.withDatabase("postgres").open(); var st = conn.createStatement()) {
            st.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            st.execute("CREATE DATABASE " + hidden);
            st.execute("REVOKE CONNECT ON DATABASE " + hidden + " FROM PUBLIC");
        }
        try {
            // Superuser: everything, including the locked-down database.
            try (var mgr = ConnectionManager.open(admin)) {
                assertThat(mgr.isSuperuser()).isTrue();
                assertThat(new SchemaInspector(mgr).getDatabases()).extracting(DatabaseInfo::name).contains(hidden);
            }

            // Ordinary role: the database it signed in to, but not the one it can't connect to.
            var limited = admin.copy();
            limited.username = role;
            limited.password = password;
            try (var mgr = ConnectionManager.open(limited)) {
                assertThat(mgr.isSuperuser()).isFalse();
                var names = new SchemaInspector(mgr).getDatabases().stream().map(DatabaseInfo::name).toList();
                assertThat(names).contains(admin.database).doesNotContain(hidden);
            }
        } finally {
            try (var conn = admin.withDatabase("postgres").open(); var st = conn.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + hidden + " WITH (FORCE)");
                st.execute("DROP ROLE IF EXISTS " + role);
            } catch (SQLException ignored) {
                // best effort cleanup
            }
        }
    }
}
