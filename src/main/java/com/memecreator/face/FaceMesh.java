package com.memecreator.face;

/**
 * The 468 points of the MediaPipe face mesh, in image pixels. The point numbering is anatomical (point 1 is always
 * the nose tip, whatever the photo), which is what lets one face be warped onto another triangle by triangle.
 *
 * @param points 936 numbers: x0, y0, x1, y1, ...
 * @param score  0..1, how sure the model is that this is a face
 */
public record FaceMesh(double[] points, double score) {

    public static final int SIZE = 468;
    static final int NOSE_TIP = 1;
    /** Edge of the cheek on the image's left side of a frontal face (the person's right). */
    static final int CHEEK_A = 234;
    /** Edge of the cheek on the image's right side of a frontal face (the person's left). */
    static final int CHEEK_B = 454;
    /** Outer corner of the eye on the image's left side of a frontal face. */
    static final int EYE_A = 33;
    /** Outer corner of the other eye. */
    static final int EYE_B = 263;

    public FaceMesh {
        if (points == null || points.length != 2 * SIZE) {
            throw new IllegalArgumentException("a face mesh has " + SIZE + " points");
        }
        points = points.clone();
    }

    /** A copy: 936 numbers x0, y0, x1, y1, ... */
    @Override
    public double[] points() {
        return points.clone();
    }

    public double x(int i) {
        return points[2 * i];
    }

    public double y(int i) {
        return points[2 * i + 1];
    }

    /**
     * Which way the face is turned, from where the nose tip sits between the two cheek edges along the cheek line:
     * 0 = looking straight at the camera, about ±1 = in profile; the sign tells left from right in the image.
     */
    public double yaw() {
        double ax = x(CHEEK_A), ay = y(CHEEK_A);
        double bx = x(CHEEK_B), by = y(CHEEK_B);
        double len2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay);
        if (len2 < 1e-9) {
            return 0;
        }
        double t = ((x(NOSE_TIP) - ax) * (bx - ax) + (y(NOSE_TIP) - ay) * (by - ay)) / len2;
        return 2 * t - 1;
    }

    /** Distance between the cheek edges: a size for the face that works at any angle. */
    public double width() {
        return Math.hypot(x(CHEEK_B) - x(CHEEK_A), y(CHEEK_B) - y(CHEEK_A));
    }

    /** Angle of the line through the outer eye corners (0 = level). */
    public double eyeAngle() {
        return Math.atan2(y(EYE_B) - y(EYE_A), x(EYE_B) - x(EYE_A));
    }

    /**
     * The box around the mesh in the face's own frame (rotated by {@code angle}):
     * {centreX, centreY, width, height}, centre in image coordinates.
     */
    public double[] alignedBox(double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double minU = Double.MAX_VALUE, minV = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int i = 0; i < SIZE; i++) {
            double u = x(i) * cos + y(i) * sin;
            double v = -x(i) * sin + y(i) * cos;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        double cu = (minU + maxU) / 2, cv = (minV + maxV) / 2;
        return new double[]{cu * cos - cv * sin, cu * sin + cv * cos, maxU - minU, maxV - minV};
    }

    /** {minX, minY, maxX, maxY} */
    public double[] bounds() {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < SIZE; i++) {
            b[0] = Math.min(b[0], x(i));
            b[1] = Math.min(b[1], y(i));
            b[2] = Math.max(b[2], x(i));
            b[3] = Math.max(b[3], y(i));
        }
        return b;
    }
}
