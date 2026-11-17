package com.memecreator.template;

/**
 * One caption area on a template, in fractions of the image size (0..1) from the top-left corner.
 *
 * @param hint what belongs in this box; shown to the AI so it writes the right kind of text
 */
public record TextBox(double x, double y, double width, double height, TextColor color, VAlign valign, String hint) {

    public enum TextColor {
        /** White text with a black outline, for photos and dark areas. */
        WHITE,
        /** Plain black text, for white or light areas (e.g. Drake's right column). */
        BLACK
    }

    public enum VAlign { TOP, MIDDLE, BOTTOM }

    public TextBox {
        x = clamp(x);
        y = clamp(y);
        width = Math.min(clamp(width), 1 - x);
        height = Math.min(clamp(height), 1 - y);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("text box must have a positive size: " + x + "," + y + "," + width + "," + height);
        }
        color = color == null ? TextColor.WHITE : color;
        valign = valign == null ? VAlign.MIDDLE : valign;
        hint = hint == null ? "" : hint.strip();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
