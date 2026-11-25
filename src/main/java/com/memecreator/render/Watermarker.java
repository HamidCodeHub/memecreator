package com.memecreator.render;

import com.memecreator.template.TextBox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes the site name small in a corner of a finished meme: white text on a translucent dark pill, readable on
 * any picture, placed where it doesn't cover a caption. Applied to memes only, never to people's uploaded photos.
 */
@Component
public class Watermarker {

    /** Pictures narrower than this get no watermark (it would cover too much). */
    private static final int MIN_WIDTH = 160;

    private final boolean enabled;
    private final String text;
    private final Font font;

    @Autowired
    public Watermarker(WatermarkProperties props) {
        this(props.enabled(), props.text());
    }

    Watermarker(boolean enabled, String text) {
        this.enabled = enabled;
        this.text = text.strip();
        this.font = pickFont(List.of("Arial", "Helvetica", "Segoe UI", "Liberation Sans", "DejaVu Sans"));
    }

    /** {@link #apply(BufferedImage, List)} for a picture without caption areas to avoid. */
    public BufferedImage apply(BufferedImage meme) {
        return apply(meme, List.of());
    }

    /**
     * A copy of the meme with the watermark in a corner that doesn't touch a caption: bottom-right, bottom-left,
     * top-right, then top-left. When every corner has a caption (classic top + bottom text), a thin dark strip is
     * added under the picture instead, so a caption is never covered. Returns the same image when watermarks are
     * off or the picture is too small.
     *
     * @param captionAreas the text boxes that got text, in fractions of the picture
     */
    public BufferedImage apply(BufferedImage meme, List<TextBox> captionAreas) {
        int w = meme.getWidth();
        int h = meme.getHeight();
        if (!enabled || w < MIN_WIDTH || h < MIN_WIDTH / 2) {
            return meme;
        }
        int size = Math.max(11, Math.min(30, Math.round(Math.min(w, h * 1.4f) * 0.03f)));
        Font f = font.deriveFont(Font.BOLD, (float) size);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics fm = pg.getFontMetrics(f);
        pg.dispose();
        int padX = Math.round(size * 0.55f);
        int padY = Math.round(size * 0.28f);
        int boxW = fm.stringWidth(text) + 2 * padX;
        int boxH = fm.getAscent() + fm.getDescent() + 2 * padY;
        int inset = Math.max(4, Math.round(Math.min(w, h) * 0.015f));

        int[][] corners = {
                {w - inset - boxW, h - inset - boxH},   // bottom-right
                {inset, h - inset - boxH},              // bottom-left
                {w - inset - boxW, inset},              // top-right
                {inset, inset}};                        // top-left
        for (int[] c : corners) {
            if (c[0] >= 0 && c[1] >= 0 && isFree(c[0], c[1], boxW, boxH, w, h, captionAreas)) {
                BufferedImage out = copy(meme, w, h);
                Graphics2D g = graphics(out);
                try {
                    pill(g, f, fm, c[0], c[1], boxW, boxH, padX, padY);
                } finally {
                    g.dispose();
                }
                return out;
            }
        }
        // No free corner: a strip under the picture
        int stripH = boxH + 2 * Math.max(2, inset / 2);
        BufferedImage out = new BufferedImage(w, h + stripH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(out);
        try {
            g.setColor(new Color(0x1b1f2a));
            g.fillRect(0, 0, w, h + stripH);
            g.drawImage(meme, 0, 0, null);
            g.setColor(Color.WHITE);
            g.setFont(f);
            g.drawString(text, w - inset - boxW + padX, h + (stripH - boxH) / 2 + padY + fm.getAscent());
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Whether the watermark rectangle (a little margin around it) stays clear of every caption area. */
    static boolean isFree(int x, int y, int bw, int bh, int w, int h, List<TextBox> areas) {
        double margin = 0.01;
        double x0 = (double) x / w - margin;
        double y0 = (double) y / h - margin;
        double x1 = (double) (x + bw) / w + margin;
        double y1 = (double) (y + bh) / h + margin;
        for (TextBox b : areas) {
            boolean overlaps = x0 < b.x() + b.width() && x1 > b.x() && y0 < b.y() + b.height() && y1 > b.y();
            if (overlaps) {
                return false;
            }
        }
        return true;
    }

    private void pill(Graphics2D g, Font f, FontMetrics fm, int x, int y, int boxW, int boxH, int padX, int padY) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.setColor(new Color(0x1b1f2a));
        g.fill(new RoundRectangle2D.Float(x, y, boxW, boxH, boxH, boxH));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.95f));
        g.setColor(Color.WHITE);
        g.setFont(f);
        g.drawString(text, x + padX, y + padY + fm.getAscent());
    }

    private static BufferedImage copy(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    private static Font pickFont(List<String> preferred) {
        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
        for (String name : preferred) {
            if (installed.contains(name)) {
                return new Font(name, Font.BOLD, 20);
            }
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 20);
    }
}
