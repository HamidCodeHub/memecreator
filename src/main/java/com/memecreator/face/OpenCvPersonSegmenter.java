package com.memecreator.face;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Person segmentation with Google's BodyPix (MobileNetV1, stride 16; Apache-2.0, see resources/models/README.md),
 * run by OpenCV's DNN module. The bundled ONNX file has a fixed 513×513 input so OpenCV can load it.
 */
@Component
public class OpenCvPersonSegmenter implements PersonSegmenter {

    private static final Logger log = LoggerFactory.getLogger(OpenCvPersonSegmenter.class);
    private static final String MODEL = "/models/bodypix_mobilenet_stride16_513.onnx";

    private Net net;

    @Override
    public synchronized float[] segment(float[] nhwc) {
        if (nhwc.length != INPUT_SIZE * INPUT_SIZE * 3) {
            throw new IllegalArgumentException("expected a 1x513x513x3 input");
        }
        Net n = net();
        Mat blob = new Mat(new int[]{1, INPUT_SIZE, INPUT_SIZE, 3}, CvType.CV_32F);
        Mat out = null;
        try {
            blob.put(new int[]{0, 0, 0, 0}, nhwc);
            n.setInput(blob);
            out = n.forward("segments");
            float[] logits = new float[OUTPUT_SIZE * OUTPUT_SIZE];
            out.get(new int[]{0, 0, 0, 0}, logits);
            return logits;
        } finally {
            blob.release();
            if (out != null) {
                out.release();
            }
        }
    }

    private Net net() {
        if (net == null) {
            OpenCv.load();
            net = Dnn.readNetFromONNX(OpenCv.extract(MODEL, ".onnx").toString());
            log.info("Person segmentation model ready (BodyPix)");
        }
        return net;
    }
}
