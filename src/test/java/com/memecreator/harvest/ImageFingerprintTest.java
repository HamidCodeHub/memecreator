package com.memecreator.harvest;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageFingerprintTest {

    static BufferedImage art(long seed, int w, int h) {
        Random r = new Random(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(r.nextInt(0xffffff)));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 25; i++) {
            g.setColor(new Color(r.nextInt(0xffffff)));
            g.fillOval(r.nextInt(w), r.nextInt(h), 40 + r.nextInt(w / 2), 40 + r.nextInt(h / 2));
        }
        g.dispose();
        return img;
    }

    static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return img;
    }

    static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    static ImageFingerprint fp(BufferedImage img, String format) throws IOException {
        byte[] bytes = encode(img, format);
        return ImageFingerprint.of(bytes, ImageIO.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void resizedAndRecompressedCopyIsSimilar() throws IOException {
        BufferedImage original = art(1, 600, 500);
        ImageFingerprint a = fp(original, "png");
        ImageFingerprint b = fp(scaled(original, 450, 375), "jpg");
        assertTrue(a.similarTo(b, 10), "distance " + a.distance(b));
    }

    @Test
    void differentPicturesAreNotSimilar() throws IOException {
        for (int i = 10; i < 30; i++) {
            ImageFingerprint a = fp(art(i, 600, 500), "png");
            ImageFingerprint b = fp(art(i + 100, 600, 500), "png");
            assertFalse(a.similarTo(b, 10), "seeds " + i + "/" + (i + 100) + " distance " + a.distance(b));
        }
    }

    @Test
    void differentAspectRatioIsNotSimilar() throws IOException {
        BufferedImage original = art(2, 600, 600);
        assertFalse(fp(original, "png").similarTo(fp(scaled(original, 900, 450), "png"), 64));
    }

    @Test
    void mostlyWhiteImagesOnlyMatchExactly() throws IOException {
        BufferedImage white1 = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        BufferedImage white2 = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        for (BufferedImage w : new BufferedImage[]{white1, white2}) {
            Graphics2D g = w.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 600, 400);
            g.dispose();
        }
        white2.setRGB(300, 200, 0);
        ImageFingerprint a = fp(white1, "png");
        ImageFingerprint b = fp(white2, "png");
        assertTrue(a.lowDetail());
        assertFalse(a.similarTo(b, 10));
        assertTrue(a.similarTo(fp(white1, "png"), 0)); // identical bytes
    }

    @Test
    void catalogTextRoundTrips() throws IOException {
        ImageFingerprint a = fp(art(3, 500, 400), "png");
        ImageFingerprint b = ImageFingerprint.fromCatalog(a.sha256(), a.hashText(), a.width(), a.height());
        assertEquals(a, b);
    }
}
