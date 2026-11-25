package com.memecreator.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * One caption suggestion from the AI: which template, plus one text per text box (in box order).
 *
 * @param face for face memes on templates with several people: the character the AI wants the user's face on
 *             (e.g. "padme"); null otherwise
 */
public record MemeCaption(String template, List<String> texts, String face) {

    public MemeCaption {
        template = template == null ? "" : template.strip();
        texts = texts == null ? List.of() : texts.stream().map(t -> t == null ? "" : t.strip()).toList();
        face = face == null || face.isBlank() ? null : face.strip();
    }

    public MemeCaption(String template, List<String> texts) {
        this(template, texts, null);
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
