package com.memecreator.account;

import com.memecreator.config.MemeProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A person's own memes: favorites (the heart) and public share links. Only memes from sets the account made
 * itself can be favorited or shared; the image must still exist.
 */
@Service
@Transactional
public class MemeLibrary {

    /** "/generated/&lt;folder&gt;/&lt;file&gt;" as the app shows it. Folder and file names are ASCII slugs. */
    private static final Pattern URL = Pattern.compile(
            "^/generated/([A-Za-z0-9][A-Za-z0-9._-]{0,299})/([A-Za-z0-9][A-Za-z0-9._-]{0,199}\\.(?:jpe?g|png|gif|webp))$");
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_FAVORITES = 500;

    private final FavoriteRepository favorites;
    private final SharedMemeRepository shares;
    private final MemeSetRepository memeSets;
    private final Path outputDir;
    private final Clock clock = Clock.systemUTC();

    public MemeLibrary(FavoriteRepository favorites, SharedMemeRepository shares, MemeSetRepository memeSets,
                       MemeProperties memeProps) {
        this.favorites = favorites;
        this.shares = shares;
        this.memeSets = memeSets;
        this.outputDir = Path.of(memeProps.outputDir()).toAbsolutePath().normalize();
    }

    /** A meme image inside meme.output-dir. */
    public record MemeRef(String folder, String file) {
    }

    /** Not one of this account's memes, or the image is gone. Shown as 404: no hint whose it is. */
    public static final class MemeNotFoundException extends RuntimeException {
        public MemeNotFoundException() {
            super("That meme can't be found. It may have been deleted.");
        }
    }

    public static final class TooManyFavoritesException extends RuntimeException {
        public TooManyFavoritesException() {
            super("You have " + MAX_FAVORITES + " favorites. Remove a few to add new ones.");
        }
    }

    // ---- favorites --------------------------------------------------------------------------------------------

    /** Adds the meme to the account's favorites; adding it twice returns the same favorite. */
    public Favorite addFavorite(long accountId, String url, String template, List<String> texts) {
        MemeRef ref = ownMeme(accountId, url);
        Optional<Favorite> existing = favorites.findByAccountIdAndFolderAndFile(accountId, ref.folder(), ref.file());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (favorites.countByAccountId(accountId) >= MAX_FAVORITES) {
            throw new TooManyFavoritesException();
        }
        return favorites.save(new Favorite(accountId, ref, clean(template, 200, "Meme"), caption(texts),
                clock.instant()));
    }

    /** Removes a favorite (the image itself stays). Unknown ids are ignored. */
    public void removeFavorite(long accountId, long favoriteId) {
        favorites.findByIdAndAccountId(favoriteId, accountId).ifPresent(favorites::delete);
    }

    @Transactional(readOnly = true)
    public List<Favorite> favorites(long accountId) {
        return favorites.findByAccountIdOrderByCreatedAtDescIdDesc(accountId);
    }

    // ---- sharing ----------------------------------------------------------------------------------------------

    /** The public link for one of the account's memes; sharing the same meme again returns the same link. */
    public SharedMeme share(long accountId, String url, String template, List<String> texts) {
        MemeRef ref = ownMeme(accountId, url);
        return shares.findByAccountIdAndFolderAndFile(accountId, ref.folder(), ref.file())
                .orElseGet(() -> shares.save(new SharedMeme(newToken(), accountId, ref, clean(template, 200, "Meme"),
                        caption(texts), clock.instant())));
    }

    /** A shared meme whose image still exists. */
    @Transactional(readOnly = true)
    public Optional<SharedMeme> findShare(String token) {
        if (token == null || token.length() > 32) {
            return Optional.empty();
        }
        return shares.findById(token).filter(s -> imagePath(s.getFolder(), s.getFile()).isPresent());
    }

    /** The image file, if it is (still) inside meme.output-dir. */
    public Optional<Path> imagePath(String folder, String file) {
        Path dir = outputDir.resolve(folder).normalize();
        Path image = dir.resolve(file).normalize();
        if (!dir.getParent().equals(outputDir) || !image.getParent().equals(dir) || !Files.isRegularFile(image)) {
            return Optional.empty();
        }
        return Optional.of(image);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** Parses the app's image URL and checks the set is this account's and the image exists. */
    public MemeRef ownMeme(long accountId, String url) {
        Matcher m = url == null ? null : URL.matcher(url.trim());
        if (m == null || !m.matches()) {
            throw new MemeNotFoundException();
        }
        MemeRef ref = new MemeRef(m.group(1), m.group(2));
        if (ref.folder().contains("..") || !memeSets.existsByAccountIdAndFolder(accountId, ref.folder())
                || imagePath(ref.folder(), ref.file()).isEmpty()) {
            throw new MemeNotFoundException();
        }
        return ref;
    }

    private static String caption(List<String> texts) {
        if (texts == null) {
            return null;
        }
        String joined = String.join("\n", texts.stream().filter(t -> t != null && !t.isBlank()).map(String::strip)
                .toList());
        return joined.isEmpty() ? null : clean(joined, 1000, null);
    }

    private static String clean(String s, int max, String fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String c = s.strip().replaceAll("[\\p{Cntrl}&&[^\\n]]", "");
        return c.length() > max ? c.substring(0, max) : c;
    }

    private static String newToken() {
        byte[] bytes = new byte[15]; // 120 bits → 20 characters
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
