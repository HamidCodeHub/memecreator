package com.memecreator.template;

import java.util.List;

/**
 * Where a face sits on a template, as fractions of the image (0..1): the face box plus 5 landmarks
 * (both eyes, nose tip, both mouth corners), in the order OpenCV's YuNet detector reports them.
 * A user's face is aligned to these landmarks when it is pasted.
 *
 * @param character who this face belongs to (e.g. "padme"), for templates with several people; null when the
 *                  template doesn't name its characters (then every slot is the same person)
 */
public record FaceSlot(double x, double y, double width, double height, List<Double> landmarks, String character) {

    public FaceSlot {
        if (landmarks == null || landmarks.size() != 10) {
            throw new IllegalArgumentException("a face slot needs 10 landmark numbers (5 x,y points)");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a face slot needs a positive size");
        }
        landmarks = List.copyOf(landmarks);
        character = character == null || character.isBlank() ? null : character.strip();
    }

    public FaceSlot(double x, double y, double width, double height, List<Double> landmarks) {
        this(x, y, width, height, landmarks, null);
    }

    /** Landmark i (0..4) in pixels for an image of the given size. */
    public double[] landmarkPx(int i, int imageWidth, int imageHeight) {
        return new double[]{landmarks.get(2 * i) * imageWidth, landmarks.get(2 * i + 1) * imageHeight};
    }
}
