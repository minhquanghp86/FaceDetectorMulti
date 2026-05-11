package com.facedetectormulti.detection;

/**
 * Represents a detected face with normalized bounding box and attributes.
 * boxNorm: float[4] = {left, top, right, bottom} normalized to [0, 1]
 */
public class FaceResult {
    public final int trackingId;
    public final float[] boxNorm;  // ✅ float[4]: {left, top, right, bottom} normalized
    public final float smilingProbability;  // -1f if not available
    public final float eulerY;  // head rotation Y axis
    public final float eulerZ;  // head rotation Z axis
    public final float leftEyeOpenProbability;
    public final float rightEyeOpenProbability;
    public final long timestamp;

    public FaceResult(int trackingId, float[] boxNorm, float smilingProbability, 
                     float eulerY, float eulerZ, float leftEyeOpen, float rightEyeOpen, long timestamp) {
        this.trackingId = trackingId;
        this.boxNorm = boxNorm;
        this.smilingProbability = smilingProbability;
        this.eulerY = eulerY;
        this.eulerZ = eulerZ;
        this.leftEyeOpenProbability = leftEyeOpen;
        this.rightEyeOpenProbability = rightEyeOpen;
        this.timestamp = timestamp;
    }
    
    // Convenience constructor for simple cases
    public FaceResult(int trackingId, float left, float top, float right, float bottom,
                     float smilingProbability, float eulerY, long timestamp) {
        this(trackingId, 
             new float[]{left, top, right, bottom},
             smilingProbability, eulerY, 0f, -1f, -1f, timestamp);
    }

    public float centerX() { return (boxNorm[0] + boxNorm[2]) / 2f; }
    public float centerY() { return (boxNorm[1] + boxNorm[3]) / 2f; }
    public float width() { return boxNorm[2] - boxNorm[0]; }
    public float height() { return boxNorm[3] - boxNorm[1]; }
}