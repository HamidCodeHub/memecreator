package com.memecreator.edit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.account.MemeLibrary;
import com.memecreator.account.MemeRecord;
import com.memecreator.account.MemeRecordRepository;
import com.memecreator.account.Throttle;
import com.memecreator.config.MemeProperties;
import com.memecreator.photo.CaptionStyle;
import com.memecreator.photo.ImagePreparer;
import com.memecreator.photo.PhotoMemeService;
import com.memecreator.render.MemeRenderer;
import com.memecreator.service.MemeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Changing the words of a meme the account made: a preview image (not saved), then saving it as a NEW meme next
 * to the original, so the original (and any link already shared) never changes. No AI involved.
 */
@Service
public class MemeEditor {

    static final int MAX_TEXT = 200;
    private static final int PREVIEWS_PER_HOUR = 300;
    private static final int SAVES_PER_HOUR = 60;
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final MemeRecordRepository records;
    private final MemeLibrary library;
    private final MemeService memes;
    private final PhotoMemeService photos;
    private final MemeRenderer renderer;
    private final Throttle throttle;
    private final ObjectMapper json = new ObjectMapper();
    private final Path outputDir;
    private final Clock clock = Clock.systemUTC();

    public MemeEditor(MemeRecordRepository records, MemeLibrary library, MemeService memes, PhotoMemeService photos,
                      MemeRenderer renderer, Throttle throttle, MemeProperties props) {
        this.records = records;
        this.library = library;
        this.memes = memes;
        this.photos = photos;
        this.renderer = renderer;
        this.throttle = throttle;
        this.outputDir = Path.of(props.outputDir()).toAbsolutePath().normalize();
    }

    /** Memes made before editing existed (no recipe), or not this account's. 404. */
    public static final class NotEditableException extends RuntimeException {
        public NotEditableException() {
            super("This meme can't be edited. Memes made before editing was added can't be changed; make a new set.");
        }
    }

    /**
     * What the editor needs.
     *
     * @param labels  one per text field: what that text is for
     * @param aiTexts what the AI wrote, for "Back to the AI's version"
     * @param aiUrl   the AI's version of this meme
     */
    public record EditInfo(String url, String template, List<String> labels, List<String> texts,
                           List<String> aiTexts, String aiUrl) {
    }

    /** A saved edit: the new meme. */
    public record Edited(String url, String template, List<String> texts) {
    }

    @Transactional(readOnly = true)
    public EditInfo info(long accountId, String url) {
        MemeRecord r = record(accountId, url);
        return new EditInfo(r.getUrl(), name(r), labels(r), fit(read(r.getTexts()), labels(r).size()),
                fit(read(r.getAiTexts()), labels(r).size()), "/generated/" + r.getFolder() + "/" + r.getAiFile());
    }

    /** JPEG of the meme with these texts; nothing is saved. */
    @Transactional(readOnly = true)
    public byte[] preview(long accountId, String url, List<String> texts) {
        MemeRecord r = record(accountId, url);
        throttle.acquire("edit-preview:" + accountId, PREVIEWS_PER_HOUR, Duration.ofHours(1));
        return ImagePreparer.toJpeg(draw(r, clean(texts, labels(r).size())));
    }

    /** Saves the meme with these texts as a new file next to the original; the original stays. */
    @Transactional
    public Edited save(long accountId, String url, List<String> texts) {
        MemeRecord r = record(accountId, url);
        List<String> clean = clean(texts, labels(r).size());
        if (clean.equals(fit(read(r.getAiTexts()), clean.size()))) {
            // Back to exactly what the AI wrote: that meme already exists
            return new Edited("/generated/" + r.getFolder() + "/" + r.getAiFile(), name(r), clean);
        }
        throttle.acquire("edit-save:" + accountId, SAVES_PER_HOUR, Duration.ofHours(1));
        Path dir = outputDir.resolve(r.getFolder()).normalize();
        if (!dir.getParent().equals(outputDir)) {
            throw new NotEditableException();
        }
        String stem = r.getAiFile().replaceFirst("\\.[A-Za-z]+$", "");
        String file = null;
        for (int v = 2; v < 1000 && file == null; v++) {
            String candidate = stem + "-v" + v + ".jpg";
            if (!Files.exists(dir.resolve(candidate))) {
                file = candidate;
            }
        }
        if (file == null) {
            throw new IllegalStateException("Too many versions of this meme");
        }
        try {
            renderer.write(draw(r, clean), dir.resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save the edited meme", e);
        }
        MemeRecord saved = records.save(new MemeRecord(accountId, r.getFolder(), file, r.getKind(), r.getTemplate(),
                r.getPhotoStyle(), write(clean), r.getAiTexts(), r.getAiFile(), clock.instant()));
        return new Edited(saved.getUrl(), name(saved), clean);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private MemeRecord record(long accountId, String url) {
        MemeLibrary.MemeRef ref;
        try {
            ref = library.ownMeme(accountId, url);
        } catch (MemeLibrary.MemeNotFoundException e) {
            throw new NotEditableException();
        }
        return records.findByAccountIdAndFolderAndFile(accountId, ref.folder(), ref.file())
                .orElseThrow(NotEditableException::new);
    }

    private BufferedImage draw(MemeRecord r, List<String> texts) {
        if (r.getKind() == MemeRecord.Kind.PHOTO) {
            Path upload = outputDir.resolve(r.getFolder()).resolve("00_upload.jpg");
            if (!Files.isRegularFile(upload)) {
                throw new NotEditableException();
            }
            return photos.drawOnPhoto(upload, CaptionStyle.valueOf(r.getPhotoStyle()), texts);
        }
        return memes.drawOnTemplate(r.getTemplate(), texts);
    }

    private List<String> labels(MemeRecord r) {
        return r.getKind() == MemeRecord.Kind.PHOTO
                ? PhotoMemeService.labels(CaptionStyle.valueOf(r.getPhotoStyle()))
                : memes.boxLabels(r.getTemplate());
    }

    private static String name(MemeRecord r) {
        return r.getKind() == MemeRecord.Kind.PHOTO ? "Your photo" : r.getTemplate();
    }

    /** Exactly {@code size} texts, trimmed, without control characters, at most MAX_TEXT characters each. */
    static List<String> clean(List<String> texts, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String t = texts != null && i < texts.size() && texts.get(i) != null ? texts.get(i) : "";
            t = t.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\p{Cntrl}", "").strip();
            if (t.length() > MAX_TEXT) {
                throw new IllegalArgumentException("Each text can be at most " + MAX_TEXT + " characters.");
            }
            out.add(t);
        }
        if (out.stream().allMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Write at least one text.");
        }
        return out;
    }

    private static List<String> fit(List<String> texts, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            out.add(i < texts.size() && texts.get(i) != null ? texts.get(i) : "");
        }
        return out;
    }

    private List<String> read(String s) {
        try {
            return s == null ? List.of() : json.readValue(s, STRINGS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String write(List<String> texts) {
        try {
            return json.writeValueAsString(texts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
