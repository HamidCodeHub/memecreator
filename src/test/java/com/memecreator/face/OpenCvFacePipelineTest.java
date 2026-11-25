package com.memecreator.face;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real OpenCV parts (face mesh model, seamless cloning, detection). Skipped where OpenCV's native library
 * can't load; the template test also needs the meme templates (-Dmeme.templates-dir, default E:/photos/meme).
 */
class OpenCvFacePipelineTest {

    @BeforeAll
    static void openCv() {
        boolean ok;
        try {
            OpenCv.load();
            ok = true;
        } catch (Throwable e) {
            ok = false;
        }
        assumeTrue(ok, "OpenCV native library not available");
    }

    @Test
    void meshModelLoadsAndAnswers() {
        float[] out = new OpenCvMeshModel().infer(new float[3 * 192 * 192]);

        assertThat(out).hasSize(3 * FaceMesh.SIZE + 1);
    }

    @Test
    void personSegmenterLoadsAndAnswers() {
        float[] out = new OpenCvPersonSegmenter().segment(new float[513 * 513 * 3]);

        assertThat(out).hasSize(33 * 33);
    }

    @Test
    void grabCutAndInpaintRun() {
        int w = 60, h = 60;
        int[] rgb = new int[w * h];
        byte[] labels = new byte[w * h];
        boolean[] hole = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inside = x >= 20 && x < 40 && y >= 20 && y < 40;
                rgb[y * w + x] = inside ? 0xC08060 : 0x2040A0;
                labels[y * w + x] = x >= 25 && x < 35 && y >= 25 && y < 35 ? ImageOps.FOREGROUND
                        : inside ? ImageOps.PROBABLY_FOREGROUND : x < 5 || y < 5 ? ImageOps.BACKGROUND
                        : ImageOps.PROBABLY_BACKGROUND;
                hole[y * w + x] = inside;
            }
        }
        OpenCvImageOps ops = new OpenCvImageOps();

        boolean[] fg = ops.grabCut(rgb, w, h, labels, 3);
        int[] filled = ops.inpaint(rgb, w, h, hole, 3);

        assertThat(fg[30 * w + 30]).isTrue();
        assertThat(fg[2 * w + 2]).isFalse();
        assertThat(filled[30 * w + 30]).isNotEqualTo(0xC08060);   // filled from the blue around it
    }

    @Test
    void seamlessBlendChangesOnlyTheMaskedArea() {
        BufferedImage template = filled(Color.GRAY);
        BufferedImage face = filled(new Color(220, 150, 120));
        boolean[] mask = new boolean[200 * 200];
        for (int y = 60; y < 140; y++) {
            for (int x = 60; x < 140; x++) {
                mask[y * 200 + x] = true;
            }
        }

        BufferedImage out = new SeamlessFaceBlender().blend(template, face, mask);

        assertThat(out.getWidth()).isEqualTo(200);
        assertThat(out.getRGB(5, 5) & 0xFFFFFF).isEqualTo(Color.GRAY.getRGB() & 0xFFFFFF);
    }

    @Test
    void realTemplateFaceGetsAConfidentMesh() throws Exception {
        Path dir = Path.of(System.getProperty("meme.templates-dir", "E:/photos/meme"));
        Optional<Path> harold;
        try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
            harold = files.filter(p -> p.getFileName().toString().contains("Hide the Pain Harold")).findFirst();
        }
        assumeTrue(harold.isPresent(), "templates folder not found: " + dir);

        BufferedImage img = ImageIO.read(harold.get().toFile());
        List<DetectedFace> faces = new OpenCvFaceDetector().detect(img);
        assertThat(faces).isNotEmpty();
        FaceMesh mesh = new FaceLandmarker(new OpenCvMeshModel()).mesh(img, faces.get(0));

        assertThat(mesh.score()).isGreaterThan(0.9);
        double[] b = mesh.bounds();
        DetectedFace f = faces.get(0);
        assertThat((b[0] + b[2]) / 2).isBetween(f.x(), f.x() + f.width());   // the mesh sits on the detected face
        assertThat((b[1] + b[3]) / 2).isBetween(f.y(), f.y() + f.height());
    }

    private static BufferedImage filled(Color c) {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 200, 200);
        g.dispose();
        return img;
    }
}
