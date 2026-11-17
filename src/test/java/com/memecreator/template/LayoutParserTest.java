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
}
