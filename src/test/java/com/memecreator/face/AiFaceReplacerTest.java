package com.memecreator.face;

import com.memecreator.ai.AiGatewayClient;
import com.memecreator.config.AiGatewayProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiFaceReplacerTest {

    @Test
    void areaStaysInsideThePanel() {
        DetectedFace face = new DetectedFace(150, 40, 60, 60, new double[10], 1);
        Rectangle panel = new Rectangle(100, 0, 200, 150);

        Rectangle area = AiFaceReplacer.areaAround(face, panel, 400, 300);

        assertThat(panel.contains(area)).isTrue();
        assertThat(area.width).isEqualTo(180);    // 3 × the face
        assertThat(area.height).isEqualTo(150);   // cut to the panel's height
    }

    @Test
    void sendsTheAreaAndTheHeadAndBlendsTheAnswerBack() {
        BufferedImage template = filled(400, 300, Color.GRAY);
        List<List<String>> sent = new ArrayList<>();
        AiGatewayClient fakeAi = new AiGatewayClient(null, props()) {
            @Override
            public byte[] editImage(String prompt, List<String> images) {
                sent.add(images);
                return png(filled(512, 512, Color.RED));   // "AI" answer at another size
            }
        };
        AiFaceReplacer replacer = new AiFaceReplacer(fakeAi, props());
        DetectedFace face = new DetectedFace(170, 120, 60, 60, new double[10], 1);
        HeadCutout head = new HeadCutout(filled(100, 120, Color.BLUE), TestMeshes.frontal(), face, 0, 0, 1);

        BufferedImage out = replacer.replace(template, face, new Rectangle(0, 0, 400, 300), head);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).hasSize(2);
        assertThat(sent.get(0).get(0)).startsWith("data:image/jpeg;base64,");
        assertThat(out.getRGB(200, 150) & 0xFFFFFF).isEqualTo(0xFF0000);              // the middle is the answer
        assertThat(out.getRGB(10, 10) & 0xFFFFFF).isEqualTo(Color.GRAY.getRGB() & 0xFFFFFF);   // outside: untouched
        int edge = out.getRGB(110, 150) & 0xFFFFFF;                                    // the area's edge: still template
        assertThat(edge).isEqualTo(Color.GRAY.getRGB() & 0xFFFFFF);
    }

    private static AiGatewayProperties props() {
        return new AiGatewayProperties("http://gateway", "/v1/chat/completions", "/v1/models", "key", "groq/x", 1.0, 100,
                Duration.ofSeconds(5), Duration.ofSeconds(90), null, null, null, 100, null,
                AiGatewayProperties.ImageFormat.AIPROXY, "gemini/gemini-2.5-flash-image", 1024, null);
    }

    private static BufferedImage filled(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
