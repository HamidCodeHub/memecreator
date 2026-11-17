package com.memecreator.template;

import com.memecreator.config.MemeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Loads meme templates from the configured folder. The folder is re-scanned when its
 * modification time changes, so templates can be added while the app is running.
 */
@Component
public class TemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(TemplateRepository.class);
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");

    private final Path dir;
    private volatile List<MemeTemplate> cache = List.of();
    private volatile long cacheStamp = Long.MIN_VALUE;

    public TemplateRepository(MemeProperties props) {
        this.dir = Path.of(props.templatesDir());
    }

    public List<MemeTemplate> findAll() {
        refreshIfChanged();
        return cache;
    }

    /** Exact (normalized) name match first, then a "contains" match either way. */
    public Optional<MemeTemplate> findByName(String name) {
        String wanted = MemeTemplate.normalize(name);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<MemeTemplate> all = findAll();
        return all.stream().filter(t -> t.normalizedName().equals(wanted)).findFirst()
                .or(() -> all.stream().filter(t -> t.id().equals(name.trim())).findFirst())
                .or(() -> all.stream()
                        .filter(t -> t.normalizedName().contains(wanted) || wanted.contains(t.normalizedName()))
                        .min(Comparator.comparingInt(t -> Math.abs(t.normalizedName().length() - wanted.length()))));
    }

    /** Random templates, skipping the excluded ones. */
    public List<MemeTemplate> random(int count, Set<MemeTemplate> exclude) {
        List<MemeTemplate> pool = new ArrayList<>(findAll());
        pool.removeAll(exclude);
        Collections.shuffle(pool, ThreadLocalRandom.current());
        return pool.subList(0, Math.min(count, pool.size()));
    }

    private void refreshIfChanged() {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Template folder not found: " + dir.toAbsolutePath());
        }
        try {
            long stamp = Files.getLastModifiedTime(dir).toMillis();
            if (stamp == cacheStamp && !cache.isEmpty()) {
                return;
            }
            synchronized (this) {
                if (stamp == cacheStamp && !cache.isEmpty()) {
                    return;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    cache = files.filter(Files::isRegularFile)
                            .filter(TemplateRepository::isImage)
                            .map(MemeTemplate::fromPath)
                            .sorted(Comparator.comparing(MemeTemplate::name, String.CASE_INSENSITIVE_ORDER))
                            .toList();
                }
                cacheStamp = stamp;
                log.info("Loaded {} meme templates from {}", cache.size(), dir.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read template folder " + dir, e);
        }
    }

    private static boolean isImage(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
