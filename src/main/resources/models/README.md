# Bundled models

| File | What | Source | Licence |
|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | face detection (box + 5 landmarks) | [OpenCV Zoo, YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) | MIT |
| `face_landmark_468.onnx` | 468-point face mesh | Google MediaPipe face landmark model, ONNX export from [keijiro/FaceLandmarkBarracuda](https://github.com/keijiro/FaceLandmarkBarracuda) (npm `jp.keijiro.mediapipe.facelandmark` 1.1.0) | Apache-2.0, see `LICENSE-face-mesh-Apache-2.0.txt` |
| `bodypix_mobilenet_stride16_513.onnx` | person segmentation (for cutting out the head) | Google BodyPix 2 (MobileNetV1 ×1.0, stride 16), ONNX export from [keijiro/BodyPixBarracuda](https://github.com/keijiro/BodyPixBarracuda) (npm `jp.keijiro.bodypix` 4.0.0) | Apache-2.0, see `LICENSE-bodypix-Apache-2.0.txt` |
| `face_mesh_triangles.txt` | triangulation of the 468 points | made for this project (Delaunay on a frontal face) | same as the project |

## NOTICE: modifications to the face mesh model

`face_landmark_468.onnx` is a modified version of `face_landmark_barracuda.onnx`. So that OpenCV 4.9's DNN module
can load it, the graph was rewritten without changing what it computes:

- each `Expand` of a PReLU slope was replaced by a constant tensor of the slope;
- each `ConstantOfShape` (zero channel padding) was replaced by a constant zero tensor;
- every node was given a name.

Both versions give the same output (checked with onnxruntime: identical; OpenCV 4.9 vs the original: max
difference 1e-4). Input: 1×3×192×192 RGB in 0..1. Outputs: `conv2d_20` (468 × x, y, z in input pixels) and
`conv2d_30` (face score logit).
## NOTICE: modifications to the BodyPix model

`bodypix_mobilenet_stride16_513.onnx` is a modified version of `MobileNetV1-x100-stride16.onnx`: the input was fixed
to 1×513×513×3 (OpenCV can't load dynamic shapes), only the `segments` and `part_heatmaps` outputs were kept, and the
graph was simplified with onnx-simplifier; every node was given a name. The weights are unchanged. Input: RGB in
−1..1 (NHWC). Output `segments`: 33×33 person logits.

- The patch is reproducible with `tools/patch_facemesh.py`.
