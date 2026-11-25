package com.memecreator.render;

import com.memecreator.template.TextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemeRendererTest {

    private final MemeRenderer renderer = new MemeRenderer(List.of("Impact", "Arial"), 0.9f);

    private static final TextBox LEFT_HALF =
            new TextBox(0, 0, 0.5, 1, TextBox.TextColor.WHITE, TextBox.VAlign.MIDDLE, "left");
    private static final TextBox RIGHT_HALF_BLACK =
            new TextBox(0.5, 0, 0.5, 1, TextBox.TextColor.BLACK, TextBox.VAlign.MIDDLE, "right");

    @Test
    void drawsTextOnlyInsideItsBox() {
        BufferedImage black = filled(400, 300, Color.BLACK);

        BufferedImage out = renderer.render(black, List.of(LEFT_HALF), List.of("a caption that has to wrap"));

        assertThat(count(out, 0, 200, Color.WHITE)).isGreaterThan(300);
        assertThat(count(out, 200, 400, Color.WHITE)).isZero();
    }

    @Test
    void blackBoxesUsePlainBlackText() {
        BufferedImage white = filled(400, 300, Color.WHITE);

        BufferedImage out = renderer.render(white, List.of(LEFT_HALF, RIGHT_HALF_BLACK), List.of("", "approved"));

        assertThat(count(out, 200, 400, Color.BLACK)).isGreaterThan(300);
        assertThat(count(out, 0, 200, Color.BLACK)).isZero();
    }

    @Test
    void writesJpegAndPreview(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("in.png");
        ImageIO.write(filled(200, 200, Color.GRAY), "png", in.toFile());
        Path out = tmp.resolve("sub/out.jpg");

        renderer.render(in, List.of(LEFT_HALF), List.of("hello"), out);
        byte[] preview = renderer.preview(in, List.of(LEFT_HALF, RIGHT_HALF_BLACK));

        assertThat(ImageIO.read(out.toFile())).isNotNull();
        assertThat(preview).isNotEmpty();
    }

    @Test
    void captionBarAddsWhiteSpaceAboveThePhoto() {
        BufferedImage photo = filled(600, 400, Color.BLUE);

        BufferedImage out = renderer.renderCaptionBar(photo, "me when the build passes on the first try");

        assertThat(out.getWidth()).isEqualTo(600);
        assertThat(out.getHeight()).isGreaterThan(400);
        int bar = out.getHeight() - 400;
        assertThat(out.getRGB(1, 1)).isEqualTo(Color.WHITE.getRGB());
        assertThat(out.getRGB(300, bar + 200)).isEqualTo(Color.BLUE.getRGB());   // photo is not covered
    }

    private static BufferedImage filled(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static int count(BufferedImage img, int fromX, int toX, Color color) {
        int rgb = color.getRGB();
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = fromX; x < toX; x++) {
                if (img.getRGB(x, y) == rgb) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void barsGoAboveAndBelowWithoutCoveringThePicture() {
        BufferedImage photo = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        int top = renderer.barHeight("me when", 600);
        int bottom = renderer.barHeight("the punchline", 600);

        BufferedImage out = renderer.renderBars(photo, "me when", "the punchline");

        assertThat(top).isPositive();
        assertThat(bottom).isPositive();
        assertThat(out.getHeight()).isEqualTo(top + 400 + bottom);
        assertThat(out.getRGB(300, top + 200) & 0xFFFFFF).isZero();   // the black picture, untouched
        assertThat(renderer.barHeight("  ", 600)).isZero();
        assertThat(renderer.renderBars(photo, null, " ")).isSameAs(photo);
    }

    @Test
    void aSmallerBoxGivesASmallerFont() {
        TextBox big = new TextBox(0.03, 0.02, 0.94, 0.26, TextBox.TextColor.WHITE, TextBox.VAlign.TOP, "top");
        TextBox small = new TextBox(0.03, 0.02, 0.94, 0.06, TextBox.TextColor.WHITE, TextBox.VAlign.TOP, "top");
        String text = "when a haters hate message tries to sneak up on you";

        assertThat(renderer.fontSizeFor(text, small, 1200, 1600))
                .isPositive().isLessThan(renderer.fontSizeFor(text, big, 1200, 1600));
        assertThat(renderer.fontSizeFor(" ", big, 1200, 1600)).isZero();
    }
}
