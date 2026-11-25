package com.memecreator.harvest;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Imgflip's free {@code get_memes}: the ~100 templates captioned most in the last 30 days, most popular first.
 * Polled daily, it is how new trending formats reach the app (with a delay of days to weeks after they go viral).
 * No account or key needed.
 */
@Component
@Order(1)
public class ImgflipSource implements TemplateSource {

    private final HarvestHttp http;
    private final HarvestProperties props;

    public ImgflipSource(HarvestHttp http, HarvestProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public String name() {
        return "imgflip";
    }

    @Override
    public boolean ranked() {
        return true;
    }

    @Override
    public List<TemplateCandidate> fetch() {
        JsonNode root = http.getJson(props.imgflipUrl());
        if (!root.path("success").asBoolean(false)) {
            throw new IllegalStateException("Imgflip answered: " + root.path("error_message").asText("success=false"));
        }
        List<TemplateCandidate> out = new ArrayList<>();
        int rank = 0;
        for (JsonNode m : root.path("data").path("memes")) {
            rank++;
            String id = m.path("id").asText("");
            String url = m.path("url").asText("");
            if (id.isBlank() || url.isBlank()) {
                continue;
            }
            out.add(new TemplateCandidate(name(), id, m.path("name").asText(id).trim(), url,
                    null, m.path("box_count").asInt(0), rank,
                    m.path("captions").asLong(0)));
        }
        return out;
    }
}
