package com.memecreator.photo;

/**
 * A part of an uploaded photo that captions must not cover (a face or head, an animal's face, the detail a joke is
 * about), in fractions of the picture (0..1) from its top-left corner.
 *
 * @param source "face" (found by the local face detector) or "ai" (named by the vision model)
 */
public record KeepClearArea(double x, double y, double width, double height, String source) {

    public KeepClearArea {
        double x0 = clamp(x);
        double y0 = clamp(y);
        width = Math.max(0, Math.min(clamp(x + width), 1) - x0);
        height = Math.max(0, Math.min(clamp(y + height), 1) - y0);
        x = x0;
        y = y0;
    }

    public double bottom() {
        return y + height;
    }

    public double area() {
        return width * height;
    }

    /**
     * From the vision model's [ymin, xmin, ymax, xmax] on a 0-1000 scale (Gemini's box format); null when the numbers
     * don't make a box.
     */
    public static KeepClearArea fromBox1000(double ymin, double xmin, double ymax, double xmax) {
        if (ymax <= ymin || xmax <= xmin) {
            return null;
        }
        KeepClearArea a = new KeepClearArea(xmin / 1000, ymin / 1000, (xmax - xmin) / 1000, (ymax - ymin) / 1000, "ai");
        return a.width() > 0 && a.height() > 0 ? a : null;
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
