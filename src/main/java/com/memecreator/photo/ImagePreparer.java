package com.memecreator.photo;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * Checks an uploaded photo and prepares two versions of it: one to draw the memes on (at most
 * {@value #MAX_RENDER_SIDE} px) and a small JPEG data URI to send to the AI (at most {@value #MAX_AI_SIDE} px,
 * because image tokens and the gateway's base64 limits make big uploads expensive).
 */
@Component
public class ImagePreparer {

    static final int MAX_RENDER_SIDE = 1600;
    static final int MAX_AI_SIDE = 1024;
    /** Refuse images whose header claims more pixels than this (decompression-bomb guard). */
    static final long MAX_PIXELS = 40_000_000L;

    public record PreparedImage(BufferedImage forRendering, String aiDataUri) {
    }

    public PreparedImage prepare(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The uploaded image is empty");
        }
        BufferedImage original = decode(bytes);
        BufferedImage render = toRgb(scaleDown(original, MAX_RENDER_SIDE));
        BufferedImage small = scaleDown(render, MAX_AI_SIDE);
        return new PreparedImage(render, "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(small, 0.85f)));
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IllegalArgumentException("Not a supported image. Upload a JPEG or PNG (also GIF/BMP).");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new IllegalArgumentException("Image is too large (" + reader.getWidth(0) + "x"
                            + reader.getHeight(0) + "); keep it under " + MAX_PIXELS / 1_000_000 + " megapixels");
                }
                if (reader.getWidth(0) < 50 || reader.getHeight(0) < 50) {
                    throw new IllegalArgumentException("Image is too small; it needs to be at least 50x50 pixels");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded image: " + e.getMessage(), e);
        }
    }

    static BufferedImage scaleDown(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (Math.max(w, h) <= maxSide) {
            return src;
        }
        double scale = (double) maxSide / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        // halve repeatedly first: one big bicubic step from a huge photo looks jagged
        BufferedImage current = toRgb(src);
        while (current.getWidth() / 2 >= tw && current.getHeight() / 2 >= th) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return resize(current, tw, th);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Flattens transparency onto white and drops alpha, so JPEG encoding works. */
    static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    static byte[] jpeg(BufferedImage img, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(toRgb(img), null, null), p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
