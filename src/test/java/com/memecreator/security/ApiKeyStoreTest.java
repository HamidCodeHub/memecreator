package com.memecreator.security;

import com.memecreator.security.ApiKeyStore.IssuedKey;
import com.memecreator.security.ApiKeyStore.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyStoreTest {

    @TempDir
    Path tmp;

    @Test
    void issuedKeyAuthenticatesAndOnlyItsHashIsStored() throws IOException {
        ApiKeyStore store = new ApiKeyStore(tmp.resolve("private/api-keys.json"));
        IssuedKey key = store.issue("footballmeme", EnumSet.of(Role.SERVICE));

        assertTrue(key.key().startsWith(ApiKeyStore.PREFIX));
        assertEquals("footballmeme", store.authenticate(key.key()).orElseThrow().client());
        assertEquals(Set.of(Role.SERVICE), store.authenticate(key.key()).orElseThrow().roles());
        assertFalse(Files.readString(store.file()).contains(key.key().substring(ApiKeyStore.PREFIX.length())));
    }

    @Test
    void unknownOrMalformedKeysAreRejected() {
        ApiKeyStore store = new ApiKeyStore(tmp.resolve("api-keys.json"));
        IssuedKey key = store.issue("footballmeme", EnumSet.of(Role.SERVICE));
        assertTrue(store.authenticate(key.key() + "x").isEmpty());
        assertTrue(store.authenticate("hello").isEmpty());
        assertTrue(store.authenticate(null).isEmpty());
    }

    @Test
    void issuingAgainRotatesTheKey() {
        ApiKeyStore store = new ApiKeyStore(tmp.resolve("api-keys.json"));
        IssuedKey first = store.issue("footballmeme", EnumSet.of(Role.SERVICE));
        IssuedKey second = store.issue("footballmeme", EnumSet.of(Role.SERVICE, Role.ADMIN));
        assertTrue(store.authenticate(first.key()).isEmpty());
        assertEquals(Set.of(Role.SERVICE, Role.ADMIN), store.authenticate(second.key()).orElseThrow().roles());
        assertEquals(1, store.list().size());
    }

    @Test
    void revokedKeyStopsWorking() {
        ApiKeyStore store = new ApiKeyStore(tmp.resolve("api-keys.json"));
        IssuedKey key = store.issue("script", EnumSet.of(Role.SERVICE));
        assertTrue(store.revoke("script"));
        assertFalse(store.revoke("script"));
        assertTrue(store.authenticate(key.key()).isEmpty());
    }

    @Test
    void keysIssuedByAnotherProcessAreSeenWithoutRestart() {
        Path file = tmp.resolve("api-keys.json");
        ApiKeyStore app = new ApiKeyStore(file);
        assertTrue(app.list().isEmpty());
        IssuedKey key = new ApiKeyStore(file).issue("footballmeme", EnumSet.of(Role.SERVICE)); // e.g. ApiKeyTool
        assertEquals("footballmeme", app.authenticate(key.key()).orElseThrow().client());
    }

    @Test
    void validatesClientNamesAndRoles() {
        ApiKeyStore store = new ApiKeyStore(tmp.resolve("api-keys.json"));
        assertThrows(IllegalArgumentException.class, () -> store.issue("Foot Ball", EnumSet.of(Role.SERVICE)));
        assertThrows(IllegalArgumentException.class, () -> store.issue("x", EnumSet.of(Role.SERVICE)));
        assertThrows(IllegalArgumentException.class, () -> store.issue("footballmeme", Set.of()));
    }
}
