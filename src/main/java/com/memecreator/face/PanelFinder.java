package com.memecreator.face;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the panel a face is in, on templates made of several pictures (Anakin and Padme's 4 panels, Drake, Harold):
 * the straight lines where one picture meets the next. A line counts when the image jumps right at it along at least
 * half of its length; shelves, window frames or text don't, because they only run part of the way. Single pictures
 * give the whole image. Used to keep a pasted face or head inside its own panel.
 */
final class PanelFinder {

    /** Share of the line that must show a jump right at it. */
    private static final double MIN_SHARE = 0.5;

    private PanelFinder() {
    }

    /**
     * The panel around {@code face} (whole image if there are no panel lines around it). Searched both ways — rows
     * across the whole width then columns within that band, and columns across the whole height then rows within
     * that column — and the smaller result kept: a line between two pictures may run along only part of the image
     * (Drake's pictures sit next to a white caption column).
     */
    static Rectangle panelAround(BufferedImage image, DetectedFace face) {
        int w = image.getWidth(), h = image.getHeight();
        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        float[] g = new float[w * h];
        for (int i = 0; i < g.length; i++) {
            int c = rgb[i];
            g[i] = (float) (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255));
        }
        // a panel edge may not cross the core of the face (eyes, nose, mouth corners); the face box is not used
        // because on close-ups it reaches past the panel
        double[] core = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < 5; i++) {
            core[0] = Math.min(core[0], face.lx(i));
            core[1] = Math.min(core[1], face.ly(i));
            core[2] = Math.max(core[2], face.lx(i));
            core[3] = Math.max(core[3], face.ly(i));
        }
        Rectangle rowsFirst = search(g, w, h, core, true);
        Rectangle colsFirst = search(g, w, h, core, false);
        return rowsFirst.intersection(colsFirst);
    }

    /** One search order: lines across the whole image first, then across the band between them. */
    private static Rectangle search(float[] g, int w, int h, double[] core, boolean rowsFirst) {
        int x0 = 0, y0 = 0, x1 = w - 1, y1 = h - 1;
        for (int pass = 0; pass < 2; pass++) {
            boolean rows = (pass == 0) == rowsFirst;
            if (rows) {
                final int left = x0, span = x1 - x0 + 1;
                // boundary y lies between rows y and y+1
                List<Integer> lines = seams(h - 1, span, (i, k) -> Math.abs(g[(i + 1) * w + left + k] - g[i * w + left + k]));
                for (int y : lines) {
                    if (y + 1 <= core[1]) {
                        y0 = Math.max(y0, y + 1);
                    } else if (y >= core[3]) {
                        y1 = Math.min(y1, y);
                    }
                }
            } else {
                final int top = y0, span = y1 - y0 + 1;
                List<Integer> lines = seams(w - 1, span, (i, k) -> Math.abs(g[(top + k) * w + i + 1] - g[(top + k) * w + i]));
                for (int x : lines) {
                    if (x + 1 <= core[0]) {
                        x0 = Math.max(x0, x + 1);
                    } else if (x >= core[2]) {
                        x1 = Math.min(x1, x);
                    }
                }
            }
        }
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    @FunctionalInterface
    private interface Diff {
        /** |difference| across boundary {@code i}, at position {@code k} along it. */
        float at(int i, int k);
    }

    /**
     * Boundaries 0..n-1 (each {@code m} samples long) where most samples jump right there, not just nearby. Of
     * neighbouring boundaries that both qualify (an edge blurred over two pixels), the sharper one is kept.
     */
    private static List<Integer> seams(int n, int m, Diff d) {
        List<Integer> out = new ArrayList<>();
        int lastSharp = -1;
        for (int i = 1; i < n - 2; i++) {
            int hits = 0, sharp = 0;
            for (int k = 0; k < m; k++) {
                float prev = d.at(i - 1, k), here = d.at(i, k), next = d.at(i + 1, k), after = d.at(i + 2, k);
                boolean step = here > 2 * Math.max(prev, next) + 6;
                boolean smeared = here + next > 2 * Math.max(prev, after) + 6;   // a line blurred over 2 pixels
                if (step || smeared) {
                    hits++;
                }
                if (step) {
                    sharp++;
                }
            }
            if (hits < MIN_SHARE * m) {
                continue;
            }
            if (!out.isEmpty() && i - out.get(out.size() - 1) <= 2) {
                if (sharp > lastSharp) {
                    out.set(out.size() - 1, i);
                    lastSharp = sharp;
                }
                continue;
            }
            out.add(i);
            lastSharp = sharp;
        }
        return out;
    }
}
