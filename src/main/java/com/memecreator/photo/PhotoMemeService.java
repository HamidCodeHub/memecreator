package com.memecreator.photo;

import com.memecreator.config.MemeProperties;
import com.memecreator.render.MemeRenderer;
import com.memecreator.template.LayoutParser;
import com.memecreator.template.TextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Memes from an uploaded photo: prepare the image, let the AI read it and write captions (1 or 2 calls,
 * see {@link Elaboration}), then draw each caption in the style the AI picked.
 */
@Service
public class PhotoMemeService {

    private static final Logger log = LoggerFactory.getLogger(PhotoMemeService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final TextBox TOP = LayoutParser.CLASSIC.boxes().get(0);
    private static final TextBox BOTTOM = LayoutParser.CLASSIC.boxes().get(1);

    private final ImagePreparer preparer;
    private final PhotoCaptionWriter writer;
    private final MemeRenderer renderer;
    private final MemeProperties props;

    public PhotoMemeService(ImagePreparer preparer, PhotoCaptionWriter writer, MemeRenderer renderer,
                            MemeProperties props) {
        this.preparer = preparer;
        this.writer = writer;
        this.renderer = renderer;
        this.props = props;
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
        try {
            renderer.write(prepared.forRendering(), dir.resolve("00_upload.jpg"));
            List<PhotoPlan.Caption> captions = plan.captions().subList(0, Math.min(count, plan.captions().size()));
            for (int i = 0; i < captions.size(); i++) {
                PhotoPlan.Caption c = captions.get(i);
                String file = "%02d_%s.jpg".formatted(i + 1, c.style().name().toLowerCase().replace('_', '-'));
                Path target = dir.resolve(file);
                renderer.write(draw(prepared.forRendering(), c), target);
                memes.add(new PhotoMeme(c.style(), c.texts(), "/generated/" + folder + "/" + file, target.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save photo memes in " + dir, e);
        }
        log.info("Created {} photo memes ({} elaboration, {} AI call(s)) in {}", memes.size(), level, result.aiCalls(), dir);
        return new PhotoMemeBatch(cleanTopic, lang, level, result.aiCalls(), plan.analysis(), dir.toString(),
                "/generated/" + folder + "/00_upload.jpg", memes);
    }

    private BufferedImage draw(BufferedImage photo, PhotoPlan.Caption c) {
        return switch (c.style()) {
            case TOP_BOTTOM -> renderer.render(photo, List.of(TOP, BOTTOM), c.texts());
            case TOP -> renderer.render(photo, List.of(TOP), c.texts());
            case BOTTOM -> renderer.render(photo, List.of(BOTTOM), c.texts());
            case CAPTION_BAR -> renderer.renderCaptionBar(photo, c.texts().get(0));
        };
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
