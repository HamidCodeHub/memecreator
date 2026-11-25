package com.memecreator.security;

import java.util.Optional;

/** Personal API keys of signed-up people ("mcu_..."), as opposed to the service keys in {@link ApiKeyStore}. */
public interface UserApiKeyAuthenticator {

    String PREFIX = "mcu_";

    /** The account and key behind a personal key; empty for unknown or revoked keys. */
    Optional<ApiUser> authenticate(String key);

    /** Who called: the principal of requests made with a personal key (role API_USER). */
    record ApiUser(long accountId, long keyId) {
    }
}
