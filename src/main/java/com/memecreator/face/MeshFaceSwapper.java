package com.memecreator.face;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Pastes the user's face onto template faces using the 468-point face mesh:
 * <ol>
 *   <li>find the mesh of the user's face and of each template face;</li>
 *   <li>direction: if the user looks one way and the template face the other, the user's photo is mirrored, so
 *       the pasted face turns the same way as the template's (a face is nearly symmetric, a wrong turn is not);</li>
 *   <li>warp the face triangle by triangle onto the template face. {@link FaceStyle#NATURAL}: onto the template
 *       face's own mesh, so it takes its shape; {@link FaceStyle#BOLD}: onto the user's own mesh, only moved,
 *       scaled and turned to the template face, so the user's chin, jaw and face width stay;</li>
 *       {@link FaceStyle#FULL} goes further: a bit bigger than the template face, and reaching past the face
 *       outline up the forehead and out at the cheeks and jaw;</li>
 *   <li>blend it in: NATURAL with seamless cloning (colours and lighting of the template), BOLD and FULL with a
 *       soft edge and only part of the colour matched, so it still looks like the user's skin.</li>
 * </ol>
 * Faces the mesh model can't read (a cat, a cartoon, the back of a head) fall back to the simpler 5-point paste.
 */
@Component
public class MeshFaceSwapper {

    private static final Logger log = LoggerFactory.getLogger(MeshFaceSwapper.class);
    /** Below this face score the mesh is not trusted. */
    static final double MIN_SCORE = 0.5;
    /** Mirror only when that makes the direction clearly closer, not on a coin flip between two frontal faces. */
    static final double MIRROR_MARGIN = 0.15;
    /** Above this direction difference (after mirroring) the result looks stretched; the user gets a hint. */
    static final double YAW_WARNING = 0.9;
    /** Gaps in the warped face up to about this fraction of the face width are filled. */
    private static final double GAP_CLOSE = 0.08;
    /** NATURAL: the face mask is shrunk by this fraction of the face width, so the template's face outline stays. */
    private static final double MASK_SHRINK = 0.02;
    /** BOLD: how much of the user's own face shape is kept (1 = only moved/scaled/rotated, 0 = template's shape). */
    static final double BOLD_SHAPE = 0.85;
    /** BOLD: how far the skin tone moves towards the template's (0 = user's own colours, 1 = fully matched). */
    static final double BOLD_COLOR_MATCH = 0.45;
    /** FULL: share of the user's own face shape (a little of the template face's head turn is kept, so it overhangs less). */
    static final double FULL_SHAPE = 0.92;
    /** FULL: the user's face is this much bigger than the template face it replaces. */
    static final double FULL_SCALE = 1.08;
    /** FULL: skin tone moved this far towards the template's. */
    static final double FULL_COLOR_MATCH = 0.25;
    /** FULL: how far past the face outline the paste reaches (fraction of the distance from the face centre). */
    static final double FULL_TOP = 0.28;
    static final double FULL_SIDE = 0.12;
    static final double FULL_CHIN = 0.06;

    /** HEAD: the user's head is this much bigger than the template face it covers (a little big is funnier). */
    static final double HEAD_SCALE = 1.1;
    /** HEAD: the head may grow up to this size to cover the template's old head ... */
    static final double MAX_COVER_SCALE = 1.4;
    /** ... until no more than this share of the old head is left to fill in. */
    static final double UNCOVERED = 0.05;
    /** HEAD: but never wider than this share of the face's panel, or taller than this share of it. */
    static final double PANEL_WIDTH = 0.9;
    static final double PANEL_HEIGHT = 1.1;
    /** HEAD: the template head that is removed is grown by this fraction of its face width (stray hair, halo). */
    private static final double REMOVE_GROW = 0.04;

    private final FaceLandmarker landmarker;
    private final FaceBlender blender;
    private final FacePaster fallback;
    private final PersonSegmenter segmenter;
    private final ImageOps ops;
    private final AiFaceReplacer aiReplacer;

    @Autowired
    public MeshFaceSwapper(FaceLandmarker landmarker, FaceBlender blender, FacePaster fallback,
                           PersonSegmenter segmenter, ImageOps ops, AiFaceReplacer aiReplacer) {
        this.landmarker = landmarker;
        this.blender = blender;
        this.fallback = fallback;
        this.segmenter = segmenter;
        this.ops = ops;
        this.aiReplacer = aiReplacer;
    }

    /** Without AI face replacement. */
    public MeshFaceSwapper(FaceLandmarker landmarker, FaceBlender blender, FacePaster fallback,
                           PersonSegmenter segmenter, ImageOps ops) {
        this(landmarker, blender, fallback, segmenter, ops, null);
    }

    /** Without person segmentation: HEAD cuts the head along an oval and leaves the template's head under it. */
    public MeshFaceSwapper(FaceLandmarker landmarker, FaceBlender blender, FacePaster fallback) {
        this(landmarker, blender, fallback, null, null);
    }

    /**
     * @param warnings hints for the user, e.g. that their photo is turned too far for one of the faces
     */
    public record Result(BufferedImage image, List<String> warnings, int aiCalls) {

        public Result(BufferedImage image, List<String> warnings) {
            this(image, warnings, 0);
        }
    }

    /**
     * Pastes the face from {@code source} onto the template faces in {@code targets}.
     *
     * @param targetNumbers the 1-based slot number of each target, for the warnings
     */
    public Result paste(BufferedImage template, List<DetectedFace> targets, List<Integer> targetNumbers,
                        BufferedImage source, DetectedFace sourceFace) {
        return paste(template, targets, targetNumbers, source, sourceFace, FaceStyle.HEAD);
    }

    public Result paste(BufferedImage template, List<DetectedFace> targets, List<Integer> targetNumbers,
                        BufferedImage source, DetectedFace sourceFace, FaceStyle style) {
        List<String> warnings = new ArrayList<>();
        BufferedImage out = rgbCopy(template);
        Source plain = new Source(source, sourceFace);
        Source mirrored = null;

        FaceMesh srcMesh = safeMesh(plain.image(), plain.face());
        if (srcMesh == null || srcMesh.score() < MIN_SCORE) {
            warnings.add("Your photo's face could not be mapped in detail, so a simpler paste was used. "
                    + "A sharp, well-lit photo of the whole face works best.");
            return new Result(fallback.paste(out, targets, source, sourceFace), warnings);
        }
        plain = plain.withMesh(srcMesh);
        if (style == FaceStyle.AI && (aiReplacer == null || !aiReplacer.available())) {
            throw new IllegalStateException("AI face replacement is off: set ai-gateway.image-model "
                    + "(a model that edits images, e.g. gemini/gemini-2.5-flash-image)");
        }
        int aiCalls = 0;
        boolean head = style == FaceStyle.HEAD;
        HeadCutout[] cuts = new HeadCutout[2];   // [plain, mirrored], made on first use

        for (int i = 0; i < targets.size(); i++) {
            DetectedFace target = targets.get(i);
            int number = targetNumbers.get(i);
            Rectangle panel = PanelFinder.panelAround(template, target);   // keep this face's changes in its panel
            BufferedImage before = out;
            FaceMesh tMesh = safeMesh(template, target);
            if (style == FaceStyle.AI) {
                // the AI puts the cut-out head in; mirror it first if it looks the other way
                boolean readable = tMesh != null && tMesh.score() >= MIN_SCORE;
                boolean flip = shouldMirror(readable ? tMesh.yaw() : yaw5(target), plain.mesh().yaw());
                if (flip && mirrored == null) {
                    mirrored = plain.mirrored(landmarker);
                }
                boolean useMirror = flip && mirrored.mesh() != null && mirrored.mesh().score() >= MIN_SCORE;
                HeadCutout cut = cutout(cuts, useMirror ? 1 : 0, useMirror ? mirrored : plain);
                out = clip(before, aiReplacer.replace(out, target, panel, cut), panel);
                aiCalls++;
                continue;
            }
            if (head && (tMesh == null || tMesh.score() < MIN_SCORE)) {
                // not a readable human face (a cat, a cartoon): place the head by the 5 landmarks
                boolean flip = shouldMirror(yaw5(target), plain.mesh().yaw());
                if (flip && mirrored == null) {
                    mirrored = plain.mirrored(landmarker);
                }
                boolean useMirror = flip && mirrored.mesh() != null && mirrored.mesh().score() >= MIN_SCORE;
                out = clip(before, pasteHead(out, cutout(cuts, useMirror ? 1 : 0, useMirror ? mirrored : plain), null,
                        target, panel), panel);
                continue;
            }
            if (tMesh == null || tMesh.score() < MIN_SCORE) {
                log.debug("Face {} is not a readable human face (score {}), using the 5-point paste",
                        number, tMesh == null ? "n/a" : tMesh.score());
                out = clip(before, fallback.paste(out, List.of(target), source, sourceFace), panel);
                continue;
            }

            Source use = plain;
            double tYaw = tMesh.yaw();
            if (shouldMirror(tYaw, plain.mesh().yaw())) {
                if (mirrored == null) {
                    mirrored = plain.mirrored(landmarker);
                }
                if (mirrored.mesh() != null && mirrored.mesh().score() >= MIN_SCORE) {
                    use = mirrored;
                }
            }
            if (Math.abs(tYaw - use.mesh().yaw()) > YAW_WARNING) {
                boolean templateMoreTurned = Math.abs(tYaw) > Math.abs(use.mesh().yaw());
                warnings.add("Your photo and face " + number + " look in quite different directions"
                        + (head ? "" : ", so the face may look stretched") + ". A photo looking more "
                        + (templateMoreTurned ? "to the side" : "straight at the camera") + " will fit it better.");
            }
            out = clip(before, head ? pasteHead(out, cutout(cuts, use == plain ? 0 : 1, use), tMesh, target, panel)
                    : swapOne(out, use, tMesh, style), panel);
        }
        return new Result(out, warnings, aiCalls);
    }

    private HeadCutout cutout(HeadCutout[] cuts, int index, Source src) {
        if (cuts[index] == null) {
            cuts[index] = HeadCutout.cut(src.image(), src.face(), src.mesh(), segmenter, ops);
        }
        return cuts[index];
    }

    /**
     * HEAD style: the template's own head is removed (filled in from the background around it), then the cut-out
     * head is moved, scaled and turned so its face lies on the template face (best fit over the 468 mesh points, or
     * the 5 landmarks when the template face has no mesh), a bit bigger, and drawn on top.
     */
    private BufferedImage pasteHead(BufferedImage canvas, HeadCutout cut, FaceMesh templateMesh, DetectedFace target,
                                    Rectangle panel) {
        double[] from;
        double[] to;
        if (templateMesh != null) {
            from = cut.mesh().points();
            to = templateMesh.points();
        } else {
            from = landmarks(cut.face());
            to = landmarks(target);
        }
        double[] t = similarity(from, to);   // x' = a·x − b·y + tx, y' = b·x + a·y + ty
        double cx = 0, cy = 0;
        for (int i = 0; i < to.length / 2; i++) {
            cx += to[2 * i];
            cy += to[2 * i + 1];
        }
        cx /= to.length / 2.0;
        cy /= to.length / 2.0;
        int w = canvas.getWidth(), h = canvas.getHeight();
        double scale = HEAD_SCALE;
        BufferedImage base = canvas;
        if (templateMesh != null && segmenter != null && ops != null) {
            try {
                boolean[] oldHead = oldHeadMask(canvas, target, templateMesh, panel);
                scale = coverScale(cut, t, cx, cy, oldHead, w, h, maxScaleFor(cut, t, panel));
                base = fill(canvas, oldHead, Math.max(3, templateMesh.width() * 0.03), panel);
            } catch (RuntimeException | LinkageError e) {
                log.warn("Could not remove the template's head ({}), pasting over it", e.toString());
            }
        }
        BufferedImage out = rgbCopy(base);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(cut.image(), placement(t, cx, cy, scale), null);
        g.dispose();
        return out;
    }

    /** {@code changed} inside {@code panel}, {@code before} everywhere else. */
    static BufferedImage clip(BufferedImage before, BufferedImage changed, Rectangle panel) {
        int w = before.getWidth(), h = before.getHeight();
        if (panel.x <= 0 && panel.y <= 0 && panel.width >= w && panel.height >= h) {
            return changed;
        }
        BufferedImage out = rgbCopy(before);
        Rectangle r = panel.intersection(new Rectangle(0, 0, w, h));
        if (!r.isEmpty()) {
            int[] px = changed.getRGB(r.x, r.y, r.width, r.height, null, 0, r.width);
            out.setRGB(r.x, r.y, r.width, r.height, px, 0, r.width);
        }
        return out;
    }

    /** The fitted similarity {@code t}, grown by {@code scale} around the template face's centre (cx, cy). */
    private static AffineTransform placement(double[] t, double cx, double cy, double scale) {
        double a = t[0] * scale, b = t[1] * scale;
        return new AffineTransform(a, b, -b, a, cx + (t[2] - cx) * scale, cy + (t[3] - cy) * scale);
    }

    /**
     * The smallest head size from {@link #HEAD_SCALE} up to {@link #MAX_COVER_SCALE} that leaves at most
     * {@link #UNCOVERED} of the template's old head showing, so as little as possible has to be filled in.
     */
    private static double coverScale(HeadCutout cut, double[] t, double cx, double cy, boolean[] oldHead, int w, int h,
                                     double maxScale) {
        int x0 = w, y0 = h, x1 = -1, y1 = -1, total = 0;
        for (int i = 0; i < oldHead.length; i++) {
            if (oldHead[i]) {
                total++;
                x0 = Math.min(x0, i % w);
                x1 = Math.max(x1, i % w);
                y0 = Math.min(y0, i / w);
                y1 = Math.max(y1, i / w);
            }
        }
        if (total == 0) {
            return HEAD_SCALE;
        }
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        double best = HEAD_SCALE, bestLeft = Double.MAX_VALUE;
        for (double scale = HEAD_SCALE; scale <= maxScale + 1e-9; scale += 0.05) {
            BufferedImage layer = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = layer.createGraphics();
            AffineTransform at = AffineTransform.getTranslateInstance(-x0, -y0);
            at.concatenate(placement(t, cx, cy, scale));
            g.drawImage(cut.image(), at, null);
            g.dispose();
            int[] a = layer.getRGB(0, 0, bw, bh, null, 0, bw);
            int left = 0;
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    if (oldHead[(y0 + y) * w + x0 + x] && (a[y * bw + x] >>> 24) < 230) {
                        left++;
                    }
                }
            }
            double share = left / (double) total;
            if (share < bestLeft - 1e-9) {
                bestLeft = share;
                best = scale;
            }
            if (share <= UNCOVERED) {
                break;
            }
        }
        return best;
    }

    /**
     * The biggest head size that still fits the panel: at most {@link #MAX_COVER_SCALE}, the head no wider than
     * {@link #PANEL_WIDTH} of the panel and no taller than {@link #PANEL_HEIGHT} of it, never below {@link #HEAD_SCALE}.
     * In a close-up panel the old head can't be covered anyway; a giant face would only be cut off.
     */
    private static double maxScaleFor(HeadCutout cut, double[] t, Rectangle panel) {
        BufferedImage img = cut.image();
        int iw = img.getWidth(), ih = img.getHeight();
        int[] px = img.getRGB(0, 0, iw, ih, null, 0, iw);
        int x0 = iw, y0 = ih, x1 = -1, y1 = -1;
        for (int i = 0; i < px.length; i++) {
            if ((px[i] >>> 24) > 128) {
                x0 = Math.min(x0, i % iw);
                x1 = Math.max(x1, i % iw);
                y0 = Math.min(y0, i / iw);
                y1 = Math.max(y1, i / iw);
            }
        }
        if (x1 < 0) {
            return HEAD_SCALE;
        }
        double unit = Math.hypot(t[0], t[1]);   // cut-out pixels → template pixels at scale 1
        double byWidth = PANEL_WIDTH * panel.width / ((x1 - x0 + 1) * unit);
        double byHeight = PANEL_HEIGHT * panel.height / ((y1 - y0 + 1) * unit);
        return Math.max(HEAD_SCALE, Math.min(MAX_COVER_SCALE, Math.min(byWidth, byHeight)));
    }

    /** The template's head (hair included, neck kept), cut out the same way as the user's, a little grown. */
    private boolean[] oldHeadMask(BufferedImage canvas, DetectedFace target, FaceMesh templateMesh, Rectangle panel) {
        HeadCutout old = HeadCutout.cut(canvas, target, templateMesh, segmenter, ops);
        int w = canvas.getWidth(), h = canvas.getHeight();
        BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = layer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(old.image(), new AffineTransform(1 / old.scale(), 0, 0, 1 / old.scale(), old.ox(), old.oy()), null);
        g.dispose();
        int[] a = layer.getRGB(0, 0, w, h, null, 0, w);
        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = (a[i] >>> 24) > 60;
        }
        mask = Pixels.dilate(mask, w, h, Math.max(2, (int) Math.round(templateMesh.width() * REMOVE_GROW)));
        for (int i = 0; i < mask.length; i++) {
            mask[i] &= panel.contains(i % w, i / w);   // the person in the next panel isn't part of this head
        }
        return mask;
    }

    /**
     * Fills the masked pixels in from the background around them, looking only inside {@code panel} (so no colours
     * come over from the next panel) and only in a window around the mask.
     */
    private BufferedImage fill(BufferedImage canvas, boolean[] mask, double radius, Rectangle panel) {
        int w = canvas.getWidth(), h = canvas.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                x0 = Math.min(x0, i % w);
                x1 = Math.max(x1, i % w);
                y0 = Math.min(y0, i / w);
                y1 = Math.max(y1, i / w);
            }
        }
        if (x1 < 0) {
            return canvas;
        }
        int pad = (int) Math.ceil(radius * 3) + 4;
        x0 = Math.max(panel.x, x0 - pad);
        y0 = Math.max(panel.y, y0 - pad);
        x1 = Math.min(panel.x + panel.width - 1, x1 + pad);
        y1 = Math.min(panel.y + panel.height - 1, y1 + pad);
        if (x1 < x0 || y1 < y0) {
            return canvas;
        }
        int rw = x1 - x0 + 1, rh = y1 - y0 + 1;
        int[] px = canvas.getRGB(x0, y0, rw, rh, null, 0, rw);
        boolean[] sub = new boolean[rw * rh];
        for (int y = 0; y < rh; y++) {
            System.arraycopy(mask, (y0 + y) * w + x0, sub, y * rw, rw);
        }
        int[] filled = ops.inpaint(px, rw, rh, sub, radius);
        BufferedImage out = rgbCopy(canvas);
        out.setRGB(x0, y0, rw, rh, filled, 0, rw);
        return out;
    }

    /** Least-squares similarity (no mirroring) from one point list to another: {a, b, tx, ty}. */
    static double[] similarity(double[] from, double[] to) {
        int n = from.length / 2;
        double fx = 0, fy = 0, gx = 0, gy = 0;
        for (int i = 0; i < n; i++) {
            fx += from[2 * i];
            fy += from[2 * i + 1];
            gx += to[2 * i];
            gy += to[2 * i + 1];
        }
        fx /= n;
        fy /= n;
        gx /= n;
        gy /= n;
        double sa = 0, sb = 0, var = 0;
        for (int i = 0; i < n; i++) {
            double ux = from[2 * i] - fx, uy = from[2 * i + 1] - fy;
            double vx = to[2 * i] - gx, vy = to[2 * i + 1] - gy;
            sa += ux * vx + uy * vy;
            sb += ux * vy - uy * vx;
            var += ux * ux + uy * uy;
        }
        double a = sa / var, b = sb / var;
        return new double[]{a, b, gx - (a * fx - b * fy), gy - (b * fx + a * fy)};
    }

    private static double[] landmarks(DetectedFace f) {
        double[] p = new double[10];
        for (int i = 0; i < 5; i++) {
            p[2 * i] = f.lx(i);
            p[2 * i + 1] = f.ly(i);
        }
        return p;
    }

    /** Rough head turn from the 5 landmarks (nose against the eyes), same sign as {@link FaceMesh#yaw()}. */
    static double yaw5(DetectedFace f) {
        double ex = f.lx(1) - f.lx(0), ey = f.ly(1) - f.ly(0);
        double d = Math.hypot(ex, ey);
        if (d < 1e-6) {
            return 0;
        }
        double mx = (f.lx(0) + f.lx(1)) / 2, my = (f.ly(0) + f.ly(1)) / 2;
        return ((f.lx(2) - mx) * ex + (f.ly(2) - my) * ey) / d / (d / 2);
    }

    /** True when the mirrored photo would look clearly closer to the template face's direction. */
    static boolean shouldMirror(double templateYaw, double photoYaw) {
        return Math.abs(templateYaw + photoYaw) + MIRROR_MARGIN < Math.abs(templateYaw - photoYaw);
    }

    private BufferedImage swapOne(BufferedImage canvas, Source src, FaceMesh templateFace, FaceStyle style) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        FaceMesh to;
        double[] fromPts;
        double[] toPts;
        int[][] tris = MeshWarp.meshTriangles();
        switch (style) {
            case NATURAL -> to = templateFace;
            case BOLD -> to = keepShape(src.mesh(), templateFace, BOLD_SHAPE);
            default -> to = scaled(keepShape(src.mesh(), templateFace, FULL_SHAPE), FULL_SCALE);
        }
        if (style == FaceStyle.FULL) {
            fromPts = FaceOutline.extend(src.mesh(), FULL_TOP, FULL_SIDE, FULL_CHIN);
            toPts = FaceOutline.extend(to, FULL_TOP, FULL_SIDE, FULL_CHIN);
            tris = FaceOutline.triangles(tris, fromPts);
        } else {
            fromPts = src.mesh().points();
            toPts = to.points();
        }
        int[] dst = canvas.getRGB(0, 0, w, h, null, 0, w);
        MeshWarp.Result warped = MeshWarp.warp(src.rgb(), src.image().getWidth(), src.image().getHeight(),
                fromPts, dst, w, h, toPts, tris);
        // close small gaps inside the face (skipped folded or flat triangles, e.g. the lips of a closed mouth) with
        // the nearest warped pixels, so the template doesn't show through them
        int close = Math.max(2, (int) Math.round(to.width() * GAP_CLOSE));
        boolean[] solid = Pixels.erode(Pixels.dilate(warped.covered(), w, h, close), w, h, close);
        boolean[] gaps = new boolean[solid.length];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = solid[i] && !warped.covered()[i];
        }
        int[] rgb = warped.rgb();
        Pixels.fillFromNearest(rgb, warped.covered(), gaps, w, h);
        for (int i = 0; i < solid.length; i++) {
            solid[i] |= warped.covered()[i];
        }
        boolean natural = style == FaceStyle.NATURAL;
        int shrink = natural ? Math.max(1, (int) Math.round(to.width() * MASK_SHRINK)) : 1;
        boolean[] mask = Pixels.erode(solid, w, h, shrink);
        rgb = Pixels.median3(rgb, mask, w, h);
        BufferedImage face = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        face.setRGB(0, 0, w, h, rgb, 0, w);
        return switch (style) {
            case NATURAL -> blender.blend(canvas, face, mask);
            case BOLD -> FeatherBlend.blend(canvas, face, mask, BOLD_COLOR_MATCH);
            case FULL, HEAD, AI -> FeatherBlend.blend(canvas, face, mask, FULL_COLOR_MATCH);
        };
    }

    /** The mesh scaled by {@code s} around its centre. */
    static FaceMesh scaled(FaceMesh m, double s) {
        double cx = 0, cy = 0;
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            cx += m.x(i);
            cy += m.y(i);
        }
        cx /= FaceMesh.SIZE;
        cy /= FaceMesh.SIZE;
        double[] p = m.points();
        for (int i = 0; i < FaceMesh.SIZE; i++) {
            p[2 * i] = cx + (p[2 * i] - cx) * s;
            p[2 * i + 1] = cy + (p[2 * i + 1] - cy) * s;
        }
        return new FaceMesh(p, m.score());
    }

    /**
     * Where the user's face goes in BOLD style: the user's own mesh, moved, scaled and rotated to best fit the template
     * face (least squares over all 468 points), then pulled {@code 1 - keep} of the way towards the template's mesh.
     */
    static FaceMesh keepShape(FaceMesh user, FaceMesh template, double keep) {
        int n = FaceMesh.SIZE;
        double ucx = 0, ucy = 0, tcx = 0, tcy = 0;
        for (int i = 0; i < n; i++) {
            ucx += user.x(i);
            ucy += user.y(i);
            tcx += template.x(i);
            tcy += template.y(i);
        }
        ucx /= n;
        ucy /= n;
        tcx /= n;
        tcy /= n;
        double a = 0, b = 0, var = 0;
        for (int i = 0; i < n; i++) {
            double ux = user.x(i) - ucx, uy = user.y(i) - ucy;
            double tx = template.x(i) - tcx, ty = template.y(i) - tcy;
            a += ux * tx + uy * ty;
            b += ux * ty - uy * tx;
            var += ux * ux + uy * uy;
        }
        // similarity without reflection: p' = tc + s·R·(p − uc), with s·cos = a/var, s·sin = b/var
        double c = a / var, sn = b / var;
        double[] pts = new double[2 * n];
        for (int i = 0; i < n; i++) {
            double ux = user.x(i) - ucx, uy = user.y(i) - ucy;
            double fx = tcx + c * ux - sn * uy;
            double fy = tcy + sn * ux + c * uy;
            pts[2 * i] = keep * fx + (1 - keep) * template.x(i);
            pts[2 * i + 1] = keep * fy + (1 - keep) * template.y(i);
        }
        return new FaceMesh(pts, template.score());
    }

    private FaceMesh safeMesh(BufferedImage image, DetectedFace face) {
        try {
            return landmarker.mesh(image, face);
        } catch (RuntimeException | LinkageError e) {
            log.warn("Face mesh failed ({}), using the 5-point paste", e.toString());
            return null;
        }
    }

    private static BufferedImage rgbCopy(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    /** The user's photo (or its mirror image) with its face and mesh. */
    private record Source(BufferedImage image, DetectedFace face, FaceMesh mesh, int[] rgb) {

        Source(BufferedImage image, DetectedFace face) {
            this(image, face, null, image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
        }

        Source withMesh(FaceMesh m) {
            return new Source(image, face, m, rgb);
        }

        Source mirrored(FaceLandmarker landmarker) {
            int w = image.getWidth();
            BufferedImage flipped = new BufferedImage(w, image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = flipped.createGraphics();
            g.drawImage(image, w, 0, -w, image.getHeight(), null);
            g.dispose();
            DetectedFace f = face.mirrored(w);
            Source s = new Source(flipped, f);
            try {
                return s.withMesh(landmarker.mesh(flipped, f));
            } catch (RuntimeException e) {
                return s;
            }
        }
    }
}
