package com.memecreator.face;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** A real frontal face mesh (from a template photo), moved so it starts at (20, 20). About 290 × 325 px. */
final class TestMeshes {

    private TestMeshes() {
    }

    static FaceMesh frontal() {
        double[] pts = new double[2 * FaceMesh.SIZE];
        try (InputStream in = TestMeshes.class.getResourceAsStream("/face/frontal-mesh.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            int i = 0;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                pts[i++] = Double.parseDouble(p[0]);
                pts[i++] = Double.parseDouble(p[1]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            minX = Math.min(minX, pts[2 * i]);
            minY = Math.min(minY, pts[2 * i + 1]);
        }
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            pts[2 * i] += 20 - minX;
            pts[2 * i + 1] += 20 - minY;
        }
        return new FaceMesh(pts, 1.0);
    }

    /** Every point moved by (dx, dy) and scaled by s around the origin. */
    static FaceMesh transformed(FaceMesh m, double s, double dx, double dy) {
        double[] pts = m.points();
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            pts[2 * i] = pts[2 * i] * s + dx;
            pts[2 * i + 1] = pts[2 * i + 1] * s + dy;
        }
        return new FaceMesh(pts, m.score());
    }
}
