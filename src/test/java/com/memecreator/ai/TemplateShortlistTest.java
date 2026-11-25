package com.memecreator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.harvest.TemplateRanks;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateShortlistTest {

    @TempDir
    Path tmp;

    private final LayoutRepository layouts = TestTemplates.layouts();
    private final List<MemeTemplate> pool = TestTemplates.pool(250);

    private TemplateShortlist shortlist(PromptProperties props) {
        return new TemplateShortlist(layouts, new TemplateRanks(tmp, new ObjectMapper()), props);
    }

    private TemplateShortlist shortlist() {
        return shortlist(new PromptProperties(15, 3, 6, 160));
    }

    private static List<String> names(List<MemeTemplate> templates) {
        return templates.stream().map(MemeTemplate::name).toList();
    }

    @Test
    void listsFifteenAndNeverFewerThanTheMemesAskedFor() {
        assertThat(shortlist().pick("Inter lost the derby against Milan again", pool, 6, new Random(1)))
                .hasSize(15).doesNotHaveDuplicates().isSubsetOf(pool);
        assertThat(shortlist(new PromptProperties(5, 3, 6, 160)).pick("exams", pool, 12, new Random(2)))
                .hasSize(12);
    }

    @Test
    void aSmallPoolIsListedWhole() {
        List<MemeTemplate> small = pool.subList(0, 10);
        assertThat(shortlist().pick("anything", small, 4, new Random(3))).isSameAs(small);
    }

    @Test
    void aTopicWordInTheNameEarnsAPlace() {
        for (int seed = 0; seed < 20; seed++) {
            assertThat(names(shortlist().pick("my code works on my machine but not in production", pool, 6,
                    new Random(seed)))).contains("An Older Code Sir, But It Checks Out");
        }
    }

    @Test
    void templatesWithoutTheirOwnTextBoxesOnlyComeInByName() {
        for (int seed = 0; seed < 20; seed++) {
            assertThat(names(shortlist().pick("Inter lost the derby against Milan again", pool, 6, new Random(seed))))
                    .noneMatch(n -> n.startsWith("Harvested Meme"));
            assertThat(names(shortlist().pick(null, pool, 6, new Random(seed))))
                    .noneMatch(n -> n.startsWith("Harvested Meme"));
        }
        // only through the keyword bonus, so at most keywordSlots of them
        assertThat(names(shortlist().pick("harvested meme", pool, 6, new Random(7))))
                .filteredOn(n -> n.startsWith("Harvested Meme")).hasSizeBetween(1, 3);
    }

    @Test
    void templatesWithIdenticalTextBoxesCountOnce() throws IOException {
        // two names for the same meme, plus 20 different templates
        StringBuilder yml = new StringBuilder("""
                layouts:
                  "Drake Hotline Bling":
                    boxes: [[0.5, 0.0, 0.5, 0.5, black, middle, "the thing Drake rejects"],
                            [0.5, 0.5, 0.5, 0.5, black, middle, "the thing Drake approves"]]
                  "Drake Blank":
                    boxes: [[0.5, 0.0, 0.5, 0.5, black, middle, "the thing Drake rejects"],
                            [0.5, 0.5, 0.5, 0.5, black, middle, "the thing Drake approves"]]
                """);
        List<MemeTemplate> small = new ArrayList<>(List.of(TestTemplates.template("1", "Drake Hotline Bling"),
                TestTemplates.template("2", "Drake Blank")));
        for (int i = 1; i <= 20; i++) {
            yml.append("  \"Other ").append(i).append("\":\n    boxes: [[0.0, 0.0, 1.0, 0.3, white, top, \"joke ")
                    .append(i).append("\"]]\n");
            small.add(TestTemplates.template(String.valueOf(100 + i), "Other " + i));
        }
        Path file = Files.writeString(tmp.resolve("layouts.yml"), yml);
        LayoutRepository custom = TestTemplates.layouts(file.toUri().toString());
        TemplateShortlist s = new TemplateShortlist(custom, new TemplateRanks(tmp, new ObjectMapper()),
                new PromptProperties(15, 3, 6, 160));
        for (int seed = 0; seed < 30; seed++) {
            assertThat(names(s.pick("football", small, 6, new Random(seed))))
                    .filteredOn(n -> n.startsWith("Drake")).hasSize(1);
        }
    }

    @Test
    void wellKnownTemplatesAreListed() throws IOException {
        MemeTemplate a = TestTemplates.named(pool, "Distracted Boyfriend");
        MemeTemplate b = TestTemplates.named(pool, "Woman Yelling At Cat");
        MemeTemplate c = TestTemplates.named(pool, "Drake Hotline Bling");
        Files.writeString(tmp.resolve("imgflip-ranks.jsonl"), """
                {"date":"2026-11-23","ids":["0"]}
                {"date":"2026-11-24","ids":["%s","%s","%s"]}
                """.formatted(a.id(), b.id(), c.id()));
        for (int seed = 0; seed < 20; seed++) {
            // 6 popular slots, only 3 ranked templates: all of them, every time
            assertThat(shortlist().pick("pizza", pool, 6, new Random(seed))).contains(a, b, c);
        }
    }
}
