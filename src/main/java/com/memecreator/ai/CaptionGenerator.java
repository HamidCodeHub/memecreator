package com.memecreator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the prompts and asks the AI gateway for meme captions. Every listed template comes with its text
 * boxes, so the AI writes one text per box (e.g. Drake: rejected thing, approved thing). When the AI picks the
 * templates, it only sees a {@link TemplateShortlist} of the pool (~15), which keeps the request small.
 */
@Component
public class CaptionGenerator {

    private static final Logger log = LoggerFactory.getLogger(CaptionGenerator.class);

    /** The topic sent when the user gave none (also tells the shortlist there are no topic words to match). */
    public static final String NO_TOPIC =
            "(no topic given) write generally funny, relatable captions that fit each template's joke format";

    private static final String SYSTEM_PROMPT = """
            You are a witty meme writer who knows classic internet meme templates and exactly
            how each format's joke works. Each template has numbered text boxes with a description of
            what belongs in each one; you write one short, punchy, genuinely funny text per box.
            Avoid hateful or demeaning jokes about protected groups.
            Reply with JSON only, no explanations, no markdown.""";

    private static final String JSON_SHAPE =
            "{\"memes\":[{\"template\":\"<exact template name>\",\"texts\":[\"<text for box 1>\",\"<text for box 2>\"]}]}";

    private static final String FACE_JSON_SHAPE =
            "{\"memes\":[{\"template\":\"<exact template name>\",\"texts\":[\"<text for box 1>\",\"<text for box 2>\"],"
                    + "\"face\":\"<character name, only for templates that list characters>\"}]}";

    private static final String FACE_RULES = """
            - The user's own face will be pasted onto ONE person in each meme. For templates that list
              "characters", add "face" with the name of the character whose role in your joke best fits the
              user (usually the one the joke is about, the "me" of the meme). Copy the name exactly.
              Leave "face" out for templates without characters.""";

    private static final String RULES = """
            Rules:
            - "template" must be copied exactly from the list.
            - "texts" must have exactly one entry per text box of that template, in the listed order.
              Use "" for a box that should stay empty (boxes marked optional, or when the image already says it).
            - Keep texts short: at most about 8 words each; small boxes need even fewer.
            - Write the texts in %s, whatever language the topic is in.""";

    private final AiGatewayClient client;
    private final ObjectMapper mapper;
    private final LayoutRepository layouts;
    private final TemplateShortlist shortlist;
    private final PromptProperties promptProps;

    public CaptionGenerator(AiGatewayClient client, ObjectMapper mapper, LayoutRepository layouts,
                            TemplateShortlist shortlist, PromptProperties promptProps) {
        this.client = client;
        this.mapper = mapper;
        this.layouts = layouts;
        this.shortlist = shortlist;
        this.promptProps = promptProps;
    }

    /** Lets the AI choose the {@code count} templates that fit the topic best. */
    public List<MemeCaption> chooseAndCaption(String topic, List<MemeTemplate> available, int count, String language) {
        return chooseAndCaption(topic, available, count, language, false);
    }

    /**
     * @param faceCharacters true for face memes: templates with several people list them, and the AI says which
     *                       one gets the user's face
     */
    public List<MemeCaption> chooseAndCaption(String topic, List<MemeTemplate> available, int count, String language,
                                              boolean faceCharacters) {
        List<MemeTemplate> listed = shortlist.pick(NO_TOPIC.equals(topic) ? null : topic, available, count);
        log.debug("Listing {} of {} templates in the prompt", listed.size(), available.size());
        String prompt = """
                Topic: %s

                Pick exactly %d DIFFERENT templates from the list below that fit this topic best,
                and write the texts for each so the meme is about the topic and uses the template's joke format.
                %s

                Templates (name — text boxes):
                %s

                Respond with JSON exactly in this shape:
                %s
                """.formatted(topic, count, rules(language, faceCharacters), describe(listed, faceCharacters),
                faceCharacters ? FACE_JSON_SHAPE : JSON_SHAPE);
        return CaptionParser.parse(client.chat(SYSTEM_PROMPT, prompt), mapper);
    }

    /** {@code count} different jokes about the topic, all on the one template the user picked. */
    public List<MemeCaption> captionVariations(String topic, MemeTemplate template, int count, String language) {
        String prompt = """
                Topic: %s

                Write %d DIFFERENT memes about this topic, ALL on this one template. Each must be a different
                joke (a different angle, situation or punchline), not the same joke reworded, and each must use
                the template's joke format.
                %s

                Template (name — text boxes):
                %s

                Respond with JSON exactly in this shape, with %d entries that all use this template's name:
                %s
                """.formatted(topic, count, rules(language, false), describe(List.of(template), false), count,
                JSON_SHAPE);
        return CaptionParser.parse(client.chat(SYSTEM_PROMPT, prompt), mapper);
    }

    /** Captions templates that were already chosen (randomly or by the caller). */
    public List<MemeCaption> captionTemplates(String topic, List<MemeTemplate> templates, String language) {
        return captionTemplates(topic, templates, language, false);
    }

    public List<MemeCaption> captionTemplates(String topic, List<MemeTemplate> templates, String language,
                                              boolean faceCharacters) {
        String prompt = """
                Topic: %s

                Write the texts for EACH of these %d templates, in this order, so every meme is about the
                topic and uses the template's joke format.
                %s

                Templates (name — text boxes):
                %s

                Respond with JSON exactly in this shape:
                %s
                """.formatted(topic, templates.size(), rules(language, faceCharacters), describe(templates, faceCharacters),
                faceCharacters ? FACE_JSON_SHAPE : JSON_SHAPE);
        return CaptionParser.parse(client.chat(SYSTEM_PROMPT, prompt), mapper);
    }

    private static String rules(String language, boolean faceCharacters) {
        return RULES.formatted(language) + (faceCharacters ? "\n" + FACE_RULES : "");
    }

    private String describe(List<MemeTemplate> templates, boolean faceCharacters) {
        return templates.stream()
                .map(t -> {
                    TemplateLayout layout = layouts.layoutFor(t);
                    String line = "- " + t.name() + " — " + layout.describeForAi(promptProps.maxUsageChars());
                    return faceCharacters && layout.hasCharacters()
                            ? line + ". Characters: " + layout.describeCharactersForAi()
                            : line;
                })
                .collect(Collectors.joining("\n"));
    }
}
