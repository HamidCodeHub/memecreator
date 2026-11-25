package com.memecreator.face;

/** Small pixel helpers on packed 0xRRGGBB int arrays. */
final class Pixels {

    private Pixels() {
    }

    /** Bilinear sample at (x, y), pixel centres at whole numbers; outside the image the edge pixels repeat. */
    static int bilinear(int[] rgb, int w, int h, double x, double y) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        double fx = x - x0;
        double fy = y - y0;
        int c00 = rgb[y0 * w + x0];
        int c10 = rgb[y0 * w + x1];
        int c01 = rgb[y1 * w + x0];
        int c11 = rgb[y1 * w + x1];
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double top = ((c00 >> shift) & 255) * (1 - fx) + ((c10 >> shift) & 255) * fx;
            double bottom = ((c01 >> shift) & 255) * (1 - fx) + ((c11 >> shift) & 255) * fx;
            int v = (int) Math.round(top * (1 - fy) + bottom * fy);
            out |= Math.max(0, Math.min(255, v)) << shift;
        }
        return out;
    }

    /** Shrinks a 0/1 mask by {@code r} pixels (square neighbourhood, two separable passes). */
    static boolean[] erode(boolean[] mask, int w, int h, int r) {
        if (r <= 0) {
            return mask.clone();
        }
        boolean[] tmp = new boolean[mask.length];
        for (int y = 0; y < h; y++) {
            int run = 0;   // pixels of the window that are set, sliding along the row
            for (int x = -r; x < w; x++) {
                int in = x + r;
                int out = x - r - 1;
                if (in < w && mask[y * w + in]) {
                    run++;
                }
                if (out >= 0 && mask[y * w + out]) {
                    run--;
                }
                if (x >= 0) {
                    int window = Math.min(w - 1, x + r) - Math.max(0, x - r) + 1;
                    tmp[y * w + x] = x - r >= 0 && x + r < w && run == window;
                }
            }
        }
        boolean[] res = new boolean[mask.length];
        for (int x = 0; x < w; x++) {
            int run = 0;
            for (int y = -r; y < h; y++) {
                int in = y + r;
                int out = y - r - 1;
                if (in < h && tmp[in * w + x]) {
                    run++;
                }
                if (out >= 0 && tmp[out * w + x]) {
                    run--;
                }
                if (y >= 0) {
                    int window = Math.min(h - 1, y + r) - Math.max(0, y - r) + 1;
                    res[y * w + x] = y - r >= 0 && y + r < h && run == window;
                }
            }
        }
        return res;
    }

    /** Grows a 0/1 mask by {@code r} pixels (square neighbourhood); pixels outside the image count as unset. */
    static boolean[] dilate(boolean[] mask, int w, int h, int r) {
        if (r <= 0) {
            return mask.clone();
        }
        boolean[] tmp = new boolean[mask.length];
        for (int y = 0; y < h; y++) {
            int run = 0;
            for (int x = -r; x < w; x++) {
                int in = x + r;
                int out = x - r - 1;
                if (in < w && mask[y * w + in]) {
                    run++;
                }
                if (out >= 0 && mask[y * w + out]) {
                    run--;
                }
                if (x >= 0) {
                    tmp[y * w + x] = run > 0;
                }
            }
        }
        boolean[] res = new boolean[mask.length];
        for (int x = 0; x < w; x++) {
            int run = 0;
            for (int y = -r; y < h; y++) {
                int in = y + r;
                int out = y - r - 1;
                if (in < h && tmp[in * w + x]) {
                    run++;
                }
                if (out >= 0 && tmp[out * w + x]) {
                    run--;
                }
                if (y >= 0) {
                    res[y * w + x] = run > 0;
                }
            }
        }
        return res;
    }

    /**
     * Fills the pixels in {@code holes} from the outside in: each new ring takes the average colour of its already
     * known neighbours (8 around), so gaps get a smooth fill instead of one pixel's colour copied along a line.
     * Changes {@code rgb} in place.
     */
    static void fillFromNearest(int[] rgb, boolean[] known, boolean[] holes, int w, int h) {
        boolean[] done = known.clone();
        java.util.List<Integer> ring = new java.util.ArrayList<>();
        for (int i = 0; i < done.length; i++) {
            if (holes[i] && !done[i] && hasDoneNeighbour(done, i, w, h)) {
                ring.add(i);
            }
        }
        while (!ring.isEmpty()) {
            int[] colours = new int[ring.size()];
            for (int k = 0; k < ring.size(); k++) {
                colours[k] = averageOfDone(rgb, done, ring.get(k), w, h);
            }
            for (int k = 0; k < ring.size(); k++) {
                rgb[ring.get(k)] = colours[k];
                done[ring.get(k)] = true;
            }
            java.util.LinkedHashSet<Integer> next = new java.util.LinkedHashSet<>();
            for (int i : ring) {
                int x = i % w, y = i / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && holes[ny * w + nx] && !done[ny * w + nx]) {
                            next.add(ny * w + nx);
                        }
                    }
                }
            }
            ring = new java.util.ArrayList<>(next);
        }
    }

    private static boolean hasDoneNeighbour(boolean[] done, int i, int w, int h) {
        int x = i % w, y = i / w;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if ((dx != 0 || dy != 0) && nx >= 0 && nx < w && ny >= 0 && ny < h && done[ny * w + nx]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int averageOfDone(int[] rgb, boolean[] done, int i, int w, int h) {
        int x = i % w, y = i / w;
        int r = 0, g = 0, b = 0, n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if ((dx != 0 || dy != 0) && nx >= 0 && nx < w && ny >= 0 && ny < h && done[ny * w + nx]) {
                    int c = rgb[ny * w + nx];
                    r += (c >> 16) & 255;
                    g += (c >> 8) & 255;
                    b += c & 255;
                    n++;
                }
            }
        }
        return n == 0 ? rgb[i] : (r / n) << 16 | (g / n) << 8 | (b / n);
    }

    /**
     * 3×3 median per colour channel over the pixels where {@code where} is set: removes isolated specks (e.g. a
     * background pixel sampled right at the face's outline) without blurring edges much. Returns a new array.
     */
    static int[] median3(int[] rgb, boolean[] where, int w, int h) {
        int[] out = rgb.clone();
        int[] r = new int[9], g = new int[9], b = new int[9];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (!where[i]) {
                    continue;
                }
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int c = rgb[i + dy * w + dx];
                        r[n] = (c >> 16) & 255;
                        g[n] = (c >> 8) & 255;
                        b[n] = c & 255;
                        n++;
                    }
                }
                java.util.Arrays.sort(r);
                java.util.Arrays.sort(g);
                java.util.Arrays.sort(b);
                out[i] = r[4] << 16 | g[4] << 8 | b[4];
            }
        }
        return out;
    }

    /**
     * Guided filter (He et al.): smooths {@code p} but lets it follow the edges of {@code guide}. Used to snap a coarse
     * person mask to the photo's real outline (hair, ears).
     */
    static double[] guidedFilter(double[] guide, double[] p, int w, int h, int r, double eps) {
        double[] meanI = boxMean(guide, w, h, r);
        double[] meanP = boxMean(p, w, h, r);
        double[] ip = new double[p.length];
        double[] ii = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            ip[i] = guide[i] * p[i];
            ii[i] = guide[i] * guide[i];
        }
        double[] corrIP = boxMean(ip, w, h, r);
        double[] corrII = boxMean(ii, w, h, r);
        double[] a = new double[p.length];
        double[] b = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            double varI = corrII[i] - meanI[i] * meanI[i];
            double covIP = corrIP[i] - meanI[i] * meanP[i];
            a[i] = covIP / (varI + eps);
            b[i] = meanP[i] - a[i] * meanI[i];
        }
        double[] meanA = boxMean(a, w, h, r);
        double[] meanB = boxMean(b, w, h, r);
        double[] q = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            q[i] = meanA[i] * guide[i] + meanB[i];
        }
        return q;
    }

    /** Mean over the (2r+1)² window around each pixel, clipped at the image edge (summed-area table). */
    static double[] boxMean(double[] v, int w, int h, int r) {
        double[] sat = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                row += v[y * w + x];
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row;
            }
        }
        double[] out = new double[v.length];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                double sum = sat[(y1 + 1) * (w + 1) + x1 + 1] - sat[y0 * (w + 1) + x1 + 1]
                        - sat[(y1 + 1) * (w + 1) + x0] + sat[y0 * (w + 1) + x0];
                out[y * w + x] = sum / ((x1 - x0 + 1) * (y1 - y0 + 1));
            }
        }
        return out;
    }
}
