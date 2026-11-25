package com.memecreator.face;

import java.awt.image.BufferedImage;

/**
 * Fallback blending without OpenCV: match the face's brightness and tint to the template's under the mask, then
 * paste it with a soft edge.
 */
final class FeatherBlend {

    private FeatherBlend() {
    }

    static BufferedImage blend(BufferedImage template, BufferedImage warped, boolean[] mask) {
        return blend(template, warped, mask, 1.0);
    }

    /** @param colorMatch 0 = keep the face's own colours, 1 = match the template's brightness, contrast and tint */
    static BufferedImage blend(BufferedImage template, BufferedImage warped, boolean[] mask, double colorMatch) {
        int w = template.getWidth();
        int h = template.getHeight();
        int[] dst = template.getRGB(0, 0, w, h, null, 0, w);
        int[] src = warped.getRGB(0, 0, w, h, null, 0, w);

        int minX = w, maxX = -1;
        double[] s = new double[5];
        double[] d = new double[5];
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                minX = Math.min(minX, i % w);
                maxX = Math.max(maxX, i % w);
                add(s, ycc(src[i]));
                add(d, ycc(dst[i]));
            }
        }
        if (maxX < 0 || s[4] < 20) {
            return template;
        }
        double sMean = s[0] / s[4], dMean = d[0] / d[4];
        double sSd = Math.sqrt(Math.max(1e-6, s[1] / s[4] - sMean * sMean));
        double dSd = Math.sqrt(Math.max(1e-6, d[1] / d[4] - dMean * dMean));
        double gain = Math.max(0.6, Math.min(1.6, dSd / sSd));
        double dCb = d[2] / d[4] - s[2] / s[4];
        double dCr = d[3] / d[4] - s[3] / s[4];

        int radius = Math.max(2, (maxX - minX) / 12);
        double[] alpha = blur(mask, w, h, radius);
        int[] out = dst.clone();
        for (int i = 0; i < out.length; i++) {
            double a = alpha[i];
            if (a <= 0.001 || !mask[i] && a < 0.5) {
                continue;
            }
            double[] c = ycc(src[i]);
            double y = c[0] + colorMatch * ((c[0] - sMean) * gain + dMean - c[0]);
            int matched = rgb(y, c[1] + colorMatch * dCb, c[2] + colorMatch * dCr);
            out[i] = mix(dst[i], matched, a);
        }
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    /** Box blur of the mask (two separable passes), so the paste fades out over {@code r} pixels inside its edge. */
    private static double[] blur(boolean[] mask, int w, int h, int r) {
        boolean[] inner = Pixels.erode(mask, w, h, r);
        double[] a = new double[mask.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = inner[i] ? 1 : 0;
        }
        double[] tmp = new double[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                for (int k = -r; k <= r; k++) {
                    sum += a[y * w + Math.max(0, Math.min(w - 1, x + k))];
                }
                tmp[y * w + x] = sum / (2 * r + 1);
            }
        }
        double[] res = new double[a.length];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                double sum = 0;
                for (int k = -r; k <= r; k++) {
                    sum += tmp[Math.max(0, Math.min(h - 1, y + k)) * w + x];
                }
                res[y * w + x] = mask[y * w + x] ? sum / (2 * r + 1) : 0;
            }
        }
        return res;
    }

    private static void add(double[] acc, double[] c) {
        acc[0] += c[0];
        acc[1] += c[0] * c[0];
        acc[2] += c[1];
        acc[3] += c[2];
        acc[4] += 1;
    }

    private static double[] ycc(int rgb) {
        double r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return new double[]{0.299 * r + 0.587 * g + 0.114 * b,
                -0.168736 * r - 0.331264 * g + 0.5 * b,
                0.5 * r - 0.418688 * g - 0.081312 * b};
    }

    private static int rgb(double y, double cb, double cr) {
        return (clamp(y + 1.402 * cr) << 16) | (clamp(y - 0.344136 * cb - 0.714136 * cr) << 8) | clamp(y + 1.772 * cb);
    }

    private static int mix(int under, int over, double a) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            out |= clamp(((under >> shift) & 255) * (1 - a) + ((over >> shift) & 255) * a) << shift;
        }
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
