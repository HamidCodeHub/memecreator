package com.memecreator.template;

import com.memecreator.config.MemeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRepositoryTest {

    @TempDir
    Path tmp;

    private TemplateRepository repository(String... files) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("meme"));
        for (String f : files) {
            Files.createFile(dir.resolve(f));   // only the names matter here
        }
        Path layouts = tmp.resolve("layouts.yml");
        Files.writeString(layouts, """
                duplicates:
                  "Drake Hotline Bling": ["Drake Blank", "No - Yes"]
                  "Megamind no bitches": ["Megamind peeking"]
                layouts: {}
                """);
        MemeProperties props = new MemeProperties(dir.toString(), tmp.resolve("out").toString(), 4, "English",
                List.of("Arial"), 0.92f, layouts.toUri().toString(), tmp.resolve("private").toString(),
                Duration.ofDays(90));
        return new TemplateRepository(props, null, new LayoutRepository(props, new DefaultResourceLoader()));
    }

    private static List<String> names(List<MemeTemplate> templates) {
        return templates.stream().map(MemeTemplate::name).toList();
    }

    @Test
    void hidesDuplicatesAndAnswersTheirNamesWithTheKeptTemplate() throws IOException {
        TemplateRepository repo = repository("1_Drake Hotline Bling.jpg", "2_Drake Blank.jpg", "3_No - Yes.png",
                "4_Two Buttons.jpg");

        assertThat(names(repo.findAll())).containsExactly("Drake Hotline Bling", "Two Buttons");
        assertThat(repo.findByName("drake blank")).map(MemeTemplate::name).hasValue("Drake Hotline Bling");
        assertThat(repo.findByName("No - Yes")).map(MemeTemplate::name).hasValue("Drake Hotline Bling");
    }

    @Test
    void keepsADuplicateWhenTheTemplateToKeepIsMissing() throws IOException {
        TemplateRepository repo = repository("4_Megamind peeking.jpg", "5_Two Buttons.jpg");

        assertThat(names(repo.findAll())).containsExactly("Megamind peeking", "Two Buttons");
    }
}
