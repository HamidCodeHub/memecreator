package com.memecreator.face;

import java.awt.image.BufferedImage;
import java.util.List;

/** Finds faces (box + 5 landmarks) in an image. */
public interface FaceDetector {

    /** All faces found, best first. */
    List<DetectedFace> detect(BufferedImage image);
}
