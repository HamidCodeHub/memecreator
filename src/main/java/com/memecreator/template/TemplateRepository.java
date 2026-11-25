package com.memecreator.template;

import com.memecreator.config.MemeProperties;
import com.memecreator.harvest.HarvestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Loads meme templates from the configured folder, plus the harvested ones in meme.harvest.storage-dir/templates
 * (when meme.harvest.use-as-templates is true). The folders are re-scanned when their modification time changes,
 * so templates can be added while the app is running. A harvested template never shadows one of yours: on a name
 * clash, the one in meme.templates-dir wins. Templates listed as another one's duplicate in template-layouts.yml
 * ("duplicates:") are left out, and asking for one by name gives the template kept in its place.
 */
@Component
public class TemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(TemplateRepository.class);
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");

    private final Path dir;
    /** Harvested templates, or null. */
    private final Path harvestedDir;
    private volatile List<MemeTemplate> cache = List.of();
    private volatile long cacheStamp = Long.MIN_VALUE;
    /** Null: no duplicates are hidden. */
    private final LayoutRepository layouts;
    private volatile Visible visible = new Visible(List.of(), Map.of(), List.of());

    /** {@link #findAll()} for one folder scan and one "duplicates:" section. */
    private record Visible(List<MemeTemplate> all, Map<String, String> duplicates, List<MemeTemplate> templates) {
    }

    @Autowired
    public TemplateRepository(MemeProperties props, HarvestProperties harvest, LayoutRepository layouts) {
        this.dir = Path.of(props.templatesDir());
        this.harvestedDir = harvest != null && harvest.useAsTemplates()
                ? Path.of(harvest.storageDir()).resolve("templates") : null;
        this.layouts = layouts;
    }

    public TemplateRepository(MemeProperties props, HarvestProperties harvest) {
        this(props, harvest, null);
    }

    public List<MemeTemplate> findAll() {
        refreshIfChanged();
        if (layouts == null) {
            return cache;
        }
        List<MemeTemplate> all = cache;
        Map<String, String> duplicates = layouts.duplicates();
        Visible v = visible;
        if (v.all() != all || v.duplicates() != duplicates) {
            v = new Visible(all, duplicates, withoutDuplicates(all, duplicates));
            visible = v;
        }
        return v.templates();
    }

    /** Drops hidden duplicates, but only when the template kept in their place is there too. */
    private static List<MemeTemplate> withoutDuplicates(List<MemeTemplate> all, Map<String, String> duplicates) {
        if (duplicates.isEmpty()) {
            return all;
        }
        Set<String> names = new HashSet<>();
        all.forEach(t -> names.add(t.normalizedName()));
        List<MemeTemplate> out = new ArrayList<>(all.size());
        int hidden = 0;
        for (MemeTemplate t : all) {
            String kept = duplicates.get(t.normalizedName());
            if (kept != null && names.contains(kept)) {
                hidden++;
            } else {
                out.add(t);
            }
        }
        Set<String> unknown = new HashSet<>();
        duplicates.forEach((name, kept) -> {
            if (!names.contains(name)) {
                unknown.add(name);
            }
            if (!names.contains(kept)) {
                unknown.add(kept);
            }
        });
        if (!unknown.isEmpty()) {
            log.warn("template-layouts.yml 'duplicates:' names templates that aren't in the template folders "
                    + "(normalized names): {}", unknown);
        }
        log.info("Hiding {} duplicate templates listed in template-layouts.yml", hidden);
        return List.copyOf(out);
    }

    /** Exact (normalized) name match first, then a "contains" match either way. */
    public Optional<MemeTemplate> findByName(String name) {
        String asked = MemeTemplate.normalize(name);
        if (asked.isEmpty()) {
            return Optional.empty();
        }
        // a hidden duplicate's name gives the template kept in its place
        String wanted = layouts == null ? asked : layouts.duplicates().getOrDefault(asked, asked);
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
            long stamp = stamp();
            if (stamp == cacheStamp && !cache.isEmpty()) {
                return;
            }
            synchronized (this) {
                if (stamp == cacheStamp && !cache.isEmpty()) {
                    return;
                }
                List<MemeTemplate> own = list(dir);
                List<MemeTemplate> all = new ArrayList<>(own);
                int harvested = 0;
                if (harvestedDir != null && Files.isDirectory(harvestedDir)) {
                    Set<String> names = new HashSet<>();
                    own.forEach(t -> names.add(t.normalizedName()));
                    for (MemeTemplate t : list(harvestedDir)) {
                        if (names.add(t.normalizedName())) {
                            all.add(t);
                            harvested++;
                        }
                    }
                }
                all.sort(Comparator.comparing(MemeTemplate::name, String.CASE_INSENSITIVE_ORDER));
                cache = List.copyOf(all);
                cacheStamp = stamp;
                if (harvested > 0) {
                    log.info("Loaded {} meme templates from {} and {} harvested ones from {}", own.size(),
                            dir.toAbsolutePath(), harvested, harvestedDir.toAbsolutePath());
                } else {
                    log.info("Loaded {} meme templates from {}", cache.size(), dir.toAbsolutePath());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read template folder " + dir, e);
        }
    }

    /** Changes when either folder's modification time changes (adding/removing a file updates it). */
    private long stamp() throws IOException {
        long stamp = Files.getLastModifiedTime(dir).toMillis();
        if (harvestedDir != null && Files.isDirectory(harvestedDir)) {
            stamp = stamp * 31 + Files.getLastModifiedTime(harvestedDir).toMillis();
        }
        return stamp;
    }

    private static List<MemeTemplate> list(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(TemplateRepository::isImage)
                    .map(MemeTemplate::fromPath)
                    .toList();
        }
    }

    private static boolean isImage(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
