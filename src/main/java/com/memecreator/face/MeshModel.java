package com.memecreator.face;

/** The face-mesh neural network: one 192×192 face crop in, 468 points out. */
public interface MeshModel {

    int INPUT_SIZE = 192;

    /**
     * @param nchw 1×3×192×192 floats: RGB planes, values 0..1
     * @return 1405 floats: x, y, z of the 468 points in crop pixels, then the raw face score (before the sigmoid)
     */
    float[] infer(float[] nchw);
}
