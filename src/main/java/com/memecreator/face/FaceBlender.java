package com.memecreator.face;

import java.awt.image.BufferedImage;

/** Blends a warped face into the template so its colours and lighting fit in. */
public interface FaceBlender {

    /**
     * @param template the image to paste onto
     * @param warped   same size as the template, with the user's face warped into place
     * @param mask     width × height, true where the face goes
     * @return a new image
     */
    BufferedImage blend(BufferedImage template, BufferedImage warped, boolean[] mask);
}
