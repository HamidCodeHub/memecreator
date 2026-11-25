package com.memecreator.render;

import com.memecreator.template.LayoutParser;
import com.memecreator.template.TextBox;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatermarkerTest {

    final Watermarker watermarker = new Watermarker(true, "memesforlaughs.com");

    static BufferedImage white(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    static boolean changed(BufferedImage img, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if ((img.getRGB(x, y) & 0xffffff) != 0xffffff) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void goesBottomRightWhenNothingIsThere() {
        BufferedImage out = watermarker.apply(white(600, 600), List.of());
        assertEquals(600, out.getHeight());
        assertTrue(changed(out, 300, 540, 600, 600), "bottom-right corner marked");
        assertFalse(changed(out, 0, 0, 600, 300), "top half untouched");
    }

    @Test
    void movesToAFreeCorner() {
        // Drake: captions on the right half
        List<TextBox> drake = List.of(
                new TextBox(0.52, 0.02, 0.46, 0.46, TextBox.TextColor.BLACK, TextBox.VAlign.MIDDLE, ""),
                new TextBox(0.52, 0.52, 0.46, 0.46, TextBox.TextColor.BLACK, TextBox.VAlign.MIDDLE, ""));
        BufferedImage out = watermarker.apply(white(600, 600), drake);
        assertEquals(600, out.getHeight());
        assertTrue(changed(out, 0, 540, 300, 600), "bottom-left corner marked");
        assertFalse(changed(out, 300, 0, 600, 600), "captions untouched");
    }

    @Test
    void addsAStripWhenEveryCornerHasACaption() {
        BufferedImage source = white(600, 400);
        BufferedImage out = watermarker.apply(source, LayoutParser.CLASSIC.boxes());
        assertTrue(out.getHeight() > 400, "strip added under the picture");
        assertEquals(600, out.getWidth());
        assertFalse(changed(out, 0, 0, 600, 400), "the picture itself untouched");
    }

    @Test
    void smallPicturesAndTurnedOffAreLeftAlone() {
        BufferedImage small = white(100, 100);
        assertSame(small, watermarker.apply(small, List.of()));
        BufferedImage big = white(600, 600);
        assertSame(big, new Watermarker(false, "x").apply(big, List.of()));
    }
}
