package com.memecreator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the prompts and asks the AI gateway for meme captions. Every template is listed with its text
 * boxes, so the AI writes one text per box (e.g. Drake: rejected thing, approved thing).
 */
@Component
public class CaptionGenerator {

    private static final String SYSTEM_PROMPT = """
            You are a witty meme writer who knows classic internet meme templates and exactly
            how each format's joke works. Each template has numbered text boxes with a description of
            what belongs in each one; you write one short, punchy, genuinely funny text per box.
            Avoid hateful or demeaning jokes about protected groups.
            Reply with JSON only, no explanations, no markdown.""";

    private static final String JSON_SHAPE =
            "{\"memes\":[{\"template\":\"<exact template name>\",\"texts\":[\"<text for box 1>\",\"<text for box 2>\"]}]}";

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

    public CaptionGenerator(AiGatewayClient client, ObjectMapper mapper, LayoutRepository layouts) {
        this.client = client;
        this.mapper = mapper;
        this.layouts = layouts;
    }

    /** Lets the AI choose the {@code count} templates that fit the topic best. */
    public List<MemeCaption> chooseAndCaption(String topic, List<MemeTemplate> available, int count, String language) {
        String prompt = """
                Topic: %s

                Pick exactly %d DIFFERENT templates from the list below that fit this topic best,
                and write the texts for each so the meme is about the topic and uses the template's joke format.
                %s

                Templates (name — text boxes):
                %s

                Respond with JSON exactly in this shape:
                %s
                """.formatted(topic, count, RULES.formatted(language), describe(available), JSON_SHAPE);
        return CaptionParser.parse(client.chat(SYSTEM_PROMPT, prompt), mapper);
    }

    /** Captions templates that were already chosen (randomly or by the caller). */
    public List<MemeCaption> captionTemplates(String topic, List<MemeTemplate> templates, String language) {
        String prompt = """
                Topic: %s

                Write the texts for EACH of these %d templates, in this order, so every meme is about the
                topic and uses the template's joke format.
                %s

                Templates (name — text boxes):
                %s

                Respond with JSON exactly in this shape:
                %s
                """.formatted(topic, templates.size(), RULES.formatted(language), describe(templates), JSON_SHAPE);
        return CaptionParser.parse(client.chat(SYSTEM_PROMPT, prompt), mapper);
    }

    private String describe(List<MemeTemplate> templates) {
        return templates.stream()
                .map(t -> "- " + t.name() + " — " + layouts.layoutFor(t).describeForAi())
                .collect(Collectors.joining("\n"));
    }
}
