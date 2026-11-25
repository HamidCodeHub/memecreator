package com.memecreator.account;

import com.memecreator.config.MemeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Keeps only the memes people want: every few minutes, in each folder of a meme set made by an account, files older
 * than meme.retention.keep-for are deleted unless they are
 * <ul>
 *   <li>a favorite (the heart) or a shared meme (/s/... links keep working), or the AI's original of one of them
 *       (the editor's "back to the AI's version");</li>
 *   <li>the uploaded photo (and its keep-clear areas) of a set with at least one favorite: the editor re-draws photo
 *       memes from it.</li>
 * </ul>
 * A meme whose heart is removed later goes at the next check. Empty folders are removed; the set's row stays (the
 * daily quotas count it). Folders not made by an account (face photos, admin/dev memes) are never touched.
 */
@Component
public class MemeRetention {

    private static final Logger log = LoggerFactory.getLogger(MemeRetention.class);
    static final Set<String> UPLOAD_FILES = Set.of("00_upload.jpg", "00_keep-clear.json");

    private final MemeSetRepository memeSets;
    private final FavoriteRepository favorites;
    private final SharedMemeRepository shares;
    private final MemeRecordRepository records;
    private final RetentionProperties props;
    private final Path outputDir;
    private final Clock clock;

    @Autowired
    public MemeRetention(MemeSetRepository memeSets, FavoriteRepository favorites, SharedMemeRepository shares,
                         MemeRecordRepository records, RetentionProperties props, MemeProperties memeProps) {
        this(memeSets, favorites, shares, records, props, memeProps, Clock.systemUTC());
    }

    MemeRetention(MemeSetRepository memeSets, FavoriteRepository favorites, SharedMemeRepository shares,
                  MemeRecordRepository records, RetentionProperties props, MemeProperties memeProps, Clock clock) {
        this.memeSets = memeSets;
        this.favorites = favorites;
        this.shares = shares;
        this.records = records;
        this.props = props;
        this.outputDir = Path.of(memeProps.outputDir()).toAbsolutePath().normalize();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${meme.retention.check-every:PT5M}", initialDelayString = "PT1M")
    public void scheduledCheck() {
        if (props.enabled()) {
            deleteExpired();
        }
    }

    /** @return how many files were deleted */
    public int deleteExpired() {
        if (!Files.isDirectory(outputDir)) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(props.keepFor());
        List<Path> dirs;
        try (Stream<Path> s = Files.list(outputDir)) {
            dirs = s.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("Could not list {}: {}", outputDir, e.toString());
            return 0;
        }
        int deleted = 0;
        for (Path dir : dirs) {
            try {
                deleted += clean(dir, cutoff);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not clean {}: {}", dir, e.toString());
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} meme files older than {} that nobody kept", deleted, props.keepFor());
        }
        return deleted;
    }

    private int clean(Path dir, Instant cutoff) throws IOException {
        String folder = dir.getFileName().toString();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        List<Path> expired = files.stream().filter(f -> modified(f).isBefore(cutoff)).toList();
        if (expired.isEmpty() || memeSets.findByFolder(folder).isEmpty()) {
            return 0;   // nothing old yet, or not an account's set
        }

        Set<String> keep = new HashSet<>();
        List<Favorite> favs = favorites.findByFolder(folder);
        favs.forEach(f -> keep.add(f.getFile()));
        shares.findByFolder(folder).forEach(s -> keep.add(s.getFile()));
        List<MemeRecord> recs = records.findByFolder(folder);
        for (MemeRecord r : recs) {
            if (keep.contains(r.getFile())) {
                keep.add(r.getAiFile());
            }
        }
        if (!favs.isEmpty()) {
            keep.addAll(UPLOAD_FILES);
        }

        Set<String> gone = new HashSet<>();
        for (Path f : expired) {
            String name = f.getFileName().toString();
            if (!keep.contains(name)) {
                Files.deleteIfExists(f);
                gone.add(name);
            }
        }
        List<MemeRecord> stale = recs.stream().filter(r -> gone.contains(r.getFile())).toList();
        if (!stale.isEmpty()) {
            records.deleteAll(stale);
        }
        if (gone.size() == files.size()) {
            try (Stream<Path> s = Files.list(dir)) {
                if (s.findAny().isEmpty()) {
                    Files.delete(dir);
                }
            }
        }
        return gone.size();
    }

    private static Instant modified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toInstant();
        } catch (IOException e) {
            return Instant.MAX;   // can't tell: keep it
        }
    }
}
