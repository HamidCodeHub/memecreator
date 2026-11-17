package com.memecreator.service;

import com.memecreator.ai.AiGatewayException;
import com.memecreator.ai.CaptionGenerator;
import com.memecreator.ai.MemeCaption;
import com.memecreator.config.MemeProperties;
import com.memecreator.render.MemeRenderer;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateLayout;
import com.memecreator.template.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

@Service
public class MemeService {

    private static final Logger log = LoggerFactory.getLogger(MemeService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TemplateRepository templates;
    private final LayoutRepository layouts;
    private final CaptionGenerator captions;
    private final MemeRenderer renderer;
    private final MemeProperties props;

    public MemeService(TemplateRepository templates, LayoutRepository layouts, CaptionGenerator captions,
                       MemeRenderer renderer, MemeProperties props) {
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
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be empty");
        }
        topic = topic.strip();
        language = (language == null || language.isBlank()) ? props.defaultLanguage() : language.strip();
        List<MemeTemplate> all = templates.findAll();
        if (all.isEmpty()) {
            throw new IllegalStateException("No meme templates found in " + props.templatesDir());
        }

        int count = props.count();
        List<MemeTemplate> fixed = resolve(templateNames);
        List<MemeCaption> suggestions;
        List<MemeTemplate> fallback;

        if (!fixed.isEmpty()) {
            suggestions = captions.captionTemplates(topic, fixed, language);
            fallback = fixed;
            count = fixed.size();
        } else if (strategy == Strategy.RANDOM) {
            fallback = templates.random(count, Set.of());
            suggestions = captions.captionTemplates(topic, fallback, language);
        } else {
            suggestions = captions.chooseAndCaption(topic, all, count, language);
            fallback = templates.random(count * 2, Set.of());
        }

        List<Pair> pairs = pair(suggestions, fixed, fallback, count);
        if (pairs.isEmpty()) {
            throw new AiGatewayException("The AI did not return any usable captions");
        }

        String folder = STAMP.format(LocalDateTime.now()) + "_" + slug(topic);
        List<GeneratedMeme> memes = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            Pair p = pairs.get(i);
            memes.add(render(folder, i + 1, p.template(), p.caption().texts()));
        }
        log.info("Created {} {} memes for \"{}\" in {}", memes.size(), language, topic, outputDir(folder));
        return new MemeBatch(topic, language, outputDir(folder).toString(), memes);
    }

    /** Renders one template with texts you provide (no AI call); handy for checking a layout. */
    public GeneratedMeme renderManual(String templateName, List<String> texts) {
        MemeTemplate t = templates.findByName(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName));
        String folder = STAMP.format(LocalDateTime.now()) + "_manual";
        return render(folder, 1, t, texts == null ? List.of() : texts);
    }

    /** PNG of the template with its text boxes outlined and numbered. */
    public byte[] layoutPreview(String templateName) {
        MemeTemplate t = templates.findByName(templateName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateName));
        try {
            return renderer.preview(t.path(), layouts.layoutFor(t).boxes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render preview of " + t.name(), e);
        }
    }

    public TemplateLayout layoutOf(MemeTemplate template) {
        return layouts.layoutFor(template);
    }

    private GeneratedMeme render(String folder, int index, MemeTemplate template, List<String> rawTexts) {
        TemplateLayout layout = layouts.layoutFor(template);
        List<String> texts = new MemeCaption(template.name(), rawTexts).textsFor(layout.boxes().size());
        String file = "%02d_%s.jpg".formatted(index, slug(template.name()));
        Path target = outputDir(folder).resolve(file);
        try {
            renderer.render(template.path(), layout.boxes(), texts, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render " + template.name(), e);
        }
        return new GeneratedMeme(template.name(), texts, "/generated/" + folder + "/" + file, target.toString());
    }

    private Path outputDir(String folder) {
        return Path.of(props.outputDir()).resolve(folder).toAbsolutePath();
    }

    /**
     * Matches each AI caption to a template. If the AI named a template that doesn't exist
     * (or repeated one), its caption is put on the next unused fallback template instead.
     */
    private List<Pair> pair(List<MemeCaption> suggestions, List<MemeTemplate> fixed,
                            List<MemeTemplate> fallback, int count) {
        Set<MemeTemplate> used = new LinkedHashSet<>();
        List<Pair> result = new ArrayList<>();
        for (MemeCaption c : suggestions) {
            if (result.size() == count) {
                break;
            }
            Optional<MemeTemplate> t = templates.findByName(c.template())
                    .filter(x -> !used.contains(x))
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
