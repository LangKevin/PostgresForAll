package com.postgresforall.core.model;

/**
 * Supplies per-database credential overrides to the connection factory.
 * Returns null when a database has no override and should use the base login.
 */
@FunctionalInterface
public interface DatabaseCredentialProvider {
    DatabaseCredential find(String database);
}
