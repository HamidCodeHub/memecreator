package com.memecreator.face;

/** The two classic image operations the head cut-out needs (OpenCV in the app). Pixels are packed 0xRRGGBB. */
public interface ImageOps {

    /** GrabCut labels, as OpenCV numbers them. */
    byte BACKGROUND = 0;
    byte FOREGROUND = 1;
    byte PROBABLY_BACKGROUND = 2;
    byte PROBABLY_FOREGROUND = 3;

    /**
     * Splits foreground from background by colour (GrabCut), starting from a labelling.
     *
     * @param labels w × h, one of the four labels per pixel
     * @return true where the pixel ends up foreground
     */
    boolean[] grabCut(int[] rgb, int w, int h, byte[] labels, int iterations);

    /** Fills the pixels where {@code mask} is set from their surroundings (Telea inpainting). Returns new pixels. */
    int[] inpaint(int[] rgb, int w, int h, boolean[] mask, double radius);
}
