package com.memecreator.photo;

import com.memecreator.template.TextBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides where the captions of a photo meme go so they never cover a {@link KeepClearArea}:
 * <ul>
 *   <li>Top text goes in the space above the highest keep-clear area, bottom text in the space below the lowest one
 *       (at most the classic 26% of the height each).</li>
 *   <li>When that space is too small for readable text, the text goes into a white bar added above (top text) or
 *       below (bottom text) the photo instead.</li>
 *   <li>CAPTION_BAR is always a bar above the photo. With no keep-clear areas, captions go where they always did.</li>
 * </ul>
 */
final class CaptionPlacer {

    /** The classic top/bottom text boxes. */
    static final double EDGE = 0.02;
    static final double MAX_BOX = 0.26;
    /** Text boxes keep this gap to a keep-clear area. */
    static final double GAP = 0.01;
    /** A box shorter than this (fraction of the height) is never used. */
    static final double MIN_BOX = 0.06;
    /** Readable: a font at least this fraction of the photo's longer side (~53 px on a 1600 px photo). */
    static final double MIN_FONT = 1.0 / 30;

    /** The font size the renderer would use for a text in a box (see MemeRenderer#fontSizeFor). */
    interface FontSizer {
        int sizeFor(String text, TextBox box, int imgW, int imgH);
    }

    /**
     * @param boxes     text boxes over the photo, in fractions of the photo
     * @param texts     the text for each box
     * @param topBar    text of a white bar above the photo, or null
     * @param bottomBar text of a white bar below the photo, or null
     */
    record Placement(List<TextBox> boxes, List<String> texts, String topBar, String bottomBar) {
    }

    private CaptionPlacer() {
    }

    static Placement place(PhotoPlan.Caption c, List<KeepClearArea> keepClear, int imgW, int imgH, FontSizer sizer) {
        List<TextBox> boxes = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        String topBar = null;
        String bottomBar = null;
        List<String> t = c.texts();
        switch (c.style()) {
            case CAPTION_BAR -> topBar = blankToNull(t.get(0));
            case TOP -> topBar = top(t.get(0), keepClear, imgW, imgH, sizer, boxes, texts);
            case BOTTOM -> bottomBar = bottom(t.get(0), keepClear, imgW, imgH, sizer, boxes, texts);
            case TOP_BOTTOM -> {
                topBar = top(t.get(0), keepClear, imgW, imgH, sizer, boxes, texts);
                bottomBar = bottom(t.get(1), keepClear, imgW, imgH, sizer, boxes, texts);
            }
        }
        return new Placement(List.copyOf(boxes), List.copyOf(texts), topBar, bottomBar);
    }

    /** Adds the top text's box, or returns the text when it must go into a bar above the photo. */
    private static String top(String text, List<KeepClearArea> keepClear, int imgW, int imgH, FontSizer sizer,
                              List<TextBox> boxes, List<String> texts) {
        if (text == null || text.isBlank()) {
            return null;
        }
        double limit = keepClear.stream().mapToDouble(KeepClearArea::y).min().orElse(1.0);
        double height = Math.min(MAX_BOX, limit - GAP - EDGE);
        TextBox box = height >= MIN_BOX
                ? new TextBox(0.03, EDGE, 0.94, height, TextBox.TextColor.WHITE, TextBox.VAlign.TOP, "top text")
                : null;
        return use(box, text, imgW, imgH, sizer, boxes, texts, keepClear.isEmpty());
    }

    /** Adds the bottom text's box, or returns the text when it must go into a bar below the photo. */
    private static String bottom(String text, List<KeepClearArea> keepClear, int imgW, int imgH, FontSizer sizer,
                                 List<TextBox> boxes, List<String> texts) {
        if (text == null || text.isBlank()) {
            return null;
        }
        double limit = keepClear.stream().mapToDouble(KeepClearArea::bottom).max().orElse(0.0);
        double height = Math.min(MAX_BOX, 1 - EDGE - (limit + GAP));
        TextBox box = height >= MIN_BOX
                ? new TextBox(0.03, 1 - EDGE - height, 0.94, height, TextBox.TextColor.WHITE, TextBox.VAlign.BOTTOM,
                "bottom text")
                : null;
        return use(box, text, imgW, imgH, sizer, boxes, texts, keepClear.isEmpty());
    }

    /** With nothing to keep clear the classic box is always used, as before; otherwise only when readable. */
    private static String use(TextBox box, String text, int imgW, int imgH, FontSizer sizer, List<TextBox> boxes,
                              List<String> texts, boolean nothingToAvoid) {
        if (box != null && (nothingToAvoid || sizer.sizeFor(text, box, imgW, imgH) >= MIN_FONT * Math.max(imgW, imgH))) {
            boxes.add(box);
            texts.add(text);
            return null;
        }
        return text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
