package com.memecreator.face;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/**
 * The user's whole head cut out of their photo, the classic meme way: face, hair, ears and hat, cut off just below
 * the chin, on a transparent background. The person is separated from the background with {@link PersonSegmenter};
 * the coarse mask is sharpened along the photo's own edges with a guided filter. Without a segmenter (or if it
 * fails) the head is cut along an oval around the face mesh instead.
 *
 * <p>Where the person mask leaks into a background of similar brightness (a light wall next to a bald head), GrabCut
 * separates head from background by colour, and everything outside a head-shaped area around the face is dropped.
 *
 * @param image  the head on transparency (ARGB), a square crop around it
 * @param mesh   the face mesh in this image's coordinates
 * @param face   the 5-point face in this image's coordinates
 * @param ox     where the crop's top-left corner is in the photo
 * @param oy     ditto
 * @param scale  crop pixels per photo pixel
 */
record HeadCutout(BufferedImage image, FaceMesh mesh, DetectedFace face, double ox, double oy, double scale) {

    private static final Logger log = LoggerFactory.getLogger(HeadCutout.class);
    /** The crop is this many face widths wide, centred a bit above the face so hair and hats fit. */
    private static final double CROP_WIDTHS = 3.2;
    private static final double CROP_UP = 0.22;
    /** Bigger crops are scaled down first; the head ends up smaller than this on a template anyway. */
    private static final int MAX_SIDE = 800;
    /** Keep this much neck below the chin (fraction of the face length), fading out over NECK_FADE. */
    private static final double NECK = 0.03;
    private static final double NECK_FADE = 0.05;
    /** The head-shaped area hair may fill: this far past the face outline, upwards and sideways. */
    private static final double SKULL_TOP = 0.95;
    private static final double SKULL_SIDE = 0.28;
    /** GrabCut runs on a crop at most this big (it is slow), the result is sharpened at full size. */
    private static final int GRABCUT_SIDE = 360;
    private static final int GRABCUT_ITERATIONS = 4;
    /** Below the nose the cut follows the jaw, widened by this fraction (cheeks, a bit of beard). */
    private static final double JAW_WIDEN = 0.10;

    /** Cuts the head out of {@code photo}, without GrabCut. */
    static HeadCutout cut(BufferedImage photo, DetectedFace face, FaceMesh mesh, PersonSegmenter segmenter) {
        return cut(photo, face, mesh, segmenter, null);
    }

    /**
     * Cuts the head out of {@code photo}. {@code segmenter} may be null (then the oval cut is used), {@code ops} too
     * (then no GrabCut).
     */
    static HeadCutout cut(BufferedImage photo, DetectedFace face, FaceMesh mesh, PersonSegmenter segmenter,
                          ImageOps ops) {
        double[] centre = centroid(mesh);
        double ux = mesh.x(10) - mesh.x(152), uy = mesh.y(10) - mesh.y(152);
        double faceLen = Math.hypot(ux, uy);
        ux /= faceLen;
        uy /= faceLen;
        double sideSrc = CROP_WIDTHS * Math.max(mesh.width(), faceLen * 0.8);
        double cx = centre[0] + ux * CROP_UP * sideSrc;
        double cy = centre[1] + uy * CROP_UP * sideSrc;
        int side = (int) Math.min(MAX_SIDE, Math.round(sideSrc));
        double scale = side / sideSrc;                 // photo pixels → crop pixels
        double ox = cx - sideSrc / 2, oy = cy - sideSrc / 2;

        // the square crop, with what lies outside the photo marked as such
        int w = photo.getWidth(), h = photo.getHeight();
        int[] src = photo.getRGB(0, 0, w, h, null, 0, w);
        int[] rgb = new int[side * side];
        boolean[] inside = new boolean[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                double px = ox + x / scale, py = oy + y / scale;
                inside[y * side + x] = px >= 0 && py >= 0 && px <= w - 1 && py <= h - 1;
                rgb[y * side + x] = inside[y * side + x] ? Pixels.bilinear(src, w, h, px, py) : 0;
            }
        }
        FaceMesh m = mapMesh(mesh, ox, oy, scale);
        DetectedFace f = mapFace(face, ox, oy, scale);

        double[] skull = Pixels.boxMean(rasterize(outline(m, SKULL_TOP, SKULL_SIDE, 0.3), side), side, side, 2);
        double[] alpha = null;
        if (segmenter != null) {
            try {
                double[] prob = personProbability(rgb, side, segmenter);
                double[] fg = prob;
                int radius = Math.max(2, side / 40);
                if (ops != null) {
                    try {
                        fg = grabCut(rgb, side, prob, skull, m, ops);
                        radius = Math.max(2, side / 120);   // GrabCut already follows the outline: only smooth it
                    } catch (RuntimeException | LinkageError e) {
                        log.warn("GrabCut failed ({}), using the person mask as it is", e.toString());
                    }
                }
                alpha = sharpen(rgb, side, fg, radius);
            } catch (RuntimeException | LinkageError e) {
                log.warn("Person segmentation failed ({}), cutting the head along an oval", e.toString());
            }
        }
        if (alpha == null) {
            alpha = ovalAlpha(m, side);
        }
        for (int i = 0; i < alpha.length; i++) {
            alpha[i] *= skull[i];
        }
        cutBelowChin(alpha, side, m);
        keepConnectedTo(alpha, side, centroid(m));
        for (int i = 0; i < alpha.length; i++) {
            if (!inside[i]) {
                alpha[i] = 0;
            }
        }

        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[side * side];
        for (int i = 0; i < argb.length; i++) {
            int a = (int) Math.round(Math.max(0, Math.min(1, alpha[i])) * 255);
            argb[i] = a << 24 | (rgb[i] & 0xFFFFFF);
        }
        out.setRGB(0, 0, side, side, argb, 0, side);
        return new HeadCutout(out, m, f, ox, oy, scale);
    }

    /** Person probability from the segmenter, upsampled to the crop. */
    private static double[] personProbability(int[] rgb, int side, PersonSegmenter segmenter) {
        int n = PersonSegmenter.INPUT_SIZE;
        float[] input = new float[n * n * 3];
        double k = (side - 1) / (double) (n - 1);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int c = Pixels.bilinear(rgb, side, side, x * k, y * k);
                int i = (y * n + x) * 3;
                input[i] = ((c >> 16) & 255) / 127.5f - 1;
                input[i + 1] = ((c >> 8) & 255) / 127.5f - 1;
                input[i + 2] = (c & 255) / 127.5f - 1;
            }
        }
        float[] logits = segmenter.segment(input);
        int g = PersonSegmenter.OUTPUT_SIZE;
        double[] prob = new double[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                // crop pixel → input pixel → mask cell (cell j sits on input pixel 16·j)
                double gx = Math.min(g - 1, x / k / PersonSegmenter.STRIDE);
                double gy = Math.min(g - 1, y / k / PersonSegmenter.STRIDE);
                int x0 = (int) gx, y0 = (int) gy;
                int x1 = Math.min(x0 + 1, g - 1), y1 = Math.min(y0 + 1, g - 1);
                double fx = gx - x0, fy = gy - y0;
                double l = logits[y0 * g + x0] * (1 - fx) * (1 - fy) + logits[y0 * g + x1] * fx * (1 - fy)
                        + logits[y1 * g + x0] * (1 - fx) * fy + logits[y1 * g + x1] * fx * fy;
                prob[y * side + x] = 1 / (1 + Math.exp(-l));
            }
        }
        return prob;
    }

    /**
     * GrabCut on a smaller copy: sure background outside the head-shaped area and where the person mask is clearly
     * empty, sure foreground in the middle of the face, the rest decided by colour.
     */
    private static double[] grabCut(int[] rgb, int side, double[] prob, double[] skull, FaceMesh m, ImageOps ops) {
        int gs = Math.min(side, GRABCUT_SIDE);
        double k = side / (double) gs;
        double[] core = rasterize(outline(m, -0.2, -0.2, -0.2), side);   // well inside the face
        int[] small = new int[gs * gs];
        byte[] labels = new byte[gs * gs];
        for (int y = 0; y < gs; y++) {
            for (int x = 0; x < gs; x++) {
                small[y * gs + x] = Pixels.bilinear(rgb, side, side, (x + 0.5) * k - 0.5, (y + 0.5) * k - 0.5);
                int i = (int) Math.min(side - 1, (y + 0.5) * k) * side + (int) Math.min(side - 1, (x + 0.5) * k);
                byte label;
                if (core[i] > 0.5) {
                    label = ImageOps.FOREGROUND;
                } else if (skull[i] < 0.5 || prob[i] < 0.15) {
                    label = ImageOps.BACKGROUND;
                } else {
                    label = prob[i] >= 0.5 ? ImageOps.PROBABLY_FOREGROUND : ImageOps.PROBABLY_BACKGROUND;
                }
                labels[y * gs + x] = label;
            }
        }
        boolean[] fg = ops.grabCut(small, gs, gs, labels, GRABCUT_ITERATIONS);
        double[] out = new double[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                double sx = (x + 0.5) / k - 0.5, sy = (y + 0.5) / k - 0.5;   // bilinear upsampling of the 0/1 result
                int x0 = (int) Math.max(0, Math.min(gs - 1, Math.floor(sx))), y0 = (int) Math.max(0, Math.min(gs - 1, Math.floor(sy)));
                int x1 = Math.min(gs - 1, x0 + 1), y1 = Math.min(gs - 1, y0 + 1);
                double fx = Math.max(0, Math.min(1, sx - x0)), fy = Math.max(0, Math.min(1, sy - y0));
                out[y * side + x] = (fg[y0 * gs + x0] ? 1 : 0) * (1 - fx) * (1 - fy) + (fg[y0 * gs + x1] ? 1 : 0) * fx * (1 - fy)
                        + (fg[y1 * gs + x0] ? 1 : 0) * (1 - fx) * fy + (fg[y1 * gs + x1] ? 1 : 0) * fx * fy;
            }
        }
        return out;
    }

    /** A soft mask snapped to the crop's own edges (guided filter), then firmed up. */
    private static double[] sharpen(int[] rgb, int side, double[] prob, int radius) {
        double[] gray = new double[side * side];
        for (int i = 0; i < gray.length; i++) {
            int c = rgb[i];
            gray[i] = (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255)) / 255;
        }
        double[] q = Pixels.guidedFilter(gray, prob, side, side, radius, 1e-3);
        for (int i = 0; i < q.length; i++) {
            q[i] = Math.max(0, Math.min(1, (q[i] - 0.3) / 0.4));   // firm up the edge
        }
        return q;
    }

    /** Fallback: an oval reaching well up the forehead, from the face outline. */
    private static double[] ovalAlpha(FaceMesh m, int side) {
        return rasterize(outline(m, 0.7, 0.25, 0.05), side);
    }

    /** The face outline pushed out (or, with negative values, pulled in) as in {@link FaceOutline#extend}. */
    private static Path2D outline(FaceMesh m, double top, double sideways, double chin) {
        double[] ext = FaceOutline.extend(m, top, sideways, chin);
        Path2D.Double path = new Path2D.Double();
        for (int k = 0; k < FaceOutline.OVAL.length; k++) {
            double x = ext[2 * (FaceMesh.SIZE + k)], y = ext[2 * (FaceMesh.SIZE + k) + 1];
            if (k == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
        return path;
    }

    /**
     * Below the nose only the face itself is kept, inside its jaw outline (a little widened), so collars, necks and
     * shoulders go; above the nose the whole person mask stays (hair, ears, hats). Fades out just below the chin.
     */
    private static void cutBelowChin(double[] alpha, int side, FaceMesh m) {
        double ux = m.x(10) - m.x(152), uy = m.y(10) - m.y(152);
        double len = Math.hypot(ux, uy);
        ux /= len;
        uy /= len;
        double[] jaw = jawCoverage(m, side);
        double noseX = m.x(FaceMesh.NOSE_TIP), noseY = m.y(FaceMesh.NOSE_TIP);
        double chinX = m.x(152), chinY = m.y(152);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int i = y * side + x;
                double up = ((x - chinX) * ux + (y - chinY) * uy) / len;   // 0 at the chin, negative below
                double keep = Math.max(0, Math.min(1, (up + NECK) / NECK_FADE));
                double belowNose = -((x - noseX) * ux + (y - noseY) * uy) / len;   // > 0 below the nose
                double inFace = belowNose <= 0 ? 1 : jaw[i] + (1 - jaw[i]) * Math.max(0, 1 - belowNose / 0.06);
                alpha[i] *= keep * inFace;
            }
        }
    }

    /** 0..1 coverage of the face outline, widened at the sides, antialiased. */
    private static double[] jawCoverage(FaceMesh m, int side) {
        return rasterize(outline(m, 0.2, JAW_WIDEN, NECK), side);
    }

    private static double[] rasterize(Path2D path, int side) {
        BufferedImage mask = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        var g = mask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);
        g.fill(path);
        g.dispose();
        double[] a = new double[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                a[y * side + x] = mask.getRaster().getSample(x, y, 0) / 255.0;
            }
        }
        return a;
    }

    /** Drops everything not connected to the face (a shoulder, someone else in the photo). */
    private static void keepConnectedTo(double[] alpha, int side, double[] seed) {
        boolean[] keep = new boolean[alpha.length];
        int sx = (int) Math.max(0, Math.min(side - 1, Math.round(seed[0])));
        int sy = (int) Math.max(0, Math.min(side - 1, Math.round(seed[1])));
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int start = sy * side + sx;
        if (alpha[start] < 0.5) {
            return;   // the face itself isn't in the mask: leave it as it is rather than drop everything
        }
        keep[start] = true;
        queue.add(start);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % side, y = i / side;
            int[] next = {x > 0 ? i - 1 : -1, x < side - 1 ? i + 1 : -1, y > 0 ? i - side : -1, y < side - 1 ? i + side : -1};
            for (int n : next) {
                if (n >= 0 && !keep[n] && alpha[n] >= 0.5) {
                    keep[n] = true;
                    queue.add(n);
                }
            }
        }
        boolean[] soft = Pixels.dilate(keep, side, side, 2);   // keep the soft edge around the kept part
        for (int i = 0; i < alpha.length; i++) {
            if (!soft[i]) {
                alpha[i] = 0;
            }
        }
    }

    private static double[] centroid(FaceMesh m) {
        double x = 0, y = 0;
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            x += m.x(i);
            y += m.y(i);
        }
        return new double[]{x / FaceMesh.SIZE, y / FaceMesh.SIZE};
    }

    private static FaceMesh mapMesh(FaceMesh m, double ox, double oy, double s) {
        double[] p = m.points();
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            p[2 * i] = (p[2 * i] - ox) * s;
            p[2 * i + 1] = (p[2 * i + 1] - oy) * s;
        }
        return new FaceMesh(p, m.score());
    }

    private static DetectedFace mapFace(DetectedFace f, double ox, double oy, double s) {
        double[] lm = new double[10];
        for (int i = 0; i < 5; i++) {
            lm[2 * i] = (f.lx(i) - ox) * s;
            lm[2 * i + 1] = (f.ly(i) - oy) * s;
        }
        return new DetectedFace((f.x() - ox) * s, (f.y() - oy) * s, f.width() * s, f.height() * s, lm, f.score());
    }
}
