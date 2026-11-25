package com.memecreator.face;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FaceLandmarkerTest {

    /** Plays the model: puts the frontal test mesh, levelled and centred, into the 192 × 192 crop. */
    private static final MeshModel UPRIGHT_FACE = input -> {
        FaceMesh m = level(TestMeshes.frontal());
        double[] b = m.bounds();
        double s = 110 / Math.max(b[2] - b[0], b[3] - b[1]);
        float[] out = new float[3 * FaceMesh.SIZE + 1];
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            out[3 * i] = (float) (96 + (m.x(i) - (b[0] + b[2]) / 2) * s);
            out[3 * i + 1] = (float) (96 + (m.y(i) - (b[1] + b[3]) / 2) * s);
        }
        out[3 * FaceMesh.SIZE] = 6;   // logit: score ≈ 0.998
        return out;
    };

    @Test
    void mapsPointsBackIntoATiltedFace() {
        // a face tilted 30° (its eye line), centred at (300, 200)
        double tilt = Math.toRadians(30);
        DetectedFace face = face(300, 200, 120, tilt);

        FaceMesh mesh = new FaceLandmarker(UPRIGHT_FACE).mesh(new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB), face);

        assertThat(mesh.eyeAngle()).isCloseTo(tilt, within(0.02));
        double[] box = mesh.alignedBox(mesh.eyeAngle());   // centre of the face in its own frame
        assertThat(box[0]).isCloseTo(300, within(1.0));
        assertThat(box[1]).isCloseTo(200, within(1.0));
        assertThat(mesh.score()).isGreaterThan(0.99);
    }

    @Test
    void cropShowsTheFaceUpright() {
        // image: left half red, right half blue; crop rotated 180° must show blue on its left
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                img.setRGB(x, y, x < 100 ? 0xFF0000 : 0x0000FF);
            }
        }
        int[] rgb = img.getRGB(0, 0, 200, 200, null, 0, 200);
        float[] upright = new FaceLandmarker.Crop(100, 100, 100, 0).sample(rgb, 200, 200);
        float[] turned = new FaceLandmarker.Crop(100, 100, 100, Math.PI).sample(rgb, 200, 200);

        int plane = 192 * 192;
        int leftMiddle = 96 * 192 + 10;
        assertThat(upright[leftMiddle]).isEqualTo(1f);            // red plane on the left
        assertThat(upright[2 * plane + leftMiddle]).isEqualTo(0f);
        assertThat(turned[leftMiddle]).isEqualTo(0f);
        assertThat(turned[2 * plane + leftMiddle]).isEqualTo(1f);  // blue plane on the left
    }

    @Test
    void frontalMeshLooksStraightAheadAndMirroringFlipsYaw() {
        FaceMesh frontal = TestMeshes.frontal();
        assertThat(frontal.yaw()).isCloseTo(0, within(0.05));

        // the nose moved towards one cheek = the head turned that way
        double[] pts = frontal.points();
        pts[2 * FaceMesh.NOSE_TIP] += 0.3 * frontal.width();
        assertThat(new FaceMesh(pts, 1).yaw()).isGreaterThan(0.4);

        DetectedFace f = face(100, 80, 60, 0.2);
        DetectedFace m = f.mirrored(400);
        assertThat(m.x()).isCloseTo(400 - f.x() - f.width(), within(1e-9));
        assertThat(m.lx(0)).isCloseTo(400 - f.lx(1), within(1e-9));   // eyes swap sides
        assertThat(m.ly(0)).isCloseTo(f.ly(1), within(1e-9));
        assertThat(m.lx(3)).isCloseTo(400 - f.lx(4), within(1e-9));   // so do the mouth corners
    }

    /** The mesh rotated so its eye line is horizontal. */
    private static FaceMesh level(FaceMesh m) {
        double a = -Math.atan2(m.y(FaceMesh.EYE_B) - m.y(FaceMesh.EYE_A), m.x(FaceMesh.EYE_B) - m.x(FaceMesh.EYE_A));
        double[] p = m.points();
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            double x = m.x(i), y = m.y(i);
            p[2 * i] = x * Math.cos(a) - y * Math.sin(a);
            p[2 * i + 1] = x * Math.sin(a) + y * Math.cos(a);
        }
        return new FaceMesh(p, 1);
    }

    /** A detected face of the given size at (cx, cy), its eye line tilted by {@code tilt}. */
    private static DetectedFace face(double cx, double cy, double size, double tilt) {
        double[][] local = {{-0.2, -0.1}, {0.2, -0.1}, {0, 0.05}, {-0.15, 0.2}, {0.15, 0.2}};
        double[] lm = new double[10];
        for (int i = 0; i < 5; i++) {
            double x = local[i][0] * size, y = local[i][1] * size;
            lm[2 * i] = cx + x * Math.cos(tilt) - y * Math.sin(tilt);
            lm[2 * i + 1] = cy + x * Math.sin(tilt) + y * Math.cos(tilt);
        }
        return new DetectedFace(cx - size / 2, cy - size / 2, size, size, lm, 0.9);
    }
}
