package com.postgresforall.core.platform;

import com.github.javakeyring.Keyring;

/**
 * macOS login Keychain (Secret Service on Linux) via java-keyring. Each secret
 * is a generic password under service "PostgresForAll", account = the entry id;
 * the JSON file only holds "keychain:&lt;id&gt;".
 */
public final class KeyringSecretStore implements SecretStore {
    public static final String SERVICE = "PostgresForAll";
    private static final String PREFIX = "keychain:";

    private Keyring keyring() throws Exception {
        return Keyring.create();
    }

    @Override
    public String protect(String secret, String id) {
        try (var k = keyring()) {
            k.setPassword(SERVICE, id, secret);
            return PREFIX + id;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }

    @Override
    public String unprotect(String stored, String id) {
        if (stored == null || !stored.startsWith(PREFIX)) return "";
        try (var k = keyring()) {
            var v = k.getPassword(SERVICE, stored.substring(PREFIX.length()));
            return v == null ? "" : v;
        } catch (Exception | LinkageError ex) {
            return "";
        }
    }

    @Override
    public void forget(String id) {
        try (var k = keyring()) {
            k.deletePassword(SERVICE, id);
        } catch (Exception | LinkageError ignored) {
            // nothing stored
        }
    }
}
