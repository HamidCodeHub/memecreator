package com.memecreator.photo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.config.MemeProperties;
import com.memecreator.render.MemeRenderer;
import com.memecreator.render.Watermarker;
import com.memecreator.template.TextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Memes from an uploaded photo: prepare the image, let the AI read it and write captions (1 or 2 calls,
 * see {@link Elaboration}), then draw each caption in the style the AI picked. Captions never cover a face: the
 * heads the local face detector finds and the areas the AI names ("keep_clear") are kept free, and a caption with no
 * room left over the photo goes into a white bar above or below it ({@link CaptionPlacer}).
 */
@Service
public class PhotoMemeService {

    private static final Logger log = LoggerFactory.getLogger(PhotoMemeService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Next to 00_upload.jpg: the areas captions must not cover, so edited memes are placed the same way. */
    static final String KEEP_CLEAR_FILE = "00_keep-clear.json";

    private final ImagePreparer preparer;
    private final PhotoCaptionWriter writer;
    private final MemeRenderer renderer;
    private final Watermarker watermarker;
    private final MemeProperties props;
    private final PhotoFaces faces;
    private final ObjectMapper mapper;

    public PhotoMemeService(ImagePreparer preparer, PhotoCaptionWriter writer, MemeRenderer renderer,
                            Watermarker watermarker, MemeProperties props, PhotoFaces faces, ObjectMapper mapper) {
        this.watermarker = watermarker;
        this.preparer = preparer;
        this.writer = writer;
        this.renderer = renderer;
        this.props = props;
        this.faces = faces;
        this.mapper = mapper;
    }

    public PhotoMemeBatch create(byte[] image, String topic, String language, int count, Elaboration elaboration) {
        String lang = language == null || language.isBlank() ? props.defaultLanguage() : language.strip();
        String cleanTopic = topic == null || topic.isBlank() ? null : topic.strip();
        Elaboration level = elaboration == null ? Elaboration.NORMAL : elaboration;

        ImagePreparer.PreparedImage prepared = preparer.prepare(image);
        PhotoCaptionWriter.Result result = writer.write(prepared.aiDataUri(), cleanTopic, lang, count, level);
        PhotoPlan plan = result.plan();

        String folder = STAMP.format(LocalDateTime.now()) + "_photo"
                + (cleanTopic == null ? "" : "_" + slug(cleanTopic));
        Path dir = Path.of(props.outputDir()).resolve(folder).toAbsolutePath();
        List<PhotoMeme> memes = new ArrayList<>();
        List<KeepClearArea> keepClear = new ArrayList<>(faces.heads(prepared.forRendering()));
        keepClear.addAll(plan.keepClear());
        log.debug("Keep clear on the photo: {}", keepClear);
        try {
            renderer.write(prepared.forRendering(), dir.resolve("00_upload.jpg"));
            Files.createDirectories(dir);
            mapper.writeValue(dir.resolve(KEEP_CLEAR_FILE).toFile(), keepClear);
            List<PhotoPlan.Caption> captions = plan.captions().subList(0, Math.min(count, plan.captions().size()));
            for (int i = 0; i < captions.size(); i++) {
                PhotoPlan.Caption c = captions.get(i);
                String file = "%02d_%s.jpg".formatted(i + 1, c.style().name().toLowerCase().replace('_', '-'));
                Path target = dir.resolve(file);
                renderer.write(draw(prepared.forRendering(), c, keepClear), target);
                memes.add(new PhotoMeme(c.style(), c.texts(), "/generated/" + folder + "/" + file, target.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save photo memes in " + dir, e);
        }
        log.info("Created {} photo memes ({} elaboration, {} AI call(s)) in {}", memes.size(), level, result.aiCalls(), dir);
        return new PhotoMemeBatch(cleanTopic, lang, level, result.aiCalls(), plan.analysis(), dir.toString(),
                "/generated/" + folder + "/00_upload.jpg", memes);
    }

    // ---- editing a photo meme's words (no AI) ----------------------------------------------------------------

    /** Labels for the editor's fields, one per text of the style. */
    public static List<String> labels(CaptionStyle style) {
        return switch (style) {
            case TOP_BOTTOM -> List.of("Top text", "Bottom text");
            case TOP -> List.of("Top text");
            case BOTTOM -> List.of("Bottom text");
            case CAPTION_BAR -> List.of("Caption above the photo");
        };
    }

    /**
     * The uploaded photo (00_upload.jpg of the set) with these texts and the watermark, without saving it. Uses the
     * set's saved keep-clear areas; sets made before they were saved only avoid the faces found now.
     */
    public BufferedImage drawOnPhoto(Path upload, CaptionStyle style, List<String> texts) {
        try {
            BufferedImage photo = renderer.read(upload);
            Path saved = upload.resolveSibling(KEEP_CLEAR_FILE);
            List<KeepClearArea> keepClear = Files.isRegularFile(saved)
                    ? mapper.readValue(saved.toFile(), new TypeReference<List<KeepClearArea>>() { })
                    : faces.heads(photo);
            return draw(photo, new PhotoPlan.Caption(style, texts), keepClear);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + upload, e);
        }
    }

    /** Captions over the photo where there is room, white bars where there isn't, then the watermark. */
    private BufferedImage draw(BufferedImage photo, PhotoPlan.Caption c, List<KeepClearArea> keepClear) {
        int w = photo.getWidth();
        int h = photo.getHeight();
        CaptionPlacer.Placement p = CaptionPlacer.place(c, keepClear, w, h, renderer::fontSizeFor);
        BufferedImage withText = p.boxes().isEmpty() ? photo : renderer.render(photo, p.boxes(), p.texts());
        int topH = renderer.barHeight(p.topBar(), w);
        int bottomH = renderer.barHeight(p.bottomBar(), w);
        BufferedImage out = renderer.renderBars(withText, p.topBar(), p.bottomBar());
        return watermarker.apply(out, captionAreas(p, h, topH, bottomH));
    }

    /** Where the captions ended up, in fractions of the final image, so the watermark avoids them. */
    private static List<TextBox> captionAreas(CaptionPlacer.Placement p, int photoH, int topH, int bottomH) {
        double total = topH + photoH + bottomH;
        List<TextBox> areas = new ArrayList<>();
        for (TextBox b : p.boxes()) {
            areas.add(new TextBox(b.x(), (topH + b.y() * photoH) / total, b.width(), b.height() * photoH / total,
                    b.color(), b.valign(), b.hint()));
        }
        if (topH > 0) {
            areas.add(new TextBox(0, 0, 1, topH / total, TextBox.TextColor.BLACK, TextBox.VAlign.TOP, "top bar"));
        }
        if (bottomH > 0) {
            areas.add(new TextBox(0, (topH + photoH) / total, 1, bottomH / total, TextBox.TextColor.BLACK,
                    TextBox.VAlign.BOTTOM, "bottom bar"));
        }
        return areas;
    }

    private static String slug(String s) {
        String slug = s.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "").toLowerCase();
        return slug.length() > 30 ? slug.substring(0, 30).replaceAll("-+$", "") : slug;
    }

    /** @param texts one text per caption area of the style (2 for TOP_BOTTOM, otherwise 1) */
    public record PhotoMeme(CaptionStyle style, List<String> texts, String url, String file) {
    }

    /**
     * @param aiCalls  how many AI gateway calls were made (1 for NORMAL, 2 for HIGH)
     * @param analysis what the AI saw in the photo
     */
    public record PhotoMemeBatch(String topic, String language, Elaboration elaboration, int aiCalls,
                                 PhotoPlan.Analysis analysis, String folder, String uploadUrl,
                                 List<PhotoMeme> memes) {
    }
}
