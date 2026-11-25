package com.memecreator.harvest;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * memegen.link (open source): {@code GET /templates/} lists every template it can render, each with a blank image.
 * Overlaps a lot with Imgflip; the duplicate check keeps one copy of each.
 */
@Component
@Order(2)
public class MemegenSource implements TemplateSource {

    private final HarvestHttp http;
    private final HarvestProperties props;

    public MemegenSource(HarvestHttp http, HarvestProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public String name() {
        return "memegen";
    }

    @Override
    public List<TemplateCandidate> fetch() {
        JsonNode root = http.getJson(props.memegenUrl());
        if (!root.isArray()) {
            throw new IllegalStateException("memegen.link: expected a JSON array of templates");
        }
        List<TemplateCandidate> out = new ArrayList<>();
        for (JsonNode t : root) {
            String id = t.path("id").asText("");
            String blank = t.path("blank").asText("");
            if (id.isBlank() || blank.isBlank()) {
                continue;
            }
            String page = t.path("source").asText(null);
            out.add(new TemplateCandidate(name(), id, t.path("name").asText(id).trim(), blank,
                    page == null || page.isBlank() ? null : page, t.path("lines").asInt(0), 0, 0));
        }
        return out;
    }
}
