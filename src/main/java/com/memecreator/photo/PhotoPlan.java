package com.memecreator.photo;

import java.util.ArrayList;
import java.util.List;

/**
 * What the AI made of an uploaded photo: its reading of the picture, the captions to render, and the parts of the
 * picture captions must not cover.
 */
public record PhotoPlan(Analysis analysis, List<Caption> captions, List<KeepClearArea> keepClear) {

    public PhotoPlan {
        analysis = analysis == null ? Analysis.EMPTY : analysis;
        captions = captions == null ? List.of() : List.copyOf(captions);
        keepClear = keepClear == null ? List.of() : List.copyOf(keepClear);
    }

    public PhotoPlan(Analysis analysis, List<Caption> captions) {
        this(analysis, captions, List.of());
    }

    /** The AI's reading of the photo, returned to the caller so they can see what it understood. */
    public record Analysis(String description, String vibe, List<String> funnyAngles, String emptyAreas) {

        public static final Analysis EMPTY = new Analysis("", "", List.of(), "");

        public Analysis {
            description = description == null ? "" : description.strip();
            vibe = vibe == null ? "" : vibe.strip();
            funnyAngles = funnyAngles == null ? List.of() : List.copyOf(funnyAngles);
            emptyAreas = emptyAreas == null ? "" : emptyAreas.strip();
        }
    }

    /** One meme: a style and exactly {@code style.texts()} texts. */
    public record Caption(CaptionStyle style, List<String> texts) {

        public Caption {
            List<String> t = new ArrayList<>();
            if (texts != null) {
                texts.stream().map(s -> s == null ? "" : s.strip()).forEach(t::add);
            }
            if (style == null) {
                style = CaptionStyle.parse(null, t.size());
            }
            // a TOP_BOTTOM meme with only one text works better as a single caption at the bottom
            if (style == CaptionStyle.TOP_BOTTOM && t.stream().filter(s -> !s.isEmpty()).count() == 1) {
                t = new ArrayList<>(t.stream().filter(s -> !s.isEmpty()).toList());
                style = CaptionStyle.BOTTOM;
            }
            // more texts than the style takes: join them rather than lose the punchline
            if (t.size() > style.texts()) {
                List<String> head = new ArrayList<>(t.subList(0, style.texts() - 1));
                head.add(String.join(" ", t.subList(style.texts() - 1, t.size())).strip());
                t = head;
            }
            while (t.size() < style.texts()) {
                t.add("");
            }
            texts = List.copyOf(t);
        }

        public boolean hasText() {
            return texts.stream().anyMatch(s -> !s.isEmpty());
        }
    }
}
