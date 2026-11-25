package com.memecreator.face;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeshWarpTest {

    private static final int W = 400;
    private static final int H = 420;

    @Test
    void sameMeshCopiesTheFaceExactly() {
        FaceMesh mesh = TestMeshes.frontal();
        int[] src = pattern();

        MeshWarp.Result r = MeshWarp.warp(src, W, H, mesh, new int[W * H], W, H, mesh);

        int covered = 0;
        for (int i = 0; i < src.length; i++) {
            if (r.covered()[i]) {
                covered++;
                assertThat(r.rgb()[i]).isEqualTo(src[i]);
            }
        }
        assertThat(covered).isGreaterThan(50_000);   // the whole face, not a few triangles
    }

    @Test
    void movedMeshMovesTheFace() {
        FaceMesh from = TestMeshes.frontal();
        FaceMesh to = TestMeshes.transformed(from, 1.0, 30, 12);
        int[] src = pattern();

        MeshWarp.Result r = MeshWarp.warp(src, W, H, from, new int[W * H], W, H, to);

        // the nose tip pixel lands 30 right and 12 down
        int nx = (int) Math.round(from.x(FaceMesh.NOSE_TIP));
        int ny = (int) Math.round(from.y(FaceMesh.NOSE_TIP));
        assertThat(r.covered()[(ny + 12) * W + nx + 30]).isTrue();
        assertThat(r.rgb()[(ny + 12) * W + nx + 30]).isEqualTo(src[ny * W + nx]);
    }

    @Test
    void uncoveredPixelsKeepTheTemplate() {
        FaceMesh mesh = TestMeshes.frontal();
        int[] template = new int[W * H];
        java.util.Arrays.fill(template, 0x123456);

        MeshWarp.Result r = MeshWarp.warp(pattern(), W, H, mesh, template, W, H, mesh);

        assertThat(r.covered()[0]).isFalse();
        assertThat(r.rgb()[0]).isEqualTo(0x123456);
    }

    @Test
    void foldedTrianglesAreSkipped() {
        // a horizontally flipped point layout turns every triangle inside out
        FaceMesh mesh = TestMeshes.frontal();
        double[] pts = mesh.points();
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            pts[2 * i] = W - pts[2 * i];
        }
        FaceMesh flipped = new FaceMesh(pts, 1.0);

        MeshWarp.Result r = MeshWarp.warp(pattern(), W, H, mesh, new int[W * H], W, H, flipped);

        for (boolean c : r.covered()) {
            assertThat(c).isFalse();
        }
    }

    @Test
    void erodeShrinksAMask() {
        boolean[] sq = new boolean[20 * 20];
        for (int y = 5; y < 15; y++) {
            for (int x = 5; x < 15; x++) {
                sq[y * 20 + x] = true;
            }
        }
        boolean[] e = Pixels.erode(sq, 20, 20, 2);
        int n = 0;
        for (boolean b : e) {
            n += b ? 1 : 0;
        }
        assertThat(n).isEqualTo(36);
        assertThat(e[7 * 20 + 7]).isTrue();
        assertThat(e[6 * 20 + 6]).isFalse();
    }

    /** Every pixel different, so a wrong mapping shows. */
    private static int[] pattern() {
        int[] p = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                p[y * W + x] = (x & 255) << 16 | (y & 255) << 8 | ((x + y) & 255);
            }
        }
        return p;
    }
}
