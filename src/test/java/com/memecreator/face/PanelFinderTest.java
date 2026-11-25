package com.memecreator.face;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PanelFinderTest {

    private static final DetectedFace FACE_BOTTOM_LEFT = new DetectedFace(80, 280, 60, 60,
            new double[]{95, 300, 125, 300, 110, 315, 98, 330, 122, 330}, 1);

    @Test
    void findsThePanelOfAFourPanelGrid() {
        // 2 × 2 panels of 200 × 200 with similar but different textures, like Anakin and Padme's
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(1);
        int[][] base = {{90, 140, 70}, {150, 110, 90}, {60, 100, 140}, {170, 160, 120}};
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 400; x++) {
                int[] c = base[(y / 200) * 2 + x / 200];
                int n = r.nextInt(8) - 4;
                img.setRGB(x, y, new Color(clamp(c[0] + n), clamp(c[1] + n), clamp(c[2] + n)).getRGB());
            }
        }

        Rectangle p = PanelFinder.panelAround(img, FACE_BOTTOM_LEFT);

        assertThat(p.x).isEqualTo(0);
        assertThat(p.y).isEqualTo(200);
        assertThat(p.width).isEqualTo(200);
        assertThat(p.height).isEqualTo(200);
    }

    @Test
    void aLineAcrossPartOfThePictureIsNotAPanelEdge() {
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 110, 100));
        g.fillRect(0, 0, 400, 400);
        g.setColor(Color.WHITE);
        g.fillRect(0, 150, 150, 6);       // a shelf across a third of the picture, above the face
        g.fillRect(250, 0, 4, 120);       // a window frame part of the way down
        g.dispose();

        Rectangle p = PanelFinder.panelAround(img, FACE_BOTTOM_LEFT);

        assertThat(p).isEqualTo(new Rectangle(0, 0, 400, 400));
    }

    @Test
    void clipKeepsChangesInsideThePanel() {
        BufferedImage before = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage changed = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = changed.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 10, 10);
        g.dispose();

        BufferedImage out = MeshFaceSwapper.clip(before, changed, new Rectangle(0, 5, 5, 5));

        assertThat(out.getRGB(2, 7) & 0xFFFFFF).isEqualTo(0xFF0000);
        assertThat(out.getRGB(2, 2) & 0xFFFFFF).isEqualTo(0);
        assertThat(out.getRGB(7, 7) & 0xFFFFFF).isEqualTo(0);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
