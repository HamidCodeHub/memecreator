package com.memecreator.face;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the MediaPipe face landmark model (Apache-2.0, see resources/models/README.md) with OpenCV's DNN module.
 * The bundled ONNX file is a lightly patched export that OpenCV 4.9 can load; its output equals the original's.
 */
@Component
public class OpenCvMeshModel implements MeshModel {

    private static final Logger log = LoggerFactory.getLogger(OpenCvMeshModel.class);
    private static final String MODEL = "/models/face_landmark_468.onnx";
    private static final List<String> OUTPUTS = List.of("conv2d_20", "conv2d_30");

    private Net net;

    /** A Net is not thread-safe, so inference is serialized. It takes a few ms per face. */
    @Override
    public synchronized float[] infer(float[] nchw) {
        if (nchw.length != 3 * INPUT_SIZE * INPUT_SIZE) {
            throw new IllegalArgumentException("expected a 1x3x192x192 input");
        }
        Net n = net();
        Mat blob = new Mat(new int[]{1, 3, INPUT_SIZE, INPUT_SIZE}, CvType.CV_32F);
        List<Mat> outs = new ArrayList<>();
        try {
            blob.put(new int[]{0, 0, 0, 0}, nchw);
            n.setInput(blob);
            n.forward(outs, OUTPUTS);
            float[] result = new float[3 * FaceMesh.SIZE + 1];
            float[] points = new float[3 * FaceMesh.SIZE];
            outs.get(0).get(new int[]{0, 0, 0, 0}, points);
            System.arraycopy(points, 0, result, 0, points.length);
            float[] score = new float[1];
            outs.get(1).get(new int[]{0, 0, 0, 0}, score);
            result[points.length] = score[0];
            return result;
        } finally {
            blob.release();
            outs.forEach(Mat::release);
        }
    }

    private Net net() {
        if (net == null) {
            OpenCv.load();
            net = Dnn.readNetFromONNX(OpenCv.extract(MODEL, ".onnx").toString());
            log.info("Face mesh model ready (MediaPipe face landmarks, 468 points)");
        }
        return net;
    }
}
