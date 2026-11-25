package com.memecreator.face;

/** Separates a person (with hair, hats, clothes) from the background: one 513×513 image in, a coarse mask out. */
public interface PersonSegmenter {

    int INPUT_SIZE = 513;
    /** The mask has one cell every 16 input pixels: cell (i, j) is input pixel (16·i, 16·j). */
    int OUTPUT_SIZE = 33;
    int STRIDE = 16;

    /**
     * @param nhwc 1×513×513×3 floats: RGB per pixel, values −1..1, rows top to bottom
     * @return 33×33 person logits (before the sigmoid), row-major
     */
    float[] segment(float[] nhwc);
}
