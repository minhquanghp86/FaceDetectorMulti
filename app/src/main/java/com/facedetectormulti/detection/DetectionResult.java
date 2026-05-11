package com.example.facedetectormulti.detection;

import java.util.List;

public class DetectionResult {
    private final List<FaceResult> faces;
    private final long processingTimeMs;
    private final long fps;
    
    public DetectionResult(List<FaceResult> faces, long processingTimeMs, long fps) {
        this.faces = faces;
        this.processingTimeMs = processingTimeMs;
        this.fps = fps;
    }
    
    public List<FaceResult> getFaces() { return faces; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public long getFps() { return fps; }
}