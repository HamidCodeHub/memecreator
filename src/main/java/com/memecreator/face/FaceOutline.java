package com.memecreator.face;

/**
 * Grows the face mesh past its own edge, for the FULL style: a ring of extra points outside the face outline, far
 * up the forehead towards the hairline and a little out at the cheeks and jaw. Built the same way on the user's face
 * and on the target, so the ring between the outline and the extra points can be warped like the rest of the mesh.
 */
final class FaceOutline {

    /** The face outline of the MediaPipe mesh, in order around the face (forehead, the image's right, chin, left). */
    static final int[] OVAL = {10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400,
            377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109};
    private static final int FOREHEAD = 10;
    private static final int CHIN = 152;

    private FaceOutline() {
    }

    /**
     * The mesh's 468 points followed by one extra point per outline point, pushed out from the face centre by
     * {@code top} (straight up), {@code side} (left/right) or {@code chin} (straight down) of its distance, blended
     * smoothly in between. Returns x0, y0, x1, y1, ...
     */
    static double[] extend(FaceMesh m, double top, double side, double chin) {
        int n = FaceMesh.SIZE;
        double[] pts = new double[2 * (n + OVAL.length)];
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            pts[2 * i] = m.x(i);
            pts[2 * i + 1] = m.y(i);
            cx += m.x(i);
            cy += m.y(i);
        }
        cx /= n;
        cy /= n;
        double ux = m.x(FOREHEAD) - m.x(CHIN), uy = m.y(FOREHEAD) - m.y(CHIN);
        double ul = Math.hypot(ux, uy);
        ux /= ul;
        uy /= ul;
        for (int k = 0; k < OVAL.length; k++) {
            double dx = m.x(OVAL[k]) - cx, dy = m.y(OVAL[k]) - cy;
            double len = Math.hypot(dx, dy);
            double up = len == 0 ? 0 : (dx * ux + dy * uy) / len;   // 1 = straight up the face, -1 = down
            double e = up >= 0 ? side + (top - side) * up * up : side + (chin - side) * up * up;
            pts[2 * (n + k)] = cx + dx * (1 + e);
            pts[2 * (n + k) + 1] = cy + dy * (1 + e);
        }
        return pts;
    }

    /**
     * The mesh's triangles plus two per outline segment for the ring, oriented like the mesh's own triangles on
     * {@code reference} (an extended point list) so the warp's fold check treats them alike.
     */
    static int[][] triangles(int[][] mesh, double[] reference) {
        int n = FaceMesh.SIZE;
        int[][] out = new int[mesh.length + 2 * OVAL.length][];
        System.arraycopy(mesh, 0, out, 0, mesh.length);
        int t = mesh.length;
        for (int k = 0; k < OVAL.length; k++) {
            int a = OVAL[k], b = OVAL[(k + 1) % OVAL.length];
            int ea = n + k, eb = n + (k + 1) % OVAL.length;
            out[t++] = oriented(a, b, eb, reference);
            out[t++] = oriented(a, eb, ea, reference);
        }
        return out;
    }

    private static int[] oriented(int a, int b, int c, double[] p) {
        double area = MeshWarp.area(p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1], p[2 * c], p[2 * c + 1]);
        return area >= 0 ? new int[]{a, b, c} : new int[]{a, c, b};
    }
}
