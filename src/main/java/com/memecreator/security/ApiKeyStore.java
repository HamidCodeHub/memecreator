package com.memecreator.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * API keys for programs that call memecreator (footballmeme, scripts), in a JSON file under the private folder.
 * <p>
 * Only a SHA-256 hash of each key is stored; the key itself is shown once, when it is issued. Keys are 256 random
 * bits, so a fast hash is enough (no password hashing needed). One key per client: issuing again for the same
 * client replaces (rotates) its key. The file is re-read when it changes, so keys issued with {@link ApiKeyTool}
 * work without restarting the app.
 */
@Component
public class ApiKeyStore {

    /** Key prefix: recognizable in config files and by secret scanners. */
    public static final String PREFIX = "mck_";
    private static final Pattern CLIENT = Pattern.compile("[a-z0-9][a-z0-9-]{1,39}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Role {
        /** Programs that make memes: /api/memes, /api/templates. */
        SERVICE,
        /** Everything, including harvest, face/photo endpoints, Swagger and key management. */
        ADMIN
    }

    /** One client's key, as stored. */
    public record ApiClient(String client, String keyHash, Set<Role> roles, Instant createdAt) {
    }

    /** What {@link #issue} returns: the only time the plain key is available. */
    public record IssuedKey(String client, String key, Set<Role> roles, Instant createdAt) {
    }

    /** The file's shape. */
    static final class Data {
        public int version = 1;
        public List<ApiClient> clients = new ArrayList<>();
    }

    private final Path file;
    private final ObjectMapper mapper;
    private Data data = new Data();
    private long loadedStamp = Long.MIN_VALUE;

    @Autowired
    public ApiKeyStore(SecurityProperties props) {
        this(Path.of(props.apiKeysFile()));
    }

    public ApiKeyStore(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public Path file() {
        return file;
    }

    /** The client a key belongs to, or empty for an unknown or malformed key. */
    public synchronized Optional<ApiClient> authenticate(String key) {
        if (key == null || !key.startsWith(PREFIX) || key.length() > 200) {
            return Optional.empty();
        }
        reloadIfChanged();
        byte[] hash = sha256(key);
        for (ApiClient c : data.clients) {
            // constant-time comparison: no timing hints about how much of a hash matched
            if (MessageDigest.isEqual(hash, HexFormat.of().parseHex(c.keyHash()))) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** Creates (or replaces) the key of a client. The returned key is not stored anywhere: hand it over now. */
    public synchronized IssuedKey issue(String client, Set<Role> roles) {
        if (client == null || !CLIENT.matcher(client).matches()) {
            throw new IllegalArgumentException("client must be 2-40 characters: lower-case letters, digits, '-'");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("give the key at least one role: " + List.of(Role.values()));
        }
        reloadIfChanged();
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant now = Instant.now();
        data.clients.removeIf(c -> c.client().equals(client));
        data.clients.add(new ApiClient(client, HexFormat.of().formatHex(sha256(key)), EnumSet.copyOf(roles), now));
        save();
        return new IssuedKey(client, key, EnumSet.copyOf(roles), now);
    }

    /** @return false when the client had no key */
    public synchronized boolean revoke(String client) {
        reloadIfChanged();
        boolean removed = data.clients.removeIf(c -> c.client().equals(client));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized List<ApiClient> list() {
        reloadIfChanged();
        return data.clients.stream().sorted(Comparator.comparing(ApiClient::client)).toList();
    }

    private void reloadIfChanged() {
        try {
            if (!Files.exists(file)) {
                data = new Data();
                loadedStamp = Long.MIN_VALUE;
                return;
            }
            long stamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
            if (stamp != loadedStamp) {
                data = mapper.readValue(file.toFile(), Data.class);
                loadedStamp = stamp;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read API keys from " + file + ": " + e.getMessage(), e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            loadedStamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write API keys to " + file + ": " + e.getMessage(), e);
        }
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
