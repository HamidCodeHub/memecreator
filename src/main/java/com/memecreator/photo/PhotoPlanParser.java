package com.memecreator.photo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.ai.AiGatewayException;
import com.memecreator.ai.CaptionParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the AI's JSON for photo memes:
 * <pre>
 * {"analysis": {"description": "...", "vibe": "...", "funny_angles": ["..."], "empty_areas": "..."},
 *  "memes": [{"style": "TOP_BOTTOM", "texts": ["top", "bottom"]}, {"style": "CAPTION_BAR", "texts": ["..."]}]}
 * </pre>
 * Both parts are optional: the first HIGH call returns only an analysis, the second only memes.
 */
public final class PhotoPlanParser {

    private PhotoPlanParser() {
    }

    public static PhotoPlan parse(String reply, ObjectMapper mapper) {
        JsonNode root = readTree(reply, mapper);
        JsonNode analysisNode = root.has("analysis") ? root.get("analysis") : root;
        PhotoPlan.Analysis analysis = analysis(analysisNode);

        List<PhotoPlan.Caption> captions = new ArrayList<>();
        JsonNode memes = root.isArray() ? root : root.path("memes");
        if (memes.isArray()) {
            for (JsonNode m : memes) {
                List<String> texts = texts(m);
                PhotoPlan.Caption c = new PhotoPlan.Caption(
                        CaptionStyle.parse(m.path("style").asText(null), texts.size()), texts);
                if (c.hasText()) {
                    captions.add(c);
                }
            }
        }
        return new PhotoPlan(analysis, captions);
    }

    private static JsonNode readTree(String reply, ObjectMapper mapper) {
        try {
            return mapper.readTree(CaptionParser.extractJson(reply));
        } catch (Exception e) {
            throw new AiGatewayException("AI reply was not valid JSON. Reply started with: "
                    + CaptionParser.preview(reply), e);
        }
    }

    private static PhotoPlan.Analysis analysis(JsonNode n) {
        if (n == null || !n.isObject()) {
            return PhotoPlan.Analysis.EMPTY;
        }
        List<String> angles = new ArrayList<>();
        JsonNode a = n.has("funny_angles") ? n.get("funny_angles") : n.get("funnyAngles");
        if (a != null && a.isArray()) {
            a.forEach(x -> angles.add(x.asText()));
        }
        return new PhotoPlan.Analysis(
                n.path("description").asText(""),
                n.path("vibe").asText(""),
                angles,
                n.has("empty_areas") ? n.path("empty_areas").asText("") : n.path("emptyAreas").asText(""));
    }

    private static List<String> texts(JsonNode m) {
        List<String> out = new ArrayList<>();
        JsonNode arr = m.get("texts");
        if (arr != null && arr.isArray()) {
            arr.forEach(t -> out.add(t.isNull() ? "" : t.asText()));
        } else if (m.hasNonNull("text")) {
            out.add(m.get("text").asText());
        } else {
            out.add(m.path("top").asText(""));
            out.add(m.path("bottom").asText(""));
        }
        return out;
    }
}
