package com.memecreator.face;

import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pastes a face from one photo onto faces in a template, the classic "photoshopped face" meme look:
 * <ol>
 *   <li>align: a similarity transform (move, rotate, scale) that best maps the source face's 5 landmarks onto
 *       the template face's landmarks;</li>
 *   <li>cut out an oval around the eyes, nose and mouth with a soft (feathered) edge, so the template keeps its
 *       hair, ears and outline;</li>
 *   <li>match colours: shift the pasted skin's brightness and tint to the template face's, so it doesn't look
 *       like it came from another photo (which it did).</li>
 * </ol>
 */
@Component
public class FacePaster {

    /** Fraction of the oval's radius over which the edge fades out. */
    private static final double FEATHER = 0.30;
    /** How strongly colours are matched (1 = fully). */
    private static final double COLOR_MATCH = 0.85;

    /** Pastes {@code sourceFace} from {@code source} onto every face in {@code targets}; returns a new image. */
    public BufferedImage paste(BufferedImage template, List<DetectedFace> targets, BufferedImage source, DetectedFace sourceFace) {
        BufferedImage out = new BufferedImage(template.getWidth(), template.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(template, 0, 0, null);
        g.dispose();
        for (DetectedFace target : targets) {
            pasteOne(out, target, source, sourceFace);
        }
        return out;
    }

    private void pasteOne(BufferedImage canvas, DetectedFace target, BufferedImage source, DetectedFace sourceFace) {
        AffineTransform align = similarity(sourceFace, target);

        // the oval: from a bit above the eyebrows to just below the mouth, as wide as the cheeks
        double ex = (target.lx(0) + target.lx(1)) / 2;
        double ey = (target.ly(0) + target.ly(1)) / 2;
        double mx = (target.lx(3) + target.lx(4)) / 2;
        double my = (target.ly(3) + target.ly(4)) / 2;
        double eyeDist = Math.hypot(target.lx(1) - target.lx(0), target.ly(1) - target.ly(0));
        double eyeToMouth = Math.hypot(mx - ex, my - ey);
        if (eyeDist < 4) {
            return;   // face too small to paste meaningfully
        }
        double angle = Math.atan2(target.ly(1) - target.ly(0), target.lx(1) - target.lx(0));
        double up = 0.75 * eyeDist;              // above the eye line
        double down = eyeToMouth + 0.55 * eyeDist; // below the eye line (past the mouth, short of the chin edge)
        double ry = (up + down) / 2;
        double rx = 1.0 * eyeDist;
        // centre on the eye-mouth axis, shifted from the eye line by (down - up) / 2
        double ax = (mx - ex) / Math.max(eyeToMouth, 1e-6);
        double ay = (my - ey) / Math.max(eyeToMouth, 1e-6);
        double cx = ex + ax * (down - up) / 2;
        double cy = ey + ay * (down - up) / 2;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        int x0 = clamp((int) Math.floor(cx - Math.max(rx, ry) - 2), canvas.getWidth());
        int y0 = clamp((int) Math.floor(cy - Math.max(rx, ry) - 2), canvas.getHeight());
        int x1 = clamp((int) Math.ceil(cx + Math.max(rx, ry) + 2), canvas.getWidth());
        int y1 = clamp((int) Math.ceil(cy + Math.max(rx, ry) + 2), canvas.getHeight());
        int w = x1 - x0;
        int h = y1 - y0;
        if (w <= 2 || h <= 2) {
            return;
        }

        // warp the source photo into this region of the canvas
        BufferedImage warped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D wg = warped.createGraphics();
        wg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        wg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        AffineTransform t = AffineTransform.getTranslateInstance(-x0, -y0);
        t.concatenate(align);
        wg.drawImage(source, t, null);
        wg.dispose();

        // feathered oval mask (combined with where the warped photo actually has pixels)
        double[] alpha = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x0 + x + 0.5 - cx;
                double dy = y0 + y + 0.5 - cy;
                double u = dx * cos + dy * sin;     // along the eye line
                double v = -dx * sin + dy * cos;    // along the face's vertical axis
                double r = Math.sqrt((u / rx) * (u / rx) + (v / ry) * (v / ry));
                double a = r <= 1 - FEATHER ? 1 : r >= 1 ? 0 : smooth((1 - r) / FEATHER);
                int argb = warped.getRGB(x, y);
                alpha[y * w + x] = a * ((argb >>> 24) / 255.0);
            }
        }

        matchColors(canvas, warped, alpha, x0, y0, w, h);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double a = alpha[y * w + x];
                if (a <= 0) {
                    continue;
                }
                int s = warped.getRGB(x, y);
                int d = canvas.getRGB(x0 + x, y0 + y);
                int r = mix((d >> 16) & 255, (s >> 16) & 255, a);
                int gg = mix((d >> 8) & 255, (s >> 8) & 255, a);
                int b = mix(d & 255, s & 255, a);
                canvas.setRGB(x0 + x, y0 + y, (r << 16) | (gg << 8) | b);
            }
        }
    }

    /**
     * Shifts the warped face's brightness (mean and contrast) and tint (mean chroma) towards the template face's,
     * measured over the solid part of the mask, in YCbCr.
     */
    private static void matchColors(BufferedImage canvas, BufferedImage warped, double[] alpha, int x0, int y0, int w, int h) {
        double[] src = new double[6];   // sum Y, sum Y^2, sum Cb, sum Cr, count, (unused)
        double[] dst = new double[6];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (alpha[y * w + x] < 0.9) {
                    continue;
                }
                accumulate(src, ycc(warped.getRGB(x, y)));
                accumulate(dst, ycc(canvas.getRGB(x0 + x, y0 + y)));
            }
        }
        if (src[4] < 20 || dst[4] < 20) {
            return;
        }
        double sMeanY = src[0] / src[4];
        double dMeanY = dst[0] / dst[4];
        double sSd = Math.sqrt(Math.max(1e-6, src[1] / src[4] - sMeanY * sMeanY));
        double dSd = Math.sqrt(Math.max(1e-6, dst[1] / dst[4] - dMeanY * dMeanY));
        double gain = Math.max(0.6, Math.min(1.6, dSd / sSd));
        double dCb = dst[2] / dst[4] - src[2] / src[4];
        double dCr = dst[3] / dst[4] - src[3] / src[4];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (alpha[y * w + x] <= 0) {
                    continue;
                }
                int argb = warped.getRGB(x, y);
                double[] c = ycc(argb);
                double ny = (c[0] - sMeanY) * gain + dMeanY;
                double[] adjusted = {ny, c[1] + dCb, c[2] + dCr};
                int rgb = rgb(new double[]{
                        lerp(c[0], adjusted[0], COLOR_MATCH),
                        lerp(c[1], adjusted[1], COLOR_MATCH),
                        lerp(c[2], adjusted[2], COLOR_MATCH)});
                warped.setRGB(x, y, (argb & 0xFF000000) | rgb);
            }
        }
    }

    /**
     * Least-squares similarity transform (Umeyama, no reflection) mapping the source landmarks onto the target's.
     */
    static AffineTransform similarity(DetectedFace from, DetectedFace to) {
        double mfx = 0, mfy = 0, mtx = 0, mty = 0;
        for (int i = 0; i < 5; i++) {
            mfx += from.lx(i);
            mfy += from.ly(i);
            mtx += to.lx(i);
            mty += to.ly(i);
        }
        mfx /= 5;
        mfy /= 5;
        mtx /= 5;
        mty /= 5;
        double var = 0, a = 0, b = 0;
        for (int i = 0; i < 5; i++) {
            double px = from.lx(i) - mfx, py = from.ly(i) - mfy;
            double qx = to.lx(i) - mtx, qy = to.ly(i) - mty;
            var += px * px + py * py;
            a += px * qx + py * qy;
            b += px * qy - py * qx;
        }
        double sc = a / var;   // scale * cos
        double ss = b / var;   // scale * sin
        double tx = mtx - sc * mfx + ss * mfy;
        double ty = mty - ss * mfx - sc * mfy;
        return new AffineTransform(sc, ss, -ss, sc, tx, ty);
    }

    private static void accumulate(double[] acc, double[] c) {
        acc[0] += c[0];
        acc[1] += c[0] * c[0];
        acc[2] += c[1];
        acc[3] += c[2];
        acc[4] += 1;
    }

    private static double[] ycc(int argb) {
        double r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        double y = 0.299 * r + 0.587 * g + 0.114 * b;
        return new double[]{y, -0.168736 * r - 0.331264 * g + 0.5 * b, 0.5 * r - 0.418688 * g - 0.081312 * b};
    }

    private static int rgb(double[] c) {
        int r = clamp255(c[0] + 1.402 * c[2]);
        int g = clamp255(c[0] - 0.344136 * c[1] - 0.714136 * c[2]);
        int b = clamp255(c[0] + 1.772 * c[1]);
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int under, int over, double a) {
        return (int) Math.round(under * (1 - a) + over * a);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    private static int clamp255(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
