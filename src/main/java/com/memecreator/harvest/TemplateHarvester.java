package com.memecreator.harvest;

import com.memecreator.config.MemeProperties;
import com.memecreator.harvest.CatalogEntry.Alias;
import com.memecreator.harvest.CatalogEntry.Status;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Downloads new templates from the {@link TemplateSource}s into meme.harvest.storage-dir, skipping duplicates.
 * <p>
 * Duplicate check, cheapest first:
 * <ol>
 *   <li>source id already in the catalog (also as an alias) → not downloaded at all;</li>
 *   <li>same bytes (SHA-256) as any known image;</li>
 *   <li>same picture (perceptual dHash + aspect ratio) as any known image, including your own
 *       meme.templates-dir folder and templates you deleted.</li>
 * </ol>
 * A duplicate is recorded as an alias of the template it matched, so it is never downloaded again. When the
 * duplicate is a sharper copy of a harvested image, it replaces that image.
 * <p>
 * Delete a file from storage-dir/templates to drop a template for good: it is marked REMOVED and neither it nor
 * its look-alikes come back.
 */
@Service
public class TemplateHarvester {

    private static final Logger log = LoggerFactory.getLogger(TemplateHarvester.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");
    private static final Map<String, String> FORMAT_EXTENSIONS =
            Map.of("jpeg", "jpg", "jpg", "jpg", "png", "png", "gif", "gif", "bmp", "bmp");

    private final List<TemplateSource> sources;
    private final TemplateCatalog catalog;
    private final HarvestHttp http;
    private final HarvestProperties props;
    private final LayoutRepository layouts;
    private final Path localTemplatesDir;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile List<HarvestReport> lastReports = List.of();

    public TemplateHarvester(List<TemplateSource> sources, TemplateCatalog catalog, HarvestHttp http,
                             HarvestProperties props, LayoutRepository layouts, MemeProperties memeProps) {
        this.sources = sources;
        this.catalog = catalog;
        this.http = http;
        this.props = props;
        this.layouts = layouts;
        this.localTemplatesDir = Path.of(memeProps.templatesDir());
        if (localTemplatesDir.toAbsolutePath().normalize().startsWith(catalog.root().toAbsolutePath().normalize())) {
            throw new IllegalStateException("meme.harvest.storage-dir must not contain meme.templates-dir");
        }
    }

    public static final class BusyException extends RuntimeException {
        BusyException() {
            super("A template harvest is already running");
        }
    }

    public List<String> sourceNames() {
        return sources.stream().map(TemplateSource::name).toList();
    }

    public List<HarvestReport> lastReports() {
        return lastReports;
    }

    public boolean running() {
        return lock.isLocked();
    }

    /**
     * Runs one source by name, or all of them for null/"all".
     *
     * @throws BusyException            when a run is already in progress
     * @throws IllegalArgumentException for an unknown source name
     */
    public List<HarvestReport> run(String sourceName) {
        List<TemplateSource> selected = select(sourceName);
        if (!lock.tryLock()) {
            throw new BusyException();
        }
        try {
            catalog.load();
            syncFiles();
            catalog.save();
            List<HarvestReport> reports = new ArrayList<>();
            for (TemplateSource source : selected) {
                HarvestReport r = harvest(source);
                catalog.save();
                log.info("Harvest {}: {} offered, {} known, {} added, {} need a layout, {} duplicates, {} rejected, "
                                + "{} failed in {}s", r.source(), r.offered(), r.known(), r.added(), r.needsLayout(),
                        r.duplicates(), r.rejected(), r.failed(), r.took().toSeconds());
                reports.add(r);
            }
            lastReports = List.copyOf(reports);
            return reports;
        } finally {
            lock.unlock();
        }
    }

    /** For the scheduler: logs instead of throwing. */
    public void runQuietly(String sourceName) {
        try {
            run(sourceName);
        } catch (BusyException e) {
            log.info("Skipping scheduled harvest of {}: {}", sourceName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Template harvest of {} failed: {}", sourceName, e.getMessage(), e);
        }
    }

    private List<TemplateSource> select(String sourceName) {
        if (sourceName == null || sourceName.isBlank() || sourceName.equalsIgnoreCase("all")) {
            return sources;
        }
        return sources.stream().filter(s -> s.name().equalsIgnoreCase(sourceName.trim())).findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source '" + sourceName + "'; use one of " + sourceNames() + " or all"));
    }

    // ---- one source ------------------------------------------------------------------------------------------

    private HarvestReport harvest(TemplateSource source) {
        Instant start = Instant.now();
        List<String> addedNames = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int known = 0, added = 0, needsLayout = 0, duplicates = 0, rejected = 0, failed = 0;

        List<TemplateCandidate> candidates;
        try {
            candidates = source.fetch();
        } catch (RuntimeException e) {
            errors.add("listing failed: " + e.getMessage());
            return new HarvestReport(source.name(), start, Duration.between(start, Instant.now()), 0, 0, 0, 0, 0, 0,
                    1, addedNames, errors);
        }
        if (source.ranked()) {
            clearRanks(source.name());
            catalog.appendRankSnapshot(source.name(), candidates);
        }

        Set<String> seenThisRun = new HashSet<>();
        boolean firstDownload = true;
        for (TemplateCandidate c : candidates) {
            if (!seenThisRun.add(c.key())) {
                continue;
            }
            Optional<CatalogEntry> existing = catalog.byKey(c.key());
            if (existing.isPresent()) {
                touch(existing.get(), c);
                known++;
                continue;
            }
            try {
                if (!firstDownload) {
                    pause();
                }
                firstDownload = false;
                switch (ingest(c)) {
                    case ADDED -> {
                        added++;
                        addedNames.add(c.name());
                    }
                    case NEEDS_LAYOUT -> {
                        needsLayout++;
                        addedNames.add(c.name() + " (needs layout)");
                    }
                    case DUPLICATE -> duplicates++;
                    case REJECTED -> rejected++;
                }
            } catch (RuntimeException e) {
                failed++;
                errors.add(c.name() + ": " + e.getMessage());
                log.warn("Harvest {}: '{}' failed: {}", source.name(), c.name(), e.getMessage());
            }
        }
        return new HarvestReport(source.name(), start, Duration.between(start, Instant.now()), candidates.size(),
                known, added, needsLayout, duplicates, rejected, failed, addedNames, errors);
    }

    private enum Outcome { ADDED, NEEDS_LAYOUT, DUPLICATE, REJECTED }

    private Outcome ingest(TemplateCandidate c) {
        byte[] bytes = http.getBytes(c.imageUrl());
        String ext = extensionOf(bytes);
        BufferedImage image = ext == null ? null : decode(bytes);
        if (image == null) {
            catalog.add(rejected(c, "not a readable jpg/png/gif/bmp image"));
            return Outcome.REJECTED;
        }
        if (Math.min(image.getWidth(), image.getHeight()) < props.minImageSide()) {
            catalog.add(rejected(c, "too small: " + image.getWidth() + "x" + image.getHeight()));
            return Outcome.REJECTED;
        }
        ImageFingerprint fp = ImageFingerprint.of(bytes, image);

        Optional<CatalogEntry> same = catalog.findSimilar(fp, props.maxHashDistance());
        if (same.isPresent()) {
            CatalogEntry match = same.get();
            int distance = fp.distance(match.fingerprint());
            String note = replaceIfSharper(match, c, bytes, ext, fp) ? "sharper copy: replaced the image" : null;
            match.aliases.add(new Alias(c.key(), c.name(), c.imageUrl(), distance, Instant.now(), note));
            log.info("Harvest {}: '{}' is the same template as '{}' ({}:{}, distance {}){}", c.source(), c.name(),
                    match.name, match.source, match.sourceId, distance, note == null ? "" : " — " + note);
            return Outcome.DUPLICATE;
        }

        CatalogEntry e = new CatalogEntry();
        e.id = catalog.assignId(c);
        e.name = c.name();
        e.source = c.source();
        e.sourceId = c.sourceId();
        e.boxCount = c.boxCount();
        e.imageUrl = c.imageUrl();
        e.pageUrl = c.pageUrl();
        e.license = "unknown";
        e.firstSeen = e.lastSeen = Instant.now();
        e.lastRank = c.rank() > 0 ? c.rank() : null;
        e.popularity = c.popularity() > 0 ? c.popularity() : null;
        e.applyFingerprint(fp);
        e.file = fileName(e.id, e.name, ext);
        e.status = hasLayoutFor(e) ? Status.ACTIVE : Status.NEEDS_LAYOUT;
        write(catalog.folderOf(e.status).resolve(e.file), bytes);
        catalog.add(e);
        return e.status == Status.ACTIVE ? Outcome.ADDED : Outcome.NEEDS_LAYOUT;
    }

    /** A harvested image is swapped for a near-identical one with clearly more pixels. Your own files never are. */
    private boolean replaceIfSharper(CatalogEntry match, TemplateCandidate c, byte[] bytes, String ext,
                                     ImageFingerprint fp) {
        if (match.status != Status.ACTIVE && match.status != Status.NEEDS_LAYOUT) {
            return false;
        }
        if (fp.pixels() < match.fingerprint().pixels() * props.replaceWhenLargerBy()) {
            return false;
        }
        Path folder = catalog.folderOf(match.status);
        String newFile = fileName(match.id, match.name, ext);
        write(folder.resolve(newFile), bytes);
        if (!newFile.equals(match.file)) {
            deleteQuietly(folder.resolve(match.file));
        }
        match.file = newFile;
        match.imageUrl = c.imageUrl();
        match.applyFingerprint(fp);
        return true;
    }

    private void touch(CatalogEntry e, TemplateCandidate c) {
        Instant now = Instant.now();
        if (c.key().equals(e.key())) {
            e.lastSeen = now;
            e.lastRank = c.rank() > 0 ? c.rank() : null;
            if (c.popularity() > 0) {
                e.popularity = c.popularity();
            }
        } else {
            e.aliases.replaceAll(a -> c.key().equals(a.key())
                    ? new Alias(a.key(), a.name(), a.imageUrl(), a.distance(), now, a.note()) : a);
        }
    }

    /** Today's list replaces yesterday's ranks: templates that dropped out of the top list lose theirs. */
    private void clearRanks(String source) {
        catalog.entries().stream().filter(e -> source.equals(e.source)).forEach(e -> e.lastRank = null);
    }

    private CatalogEntry rejected(TemplateCandidate c, String reason) {
        CatalogEntry e = new CatalogEntry();
        e.id = null;
        e.name = c.name();
        e.source = c.source();
        e.sourceId = c.sourceId();
        e.status = Status.REJECTED;
        e.note = reason;
        e.imageUrl = c.imageUrl();
        e.firstSeen = e.lastSeen = Instant.now();
        log.info("Harvest {}: '{}' rejected: {}", c.source(), c.name(), reason);
        return e;
    }

    // ---- keeping the catalog in step with the folders ---------------------------------------------------------

    /**
     * Before each run: index your own template folder, notice harvested files you deleted or moved, and promote
     * needs-layout templates whose layout now exists in template-layouts.yml.
     */
    void syncFiles() {
        indexLocalFolder();
        for (CatalogEntry e : catalog.entries()) {
            if (e.file == null || e.status == Status.LOCAL || e.status == Status.REJECTED) {
                continue;
            }
            boolean inTemplates = Files.isRegularFile(catalog.templatesDir().resolve(e.file));
            boolean inNeedsLayout = Files.isRegularFile(catalog.needsLayoutDir().resolve(e.file));
            Status before = e.status;
            if (inTemplates) {
                e.status = Status.ACTIVE;
            } else if (inNeedsLayout) {
                e.status = Status.NEEDS_LAYOUT;
                if (hasLayoutFor(e)) {
                    move(catalog.needsLayoutDir().resolve(e.file), catalog.templatesDir().resolve(e.file));
                    e.status = Status.ACTIVE;
                }
            } else {
                e.status = Status.REMOVED;
            }
            if (e.status != before) {
                log.info("Template '{}': {} -> {}", e.name, before, e.status);
            }
        }
    }

    private void indexLocalFolder() {
        if (!Files.isDirectory(localTemplatesDir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(localTemplatesDir)) {
            files = s.filter(Files::isRegularFile).filter(TemplateHarvester::isImageFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + localTemplatesDir, e);
        }
        Set<String> present = new HashSet<>();
        int hashed = 0;
        for (Path p : files) {
            String abs = p.toAbsolutePath().normalize().toString();
            present.add(abs);
            try {
                long size = Files.size(p);
                long modified = Files.getLastModifiedTime(p).toMillis();
                Optional<CatalogEntry> existing = catalog.entries().stream()
                        .filter(e -> e.status == Status.LOCAL && abs.equals(e.file)).findFirst();
                if (existing.isPresent() && Long.valueOf(size).equals(existing.get().fileSize)
                        && Long.valueOf(modified).equals(existing.get().fileModified)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(p);
                BufferedImage image = decode(bytes);
                if (image == null) {
                    continue;
                }
                MemeTemplate t = MemeTemplate.fromPath(p);
                CatalogEntry e = existing.orElseGet(CatalogEntry::new);
                e.id = t.id();
                e.name = t.name();
                e.source = "local";
                e.sourceId = p.getFileName().toString();
                e.status = Status.LOCAL;
                e.file = abs;
                e.license = null;
                e.fileSize = size;
                e.fileModified = modified;
                e.applyFingerprint(ImageFingerprint.of(bytes, image));
                // Your files are named "<imgflip id>_<name>": don't download those ids again
                if (t.id().chars().allMatch(Character::isDigit) && !t.id().equals(t.name())
                        && e.aliases.stream().noneMatch(a -> a.key().equals("imgflip:" + t.id()))) {
                    e.aliases.add(new Alias("imgflip:" + t.id(), t.name(), null, null, null, "id from the file name"));
                }
                if (existing.isEmpty()) {
                    catalog.add(e);
                }
                hashed++;
            } catch (IOException | RuntimeException ex) {
                log.warn("Cannot index {}: {}", p, ex.getMessage());
            }
        }
        catalog.entries().removeIf(e -> e.status == Status.LOCAL && !present.contains(e.file));
        if (hashed > 0) {
            log.info("Indexed {} new or changed templates in {}", hashed, localTemplatesDir);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private boolean hasLayoutFor(CatalogEntry e) {
        if (e.boxCount <= 2) {
            return true; // the default top/bottom layout fits
        }
        TemplateLayout layout = layouts.layoutFor(new MemeTemplate(e.id, e.name, Path.of(e.file)));
        return layout.custom() || layout.boxes().size() >= e.boxCount;
    }

    /** "80000000012_Daily Struggle.jpg": the "&lt;id&gt;_&lt;name&gt;" convention of meme.templates-dir. */
    static String fileName(String id, String name, String ext) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (safe.length() > 80) {
            safe = safe.substring(0, 80).trim();
        }
        safe = safe.replaceAll("[. ]+$", "");
        return id + "_" + (safe.isEmpty() ? "template" : safe) + "." + ext;
    }

    /** "jpg", "png", "gif" or "bmp" from the bytes themselves (URLs and content types lie), else null. */
    static String extensionOf(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                return FORMAT_EXTENSIONS.get(reader.getFormatName().toLowerCase(Locale.ROOT));
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return null; // e.g. CMYK JPEGs
        }
    }

    private static boolean isImageFile(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && IMAGE_EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static void write(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target + ": " + e.getMessage(), e);
        }
    }

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move " + from + " to " + to + ": " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", p, e.getMessage());
        }
    }

    private void pause() {
        try {
            Thread.sleep(props.downloadDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
