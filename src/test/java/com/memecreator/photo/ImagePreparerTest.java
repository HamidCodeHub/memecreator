package com.memecreator.photo;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImagePreparerTest {

    private final ImagePreparer preparer = new ImagePreparer();

    @Test
    void shrinksForRenderingAndForTheAi() throws Exception {
        ImagePreparer.PreparedImage p = preparer.prepare(png(4000, 2000));

        assertThat(p.forRendering().getWidth()).isEqualTo(ImagePreparer.MAX_RENDER_SIDE);
        assertThat(p.forRendering().getHeight()).isEqualTo(ImagePreparer.MAX_RENDER_SIDE / 2);
        assertThat(p.aiDataUri()).startsWith("data:image/jpeg;base64,");
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(p.aiDataUri().substring("data:image/jpeg;base64,".length()))));
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isEqualTo(ImagePreparer.MAX_AI_SIDE);
    }

    @Test
    void keepsSmallImagesAsTheyAre() throws Exception {
        assertThat(preparer.prepare(png(640, 480)).forRendering().getWidth()).isEqualTo(640);
    }

    @Test
    void rejectsNonImagesAndTinyImages() throws Exception {
        assertThatThrownBy(() -> preparer.prepare("not an image".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a supported image");
        assertThatThrownBy(() -> preparer.prepare(png(20, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
