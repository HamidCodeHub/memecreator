package com.memecreator.harvest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.template.MemeTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * How well known a template is right now: its position in the latest Imgflip top 100 (the last line of
 * storage-dir/imgflip-ranks.jsonl, written by the harvester). Read-only; re-read when the files change.
 * <p>
 * A template is matched by id (harvested Imgflip files and your own "&lt;imgflip id&gt;_&lt;name&gt;.jpg" files carry
 * the Imgflip id) or by name through catalog.json, which records which entry absorbed which Imgflip template.
 * No ranks file (harvest never ran) means no template has a rank.
 */
@Component
public class TemplateRanks {

    private static final Logger log = LoggerFactory.getLogger(TemplateRanks.class);
    private static final String SOURCE = "imgflip";

    private record Ranks(Map<String, Integer> byId, Map<String, Integer> byName) {
        static final Ranks NONE = new Ranks(Map.of(), Map.of());
    }

    private final Path ranksFile;
    private final Path catalogFile;
    private final ObjectMapper mapper;
    private volatile Ranks ranks = Ranks.NONE;
    private volatile long stamp = Long.MIN_VALUE;

    @Autowired
    public TemplateRanks(HarvestProperties props, ObjectMapper mapper) {
        this(Path.of(props.storageDir()), mapper);
    }

    public TemplateRanks(Path storageDir, ObjectMapper mapper) {
        this.ranksFile = storageDir.resolve(SOURCE + "-ranks.jsonl");
        this.catalogFile = storageDir.resolve("catalog.json");
        this.mapper = mapper;
    }

    /** 1 = most popular; empty when the template isn't in the latest top list. */
    public OptionalInt rankOf(MemeTemplate template) {
        refreshIfChanged();
        Ranks r = ranks;
        Integer rank = r.byId().get(template.id());
        if (rank == null) {
            rank = r.byName().get(template.normalizedName());
        }
        return rank == null ? OptionalInt.empty() : OptionalInt.of(rank);
    }

    private void refreshIfChanged() {
        long now = lastModified(ranksFile) * 31 + lastModified(catalogFile);
        if (now == stamp) {
            return;
        }
        synchronized (this) {
            if (now == stamp) {
                return;
            }
            try {
                ranks = read();
            } catch (IOException | RuntimeException e) {
                // keep the previous ranks: a half-written file must not break meme creation
                log.warn("Could not read template ranks from {}: {}", ranksFile, e.getMessage());
            }
            stamp = now;
        }
    }

    private Ranks read() throws IOException {
        if (!Files.isRegularFile(ranksFile)) {
            return Ranks.NONE;
        }
        String last = null;
        for (String line : Files.readAllLines(ranksFile)) {
            if (!line.isBlank()) {
                last = line;
            }
        }
        if (last == null) {
            return Ranks.NONE;
        }
        Map<String, Integer> bySourceId = new HashMap<>();
        int rank = 0;
        for (JsonNode id : mapper.readTree(last).path("ids")) {
            bySourceId.putIfAbsent(id.asText(), ++rank);
        }
        Map<String, Integer> byId = new HashMap<>(bySourceId);
        Map<String, Integer> byName = new HashMap<>();
        if (Files.isRegularFile(catalogFile)) {
            for (JsonNode e : mapper.readTree(catalogFile.toFile()).path("templates")) {
                Integer r = SOURCE.equals(e.path("source").asText()) ? bySourceId.get(e.path("sourceId").asText()) : null;
                for (JsonNode a : e.path("aliases")) {
                    Integer ar = aliasRank(a.path("key").asText(""), bySourceId);
                    if (ar != null) {
                        r = r == null ? ar : Math.min(r, ar);
                        put(byName, MemeTemplate.normalize(a.path("name").asText("")), ar);
                    }
                }
                if (r != null) {
                    put(byId, e.path("id").asText(""), r);
                    put(byName, MemeTemplate.normalize(e.path("name").asText("")), r);
                }
            }
        }
        log.info("Loaded the ranks of {} templates from {}", bySourceId.size(), ranksFile);
        return new Ranks(Map.copyOf(byId), Map.copyOf(byName));
    }

    private static Integer aliasRank(String key, Map<String, Integer> bySourceId) {
        return key.startsWith(SOURCE + ":") ? bySourceId.get(key.substring(SOURCE.length() + 1)) : null;
    }

    private static void put(Map<String, Integer> map, String key, int rank) {
        if (!key.isEmpty()) {
            map.merge(key, rank, Math::min);
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** For tests and diagnostics: the templates of this list that have a rank. */
    public long rankedCount(List<MemeTemplate> templates) {
        return templates.stream().filter(t -> rankOf(t).isPresent()).count();
    }
}
