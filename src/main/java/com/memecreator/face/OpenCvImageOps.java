package com.memecreator.face;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;
import org.springframework.stereotype.Component;

/** {@link ImageOps} with OpenCV. */
@Component
public class OpenCvImageOps implements ImageOps {

    @Override
    public boolean[] grabCut(int[] rgb, int w, int h, byte[] labels, int iterations) {
        OpenCv.load();
        Mat img = bgr(rgb, w, h);
        Mat mask = new Mat(h, w, CvType.CV_8UC1);
        Mat bgd = new Mat();
        Mat fgd = new Mat();
        try {
            mask.put(0, 0, labels);
            Imgproc.grabCut(img, mask, new Rect(), bgd, fgd, iterations, Imgproc.GC_INIT_WITH_MASK);
            byte[] out = new byte[w * h];
            mask.get(0, 0, out);
            boolean[] fg = new boolean[w * h];
            for (int i = 0; i < fg.length; i++) {
                fg[i] = out[i] == FOREGROUND || out[i] == PROBABLY_FOREGROUND;
            }
            return fg;
        } finally {
            img.release();
            mask.release();
            bgd.release();
            fgd.release();
        }
    }

    @Override
    public int[] inpaint(int[] rgb, int w, int h, boolean[] mask, double radius) {
        OpenCv.load();
        Mat img = bgr(rgb, w, h);
        Mat m = new Mat(h, w, CvType.CV_8UC1);
        Mat out = new Mat();
        try {
            byte[] bytes = new byte[w * h];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = mask[i] ? (byte) 255 : 0;
            }
            m.put(0, 0, bytes);
            Photo.inpaint(img, m, out, radius, Photo.INPAINT_TELEA);
            byte[] px = new byte[w * h * 3];
            out.get(0, 0, px);
            int[] res = new int[w * h];
            for (int i = 0; i < res.length; i++) {
                res[i] = (px[3 * i + 2] & 255) << 16 | (px[3 * i + 1] & 255) << 8 | (px[3 * i] & 255);
            }
            return res;
        } finally {
            img.release();
            m.release();
            out.release();
        }
    }

    private static Mat bgr(int[] rgb, int w, int h) {
        byte[] px = new byte[w * h * 3];
        for (int i = 0; i < rgb.length; i++) {
            px[3 * i] = (byte) (rgb[i] & 255);
            px[3 * i + 1] = (byte) ((rgb[i] >> 8) & 255);
            px[3 * i + 2] = (byte) ((rgb[i] >> 16) & 255);
        }
        Mat m = new Mat(h, w, CvType.CV_8UC3);
        m.put(0, 0, px);
        return m;
    }
}
