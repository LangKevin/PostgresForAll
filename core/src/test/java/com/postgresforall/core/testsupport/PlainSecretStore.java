package com.postgresforall.core.testsupport;

import com.postgresforall.core.platform.SecretStore;

import java.util.ArrayList;
import java.util.List;

/** Test double: "protects" by prefixing, so file round-trips are deterministic on any OS. */
public final class PlainSecretStore implements SecretStore {
    public final List<String> forgotten = new ArrayList<>();

    @Override
    public String protect(String secret, String id) {
        return "plain:" + secret;
    }

    @Override
    public String unprotect(String stored, String id) {
        return stored != null && stored.startsWith("plain:") ? stored.substring(6) : "";
    }

    @Override
    public void forget(String id) {
        forgotten.add(id);
    }
}
