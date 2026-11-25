package com.memecreator.photo;

import com.memecreator.template.TextBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class CaptionPlacerTest {

    private static final int W = 1200;
    private static final int H = 1600;
    /** Pretends text is readable when its box is at least 10% of the height: 60 px font, else 20 px. */
    private static final CaptionPlacer.FontSizer SIZER = (text, box, w, h) -> box.height() >= 0.10 ? 60 : 20;

    private static PhotoPlan.Caption caption(CaptionStyle style, String... texts) {
        return new PhotoPlan.Caption(style, List.of(texts));
    }

    private static KeepClearArea head(double y, double height) {
        return new KeepClearArea(0.45, y, 0.15, height, "face");
    }

    private static CaptionPlacer.Placement place(PhotoPlan.Caption c, KeepClearArea... keepClear) {
        return CaptionPlacer.place(c, List.of(keepClear), W, H, SIZER);
    }

    @Test
    void withNothingToKeepClearCaptionsGoWhereTheyAlwaysDid() {
        CaptionPlacer.Placement p = place(caption(CaptionStyle.TOP_BOTTOM, "setup", "punchline"));

        assertThat(p.boxes()).hasSize(2);
        assertThat(p.boxes().get(0).y()).isCloseTo(0.02, offset(1e-9));
        assertThat(p.boxes().get(0).height()).isCloseTo(0.26, offset(1e-9));
        assertThat(p.boxes().get(1).y()).isCloseTo(0.72, offset(1e-9));
        assertThat(p.boxes().get(1).height()).isCloseTo(0.26, offset(1e-9));
        assertThat(p.texts()).containsExactly("setup", "punchline");
        assertThat(p.topBar()).isNull();
        assertThat(p.bottomBar()).isNull();
    }

    @Test
    void aHeadAtTheTopSendsTheTopTextIntoABar() {
        // the statue: head from 5% to 20% of the height
        CaptionPlacer.Placement p = place(caption(CaptionStyle.TOP_BOTTOM, "setup", "punchline"), head(0.05, 0.15));

        assertThat(p.topBar()).isEqualTo("setup");
        assertThat(p.texts()).containsExactly("punchline");
        assertThat(p.boxes()).singleElement().satisfies(b -> assertThat(b.y()).isCloseTo(0.72, offset(1e-9)));
        assertThat(p.bottomBar()).isNull();
    }

    @Test
    void topTextShrinksIntoTheSpaceAboveAHead() {
        CaptionPlacer.Placement p = place(caption(CaptionStyle.TOP, "setup"), head(0.18, 0.20));

        TextBox box = p.boxes().get(0);
        assertThat(box.y()).isCloseTo(0.02, offset(1e-9));
        assertThat(box.y() + box.height()).isLessThanOrEqualTo(0.18 - CaptionPlacer.GAP + 1e-9);
        assertThat(p.topBar()).isNull();
    }

    @Test
    void roomThatIsTooSmallForReadableTextMeansABar() {
        // 0.02..0.11: a box of 0.08, enough to exist but the text would be too small
        CaptionPlacer.Placement p = place(caption(CaptionStyle.TOP, "a long setup"), head(0.11, 0.20));

        assertThat(p.boxes()).isEmpty();
        assertThat(p.topBar()).isEqualTo("a long setup");
    }

    @Test
    void aHeadAtTheBottomSendsTheBottomTextIntoABarBelow() {
        CaptionPlacer.Placement p = place(caption(CaptionStyle.BOTTOM, "punchline"), head(0.70, 0.25));

        assertThat(p.boxes()).isEmpty();
        assertThat(p.bottomBar()).isEqualTo("punchline");
        assertThat(p.topBar()).isNull();
    }

    @Test
    void theCaptionBarStyleIsAlwaysABarAbove() {
        CaptionPlacer.Placement p = place(caption(CaptionStyle.CAPTION_BAR, "me when"), head(0.40, 0.20));

        assertThat(p.boxes()).isEmpty();
        assertThat(p.topBar()).isEqualTo("me when");
        assertThat(p.bottomBar()).isNull();
    }

    @Test
    void textNeverOverlapsAKeepClearArea() {
        KeepClearArea[] heads = {head(0.03, 0.10), head(0.15, 0.30), head(0.50, 0.30), head(0.62, 0.30)};
        for (KeepClearArea h : heads) {
            CaptionPlacer.Placement p = place(caption(CaptionStyle.TOP_BOTTOM, "setup", "punchline"), h);
            for (TextBox b : p.boxes()) {
                boolean apart = b.y() + b.height() <= h.y() || b.y() >= h.bottom();
                assertThat(apart).as("box %s vs head %s", b, h).isTrue();
            }
        }
    }
}
