package com.memecreator.face;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Face detection with OpenCV's YuNet model (MIT licensed, bundled in resources/models). OpenCV's native library
 * is loaded on first use (see {@link OpenCv}).
 */
@Component
public class OpenCvFaceDetector implements FaceDetector {

    private static final Logger log = LoggerFactory.getLogger(OpenCvFaceDetector.class);
    private static final String MODEL = "/models/face_detection_yunet_2023mar.onnx";
    private static final float SCORE_THRESHOLD = 0.8f;
    private static final int MAX_SIDE = 1280;

    private final float scoreThreshold;
    private FaceDetectorYN detector;

    /** The face feature's detector: only confident detections (score 0.8+). */
    public OpenCvFaceDetector() {
        this(SCORE_THRESHOLD);
    }

    /** @param scoreThreshold lowest YuNet score that counts as a face (0..1) */
    public OpenCvFaceDetector(float scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    /** FaceDetectorYN is not thread-safe, so detection is serialized. It takes a few ms per image. */
    @Override
    public synchronized List<DetectedFace> detect(BufferedImage image) {
        FaceDetectorYN det = detector();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(image.getWidth(), image.getHeight()));
        BufferedImage bgr = toBgr(image, scale);

        Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData());
        Mat faces = new Mat();
        try {
            det.setInputSize(new Size(bgr.getWidth(), bgr.getHeight()));
            det.detect(mat, faces);

            List<DetectedFace> found = new ArrayList<>();
            float[] row = new float[15];
            for (int i = 0; i < faces.rows(); i++) {
                faces.get(i, 0, row);
                double[] lm = new double[10];
                for (int k = 0; k < 10; k++) {
                    lm[k] = row[4 + k] / scale;
                }
                found.add(new DetectedFace(row[0] / scale, row[1] / scale, row[2] / scale, row[3] / scale, lm, row[14]));
            }
            found.sort(Comparator.comparingDouble(DetectedFace::area).reversed());
            return found;
        } finally {
            mat.release();
            faces.release();
        }
    }

    private FaceDetectorYN detector() {
        if (detector == null) {
            OpenCv.load();
            Path model = OpenCv.extract(MODEL, ".onnx");
            detector = FaceDetectorYN.create(model.toString(), "", new Size(320, 320), scoreThreshold, 0.3f, 50);
            log.info("Face detector ready (OpenCV {}, YuNet)", org.opencv.core.Core.VERSION);
        }
        return detector;
    }

    /** OpenCV wants 8-bit BGR pixels; TYPE_3BYTE_BGR stores exactly that. */
    private static BufferedImage toBgr(BufferedImage src, double scale) {
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
