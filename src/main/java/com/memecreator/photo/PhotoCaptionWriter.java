package com.memecreator.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.ai.AiGatewayClient;
import com.memecreator.ai.AiGatewayException;
import org.springframework.stereotype.Component;

/**
 * Asks the AI gateway for memes about an uploaded photo.
 * <ul>
 *   <li>{@link Elaboration#NORMAL}: 1 call. The vision model sees the photo and writes the captions.</li>
 *   <li>{@link Elaboration#HIGH}: 2 calls. The vision model describes the photo in detail, then the text model
 *       (usually the stronger joke writer) writes the captions from that description.</li>
 * </ul>
 */
@Component
public class PhotoCaptionWriter {

    private static final String WRITER_SYSTEM = """
            You are a witty meme writer. You understand people's expressions, body language, situations and the
            vibe of a picture, and you find what is genuinely funny about it. You write short, punchy, relatable
            captions. Avoid hateful or demeaning jokes about protected groups and never mock someone's body or
            appearance in a cruel way. Reply with JSON only, no explanations, no markdown.""";

    private static final String DESCRIBER_SYSTEM = """
            You describe photos for a meme writer who cannot see them. Be concrete and observant: who or what is
            in the picture, facial expressions, body language, the situation, the mood, anything odd or ironic,
            and where the picture has calm empty space for text. Reply with JSON only, no markdown.""";

    private static final String STYLES = """
            Caption styles (pick one per meme):
            - TOP_BOTTOM: classic meme, 2 texts: setup at the top, punchline at the bottom (white text over the photo)
            - TOP: 1 text over the top of the photo
            - BOTTOM: 1 text over the bottom of the photo
            - CAPTION_BAR: 1 text in a white bar above the photo, modern style ("me when...", "nobody: ... me: ...");
              best when the photo has no calm empty area, or when text would cover faces""";

    private static final String RULES = """
            Rules:
            - Write exactly %d memes. Make them different: different jokes and angles, and vary the styles.
            - Choose the style by where the photo has empty space; don't put text over faces.
            - Keep texts short: at most about 8 words per text for TOP_BOTTOM/TOP/BOTTOM, 15 for CAPTION_BAR.
            - Write the texts in %s.""";

    private static final String MEMES_SHAPE =
            "\"memes\":[{\"style\":\"TOP_BOTTOM\",\"texts\":[\"<top>\",\"<bottom>\"]},{\"style\":\"CAPTION_BAR\",\"texts\":[\"<text>\"]}]";

    private static final String ANALYSIS_SHAPE =
            "\"analysis\":{\"description\":\"<what is in the photo, 1-2 sentences>\",\"vibe\":\"<the mood>\","
                    + "\"funny_angles\":[\"<angle 1>\",\"<angle 2>\",\"<angle 3>\"],"
                    + "\"empty_areas\":\"<where text fits without covering faces>\"}";

    private final AiGatewayClient client;
    private final ObjectMapper mapper;

    public PhotoCaptionWriter(AiGatewayClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /** @return the plan and how many AI calls it took */
    public Result write(String imageDataUri, String topic, String language, int count, Elaboration elaboration) {
        return elaboration == Elaboration.HIGH
                ? high(imageDataUri, topic, language, count)
                : normal(imageDataUri, topic, language, count);
    }

    public record Result(PhotoPlan plan, int aiCalls) {
    }

    /** 1 call: the vision model analyses the photo and writes the memes in the same answer. */
    private Result normal(String imageDataUri, String topic, String language, int count) {
        String prompt = """
                %s

                First look closely at the photo, then write %d memes for it.

                %s

                %s

                Respond with JSON exactly in this shape:
                {%s,%s}
                """.formatted(topicLine(topic), count, STYLES, RULES.formatted(count, language),
                ANALYSIS_SHAPE, MEMES_SHAPE);
        PhotoPlan plan = PhotoPlanParser.parse(client.chatWithImage(WRITER_SYSTEM, prompt, imageDataUri), mapper);
        return new Result(requireCaptions(plan), 1);
    }

    /** 2 calls: vision model describes, text model writes. */
    private Result high(String imageDataUri, String topic, String language, int count) {
        String describe = """
                Describe this photo for a meme writer who will not see it.
                %s
                Give at least 5 different funny angles (what is ironic, relatable, exaggerated or absurd about it).

                Respond with JSON exactly in this shape:
                {%s}
                """.formatted(topic == null || topic.isBlank() ? ""
                        : "The memes will be about this topic, so note anything in the photo that connects to it: " + topic,
                ANALYSIS_SHAPE.replace("<angle 3>\"]", "<angle 3>\",\"<angle 4>\",\"<angle 5>\"]")
                        .replace("1-2 sentences", "3-5 sentences: people, expressions, body language, situation"));
        PhotoPlan.Analysis analysis = PhotoPlanParser.parse(
                client.chatWithImage(DESCRIBER_SYSTEM, describe, imageDataUri), mapper).analysis();
        if (analysis.description().isEmpty()) {
            throw new AiGatewayException("The vision model did not describe the photo");
        }

        String write = """
                You can't see the photo, but here is a detailed description of it:
                %s

                %s

                Write %d memes for this photo.

                %s

                %s

                Respond with JSON exactly in this shape:
                {%s}
                """.formatted(describeForWriter(analysis), topicLine(topic), count, STYLES,
                RULES.formatted(count, language), MEMES_SHAPE);
        PhotoPlan captions = PhotoPlanParser.parse(client.chat(WRITER_SYSTEM, write), mapper);
        return new Result(requireCaptions(new PhotoPlan(analysis, captions.captions())), 2);
    }

    private static String topicLine(String topic) {
        if (topic == null || topic.isBlank()) {
            return "No topic was given: decide yourself what is funny about this photo (the expressions, the "
                    + "situation, the vibe) and write captions people would relate to.";
        }
        return "Topic: " + topic.strip() + "\nEvery meme must connect what is in the photo to this topic.";
    }

    private static String describeForWriter(PhotoPlan.Analysis a) {
        StringBuilder sb = new StringBuilder();
        sb.append("- What it shows: ").append(a.description()).append('\n');
        if (!a.vibe().isEmpty()) {
            sb.append("- Vibe: ").append(a.vibe()).append('\n');
        }
        if (!a.funnyAngles().isEmpty()) {
            sb.append("- Funny angles: ").append(String.join("; ", a.funnyAngles())).append('\n');
        }
        if (!a.emptyAreas().isEmpty()) {
            sb.append("- Empty space for text: ").append(a.emptyAreas()).append('\n');
        }
        return sb.toString().strip();
    }

    private static PhotoPlan requireCaptions(PhotoPlan plan) {
        if (plan.captions().isEmpty()) {
            throw new AiGatewayException("The AI did not return any usable captions for the photo");
        }
        return plan;
    }
}
