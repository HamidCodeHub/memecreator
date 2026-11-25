package com.memecreator.harvest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * storage-dir/catalog.json: every template the harvester has seen, so each run only downloads what is new.
 * Not thread-safe on its own; {@link TemplateHarvester} serializes access.
 */
@Component
public class TemplateCatalog {

    /** Ids for templates whose source id isn't numeric (memegen "ds"): far above Imgflip's numeric ids. */
    static final long FIRST_GENERATED_ID = 80_000_000_001L;

    /** The JSON file's shape. */
    public static final class Data {
        public int version = 1;
        public long nextId = FIRST_GENERATED_ID;
        public List<CatalogEntry> templates = new ArrayList<>();
    }

    private final Path root;
    private final ObjectMapper mapper;
    private Data data;

    public TemplateCatalog(HarvestProperties props, ObjectMapper mapper) {
        this.root = Path.of(props.storageDir());
        this.mapper = mapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public Path root() {
        return root;
    }

    public Path templatesDir() {
        return root.resolve("templates");
    }

    public Path needsLayoutDir() {
        return root.resolve("needs-layout");
    }

    public Path folderOf(CatalogEntry.Status status) {
        return status == CatalogEntry.Status.NEEDS_LAYOUT ? needsLayoutDir() : templatesDir();
    }

    private Path file() {
        return root.resolve("catalog.json");
    }

    /** (Re)reads catalog.json; an absent file means an empty catalog. */
    public void load() {
        try {
            Files.createDirectories(templatesDir());
            Files.createDirectories(needsLayoutDir());
            data = Files.exists(file()) ? mapper.readValue(file().toFile(), Data.class) : new Data();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file() + " (fix or delete it): " + e.getMessage(), e);
        }
    }

    /** Writes to a temp file first, so a crash never leaves a half-written catalog. */
    public void save() {
        data.templates.sort(Comparator.comparing((CatalogEntry e) -> e.source).thenComparing(e -> e.name,
                String.CASE_INSENSITIVE_ORDER));
        Path tmp = root.resolve("catalog.json.tmp");
        try {
            mapper.writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file() + ": " + e.getMessage(), e);
        }
    }

    public List<CatalogEntry> entries() {
        return data.templates;
    }

    public void add(CatalogEntry entry) {
        data.templates.add(entry);
    }

    public void remove(CatalogEntry entry) {
        data.templates.remove(entry);
    }

    /** The entry for "source:sourceId", directly or through an alias. */
    public Optional<CatalogEntry> byKey(String key) {
        for (CatalogEntry e : data.templates) {
            if (key.equals(e.key()) || e.aliases.stream().anyMatch(a -> key.equals(a.key()))) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** Closest already-known image that counts as the same template (removed and rejected ones included). */
    public Optional<CatalogEntry> findSimilar(ImageFingerprint fp, int maxDistance) {
        return data.templates.stream()
                .filter(CatalogEntry::hasFingerprint)
                .filter(e -> fp.similarTo(e.fingerprint(), maxDistance))
                .min(Comparator.comparingInt(e -> fp.distance(e.fingerprint())));
    }

    public boolean idInUse(String id) {
        return data.templates.stream().anyMatch(e -> id.equals(e.id));
    }

    /** The source's id when it is numeric and free (Imgflip), otherwise a generated one. */
    public String assignId(TemplateCandidate c) {
        if (c.sourceId().matches("\\d{1,18}") && !idInUse(c.sourceId())) {
            return c.sourceId();
        }
        String id;
        do {
            id = Long.toString(data.nextId++);
        } while (idInUse(id));
        return id;
    }

    /** One line per run of a ranked source: the date and the ids in list order. */
    public void appendRankSnapshot(String source, List<TemplateCandidate> ranked) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("date", LocalDate.now().toString());
        line.put("capturedAt", Instant.now().toString());
        line.put("ids", ranked.stream().map(TemplateCandidate::sourceId).toList());
        try {
            String json = mapper.copy().disable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(line);
            Files.writeString(root.resolve(source + "-ranks.jsonl"), json + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append rank snapshot: " + e.getMessage(), e);
        }
    }
}
