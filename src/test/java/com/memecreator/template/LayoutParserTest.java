package com.memecreator.template;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayoutParserTest {

    @Test
    void parsesBundledLayoutFile() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/template-layouts.yml")) {
            LayoutParser.Parsed p = LayoutParser.parse(in);

            TemplateLayout drake = p.layouts().get(MemeTemplate.normalize("Drake Hotline Bling"));
            assertThat(drake.custom()).isTrue();
            assertThat(drake.boxes()).hasSize(2);
            assertThat(drake.boxes().get(0).x()).isEqualTo(0.52);
            assertThat(drake.boxes().get(0).color()).isEqualTo(TextBox.TextColor.BLACK);
            assertThat(p.defaultLayout().custom()).isFalse();
            assertThat(p.defaultLayout().boxes()).hasSize(2);
        }
    }

    @Test
    void readsFaceSlotsAndFaceOnlyEntries() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/template-layouts.yml")) {
            LayoutParser.Parsed p = LayoutParser.parse(in);

            TemplateLayout harold = p.layouts().get(MemeTemplate.normalize("Hide the Pain Harold"));
            assertThat(harold.faces()).hasSize(2);
            assertThat(harold.custom()).isFalse();                       // faces only: classic captions
            assertThat(harold.boxes()).hasSize(p.defaultLayout().boxes().size());
            assertThat(p.layouts().get(MemeTemplate.normalize("Drake Hotline Bling")).faces()).hasSize(2);
            assertThat(p.layouts().get(MemeTemplate.normalize("Two Buttons")).hasFaces()).isFalse();
        }
    }

    @Test
    void faceNeedsFourteenNumbers() {
        assertThatThrownBy(() -> parse("""
                layouts:
                  Harold:
                    faces:
                      - [0.1, 0.1, 0.2, 0.2]
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout 'Harold' face 1");
    }

    @Test
    void readsCharactersOfMultiPersonTemplates() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/template-layouts.yml")) {
            LayoutParser.Parsed p = LayoutParser.parse(in);

            TemplateLayout ap = p.layouts().get(MemeTemplate.normalize("Anakin Padme 4 Panel"));
            assertThat(ap.characters()).containsOnlyKeys("anakin", "padme");
            assertThat(ap.defaultCharacter()).isEqualTo("anakin");
            assertThat(ap.slotsOf("anakin")).containsExactly(1, 3);
            assertThat(ap.slotsOf("padme")).containsExactly(2, 4);
            assertThat(ap.findCharacter("PADME")).contains("padme");
            assertThat(ap.findCharacter("vader")).isEmpty();
            assertThat(ap.describeCharactersForAi()).startsWith("anakin = Anakin");

            TemplateLayout harold = p.layouts().get(MemeTemplate.normalize("Hide the Pain Harold"));
            assertThat(harold.hasCharacters()).isFalse();             // the same man in both panels
            assertThat(harold.defaultCharacter()).isNull();
            assertThat(harold.slotsOf(null)).containsExactly(1, 2);
        }
    }

    @Test
    void faceCharacterMustBeListed() {
        assertThatThrownBy(() -> parse("""
                layouts:
                  Duo:
                    characters:
                      a: "first"
                      b: "second"
                    faces:
                      - [0.1, 0.1, 0.2, 0.2, 0.1, 0.1, 0.2, 0.1, 0.15, 0.15, 0.1, 0.2, 0.2, 0.2, a]
                      - [0.5, 0.1, 0.2, 0.2, 0.5, 0.1, 0.6, 0.1, 0.55, 0.15, 0.5, 0.2, 0.6, 0.2, c]
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout 'Duo' face 2")
                .hasMessageContaining("unknown character 'c'");
        assertThatThrownBy(() -> parse("""
                layouts:
                  Duo:
                    characters:
                      a: "first"
                      b: "second"
                    faces:
                      - [0.1, 0.1, 0.2, 0.2, 0.1, 0.1, 0.2, 0.1, 0.15, 0.15, 0.1, 0.2, 0.2, 0.2, a]
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("character 'b' has no face slot");
    }

    @Test
    void matchesNamesIgnoringCaseAndPunctuation() {
        LayoutParser.Parsed p = parse("""
                layouts:
                  "Gru's Plan":
                    boxes:
                      - [0.1, 0.1, 0.5, 0.5, black, top, "step 1"]
                """);

        assertThat(p.layouts()).containsKey(MemeTemplate.normalize("grus plan"));
        assertThat(p.layouts().get(MemeTemplate.normalize("GRU'S PLAN")).describeForAi())
                .isEqualTo("1 text: 1) step 1");
    }

    @Test
    void reportsWhichBoxIsBroken() {
        assertThatThrownBy(() -> parse("""
                layouts:
                  Drake:
                    boxes:
                      - [0.1, 0.1, 0.5, 0.5, purple, top, "x"]
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout 'Drake' box 1");
    }

    private static LayoutParser.Parsed parse(String yaml) {
        return LayoutParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsDuplicatesAsHiddenNameToKeptName() {
        LayoutParser.Parsed p = LayoutParser.parse(new ByteArrayInputStream("""
                duplicates:
                  "Drake Hotline Bling": ["Drake Blank", "No - Yes"]
                  "Megamind no bitches": "Megamind peeking"
                layouts: {}
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(p.duplicates()).containsOnly(
                java.util.Map.entry("drakeblank", "drakehotlinebling"),
                java.util.Map.entry("noyes", "drakehotlinebling"),
                java.util.Map.entry("megamindpeeking", "megamindnobitches"));
    }

    @Test
    void aTemplateCantBeBothKeptAndHidden() {
        assertThatThrownBy(() -> LayoutParser.parse(new ByteArrayInputStream("""
                duplicates:
                  "A": ["B"]
                  "B": ["C"]
                """.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both kept and hidden");
    }

}
