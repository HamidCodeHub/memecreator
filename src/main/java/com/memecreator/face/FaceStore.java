package com.memecreator.face;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.config.MemeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps users' face photos privately, so they can reuse, download or delete them later (GDPR: access and erasure).
 * <ul>
 *   <li>Stored under {@code meme.private-dir}/faces/&lt;uuid&gt;/, which is never served over HTTP and is separate
 *       from the template folder: an uploaded face can never become a template.</li>
 *   <li>Saved re-encoded as JPEG, which drops all EXIF metadata (camera, GPS location, time).</li>
 *   <li>Each face records when it was uploaded, the user's confirmations (adult, consent) and the meme folders
 *       made from it, so deleting a face deletes those memes too.</li>
 *   <li>Deleted automatically after {@code meme.face-retention} (default 90 days).</li>
 * </ul>
 */
@Component
public class FaceStore {

    private static final Logger log = LoggerFactory.getLogger(FaceStore.class);
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Path root;
    private final Path outputDir;
    private final MemeProperties props;
    private final ObjectMapper mapper;

    public FaceStore(MemeProperties props, ObjectMapper mapper) {
        this.root = Path.of(props.privateDir()).resolve("faces").toAbsolutePath().normalize();
        this.outputDir = Path.of(props.outputDir()).toAbsolutePath().normalize();
        this.props = props;
        this.mapper = mapper;
    }

    /** What is stored next to each face. */
    public record FaceRecord(String id, Instant uploadedAt, boolean confirmedAdult, boolean confirmedConsent,
                             List<String> memeFolders) {
    }

    /** Saves a face photo (already re-encoded JPEG bytes, so no EXIF) and returns its new id. */
    public synchronized FaceRecord save(byte[] jpeg, boolean confirmedAdult, boolean confirmedConsent) {
        String id = UUID.randomUUID().toString();
        FaceRecord rec = new FaceRecord(id, Instant.now(), confirmedAdult, confirmedConsent, List.of());
        try {
            Path dir = dir(id);
            Files.createDirectories(dir);
            Files.write(dir.resolve("face.jpg"), jpeg);
            writeMeta(rec);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the face photo", e);
        }
        return rec;
    }

    public Optional<byte[]> photo(String id) {
        Path file = dir(id).resolve("face.jpg");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the face photo", e);
        }
    }

    public Optional<FaceRecord> record(String id) {
        Path meta = dir(id).resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(meta.toFile(), FaceRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read face metadata", e);
        }
    }

    /** Remembers a meme folder (name under the output dir) made with this face, so it's deleted with the face. */
    public synchronized void addMemeFolder(String id, String folder) {
        record(id).ifPresent(rec -> {
            List<String> folders = new ArrayList<>(rec.memeFolders());
            folders.add(folder);
            writeMeta(new FaceRecord(rec.id(), rec.uploadedAt(), rec.confirmedAdult(), rec.confirmedConsent(), folders));
        });
    }

    /** Deletes the face photo and every meme made from it. Returns false if there was no such face. */
    public synchronized boolean delete(String id) {
        Optional<FaceRecord> rec = record(id);
        Path dir = dir(id);
        if (rec.isEmpty() && !Files.exists(dir)) {
            return false;
        }
        rec.ifPresent(r -> r.memeFolders().forEach(f -> deleteTree(memeFolder(f))));
        deleteTree(dir);
        log.info("Deleted face {} and its memes", id);
        return true;
    }

    /** Daily clean-up of faces older than meme.face-retention (time configurable with meme.face-purge-cron). */
    @Scheduled(cron = "${meme.face-purge-cron:0 30 3 * * *}")
    public void purgeExpired() {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(props.faceRetention());
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.map(p -> p.getFileName().toString())
                    .filter(id -> ID.matcher(id).matches())
                    .filter(id -> record(id).map(r -> r.uploadedAt().isBefore(cutoff)).orElse(true))
                    .forEach(this::delete);
        } catch (IOException e) {
            log.error("Face clean-up failed: {}", e.getMessage());
        }
    }

    private void writeMeta(FaceRecord rec) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir(rec.id()).resolve("meta.json").toFile(), rec);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write face metadata", e);
        }
    }

    /** Only well-formed UUIDs are accepted, so an id can never point outside the store. */
    private Path dir(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid face id");
        }
        return root.resolve(id);
    }

    private Path memeFolder(String folder) {
        Path p = outputDir.resolve(folder).normalize();
        if (!p.startsWith(outputDir) || p.equals(outputDir)) {
            throw new IllegalStateException("Refusing to delete outside the output folder: " + folder);
        }
        return p;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + dir, e);
        }
    }
}
