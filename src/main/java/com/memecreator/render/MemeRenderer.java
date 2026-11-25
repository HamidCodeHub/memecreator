package com.memecreator.render;

import com.memecreator.config.MemeProperties;
import com.memecreator.template.TextBox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Draws meme captions into the text boxes of a template. Each text is word-wrapped and shrunk until it fits
 * its box. WHITE boxes get the classic white text with black outline; BLACK boxes get plain black text for
 * white areas.
 */
@Component
public class MemeRenderer {

    private static final int MIN_FONT_SIZE = 10;

    private final Font baseFont;
    private final Font barFont;
    private final float jpegQuality;

    @Autowired
    public MemeRenderer(MemeProperties props) {
        this(props.fonts(), props.jpegQuality());
    }

    MemeRenderer(List<String> preferredFonts, float jpegQuality) {
        this.baseFont = pickFont(preferredFonts);
        this.barFont = pickPlainFont(List.of("Arial", "Helvetica", "Segoe UI", "Liberation Sans", "DejaVu Sans"));
        this.jpegQuality = jpegQuality;
    }

    /** Draws texts.get(i) into boxes.get(i); missing or blank texts leave their box empty. */
    public BufferedImage render(BufferedImage source, List<TextBox> boxes, List<String> texts) {
        BufferedImage out = copy(source);
        Graphics2D g = graphics(out);
        try {
            for (int i = 0; i < boxes.size(); i++) {
                String text = i < texts.size() ? texts.get(i) : null;
                if (text != null && !text.isBlank()) {
                    TextBox box = boxes.get(i);
                    drawText(g, text.strip().toUpperCase(Locale.ROOT), toPixels(box, out), box.color(), box.valign(),
                            out.getWidth(), out.getHeight());
                }
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    public void render(Path template, List<TextBox> boxes, List<String> texts, Path target) throws IOException {
        writeJpeg(render(readImage(template), boxes, texts), target);
    }

    /**
     * Modern caption style: a white bar is added above the picture with the text in black, left-aligned and not
     * upper-cased ("me when ..."). The picture itself is not covered.
     */
    public BufferedImage renderCaptionBar(BufferedImage source, String text) {
        return renderBars(source, text, null);
    }

    /**
     * White caption bars like {@link #renderCaptionBar}, above and/or below the picture; a null or blank text means
     * no bar on that side. The picture itself is not covered.
     */
    public BufferedImage renderBars(BufferedImage source, String topText, String bottomText) {
        int w = source.getWidth();
        BarText top = barText(topText, w);
        BarText bottom = barText(bottomText, w);
        if (top == null && bottom == null) {
            return source;
        }
        int topH = top == null ? 0 : top.height();
        int bottomH = bottom == null ? 0 : bottom.height();
        BufferedImage out = new BufferedImage(w, topH + source.getHeight() + bottomH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(out);
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, out.getHeight());
            g.drawImage(source, 0, topH, null);
            if (top != null) {
                drawBarText(g, top, 0);
            }
            if (bottom != null) {
                drawBarText(g, bottom, topH + source.getHeight());
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Height in pixels of the white bar {@link #renderBars} adds for this text on a picture this wide (0: none). */
    public int barHeight(String text, int width) {
        BarText b = barText(text, width);
        return b == null ? 0 : b.height();
    }

    private record BarText(Font font, int ascent, int lineHeight, int pad, List<String> lines, int height) {
    }

    private BarText barText(String text, int w) {
        String caption = text == null ? "" : text.strip();
        if (caption.isEmpty()) {
            return null;
        }
        int pad = Math.max(8, Math.round(w * 0.04f));
        int availW = w - 2 * pad;

        // font size relative to the width; shrink while the caption needs more than 4 lines
        Font family = fontFor(barFont, caption);
        int size = Math.max(MIN_FONT_SIZE + 4, Math.round(w / 16f));
        Font font;
        FontMetrics fm;
        List<String> lines;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = graphics(probe);
        try {
            while (true) {
                font = family.deriveFont((float) size);
                fm = pg.getFontMetrics(font);
                lines = wrap(caption, fm, availW);
                if ((lines.size() <= 4 && longestWord(caption, fm) <= availW) || size <= MIN_FONT_SIZE + 4) {
                    break;
                }
                size -= Math.max(1, size / 12);
            }
        } finally {
            pg.dispose();
        }
        int lh = Math.round((fm.getAscent() + fm.getDescent()) * 1.15f);
        int barH = lines.size() * lh + 2 * pad - (lh - fm.getAscent() - fm.getDescent());
        return new BarText(font, fm.getAscent(), lh, pad, lines, barH);
    }

    private static void drawBarText(Graphics2D g, BarText bar, int y) {
        g.setColor(Color.BLACK);
        int baseline = y + bar.pad() + bar.ascent();
        for (String line : bar.lines()) {
            TextLayout layout = new TextLayout(line, bar.font(), g.getFontRenderContext());
            g.fill(layout.getOutline(AffineTransform.getTranslateInstance(bar.pad(), baseline)));
            baseline += bar.lineHeight();
        }
    }

    /**
     * The font size {@link #render} would draw this text with in this box (upper-cased, as there); 0 for no text.
     * Lets a caller check that a smaller box still gives readable text.
     */
    public int fontSizeFor(String text, TextBox box, int imgW, int imgH) {
        String t = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return 0;
        }
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(probe);
        try {
            Fit f = fit(g, t, toPixels(box, imgW, imgH), box.color() == TextBox.TextColor.WHITE, imgW, imgH);
            return f == null ? 0 : f.size();
        } finally {
            g.dispose();
        }
    }

    /** Saves an image as JPEG with the configured quality. */
    public void write(BufferedImage image, Path target) throws IOException {
        writeJpeg(image, target);
    }

    /** The template with every box outlined and labeled "#n hint", to check a layout. Returns PNG bytes. */
    public byte[] preview(Path template, List<TextBox> boxes) throws IOException {
        return preview(template, boxes, List.of());
    }

    /** Preview with text boxes (red, "#n") and face slots (blue, "Fn"). Returns PNG bytes. */
    public byte[] preview(Path template, List<TextBox> boxes, List<com.memecreator.template.FaceSlot> faces) throws IOException {
        BufferedImage out = copy(readImage(template));
        Graphics2D g = graphics(out);
        try {
            float stroke = Math.max(2f, Math.min(out.getWidth(), out.getHeight()) / 200f);
            for (int i = 0; i < boxes.size(); i++) {
                TextBox box = boxes.get(i);
                Rectangle r = toPixels(box, out);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
                g.setColor(new Color(255, 40, 40));
                g.fill(r);
                g.setComposite(AlphaComposite.SrcOver);
                g.setStroke(new BasicStroke(stroke));
                g.draw(r);
                drawText(g, "#" + (i + 1) + " " + box.hint(), r, box.color(), box.valign(), out.getWidth(), out.getHeight());
            }
            for (int i = 0; i < faces.size(); i++) {
                com.memecreator.template.FaceSlot f = faces.get(i);
                Rectangle r = new Rectangle((int) Math.round(f.x() * out.getWidth()), (int) Math.round(f.y() * out.getHeight()),
                        (int) Math.round(f.width() * out.getWidth()), (int) Math.round(f.height() * out.getHeight()));
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
                g.setColor(new Color(40, 120, 255));
                g.fill(r);
                g.setComposite(AlphaComposite.SrcOver);
                g.setStroke(new BasicStroke(stroke));
                g.draw(r);
                drawText(g, "F" + (i + 1) + (f.character() == null ? "" : " " + f.character()), r, TextBox.TextColor.WHITE, TextBox.VAlign.TOP, out.getWidth(), out.getHeight());
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }

    /** The largest font size (down to the minimum) at which the text fits the box, wrapped into lines. */
    private record Fit(Font font, FontMetrics fm, List<String> lines, float strokeW, int size, int pad) {
    }

    private Fit fit(Graphics2D g, String text, Rectangle box, boolean outlined, int imgW, int imgH) {
        Font family = fontFor(text);
        int pad = Math.max(2, Math.round(Math.min(box.width, box.height) * 0.04f));
        int availW = box.width - 2 * pad;
        int availH = box.height - 2 * pad;
        if (availW < 4 || availH < 4) {
            return null;
        }

        int size = Math.max(MIN_FONT_SIZE, Math.min(availH, Math.max(imgW, imgH) / 7));
        Font font;
        FontMetrics fm;
        List<String> lines;
        float strokeW;
        while (true) {
            font = family.deriveFont((float) size);
            fm = g.getFontMetrics(font);
            strokeW = outlined ? Math.max(2f, size / 8f) : 0f;
            int fitW = availW - Math.round(strokeW);
            lines = wrap(text, fm, fitW);
            int widest = lines.stream().mapToInt(fm::stringWidth).max().orElse(0);
            int longestWord = longestWord(text, fm);
            int blockH = lines.size() * lineHeight(fm) + Math.round(strokeW);
            // also require every word to fit on a line, so words are never split mid-word (unless at min size)
            if ((blockH <= availH && widest <= fitW && longestWord <= fitW) || size <= MIN_FONT_SIZE) {
                break;
            }
            size -= Math.max(1, size / 12);
        }
        return new Fit(font, fm, lines, strokeW, size, pad);
    }

    private void drawText(Graphics2D g, String text, Rectangle box, TextBox.TextColor color, TextBox.VAlign valign,
                          int imgW, int imgH) {
        boolean outlined = color == TextBox.TextColor.WHITE;
        Fit f = fit(g, text, box, outlined, imgW, imgH);
        if (f == null) {
            return;
        }
        Font font = f.font();
        FontMetrics fm = f.fm();
        List<String> lines = f.lines();
        float strokeW = f.strokeW();
        int pad = f.pad();

        int lh = lineHeight(fm);
        int blockH = lines.size() * lh;
        int top = switch (valign) {
            case TOP -> box.y + pad;
            case BOTTOM -> box.y + box.height - pad - blockH;
            case MIDDLE -> box.y + (box.height - blockH) / 2;
        };
        int baseline = top + fm.getAscent();

        if (outlined) {
            g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }
        for (String line : lines) {
            TextLayout layout = new TextLayout(line, font, g.getFontRenderContext());
            float x = box.x + (box.width - layout.getAdvance()) / 2f;
            Shape shape = layout.getOutline(AffineTransform.getTranslateInstance(x, baseline));
            if (outlined) {
                g.setColor(Color.BLACK);
                g.draw(shape);
                g.setColor(Color.WHITE);
            } else {
                g.setColor(Color.BLACK);
            }
            g.fill(shape);
            baseline += lh;
        }
    }

    private static int longestWord(String text, FontMetrics fm) {
        int max = 0;
        for (String w : text.split("\\s+")) {
            max = Math.max(max, fm.stringWidth(w));
        }
        return max;
    }

    private static int lineHeight(FontMetrics fm) {
        return fm.getAscent() + fm.getDescent();
    }

    /** Greedy word wrap; words longer than a line are split by character. */
    static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (fm.stringWidth(word) <= maxWidth) {
                line.append(word);
            } else {
                for (char c : word.toCharArray()) {
                    if (fm.stringWidth(line.toString() + c) > maxWidth && !line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(c);
                }
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static Rectangle toPixels(TextBox b, BufferedImage img) {
        return toPixels(b, img.getWidth(), img.getHeight());
    }

    private static Rectangle toPixels(TextBox b, int imgW, int imgH) {
        int x = (int) Math.round(b.x() * imgW);
        int y = (int) Math.round(b.y() * imgH);
        int w = (int) Math.round(b.width() * imgW);
        int h = (int) Math.round(b.height() * imgH);
        return new Rectangle(x, y, Math.min(w, imgW - x), Math.min(h, imgH - y));
    }

    /** Reads a template image. */
    public BufferedImage read(Path template) throws IOException {
        return readImage(template);
    }

    private static BufferedImage readImage(Path template) throws IOException {
        BufferedImage src = ImageIO.read(template.toFile());
        if (src == null) {
            throw new IOException("Unsupported image format: " + template);
        }
        return src;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);              // background for transparent PNGs
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** Falls back to the logical SansSerif font (which has glyph fallback) for non-Latin text. */
    private Font fontFor(String text) {
        return fontFor(baseFont, text);
    }

    private static Font fontFor(Font preferred, String text) {
        if (preferred.canDisplayUpTo(text) == -1) {
            return preferred;
        }
        return new Font(Font.SANS_SERIF, preferred.getStyle(), preferred.getSize());
    }

    private static Font pickFont(List<String> preferred) {
        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
        for (String name : preferred) {
            if (installed.contains(name)) {
                // Impact/Anton are already heavy; synthetic bold makes them blurry
                int style = name.equalsIgnoreCase("Impact") || name.equalsIgnoreCase("Anton") ? Font.PLAIN : Font.BOLD;
                return new Font(name, style, 48);
            }
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 48);
    }

    /** Regular-weight font for the caption bar (the classic meme font looks wrong there). */
    private static Font pickPlainFont(List<String> preferred) {
        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
        for (String name : preferred) {
            if (installed.contains(name)) {
                return new Font(name, Font.PLAIN, 48);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 48);
    }

    public String fontName() {
        return baseFont.getFamily(Locale.ROOT);
    }

    private void writeJpeg(BufferedImage img, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(jpegQuality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
