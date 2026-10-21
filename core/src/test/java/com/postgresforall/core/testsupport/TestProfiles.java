package com.postgresforall.core.testsupport;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.model.PgSslMode;

/**
 * Connection settings for live-PG tests, from the same postgresconnect.config.json
 * the app ships with (POSTGRESCONNECT_CONFIG is set by the Gradle test task), so
 * tests and app always target the same server.
 */
public final class TestProfiles {
    private TestProfiles() { }

    public static ConnectionProfile owner() {
        var p = ConnectionProfile.fromConfig();
        p.sslMode = PgSslMode.PREFER;
        return p;
    }

    /** A short unique suffix for throwaway object names. */
    public static String tag() {
        var t = Long.toString(System.nanoTime());
        return t.substring(t.length() - 8);
    }
}
