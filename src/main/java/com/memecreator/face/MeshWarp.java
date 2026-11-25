package com.memecreator.face;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Warps one face onto another, triangle by triangle over the face mesh: every triangle of the source face is
 * stretched onto the same triangle of the target face, so the pasted face takes the target's shape, size, tilt and
 * head turn. Triangles that are folded over in either face, or seen nearly edge-on in one of them (the far cheek of
 * a turned head), are skipped; the caller fills small gaps from the neighbouring pixels.
 */
final class MeshWarp {

    private static final String TRIANGLES = "/models/face_mesh_triangles.txt";
    private static final int[][] TRIS = load();
    /**
     * Triangles squeezed below this fraction of their size in the photo, or stretched beyond its inverse (relative to
     * the whole face), are skipped.
     */
    static final double EDGE_ON = 0.3;

    private MeshWarp() {
    }

    /** A warped face over the whole template: {@code rgb} has the template's pixels where {@code covered} is false. */
    record Result(int[] rgb, boolean[] covered, int width, int height) {
    }

    static int triangleCount() {
        return TRIS.length;
    }

    static Result warp(int[] src, int sw, int sh, FaceMesh from, int[] dst, int dw, int dh, FaceMesh to) {
        return warp(src, sw, sh, from.points(), dst, dw, dh, to.points(), TRIS);
    }

    /** The mesh's own triangles (point indices). */
    static int[][] meshTriangles() {
        return TRIS;
    }

    /**
     * General form: {@code from} and {@code to} are x0, y0, x1, y1, ... point lists of the same length (the 468 mesh
     * points, possibly followed by extra points), {@code tris} indexes into them.
     */
    static Result warp(int[] src, int sw, int sh, double[] from, int[] dst, int dw, int dh, double[] to,
                       int[][] tris) {
        int[] out = dst.clone();
        boolean[] covered = new boolean[dw * dh];
        double[] dAreas = new double[tris.length];
        double[] sAreas = new double[tris.length];
        double dSum = 0, sSum = 0;
        for (int k = 0; k < tris.length; k++) {
            int[] t = tris[k];
            dAreas[k] = area(to[2 * t[0]], to[2 * t[0] + 1], to[2 * t[1]], to[2 * t[1] + 1], to[2 * t[2]], to[2 * t[2] + 1]);
            sAreas[k] = area(from[2 * t[0]], from[2 * t[0] + 1], from[2 * t[1]], from[2 * t[1] + 1], from[2 * t[2]], from[2 * t[2] + 1]);
            if (dAreas[k] > 0 && sAreas[k] > 0) {
                dSum += dAreas[k];
                sSum += sAreas[k];
            }
        }
        double scale = sSum > 0 ? dSum / sSum : 1;   // how much bigger the target face is overall
        for (int k = 0; k < tris.length; k++) {
            int[] t = tris[k];
            double dArea = dAreas[k];
            double sArea = sAreas[k];
            if (dArea <= 1e-9 || sArea <= 0) {
                continue;   // folded or degenerate: every triangle in the file has a positive area on a frontal face
            }
            double stretch = dArea / (sArea * scale);
            if (stretch < EDGE_ON || stretch > 1 / EDGE_ON) {
                // nearly edge-on in the template (the far side of a turned head): keep the template there; or
                // nearly edge-on in the photo, where a few pixels would be smeared over a big area
                continue;
            }
            double ax = to[2 * t[0]], ay = to[2 * t[0] + 1];
            double bx = to[2 * t[1]], by = to[2 * t[1] + 1];
            double cx = to[2 * t[2]], cy = to[2 * t[2] + 1];
            // affine map from target pixel to source pixel, through barycentric coordinates
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
            int x1 = Math.min(dw - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
            int y1 = Math.min(dh - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
            double inv = 1 / (2 * dArea);
            double eps = -1e-9;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    // barycentric weights of (x, y): w0 for a, w1 for b, w2 for c
                    double w0 = ((bx - x) * (cy - y) - (cx - x) * (by - y)) * inv;
                    double w1 = ((cx - x) * (ay - y) - (ax - x) * (cy - y)) * inv;
                    double w2 = 1 - w0 - w1;
                    if (w0 < eps || w1 < eps || w2 < eps) {
                        continue;
                    }
                    double sx = w0 * from[2 * t[0]] + w1 * from[2 * t[1]] + w2 * from[2 * t[2]];
                    double sy = w0 * from[2 * t[0] + 1] + w1 * from[2 * t[1] + 1] + w2 * from[2 * t[2] + 1];
                    out[y * dw + x] = Pixels.bilinear(src, sw, sh, sx, sy);
                    covered[y * dw + x] = true;
                }
            }
        }
        return new Result(out, covered, dw, dh);
    }

    /** Signed area; positive when a, b, c run clockwise on screen (y pointing down). */
    static double area(double ax, double ay, double bx, double by, double cx, double cy) {
        return 0.5 * ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay));
    }

    private static int[][] load() {
        try (InputStream in = MeshWarp.class.getResourceAsStream(TRIANGLES)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + TRIANGLES);
            }
            List<int[]> tris = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\s+");
                tris.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
            }
            return tris.toArray(new int[0][]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
