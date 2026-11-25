package com.memecreator.photo;

import com.memecreator.face.DetectedFace;
import com.memecreator.face.FaceDetector;
import com.memecreator.face.OpenCvFaceDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the heads in an uploaded photo with the local face detector (YuNet), so captions can stay off them. Uses a
 * lower score threshold (0.5) than the face feature: a false alarm only moves a caption into a white bar, a miss puts
 * text on someone's face (a bronze statue with goggles scores ~0.56). Each face box is grown to the whole head
 * (hair, hats, glasses, chin). When OpenCV can't load, no heads are found and captions go where they always did.
 */
@Component
public class PhotoFaces {

    private static final Logger log = LoggerFactory.getLogger(PhotoFaces.class);
    static final float SCORE_THRESHOLD = 0.5f;

    private final FaceDetector detector;
    private volatile boolean broken;

    @Autowired
    public PhotoFaces() {
        this(new OpenCvFaceDetector(SCORE_THRESHOLD));
    }

    PhotoFaces(FaceDetector detector) {
        this.detector = detector;
    }

    public List<KeepClearArea> heads(BufferedImage photo) {
        if (broken) {
            return List.of();
        }
        List<DetectedFace> faces;
        try {
            faces = detector.detect(photo);
        } catch (Throwable e) {   // includes UnsatisfiedLinkError when the native library is missing
            broken = true;
            log.warn("Face detection for photo memes is off: {}", e.toString());
            return List.of();
        }
        List<KeepClearArea> out = new ArrayList<>();
        for (DetectedFace f : faces) {
            out.add(head(f, photo.getWidth(), photo.getHeight()));
        }
        return out;
    }

    /** YuNet's box is the face (brows to chin); the head reaches higher and a bit wider. */
    static KeepClearArea head(DetectedFace f, int imgW, int imgH) {
        double x = f.x() - 0.35 * f.width();
        double y = f.y() - 0.6 * f.height();
        double w = 1.7 * f.width();
        double h = 1.75 * f.height();
        return new KeepClearArea(x / imgW, y / imgH, w / imgW, h / imgH, "face");
    }
}
