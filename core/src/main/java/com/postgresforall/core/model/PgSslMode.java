package com.postgresforall.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * SSL mode. Stored in JSON as its ordinal (0..4), the same numbers the
 * original app wrote, so saved profiles stay readable by both.
 */
public enum PgSslMode {
    DISABLE("disable", "Disable"),
    PREFER("prefer", "Prefer"),
    REQUIRE("require", "Require"),
    VERIFY_CA("verify-ca", "VerifyCA"),
    VERIFY_FULL("verify-full", "VerifyFull");

    private final String libpqName;
    private final String label;

    PgSslMode(String libpqName, String label) {
        this.libpqName = libpqName;
        this.label = label;
    }

    /** The value libpq tools (PGSSLMODE) and the JDBC driver (sslmode) understand. */
    public String libpqName() {
        return libpqName;
    }

    @JsonValue
    public int jsonValue() {
        return ordinal();
    }

    @JsonCreator
    public static PgSslMode fromJson(Object value) {
        if (value instanceof Number n) {
            var i = n.intValue();
            return i >= 0 && i < values().length ? values()[i] : PREFER;
        }
        if (value instanceof String s) {
            for (var m : values()) {
                if (m.label.equalsIgnoreCase(s) || m.libpqName.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) {
                    return m;
                }
            }
        }
        return PREFER;
    }

    @Override
    public String toString() {
        return label;
    }
}
