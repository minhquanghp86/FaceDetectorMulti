package com.facedetectormulti.detection;

public class FaceResult {
    public final int trackingId;
    public final float[] boxNorm;
    public final float smilingProbability;
    public final float eulerY;
    public final long timestamp;
    
    public FaceResult(int trackingId, float left, float top, float right, float bottom,
                     float smilingProbability, float eulerY, long timestamp) {
        this.trackingId = trackingId;
        this.boxNorm = new float[]{left, top, right, bottom};
        this.smilingProbability = smilingProbability;
        this.eulerY = eulerY;
        this.timestamp = timestamp;
    }
    
    public float centerX() { return (boxNorm[0] + boxNorm[2]) / 2f; }
    public float centerY() { return (boxNorm[1] + boxNorm[3]) / 2f; }
}