package com.memecreator.account;

import com.memecreator.security.UserApiKeyAuthenticator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Personal API keys: created and revoked on the profile page, checked on every API call. */
@Service
@Transactional
public class UserApiKeyService implements UserApiKeyAuthenticator {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** last_used_at is written at most this often per key, not on every call. */
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final UserApiKeyRepository keys;
    private final ApiProperties props;
    private final Clock clock = Clock.systemUTC();

    public UserApiKeyService(UserApiKeyRepository keys, ApiProperties props) {
        this.keys = keys;
        this.props = props;
    }

    /** The new key, in full. It can't be shown again. */
    public record IssuedKey(UserApiKey info, String key) {
    }

    public static final class TooManyKeysException extends RuntimeException {
        TooManyKeysException(int max) {
            super("You can have " + max + " API keys. Revoke one to create a new one.");
        }
    }

    public IssuedKey create(long accountId, String name) {
        if (keys.countByAccountId(accountId) >= props.maxKeysPerAccount()) {
            throw new TooManyKeysException(props.maxKeysPerAccount());
        }
        byte[] secret = new byte[24]; // 192 bits
        RANDOM.nextBytes(secret);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String label = name == null || name.isBlank() ? "API key" : name.strip().replaceAll("\\p{Cntrl}", "");
        if (label.length() > 60) {
            label = label.substring(0, 60);
        }
        UserApiKey saved = keys.save(new UserApiKey(accountId, AccountService.hash(key),
                key.substring(0, PREFIX.length() + 6), label, clock.instant()));
        return new IssuedKey(saved, key);
    }

    @Transactional(readOnly = true)
    public List<UserApiKey> list(long accountId) {
        return keys.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    /** Revokes (deletes) the key; it stops working at once. Unknown ids are ignored. */
    public void revoke(long accountId, long keyId) {
        keys.findByIdAndAccountId(keyId, accountId).ifPresent(keys::delete);
    }

    @Override
    public Optional<ApiUser> authenticate(String key) {
        if (key == null || !key.startsWith(PREFIX) || key.length() > 100) {
            return Optional.empty();
        }
        // Looked up by its SHA-256 hash: an index lookup, nothing to compare byte by byte
        return keys.findByKeyHash(AccountService.hash(key)).map(k -> {
            Instant now = clock.instant();
            if (k.getLastUsedAt() == null || k.getLastUsedAt().isBefore(now.minus(TOUCH_EVERY))) {
                k.usedAt(now);
            }
            return new ApiUser(k.getAccountId(), k.getId());
        });
    }
}
