package com.memecreator.harvest;

import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What the duplicate check compares: an exact SHA-256 of the file bytes, and two 64-bit difference hashes
 * (dHash) of the picture — one comparing horizontal neighbours, one vertical. Re-encoded, resized or slightly
 * recompressed copies of a template keep (almost) the same dHashes.
 * <p>
 * Using both directions plus the aspect ratio avoids the classic dHash false positive: two different
 * mostly-white templates (comic panels, charts) that look alike to a single horizontal hash.
 */
public record ImageFingerprint(String sha256, long dhashH, long dhashV, int width, int height) {

    /** Brightness difference (0..255) a neighbour must exceed to set a bit. */
    static final double FLAT_MARGIN = 2.0;
    /** Fewer set bits than this (both hashes together) = mostly flat image; matched only by exact bytes. */
    static final int MIN_DETAIL_BITS = 8;

    public static ImageFingerprint of(byte[] bytes, BufferedImage image) {
        return new ImageFingerprint(sha256(bytes), dHash(image, true), dHash(image, false),
                image.getWidth(), image.getHeight());
    }

    /** Max of the two hash distances (0..64). */
    public int distance(ImageFingerprint other) {
        return Math.max(Long.bitCount(dhashH ^ other.dhashH), Long.bitCount(dhashV ^ other.dhashV));
    }

    /**
     * Same picture: identical bytes, or hashes within maxDistance bits and aspect ratios within 12%.
     * Mostly flat images (blank panels, plain backgrounds) only match exactly: their hashes are nearly all zeros,
     * so any two of them would look "similar".
     */
    public boolean similarTo(ImageFingerprint other, int maxDistance) {
        if (sha256.equals(other.sha256)) {
            return true;
        }
        if (lowDetail() || other.lowDetail()) {
            return false;
        }
        double a = aspect();
        double b = other.aspect();
        return Math.abs(a - b) / Math.max(a, b) <= 0.12 && distance(other) <= maxDistance;
    }

    public boolean lowDetail() {
        return Long.bitCount(dhashH) + Long.bitCount(dhashV) < MIN_DETAIL_BITS;
    }

    public long pixels() {
        return (long) width * height;
    }

    public double aspect() {
        return (double) width / height;
    }

    /** "hhhhhhhhhhhhhhhh:vvvvvvvvvvvvvvvv" for the catalog. */
    public String hashText() {
        return HexFormat.of().toHexDigits(dhashH) + ":" + HexFormat.of().toHexDigits(dhashV);
    }

    public static ImageFingerprint fromCatalog(String sha256, String hashText, int width, int height) {
        int colon = hashText.indexOf(':');
        return new ImageFingerprint(sha256, HexFormat.fromHexDigitsToLong(hashText.substring(0, colon)),
                HexFormat.fromHexDigitsToLong(hashText.substring(colon + 1)), width, height);
    }

    static long dHash(BufferedImage src, boolean horizontal) {
        int w = horizontal ? 9 : 8;
        int h = horizontal ? 8 : 9;
        double[][] cells = averageGray(src, w, h);
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                double a = cells[y][x];
                double b = horizontal ? cells[y][x + 1] : cells[y + 1][x];
                // The margin keeps flat areas at 0 instead of flipping with JPEG noise from copy to copy
                hash = (hash << 1) | (a > b + FLAT_MARGIN ? 1 : 0);
            }
        }
        return hash;
    }

    /**
     * Mean luminance of each cell of a w x h grid over the image (a box downscale: every pixel counts, unlike
     * bilinear scaling, which on a large image only samples a few). Transparent pixels count as white.
     */
    static double[][] averageGray(BufferedImage src, int w, int h) {
        int iw = src.getWidth();
        int ih = src.getHeight();
        double[][] sum = new double[h][w];
        long[][] count = new long[h][w];
        int[] row = new int[iw];
        for (int y = 0; y < ih; y++) {
            src.getRGB(0, y, iw, 1, row, 0, iw);
            int cy = (int) ((long) y * h / ih);
            for (int x = 0; x < iw; x++) {
                int argb = row[x];
                double alpha = ((argb >>> 24) & 0xff) / 255.0;
                double lum = 0.299 * ((argb >> 16) & 0xff) + 0.587 * ((argb >> 8) & 0xff) + 0.114 * (argb & 0xff);
                int cx = (int) ((long) x * w / iw);
                sum[cy][cx] += lum * alpha + 255 * (1 - alpha);
                count[cy][cx]++;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                sum[y][x] = count[y][x] == 0 ? 0 : sum[y][x] / count[y][x];
            }
        }
        return sum;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
