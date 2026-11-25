package com.memecreator.face;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * OpenCV helpers. The native library comes from the org.openpnp:opencv dependency and is loaded on first use,
 * so the rest of the app still starts if it can't be loaded on some machine.
 */
final class OpenCv {

    private static boolean loaded;

    private OpenCv() {
    }

    static synchronized void load() {
        if (!loaded) {
            nu.pattern.OpenCV.loadLocally();
            loaded = true;
        }
    }

    /** Copies a model from the classpath to a temp file, because OpenCV reads models from disk. */
    static Path extract(String resource, String suffix) {
        try (InputStream in = OpenCv.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Model missing from the classpath: " + resource);
            }
            Path tmp = Files.createTempFile("memecreator-model", suffix);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract " + resource, e);
        }
    }

    /** 8-bit BGR Mat (OpenCV's pixel order); TYPE_3BYTE_BGR stores exactly that. */
    static Mat toMat(BufferedImage image) {
        BufferedImage bgr = image;
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bgr.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData());
        return mat;
    }

    static BufferedImage toImage(Mat mat) {
        BufferedImage out = new BufferedImage(mat.cols(), mat.rows(), BufferedImage.TYPE_3BYTE_BGR);
        mat.get(0, 0, ((DataBufferByte) out.getRaster().getDataBuffer()).getData());
        return out;
    }
}
