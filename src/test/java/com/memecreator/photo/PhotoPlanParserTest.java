package com.memecreator.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.ai.AiGatewayException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoPlanParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesAnalysisAndMemes() {
        String reply = """
                <think>the guy looks tired, maybe a monday joke</think>
                {"analysis":{"description":"A man asleep on a keyboard","vibe":"exhausted",
                             "funny_angles":["monday","deadlines"],"empty_areas":"top"},
                 "memes":[{"style":"TOP_BOTTOM","texts":["Me at 9 AM","Me at 9:05 AM"]},
                          {"style":"caption bar","texts":["me after one meeting"]}]}""";

        PhotoPlan plan = PhotoPlanParser.parse(reply, mapper);

        assertThat(plan.analysis().description()).isEqualTo("A man asleep on a keyboard");
        assertThat(plan.analysis().funnyAngles()).containsExactly("monday", "deadlines");
        assertThat(plan.captions()).containsExactly(
                new PhotoPlan.Caption(CaptionStyle.TOP_BOTTOM, List.of("Me at 9 AM", "Me at 9:05 AM")),
                new PhotoPlan.Caption(CaptionStyle.CAPTION_BAR, List.of("me after one meeting")));
    }

    @Test
    void normalizesTextCountsToTheStyle() {
        assertThat(new PhotoPlan.Caption(CaptionStyle.TOP_BOTTOM, List.of("", "only punchline")))
                .isEqualTo(new PhotoPlan.Caption(CaptionStyle.BOTTOM, List.of("only punchline")));
        assertThat(new PhotoPlan.Caption(CaptionStyle.CAPTION_BAR, List.of("me when", "the build fails")).texts())
                .containsExactly("me when the build fails");
        assertThat(new PhotoPlan.Caption(null, List.of("a", "b")).style()).isEqualTo(CaptionStyle.TOP_BOTTOM);
    }

    @Test
    void analysisOnlyReplyHasNoCaptions() {
        PhotoPlan plan = PhotoPlanParser.parse("{\"analysis\":{\"description\":\"a cat\"}}", mapper);

        assertThat(plan.analysis().description()).isEqualTo("a cat");
        assertThat(plan.captions()).isEmpty();
    }

    @Test
    void failsOnNonJson() {
        assertThatThrownBy(() -> PhotoPlanParser.parse("sorry, I can't see images", mapper))
                .isInstanceOf(AiGatewayException.class);
    }

    @Test
    void readsKeepClearBoxesOnAThousandScale() {
        PhotoPlan plan = PhotoPlanParser.parse("""
                {"analysis":{"description":"a statue","keep_clear":[[50,450,200,640],{"box_2d":[400,130,560,420]},
                  [0,0,900,900],[10,20],"oops",[300,300,200,400]]},
                 "memes":[{"style":"BOTTOM","texts":["x"]}]}""", mapper);

        assertThat(plan.keepClear()).hasSize(2);   // the whole-photo box and the broken ones are dropped
        KeepClearArea head = plan.keepClear().get(0);
        assertThat(head.y()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(head.x()).isCloseTo(0.45, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(head.bottom()).isCloseTo(0.20, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(head.source()).isEqualTo("ai");
    }

    @Test
    void noKeepClearMeansNone() {
        assertThat(PhotoPlanParser.parse("{\"memes\":[{\"style\":\"TOP\",\"texts\":[\"x\"]}]}", mapper)
                .keepClear()).isEmpty();
    }
}
