package com.postgresforall.core.platform;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows DPAPI, current-user scope, with the same optional entropy as the
 * original .NET app's ProtectedData calls — so passwords either app saved can
 * be read by the other.
 */
public final class DpapiSecretStore implements SecretStore {
    private final byte[] entropy;

    public DpapiSecretStore(String entropy) {
        this.entropy = entropy.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String protect(String secret, String id) {
        try {
            var cipher = Crypt32Util.cryptProtectData(secret.getBytes(StandardCharsets.UTF_8), entropy,
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "", null);
            return Base64.getEncoder().encodeToString(cipher);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    @Override
    public String unprotect(String stored, String id) {
        try {
            var plain = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored), entropy,
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    @Override
    public void forget(String id) {
        // the secret lives only in the file
    }
}
