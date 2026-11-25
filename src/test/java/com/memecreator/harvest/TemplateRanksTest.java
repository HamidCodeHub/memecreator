package com.memecreator.harvest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.template.MemeTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRanksTest {

    @TempDir
    Path tmp;

    private static MemeTemplate t(String id, String name) {
        return new MemeTemplate(id, name, Path.of(id + "_" + name + ".jpg"));
    }

    @Test
    void noHarvestMeansNoRanks() {
        assertThat(new TemplateRanks(tmp, new ObjectMapper()).rankOf(t("181913649", "Drake Hotline Bling")))
                .isEmpty();
    }

    @Test
    void usesTheLatestTopListByIdAndThroughCatalogAliases() throws IOException {
        Files.writeString(tmp.resolve("imgflip-ranks.jsonl"), """
                {"date":"2026-11-23","ids":["87743020","181913649"]}
                {"date":"2026-11-24","ids":["181913649","87743020"]}
                """);
        Files.writeString(tmp.resolve("catalog.json"), """
                {"templates":[
                  {"id":"555","name":"My Drake","source":"local","status":"LOCAL",
                   "aliases":[{"key":"imgflip:181913649","name":"Drake Hotline Bling"}]},
                  {"id":"87743020","name":"Two Buttons","source":"imgflip","sourceId":"87743020","status":"ACTIVE"}
                ]}""");
        TemplateRanks ranks = new TemplateRanks(tmp, new ObjectMapper());

        assertThat(ranks.rankOf(t("181913649", "Whatever"))).hasValue(1);       // own file named after the Imgflip id
        assertThat(ranks.rankOf(t("555", "My Drake"))).hasValue(1);             // local entry that absorbed it
        assertThat(ranks.rankOf(t("42", "Drake Hotline Bling"))).hasValue(1);   // by the alias's name
        assertThat(ranks.rankOf(t("87743020", "Two Buttons"))).hasValue(2);
        assertThat(ranks.rankOf(t("7", "Not Ranked"))).isEmpty();
    }
}
