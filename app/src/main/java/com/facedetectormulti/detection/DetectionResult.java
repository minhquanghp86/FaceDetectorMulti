package com.facedetectormulti.detection;

import java.util.List;

public class DetectionResult {
    public final List<FaceResult> faces;
    public final long processingMs;
    public final int imageWidth;
    public final int imageHeight;

    public DetectionResult(List<FaceResult> faces, long processingMs, int imageWidth, int imageHeight) {
        this.faces = faces;
        this.processingMs = processingMs;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    public boolean isEmpty() { return faces == null || faces.isEmpty(); }
    public int getFaceCount() { return faces != null ? faces.size() : 0; }
}