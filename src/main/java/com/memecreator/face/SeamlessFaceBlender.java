package com.memecreator.face;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/**
 * Poisson ("seamless") cloning with OpenCV: the pasted face keeps its own details (eyes, mouth, wrinkles) but takes
 * the template's colours and lighting at its edge, so there's no visible seam. If OpenCV can't do it, a soft-edged
 * paste with colour matching is used instead.
 */
@Component
public class SeamlessFaceBlender implements FaceBlender {

    private static final Logger log = LoggerFactory.getLogger(SeamlessFaceBlender.class);
    /** seamlessClone needs the face area to stay clear of the image edge, so both images get a border first. */
    private static final int PAD = 16;

    @Override
    public BufferedImage blend(BufferedImage template, BufferedImage warped, boolean[] mask) {
        try {
            return seamless(template, warped, mask);
        } catch (RuntimeException | LinkageError e) {
            log.warn("Seamless blending failed ({}), using a soft-edged paste", e.toString());
            return FeatherBlend.blend(template, warped, mask);
        }
    }

    private static BufferedImage seamless(BufferedImage template, BufferedImage warped, boolean[] mask) {
        OpenCv.load();
        int w = template.getWidth();
        int h = template.getHeight();
        Mat dst = OpenCv.toMat(template);
        Mat src = OpenCv.toMat(warped);
        Mat m = new Mat(h, w, CvType.CV_8UC1);
        Mat dstP = new Mat();
        Mat srcP = new Mat();
        Mat mP = new Mat();
        Mat out = new Mat();
        try {
            byte[] bytes = new byte[w * h];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = mask[i] ? (byte) 255 : 0;
            }
            m.put(0, 0, bytes);
            Core.copyMakeBorder(dst, dstP, PAD, PAD, PAD, PAD, Core.BORDER_REFLECT);
            Core.copyMakeBorder(src, srcP, PAD, PAD, PAD, PAD, Core.BORDER_REFLECT);
            Core.copyMakeBorder(m, mP, PAD, PAD, PAD, PAD, Core.BORDER_CONSTANT);
            Rect r = Imgproc.boundingRect(mP);
            if (r.width < 3 || r.height < 3) {
                return template;
            }
            // seamlessClone centres the mask's bounding box on this point; this keeps the face where it is
            Point center = new Point(r.x + r.width / 2, r.y + r.height / 2);
            Photo.seamlessClone(srcP, dstP, mP, center, out, Photo.NORMAL_CLONE);
            Mat crop = out.submat(PAD, PAD + h, PAD, PAD + w).clone();
            try {
                return OpenCv.toImage(crop);
            } finally {
                crop.release();
            }
        } finally {
            dst.release();
            src.release();
            m.release();
            dstP.release();
            srcP.release();
            mP.release();
            out.release();
        }
    }
}
