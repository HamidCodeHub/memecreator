package com.memecreator.photo;

import java.util.Locale;

/** Where the caption goes on an uploaded photo (photos have no hand-made text boxes). */
public enum CaptionStyle {
    /** Classic: setup on top, punchline at the bottom, white outlined text over the photo. */
    TOP_BOTTOM(2),
    /** One text over the top of the photo. */
    TOP(1),
    /** One text over the bottom of the photo. */
    BOTTOM(1),
    /** One text in a white bar added above the photo ("me when ..."), never covers the picture. */
    CAPTION_BAR(1);

    private final int texts;

    CaptionStyle(int texts) {
        this.texts = texts;
    }

    public int texts() {
        return texts;
    }

    /** Lenient parsing of the AI's answer; unknown styles are guessed from the number of texts. */
    public static CaptionStyle parse(String raw, int textCount) {
        if (raw != null) {
            String s = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
            for (CaptionStyle c : values()) {
                if (c.name().equals(s)) {
                    return c;
                }
            }
            if (s.contains("BAR")) {
                return CAPTION_BAR;
            }
        }
        return textCount >= 2 ? TOP_BOTTOM : CAPTION_BAR;
    }
}
