package com.memecreator.face;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FacePasterTest {

    private final FacePaster paster = new FacePaster();

    /** Eyes, nose, mouth corners of a face centred at (cx, cy) with the given eye distance. */
    private static DetectedFace face(double cx, double cy, double eyeDist) {
        double d = eyeDist;
        double[] lm = {cx - d / 2, cy - d / 3, cx + d / 2, cy - d / 3, cx, cy + d / 6,
                cx - d / 3, cy + d * 0.6, cx + d / 3, cy + d * 0.6};
        return new DetectedFace(cx - d, cy - d, 2 * d, 2.4 * d, lm, 0.9);
    }

    @Test
    void similarityMapsLandmarksExactlyForAScaledShiftedFace() {
        DetectedFace from = face(100, 100, 40);
        DetectedFace to = face(300, 250, 80);

        AffineTransform t = FacePaster.similarity(from, to);

        for (int i = 0; i < 5; i++) {
            Point2D p = t.transform(new Point2D.Double(from.lx(i), from.ly(i)), null);
            assertThat(p.getX()).isCloseTo(to.lx(i), within(1e-6));
            assertThat(p.getY()).isCloseTo(to.ly(i), within(1e-6));
        }
        assertThat(t.getScaleX()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void pastesOnlyAroundTheTargetFace() {
        BufferedImage template = filled(400, 400, new Color(200, 160, 140));
        BufferedImage source = filled(200, 200, new Color(30, 60, 200));
        DetectedFace target = face(200, 200, 60);

        BufferedImage out = paster.paste(template, List.of(target), source, face(100, 100, 50));

        assertThat(out.getRGB(200, 200)).isNotEqualTo(template.getRGB(200, 200));   // face centre changed
        assertThat(out.getRGB(10, 10)).isEqualTo(template.getRGB(10, 10));        // far away untouched
        assertThat(out.getRGB(390, 390)).isEqualTo(template.getRGB(390, 390));
    }

    @Test
    void colourMatchingPullsThePastedFaceTowardsTheTemplateTone() {
        BufferedImage template = filled(400, 400, new Color(200, 160, 140));
        BufferedImage source = noisy(200, 200, new Color(60, 90, 220));

        BufferedImage out = paster.paste(template, List.of(face(200, 200, 60)), source, face(100, 100, 50));

        Color c = new Color(out.getRGB(200, 200));
        assertThat(c.getRed()).isGreaterThan(c.getBlue());   // the blue source took on the warm template tone
    }

    private static BufferedImage filled(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** Colour with a little variation, so brightness contrast can be measured. */
    private static BufferedImage noisy(int w, int h, Color c) {
        BufferedImage img = filled(w, h, c);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = ((x * 31 + y * 17) % 21) - 10;
                img.setRGB(x, y, new Color(clamp(c.getRed() + n), clamp(c.getGreen() + n), clamp(c.getBlue() + n)).getRGB());
            }
        }
        return img;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
