package com.postgresforall.core.platform;

/**
 * Where remembered passwords live. The JSON stores write whatever
 * {@link #protect} returns into their "EncryptedPassword" field:
 * <ul>
 *   <li>Windows: the DPAPI-encrypted secret itself (base64, current Windows
 *       user only) — byte-compatible with the original .NET app;</li>
 *   <li>macOS / Linux: a reference ("keychain:&lt;id&gt;"); the secret is in the
 *       login Keychain / Secret Service.</li>
 * </ul>
 */
public interface SecretStore {

    /** What to write in the file, or null when the secret can't be kept. */
    String protect(String secret, String id);

    /** The secret behind a stored value; "" when it can't be recovered. */
    String unprotect(String stored, String id);

    /** Removes any copy kept outside the file (no-op for DPAPI). */
    void forget(String id);

    /** @param entropy DPAPI optional entropy, distinct per store (as in the original app) */
    static SecretStore forPlatform(String entropy) {
        return Platform.isWindows() ? new DpapiSecretStore(entropy) : new KeyringSecretStore();
    }
}
