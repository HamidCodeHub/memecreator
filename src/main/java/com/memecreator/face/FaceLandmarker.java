package com.memecreator.face;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

import static com.memecreator.face.MeshModel.INPUT_SIZE;

/**
 * Finds the 468-point face mesh of a face that was already detected. The face is cut out of the image upright
 * (rotated so the eyes are level) into a 192×192 square, the model finds the points in that square, and they are
 * mapped back to the image. A second pass re-centres the square on the first pass's mesh, as MediaPipe does.
 */
@Component
public class FaceLandmarker {

    /** The square is this many times the face box, so the whole head is in it. */
    private static final double CROP_SCALE = 1.5;
    private static final int PASSES = 2;

    private final MeshModel model;

    public FaceLandmarker(MeshModel model) {
        this.model = model;
    }

    public FaceMesh mesh(BufferedImage image, DetectedFace face) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);

        double angle = Math.atan2(face.ly(1) - face.ly(0), face.lx(1) - face.lx(0));
        double cx = face.x() + face.width() / 2;
        double cy = face.y() + face.height() / 2;
        double size = Math.max(face.width(), face.height()) * CROP_SCALE;
        if (!(size > 4)) {
            throw new IllegalArgumentException("face too small for the face mesh");
        }

        FaceMesh mesh = null;
        for (int pass = 0; pass < PASSES; pass++) {
            Crop crop = new Crop(cx, cy, size, angle);
            float[] out = model.infer(crop.sample(rgb, w, h));
            double[] pts = new double[2 * FaceMesh.SIZE];
            for (int i = 0; i < FaceMesh.SIZE; i++) {
                double[] p = crop.toImage(out[3 * i], out[3 * i + 1]);
                pts[2 * i] = p[0];
                pts[2 * i + 1] = p[1];
            }
            mesh = new FaceMesh(pts, sigmoid(out[3 * FaceMesh.SIZE]));

            // next square: around the mesh, in the face's own (rotated) frame, as MediaPipe does
            angle = mesh.eyeAngle();
            double[] box = mesh.alignedBox(angle);
            if (!(Math.max(box[2], box[3]) > 4)) {
                break;   // a collapsed mesh: no better crop to try
            }
            cx = box[0];
            cy = box[1];
            size = Math.max(box[2], box[3]) * CROP_SCALE;
        }
        return mesh;
    }

    private static double sigmoid(double v) {
        return 1 / (1 + Math.exp(-v));
    }

    /**
     * The square cut around a face: crop pixel (u, v) shows image point
     * centre + R(angle) · ((u, v) − 96) / scale, the same mapping as OpenCV's getRotationMatrix2D + warpAffine.
     */
    record Crop(double cx, double cy, double size, double angle) {

        double scale() {
            return INPUT_SIZE / size;
        }

        double[] toImage(double u, double v) {
            double dx = (u - INPUT_SIZE / 2.0) / scale();
            double dy = (v - INPUT_SIZE / 2.0) / scale();
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            return new double[]{cx + cos * dx - sin * dy, cy + sin * dx + cos * dy};
        }

        /** The model's input: RGB planes scaled to 0..1, sampled bilinearly, edge pixels repeated outside the image. */
        float[] sample(int[] rgb, int w, int h) {
            int plane = INPUT_SIZE * INPUT_SIZE;
            float[] out = new float[3 * plane];
            for (int v = 0; v < INPUT_SIZE; v++) {
                for (int u = 0; u < INPUT_SIZE; u++) {
                    double[] p = toImage(u, v);
                    int c = Pixels.bilinear(rgb, w, h, p[0], p[1]);
                    int i = v * INPUT_SIZE + u;
                    out[i] = ((c >> 16) & 255) / 255f;
                    out[plane + i] = ((c >> 8) & 255) / 255f;
                    out[2 * plane + i] = (c & 255) / 255f;
                }
            }
            return out;
        }
    }
}
