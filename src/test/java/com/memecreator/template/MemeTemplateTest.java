package com.memecreator.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemeTemplateTest {

    @Test
    void splitsIdAndName() {
        MemeTemplate t = MemeTemplate.fromPath(Path.of("181913649_Drake Hotline Bling.jpg"));
        assertThat(t.id()).isEqualTo("181913649");
        assertThat(t.name()).isEqualTo("Drake Hotline Bling");
    }

    @Test
    void keepsUnderscoresInsideName() {
        MemeTemplate t = MemeTemplate.fromPath(Path.of("208915813_George Bush 9_11.jpg"));
        assertThat(t.name()).isEqualTo("George Bush 9_11");
    }

    @Test
    void fileWithoutIdUsesWholeNameAsId() {
        MemeTemplate t = MemeTemplate.fromPath(Path.of("My Own Template.png"));
        assertThat(t.id()).isEqualTo("My Own Template");
        assertThat(t.name()).isEqualTo("My Own Template");
    }

    @Test
    void normalizationIgnoresCaseAndPunctuation() {
        assertThat(MemeTemplate.normalize("You Guys Are Getting Paid?"))
                .isEqualTo(MemeTemplate.normalize("You Guys are Getting Paid"));
    }
}
