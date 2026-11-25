package com.memecreator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the model's reply into captions. Models sometimes wrap JSON in ```json fences
 * or add a sentence around it, so the outermost JSON object/array is cut out first.
 * Accepts {"texts": [...]} per meme, and the older {"top": .., "bottom": ..} shape. An optional "face" names the
 * character the user's face goes on (face memes only).
 */
public final class CaptionParser {

    private CaptionParser() {
    }

    public static List<MemeCaption> parse(String reply, ObjectMapper mapper) {
        String json = extractJson(reply);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new AiGatewayException("AI reply was not valid JSON. Reply started with: " + preview(reply), e);
        }

        JsonNode items = root.isArray() ? root : root.path("memes");
        if (!items.isArray()) {
            throw new AiGatewayException("AI reply had no \"memes\" array. Reply started with: " + preview(reply));
        }

        List<MemeCaption> captions = new ArrayList<>();
        for (JsonNode item : items) {
            MemeCaption c = new MemeCaption(text(item, "template", "name"), texts(item), text(item, "face", "character"));
            if (c.hasText()) {
                captions.add(c);
            }
        }
        return captions;
    }

    private static List<String> texts(JsonNode item) {
        for (String field : List.of("texts", "captions", "text")) {
            JsonNode arr = item.get(field);
            if (arr != null && arr.isArray()) {
                List<String> out = new ArrayList<>();
                arr.forEach(t -> out.add(t.isNull() ? "" : t.asText()));
                return out;
            }
        }
        return List.of(text(item, "top", "top_text", "topText"), text(item, "bottom", "bottom_text", "bottomText"));
    }

    public static String extractJson(String reply) {
        if (reply == null) {
            return "";
        }
        // some reasoning models (e.g. Qwen) put their thinking in <think>...</think> before the answer
        reply = reply.replaceAll("(?s)<think>.*?</think>", "").strip();
        // ```json ... ``` fences
        if (reply.startsWith("```")) {
            int firstLineEnd = reply.indexOf('\n');
            reply = firstLineEnd < 0 ? reply.substring(3) : reply.substring(firstLineEnd + 1);
            if (reply.endsWith("```")) {
                reply = reply.substring(0, reply.length() - 3);
            }
            reply = reply.strip();
        }
        int obj = reply.indexOf('{');
        int arr = reply.indexOf('[');
        int start = obj < 0 ? arr : (arr < 0 ? obj : Math.min(obj, arr));
        if (start < 0) {
            return reply.strip();
        }
        char close = reply.charAt(start) == '{' ? '}' : ']';
        int end = reply.lastIndexOf(close);
        return end > start ? reply.substring(start, end + 1) : reply.substring(start);
    }

    public static String preview(String reply) {
        if (reply == null) {
            return "<null>";
        }
        String oneLine = reply.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    private static String text(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull()) {
                return v.asText();
            }
        }
        return "";
    }
}
