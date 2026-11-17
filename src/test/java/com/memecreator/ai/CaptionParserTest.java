package com.memecreator.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptionParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesTextsWrappedInMarkdownFenceAndChatter() {
        String reply = """
                Sure! Here you go:
                ```json
                {"memes":[
                  {"template":"Drake Hotline Bling","texts":["Writing tests","Testing in prod"]},
                  {"template":"Two Buttons","texts":["Deploy now","Deploy Monday","Me"]}
                ]}
                ```""";

        List<MemeCaption> captions = CaptionParser.parse(reply, mapper);

        assertThat(captions).containsExactly(
                new MemeCaption("Drake Hotline Bling", List.of("Writing tests", "Testing in prod")),
                new MemeCaption("Two Buttons", List.of("Deploy now", "Deploy Monday", "Me")));
    }

    @Test
    void acceptsOldTopBottomShapeAndBareArray() {
        String reply = "[{\"name\":\"Bad Luck Brian\",\"top_text\":\"A\",\"bottom_text\":\"B\"}]";

        assertThat(CaptionParser.parse(reply, mapper))
                .containsExactly(new MemeCaption("Bad Luck Brian", List.of("A", "B")));
    }

    @Test
    void dropsEntriesWithoutText() {
        String reply = "{\"memes\":[{\"template\":\"X\",\"texts\":[\"\",\" \"]}]}";

        assertThat(CaptionParser.parse(reply, mapper)).isEmpty();
    }

    @Test
    void textsForPadsAndTrims() {
        MemeCaption c = new MemeCaption("X", List.of("a", "b", "c"));

        assertThat(c.textsFor(2)).containsExactly("a", "b");
        assertThat(c.textsFor(4)).containsExactly("a", "b", "c", "");
    }

    @Test
    void failsOnNonJson() {
        assertThatThrownBy(() -> CaptionParser.parse("I can't do that", mapper))
                .isInstanceOf(AiGatewayException.class);
    }
}
