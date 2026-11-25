package com.memecreator.face;

import com.memecreator.template.FaceSlot;

/**
 * A face in pixel coordinates: its box and 5 landmarks (both eyes, nose tip, both mouth corners), in the order
 * OpenCV's YuNet detector reports them.
 *
 * @param landmarks 10 numbers: x0, y0, x1, y1, ... x4, y4
 */
public record DetectedFace(double x, double y, double width, double height, double[] landmarks, double score) {

    public DetectedFace {
        if (landmarks == null || landmarks.length != 10) {
            throw new IllegalArgumentException("a face needs 5 landmarks (10 numbers)");
        }
        landmarks = landmarks.clone();
    }

    public double area() {
        return width * height;
    }

    public double lx(int i) {
        return landmarks[2 * i];
    }

    public double ly(int i) {
        return landmarks[2 * i + 1];
    }

    /**
     * The same face in the image flipped left-to-right: the box moves, and the eyes and mouth corners swap places
     * so landmark 0 is still the eye on the image's left.
     */
    public DetectedFace mirrored(int imageWidth) {
        double[] lm = new double[10];
        int[] from = {1, 0, 2, 4, 3};
        for (int i = 0; i < 5; i++) {
            lm[2 * i] = imageWidth - lx(from[i]);
            lm[2 * i + 1] = ly(from[i]);
        }
        return new DetectedFace(imageWidth - x - width, y, width, height, lm, score);
    }

    /** A template's face slot converted to pixels for an image of the given size. */
    public static DetectedFace fromSlot(FaceSlot slot, int imageWidth, int imageHeight) {
        double[] lm = new double[10];
        for (int i = 0; i < 5; i++) {
            double[] p = slot.landmarkPx(i, imageWidth, imageHeight);
            lm[2 * i] = p[0];
            lm[2 * i + 1] = p[1];
        }
        return new DetectedFace(slot.x() * imageWidth, slot.y() * imageHeight,
                slot.width() * imageWidth, slot.height() * imageHeight, lm, 1.0);
    }
}
