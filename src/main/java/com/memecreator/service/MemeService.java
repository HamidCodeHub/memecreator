package com.memecreator.service;

import com.memecreator.ai.AiGatewayException;
import com.memecreator.ai.CaptionGenerator;
import com.memecreator.ai.MemeCaption;
import com.memecreator.config.MemeProperties;
import com.memecreator.render.MemeRenderer;
import com.memecreator.render.Watermarker;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateLayout;
import com.memecreator.template.TemplateRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class MemeService {

    private static final Logger log = LoggerFactory.getLogger(MemeService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TemplateRepository templates;
    private final LayoutRepository layouts;
    private final CaptionGenerator captions;
    private final MemeRenderer renderer;
    private final Watermarker watermarker;
    private final MemeProperties props;

    public MemeService(TemplateRepository templates, LayoutRepository layouts, CaptionGenerator captions,
                       MemeRenderer renderer, Watermarker watermarker, MemeProperties props) {
        this.watermarker = watermarker;
        this.templates = templates;
        this.layouts = layouts;
        this.captions = captions;
        this.renderer = renderer;
        this.props = props;
    }

    public MemeBatch create(String topic, List<String> templateNames, Strategy strategy) {
        return create(topic, templateNames, strategy, null);
    }

    /**
     * @param language caption language, e.g. "English" or "Italian"; null/blank uses meme.default-language
     */
    public MemeBatch create(String topic, List<String> templateNames, Strategy strategy, String language) {
        return create(topic, templateNames, strategy, language, props.count());
    }

    /** Same, making {@code count} memes (1..12) instead of meme.count; the web app asks for 6. */
    public MemeBatch create(String topic, List<String> templateNames, Strategy strategy, String language, int count) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be empty");
        }
        if (count < 1 || count > 12) {
            throw new IllegalArgumentException("count must be between 1 and 12");
        }
        String folder = STAMP.format(LocalDateTime.now()) + "_" + slug(topic.strip());
        return create(topic, templateNames, strategy, language, templates.findAll(), null, false, folder, count);
    }

    /**
     * {@code count} memes on ONE template the user picked, each a different joke.
     *
     * @param templateId the template's id (as the templates page lists it) or name
     */
    public MemeBatch createOnTemplate(String topic, String templateId, String language, int count) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be empty");
        }
        if (count < 1 || count > 12) {
            throw new IllegalArgumentException("count must be between 1 and 12");
        }
        MemeTemplate template = templates.findAll().stream().filter(t -> t.id().equals(templateId)).findFirst()
                .or(() -> templates.findByName(templateId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateId));
        String cleanTopic = topic.strip();
        language = (language == null || language.isBlank()) ? props.defaultLanguage() : language.strip();
        String folder = STAMP.format(LocalDateTime.now()) + "_" + slug(cleanTopic);

        List<MemeCaption> captions = this.captions.captionVariations(cleanTopic, template, count, language);
        List<GeneratedMeme> memes = new ArrayList<>();
        for (MemeCaption c : captions) {
            if (memes.size() == count) {
                break;
            }
            if (c.texts() == null || c.texts().stream().allMatch(t -> t == null || t.isBlank())) {
                continue;
            }
            memes.add(render(folder, memes.size() + 1, template, c, null));
        }
        if (memes.isEmpty()) {
            throw new AiGatewayException("The AI did not return any usable captions");
        }
        log.info("Created {} {} memes on \"{}\" for \"{}\" in {}", memes.size(), language, template.name(),
                cleanTopic, outputDir(folder));
        return new MemeBatch(cleanTopic, language, outputDir(folder).toString(), memes);
    }

    /** A change to a template image before the captions are drawn, e.g. pasting the user's face. */
    @FunctionalInterface
    public interface Decorator {
        /** @param caption the AI's caption for this meme (its {@code face} says which character to use), or the user's */
        BufferedImage apply(MemeTemplate template, BufferedImage image, MemeCaption caption);
    }

    /**
     * The general version used by the face feature too.
     *
     * @param topic    what the memes are about; null/blank lets the AI write generally funny captions
     * @param pool     templates the AI may choose from (and that templateNames must belong to)
     * @param decorate       optional change to each template image before captions are drawn (e.g. paste a face)
     * @param faceCharacters ask the AI which character on multi-person templates gets the user's face
     * @param folder         output sub-folder name
     */
    public MemeBatch create(String topic, List<String> templateNames, Strategy strategy, String language,
                            List<MemeTemplate> pool, Decorator decorate, boolean faceCharacters,
                            String folder, int count) {
        String cleanTopic = topic == null || topic.isBlank() ? null : topic.strip();
        String aiTopic = cleanTopic != null ? cleanTopic
                : CaptionGenerator.NO_TOPIC;
        language = (language == null || language.isBlank()) ? props.defaultLanguage() : language.strip();
        if (pool.isEmpty()) {
            throw new IllegalStateException("No meme templates available in " + props.templatesDir());
        }

        List<MemeTemplate> fixed = resolve(templateNames);
        for (MemeTemplate t : fixed) {
            if (!pool.contains(t)) {
                throw new IllegalArgumentException("Template \"" + t.name() + "\" can't be used here");
            }
        }
        List<MemeCaption> suggestions;
        List<MemeTemplate> fallback;

        if (!fixed.isEmpty()) {
            suggestions = captions.captionTemplates(aiTopic, fixed, language, faceCharacters);
            fallback = fixed;
            count = fixed.size();
        } else if (strategy == Strategy.RANDOM) {
            fallback = randomFrom(pool, count);
            suggestions = captions.captionTemplates(aiTopic, fallback, language, faceCharacters);
        } else {
            suggestions = captions.chooseAndCaption(aiTopic, pool, count, language, faceCharacters);
            fallback = randomFrom(pool, count * 2);
        }

        List<Pair> pairs = pair(suggestions, fixed, fallback, pool, count);
        if (pairs.isEmpty()) {
            throw new AiGatewayException("The AI did not return any usable captions");
        }

        List<GeneratedMeme> memes = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            Pair p = pairs.get(i);
            memes.add(render(folder, i + 1, p.template(), p.caption(), decorate));
        }
        log.info("Created {} {} memes for \"{}\" in {}", memes.size(), language, aiTopic, outputDir(folder));
        return new MemeBatch(cleanTopic, language, outputDir(folder).toString(), memes);
    }

    /** Renders one template with texts you provide (no AI call); handy for checking a layout. */
    public GeneratedMeme renderManual(String templateName, List<String> texts) {
        String folder = STAMP.format(LocalDateTime.now()) + "_manual";
        return renderManual(templateName, texts, null, folder);
    }

    /** Same, with an optional image change before captions are drawn (e.g. paste a face). */
    public GeneratedMeme renderManual(String templateName, List<String> texts, Decorator decorate, String folder) {
        MemeTemplate t = templates.findByName(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName));
        return render(folder, 1, t, new MemeCaption(t.name(), texts == null ? List.of() : texts), decorate);
    }

    /** PNG of the template with its text boxes outlined and numbered. */
    public byte[] layoutPreview(String templateName) {
        MemeTemplate t = templates.findByName(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName));
        try {
            TemplateLayout layout = layouts.layoutFor(t);
            return renderer.preview(t.path(), layout.boxes(), layout.faces());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render preview of " + t.name(), e);
        }
    }

    public TemplateLayout layoutOf(MemeTemplate template) {
        return layouts.layoutFor(template);
    }

    private GeneratedMeme render(String folder, int index, MemeTemplate template, MemeCaption caption,
                                 Decorator decorate) {
        TemplateLayout layout = layouts.layoutFor(template);
        List<String> texts = caption.textsFor(layout.boxes().size());
        String file = "%02d_%s.jpg".formatted(index, slug(template.name()));
        Path target = outputDir(folder).resolve(file);
        try {
            BufferedImage image = renderer.read(template.path());
            if (decorate != null) {
                image = decorate.apply(template, image, caption);
            }
            renderer.write(draw(image, layout, texts), target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render " + template.name(), e);
        }
        return new GeneratedMeme(template.name(), texts, "/generated/" + folder + "/" + file, target.toString());
    }

    /** Captions plus the watermark (placed away from the boxes that got text). */
    private BufferedImage draw(BufferedImage image, TemplateLayout layout, List<String> texts) {
        BufferedImage meme = renderer.render(image, layout.boxes(), texts);
        List<TextBox> used = new ArrayList<>();
        for (int i = 0; i < layout.boxes().size() && i < texts.size(); i++) {
            if (texts.get(i) != null && !texts.get(i).isBlank()) {
                used.add(layout.boxes().get(i));
            }
        }
        return watermarker.apply(meme, used);
    }

    // ---- editing a meme's words (no AI) ---------------------------------------------------------------------------

    /** What each text box of a template is for, e.g. "What Drake rejects"; the editor labels its fields with them. */
    public List<String> boxLabels(String templateName) {
        MemeTemplate t = templates.findByName(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName));
        List<TextBox> boxes = layouts.layoutFor(t).boxes();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            String hint = boxes.get(i).hint();
            labels.add(hint == null || hint.isBlank() ? "Text " + (i + 1) : hint);
        }
        return labels;
    }

    /** The template with these texts and the watermark, as a meme is made, without saving it. */
    public BufferedImage drawOnTemplate(String templateName, List<String> texts) {
        MemeTemplate t = templates.findByName(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName));
        TemplateLayout layout = layouts.layoutFor(t);
        try {
            return draw(renderer.read(t.path()), layout, new MemeCaption(t.name(), texts).textsFor(layout.boxes().size()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render " + t.name(), e);
        }
    }

    private Path outputDir(String folder) {
        return Path.of(props.outputDir()).resolve(folder).toAbsolutePath();
    }

    /**
     * Matches each AI caption to a template. If the AI named a template that doesn't exist
     * (or repeated one), its caption is put on the next unused fallback template instead.
     */
    private List<Pair> pair(List<MemeCaption> suggestions, List<MemeTemplate> fixed,
                            List<MemeTemplate> fallback, List<MemeTemplate> pool, int count) {
        Set<MemeTemplate> used = new LinkedHashSet<>();
        List<Pair> result = new ArrayList<>();
        for (MemeCaption c : suggestions) {
            if (result.size() == count) {
                break;
            }
            Optional<MemeTemplate> t = templates.findByName(c.template())
                    .filter(x -> !used.contains(x))
                    .filter(pool::contains)
                    .filter(x -> fixed.isEmpty() || fixed.contains(x));
            if (t.isEmpty()) {
                t = fallback.stream().filter(x -> !used.contains(x)).findFirst();
                t.ifPresent(x -> log.warn("AI picked unknown template \"{}\", using \"{}\"", c.template(), x.name()));
            }
            if (t.isEmpty()) {
                break;
            }
            used.add(t.get());
            result.add(new Pair(t.get(), c));
        }
        return result;
    }

    private List<MemeTemplate> resolve(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<MemeTemplate> out = new ArrayList<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            MemeTemplate t = templates.findByName(n)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + n));
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<MemeTemplate> randomFrom(List<MemeTemplate> pool, int count) {
        List<MemeTemplate> copy = new ArrayList<>(pool);
        java.util.Collections.shuffle(copy, ThreadLocalRandom.current());
        return copy.subList(0, Math.min(count, copy.size()));
    }

    public TemplateLayout layoutOf(String templateName) {
        return layouts.layoutFor(templates.findByName(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName)));
    }

    public List<MemeTemplate> allTemplates() {
        return templates.findAll();
    }

    static String slug(String s) {
        String slug = s.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "").toLowerCase();
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "meme" : slug;
    }

    private record Pair(MemeTemplate template, MemeCaption caption) {
    }

    public enum Strategy {
        /** The AI picks the templates that fit the topic best (default). */
        AI_PICK,
        /** Templates are chosen at random, the AI only writes captions. */
        RANDOM
    }

    /** @param texts one text per text box of the template's layout, in box order */
    public record GeneratedMeme(String template, List<String> texts, String url, String file) {
    }

    public record MemeBatch(String topic, String language, String folder, List<GeneratedMeme> memes) {
    }
}
