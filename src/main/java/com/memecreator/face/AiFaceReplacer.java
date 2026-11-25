package com.memecreator.face;

import com.memecreator.ai.AiGatewayClient;
import com.memecreator.config.AiGatewayProperties;
import com.memecreator.photo.ImagePreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Replaces a template face with the user's using an image-editing AI model (through the gateway). The user's head is
 * cut out locally first ({@link HeadCutout}); the model gets two images: the area around the template face and the
 * cut-out head, and is asked to put that head in, matching pose and light. Only the area around the face is sent
 * and changed: the answer is blended back with a soft edge, so the rest of the template (other people, other
 * panels, where the captions go) stays exactly as it was. One AI call per face.
 *
 * <p>Unlike every other face style, this sends the user's photo (their cut-out head) to the AI provider.
 */
@Component
public class AiFaceReplacer {

    private static final Logger log = LoggerFactory.getLogger(AiFaceReplacer.class);
    /** The area sent around a template face: this many times the face box (head, hair, some shoulders). */
    static final double AREA_FACES = 3.0;
    /** The model's answer fades into the template over this share of the area's side, at its edge. */
    static final double FEATHER = 0.12;
    private static final int HEAD_MAX_SIDE = 768;

    static final String PROMPT = """
            Edit the first image. Replace the head of the person in the middle of the first image (face and hair) \
            with the head of the person in the second image.
            - Keep that person recognisable: their face, hair, skin tone, facial hair and glasses if any.
            - Fit it to the first image: same head position, size, tilt and direction, the same expression as the \
            original face, the same lighting, colours, sharpness and photo style, so it looks like it was always there.
            - Leave everything else in the first image exactly as it is: body, clothes, background, other people.
            - Do not add any text, captions, borders, logos or watermarks.
            Return one image with exactly the same framing and aspect ratio as the first image.""";

    private final AiGatewayClient ai;
    private final AiGatewayProperties props;

    public AiFaceReplacer(AiGatewayClient ai, AiGatewayProperties props) {
        this.ai = ai;
        this.props = props;
    }

    /** True when an image model is configured. */
    public boolean available() {
        return props.imageModel() != null && !props.imageModel().isBlank();
    }

    /** Returns a new image: {@code canvas} with the face at {@code target} replaced by the head in {@code head}. */
    public BufferedImage replace(BufferedImage canvas, DetectedFace target, Rectangle panel, HeadCutout head) {
        Rectangle area = areaAround(target, panel, canvas.getWidth(), canvas.getHeight());
        int send = Math.min(props.imageEditSize(), Math.max(area.width, area.height));
        BufferedImage crop = scaled(canvas.getSubimage(area.x, area.y, area.width, area.height),
                (int) Math.round(area.width * send / (double) Math.max(area.width, area.height)),
                (int) Math.round(area.height * send / (double) Math.max(area.width, area.height)));

        byte[] answer = ai.editImage(PROMPT, List.of(dataUri(crop), dataUri(headOnWhite(head))));
        BufferedImage edited;
        try {
            edited = ImageIO.read(new ByteArrayInputStream(answer));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the image the AI returned", e);
        }
        if (edited == null) {
            throw new IllegalStateException("The AI returned something that isn't an image");
        }
        double askedRatio = area.width / (double) area.height;
        double gotRatio = edited.getWidth() / (double) edited.getHeight();
        if (Math.abs(askedRatio - gotRatio) > 0.05 * askedRatio) {
            log.warn("The image model changed the aspect ratio ({}x{} for a {}x{} area); stretching it back",
                    edited.getWidth(), edited.getHeight(), area.width, area.height);
        }
        return blendBack(canvas, scaled(edited, area.width, area.height), area);
    }

    /**
     * The area sent to the model: a square around the face, {@link #AREA_FACES} times its size, kept inside the
     * face's panel (and the image). Smaller than that when the panel is.
     */
    static Rectangle areaAround(DetectedFace face, Rectangle panel, int w, int h) {
        Rectangle bounds = panel.intersection(new Rectangle(0, 0, w, h));
        double cx = face.x() + face.width() / 2, cy = face.y() + face.height() / 2;
        int side = (int) Math.round(Math.max(face.width(), face.height()) * AREA_FACES);
        int aw = Math.min(side, bounds.width), ah = Math.min(side, bounds.height);
        int x = (int) Math.round(cx - aw / 2.0), y = (int) Math.round(cy - ah / 2.0);
        x = Math.max(bounds.x, Math.min(bounds.x + bounds.width - aw, x));
        y = Math.max(bounds.y, Math.min(bounds.y + bounds.height - ah, y));
        return new Rectangle(x, y, aw, ah);
    }

    /** The edited area drawn back over the template, fading out over {@link #FEATHER} of its size at the edge. */
    static BufferedImage blendBack(BufferedImage canvas, BufferedImage edited, Rectangle area) {
        BufferedImage out = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(canvas, 0, 0, null);
        g.dispose();
        int[] base = out.getRGB(area.x, area.y, area.width, area.height, null, 0, area.width);
        int[] top = edited.getRGB(0, 0, area.width, area.height, null, 0, area.width);
        double fade = Math.max(2, FEATHER * Math.min(area.width, area.height));
        for (int y = 0; y < area.height; y++) {
            for (int x = 0; x < area.width; x++) {
                double edge = Math.min(Math.min(x, area.width - 1 - x), Math.min(y, area.height - 1 - y));
                double a = Math.min(1, edge / fade);
                a = a * a * (3 - 2 * a);   // smoothstep
                int i = y * area.width + x;
                base[i] = mix(base[i], top[i], a);
            }
        }
        out.setRGB(area.x, area.y, area.width, area.height, base, 0, area.width);
        return out;
    }

    /** The cut-out head on white, cropped to it with a small margin, at most {@link #HEAD_MAX_SIDE} pixels. */
    static BufferedImage headOnWhite(HeadCutout head) {
        BufferedImage img = head.image();
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int i = 0; i < px.length; i++) {
            if ((px[i] >>> 24) > 20) {
                x0 = Math.min(x0, i % w);
                x1 = Math.max(x1, i % w);
                y0 = Math.min(y0, i / w);
                y1 = Math.max(y1, i / w);
            }
        }
        if (x1 < 0) {
            x0 = 0;
            y0 = 0;
            x1 = w - 1;
            y1 = h - 1;
        }
        int m = (int) Math.round(0.08 * Math.max(x1 - x0, y1 - y0));
        x0 = Math.max(0, x0 - m);
        y0 = Math.max(0, y0 - m);
        x1 = Math.min(w - 1, x1 + m);
        y1 = Math.min(h - 1, y1 + m);
        int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
        BufferedImage onWhite = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = onWhite.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, cw, ch);
        g.drawImage(img, -x0, -y0, null);
        g.dispose();
        double k = Math.min(1, HEAD_MAX_SIDE / (double) Math.max(cw, ch));
        return k < 1 ? scaled(onWhite, (int) Math.round(cw * k), (int) Math.round(ch * k)) : onWhite;
    }

    private static String dataUri(BufferedImage img) {
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(ImagePreparer.toJpeg(img));
    }

    private static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
    }

    private static int mix(int under, int over, double a) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double v = ((under >> shift) & 255) * (1 - a) + ((over >> shift) & 255) * a;
            out |= (int) Math.max(0, Math.min(255, Math.round(v))) << shift;
        }
        return out;
    }
}
