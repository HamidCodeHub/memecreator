package com.memecreator.ai;

import java.util.ArrayList;
import java.util.List;

/** One caption suggestion from the AI: which template, plus one text per text box (in box order). */
public record MemeCaption(String template, List<String> texts) {

    public MemeCaption {
        template = template == null ? "" : template.strip();
        texts = texts == null ? List.of() : texts.stream().map(t -> t == null ? "" : t.strip()).toList();
    }

    public boolean hasText() {
        return texts.stream().anyMatch(t -> !t.isEmpty());
    }

    /** Exactly {@code boxes} texts: extra ones are dropped, missing ones are empty. */
    public List<String> textsFor(int boxes) {
        List<String> out = new ArrayList<>(texts.subList(0, Math.min(boxes, texts.size())));
        while (out.size() < boxes) {
            out.add("");
        }
        return out;
    }
}
