package com.memecreator.face;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MeshFaceSwapperTest {

    @Test
    void mirrorsOnlyWhenTheDirectionsClearlyDisagree() {
        assertThat(MeshFaceSwapper.shouldMirror(-0.8, 0.5)).isTrue();    // template looks left, photo right
        assertThat(MeshFaceSwapper.shouldMirror(0.6, 0.4)).isFalse();    // same way already
        assertThat(MeshFaceSwapper.shouldMirror(0.05, -0.05)).isFalse(); // both about frontal: not worth it
    }

    @Test
    void boldKeepsTheUsersFaceShape() {
        FaceMesh user = TestMeshes.frontal();
        // a template face of a different shape: the user's face squashed to 70% height, then moved and doubled
        double[] p = user.points();
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            p[2 * i + 1] *= 0.7;
        }
        FaceMesh template = TestMeshes.transformed(new FaceMesh(p, 1), 2, 100, 50);

        FaceMesh kept = MeshFaceSwapper.keepShape(user, template, 1.0);
        FaceMesh melted = MeshFaceSwapper.keepShape(user, template, 0.0);

        // keep = 1: the user's own proportions (height/width), just placed and scaled onto the template face
        assertThat(ratio(kept)).isCloseTo(ratio(user), within(1e-6));
        assertThat(ratio(template)).isLessThan(ratio(user) * 0.8);
        assertThat(kept.alignedBox(0)[0]).isCloseTo(template.alignedBox(0)[0], within(3.0));
        // keep = 0: exactly the template's shape (the NATURAL warp)
        assertThat(melted.x(152)).isCloseTo(template.x(152), within(1e-9));
        assertThat(melted.y(152)).isCloseTo(template.y(152), within(1e-9));
    }

    @Test
    void headCutoutKeepsTheHeadAndDropsTheRest() {
        FaceMesh mesh = TestMeshes.frontal();            // about 290 × 325 px, starting at (20, 20)
        double[] b = mesh.bounds();
        BufferedImage photo = filled(900, 900, new Color(200, 150, 120));
        FaceMesh moved = TestMeshes.transformed(mesh, 1, 300, 250);
        DetectedFace face = new DetectedFace(320, 270, 290, 300,
                new double[]{400, 380, 520, 380, 460, 440, 410, 500, 510, 500}, 1);
        PersonSegmenter everyoneIsAPerson = input -> {
            float[] l = new float[PersonSegmenter.OUTPUT_SIZE * PersonSegmenter.OUTPUT_SIZE];
            java.util.Arrays.fill(l, 8f);
            return l;
        };

        HeadCutout cut = HeadCutout.cut(photo, face, moved, everyoneIsAPerson);

        FaceMesh m = cut.mesh();
        int nose = cut.image().getRGB((int) m.x(FaceMesh.NOSE_TIP), (int) m.y(FaceMesh.NOSE_TIP)) >>> 24;
        int belowChin = cut.image().getRGB((int) m.x(152), (int) Math.min(cut.image().getHeight() - 1,
                m.y(152) + 0.3 * (b[3] - b[1]))) >>> 24;
        int besideJaw = cut.image().getRGB((int) Math.max(0, m.x(152) - 0.9 * m.width()), (int) m.y(152)) >>> 24;
        assertThat(nose).isEqualTo(255);
        assertThat(belowChin).isEqualTo(0);      // no neck or shirt
        assertThat(besideJaw).isEqualTo(0);      // no shoulders
        int aboveForehead = cut.image().getRGB((int) m.x(10), (int) Math.max(0, m.y(10) - 0.15 * (b[3] - b[1]))) >>> 24;
        assertThat(aboveForehead).isEqualTo(255);   // hair above the forehead stays
    }

    @Test
    void grabCutDecidesTheOutlineWhenAvailable() {
        FaceMesh moved = TestMeshes.transformed(TestMeshes.frontal(), 1, 300, 250);
        double[] b = moved.bounds();
        BufferedImage photo = filled(900, 900, new Color(230, 220, 200));   // a light wall...
        DetectedFace face = new DetectedFace(320, 270, 290, 300,
                new double[]{400, 380, 520, 380, 460, 440, 410, 500, 510, 500}, 1);
        PersonSegmenter leaky = input -> {                                   // ...that the person mask spills into
            float[] l = new float[PersonSegmenter.OUTPUT_SIZE * PersonSegmenter.OUTPUT_SIZE];
            java.util.Arrays.fill(l, 8f);
            return l;
        };
        byte[][] seen = new byte[1][];
        ImageOps onlySureForeground = new ImageOps() {                       // GrabCut keeps just the face core
            public boolean[] grabCut(int[] rgb, int w, int h, byte[] labels, int iterations) {
                seen[0] = labels;
                boolean[] fg = new boolean[labels.length];
                for (int i = 0; i < fg.length; i++) {
                    fg[i] = labels[i] == ImageOps.FOREGROUND;
                }
                return fg;
            }

            public int[] inpaint(int[] rgb, int w, int h, boolean[] mask, double radius) {
                return rgb;
            }
        };

        HeadCutout cut = HeadCutout.cut(photo, face, moved, leaky, onlySureForeground);

        FaceMesh m = cut.mesh();
        assertThat(cut.image().getRGB((int) m.x(FaceMesh.NOSE_TIP), (int) m.y(FaceMesh.NOSE_TIP)) >>> 24).isEqualTo(255);
        int above = cut.image().getRGB((int) m.x(10), (int) Math.max(0, m.y(10) - 0.15 * (b[3] - b[1]))) >>> 24;
        assertThat(above).isEqualTo(0);   // the leaked wall above the forehead is gone
        boolean hasBackground = false, hasSure = false;
        for (byte l : seen[0]) {
            hasBackground |= l == ImageOps.BACKGROUND;
            hasSure |= l == ImageOps.FOREGROUND;
        }
        assertThat(hasBackground).isTrue();   // outside the head-shaped area
        assertThat(hasSure).isTrue();         // the middle of the face
    }

    @Test
    void similarityFindsMoveScaleAndTurn() {
        double[] from = {0, 0, 10, 0, 0, 10};
        double c = Math.cos(0.5) * 2, s = Math.sin(0.5) * 2;
        double[] to = new double[6];
        for (int i = 0; i < 3; i++) {
            to[2 * i] = c * from[2 * i] - s * from[2 * i + 1] + 5;
            to[2 * i + 1] = s * from[2 * i] + c * from[2 * i + 1] - 3;
        }
        double[] t = MeshFaceSwapper.similarity(from, to);
        assertThat(t[0]).isCloseTo(c, within(1e-9));
        assertThat(t[1]).isCloseTo(s, within(1e-9));
        assertThat(t[2]).isCloseTo(5, within(1e-9));
        assertThat(t[3]).isCloseTo(-3, within(1e-9));
    }

    @Test
    void faceStyleDefaultsToHead() {
        assertThat(FaceStyle.parse(null)).isEqualTo(FaceStyle.HEAD);
        assertThat(FaceStyle.parse(" natural ")).isEqualTo(FaceStyle.NATURAL);
    }

    @Test
    void fullStyleReachesPastTheFaceOutline() {
        FaceMesh face = TestMeshes.frontal();
        double[] ext = FaceOutline.extend(face, 0.3, 0.1, 0.05);
        int n = FaceMesh.SIZE;
        double[] b = face.bounds();

        assertThat(ext.length).isEqualTo(2 * (n + FaceOutline.OVAL.length));
        double top = Double.MAX_VALUE;
        for (int k = 0; k < FaceOutline.OVAL.length; k++) {
            top = Math.min(top, ext[2 * (n + k) + 1]);
        }
        double height = b[3] - b[1];
        assertThat(b[1] - top).isGreaterThan(0.2 * height / 2);   // well up the forehead
        int[][] tris = FaceOutline.triangles(MeshWarp.meshTriangles(), ext);
        assertThat(tris.length).isEqualTo(MeshWarp.triangleCount() + 2 * FaceOutline.OVAL.length);
        for (int k = MeshWarp.triangleCount(); k < tris.length; k++) {
            int[] t = tris[k];
            assertThat(MeshWarp.area(ext[2 * t[0]], ext[2 * t[0] + 1], ext[2 * t[1]], ext[2 * t[1] + 1],
                    ext[2 * t[2]], ext[2 * t[2] + 1])).isGreaterThan(0);
        }
    }

    @Test
    void featherBlendCanKeepTheFacesOwnColours() {
        BufferedImage template = filled(100, 100, Color.GRAY);
        BufferedImage face = filled(100, 100, new Color(200, 120, 90));
        boolean[] mask = new boolean[100 * 100];
        for (int y = 20; y < 80; y++) {
            for (int x = 20; x < 80; x++) {
                mask[y * 100 + x] = true;
            }
        }

        int own = FeatherBlend.blend(template, face, mask, 0.0).getRGB(50, 50) & 0xFFFFFF;
        int matched = FeatherBlend.blend(template, face, mask, 1.0).getRGB(50, 50) & 0xFFFFFF;

        assertThat(own).isEqualTo(new Color(200, 120, 90).getRGB() & 0xFFFFFF);
        assertThat(matched).isNotEqualTo(own);
    }

    /** Face length (forehead to chin) over face width (cheek to cheek): unchanged by moving, scaling or turning. */
    private static double ratio(FaceMesh m) {
        return Math.hypot(m.x(152) - m.x(10), m.y(152) - m.y(10)) / m.width();
    }

    @Test
    void pastesTheFaceOnlyAroundTheTarget() {
        BufferedImage template = filled(500, 400, Color.GRAY);
        BufferedImage photo = filled(300, 300, new Color(200, 120, 90));
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(140, 100, 20, 60);   // some detail on the "face"
        g.dispose();
        DetectedFace target = new DetectedFace(300, 120, 120, 120,
                new double[]{330, 160, 390, 160, 360, 190, 335, 215, 385, 215}, 1);
        DetectedFace source = new DetectedFace(90, 90, 120, 120,
                new double[]{120, 130, 180, 130, 150, 160, 125, 185, 175, 185}, 1);
        MeshFaceSwapper swapper = new MeshFaceSwapper(new FaceLandmarker(FakeModel.UPRIGHT),
                FeatherBlend::blend, new FacePaster());

        MeshFaceSwapper.Result r = swapper.paste(template, List.of(target), List.of(1), photo, source);

        assertThat(r.warnings()).isEmpty();
        assertThat(r.image().getRGB(360, 180)).isNotEqualTo(Color.GRAY.getRGB());   // the face is there
        assertThat(r.image().getRGB(50, 50)).isEqualTo(Color.GRAY.getRGB());        // the rest is untouched
    }

    @Test
    void fallsBackToTheSimplePasteWhenTheMeshFails() {
        BufferedImage template = filled(300, 300, Color.GRAY);
        BufferedImage photo = filled(300, 300, Color.ORANGE);
        DetectedFace face = new DetectedFace(100, 100, 100, 100,
                new double[]{125, 130, 175, 130, 150, 155, 130, 175, 170, 175}, 1);
        MeshModel broken = input -> {
            throw new IllegalStateException("no OpenCV here");
        };
        MeshFaceSwapper swapper = new MeshFaceSwapper(new FaceLandmarker(broken), FeatherBlend::blend, new FacePaster());

        MeshFaceSwapper.Result r = swapper.paste(template, List.of(face), List.of(1), photo, face);

        assertThat(r.warnings()).singleElement().asString().contains("simpler paste");
        assertThat(r.image().getRGB(150, 150)).isNotEqualTo(Color.GRAY.getRGB());
    }

    private static BufferedImage filled(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** Plays the model: always "finds" the frontal test mesh, levelled and centred in the crop. */
    static final class FakeModel {
        static final MeshModel UPRIGHT = input -> {
            FaceMesh m = TestMeshes.frontal();
            double a = -Math.atan2(m.y(FaceMesh.EYE_B) - m.y(FaceMesh.EYE_A), m.x(FaceMesh.EYE_B) - m.x(FaceMesh.EYE_A));
            double[] b = m.bounds();
            double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
            double s = 110 / Math.max(b[2] - b[0], b[3] - b[1]);
            float[] out = new float[3 * FaceMesh.SIZE + 1];
            for (int i = 0; i < FaceMesh.SIZE; i++) {
                double x = m.x(i) - cx, y = m.y(i) - cy;
                out[3 * i] = (float) (96 + (x * Math.cos(a) - y * Math.sin(a)) * s);
                out[3 * i + 1] = (float) (96 + (x * Math.sin(a) + y * Math.cos(a)) * s);
            }
            out[3 * FaceMesh.SIZE] = 6;
            return out;
        };
    }
}
