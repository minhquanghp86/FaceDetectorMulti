package com.facedetectormulti.detection;

/**
 * Extended FaceResult with recognition information.
 */
public class FaceRecognitionResult extends FaceResult {
    public final boolean isRegistered;
    public final String personName;
    public final float confidence;  // 0.0 ~ 1.0
    public final int registeredFaceId;

    public FaceRecognitionResult(FaceResult base, boolean isRegistered, 
                               String personName, float confidence, int registeredFaceId) {
        // ✅ Call parent constructor with all required params
        super(base.trackingId, base.boxNorm, base.smilingProbability,
              base.eulerY, 0f, -1f, -1f, base.timestamp);
        this.isRegistered = isRegistered;
        this.personName = personName;
        this.confidence = confidence;
        this.registeredFaceId = registeredFaceId;
    }
    
    public String getDisplayLabel() {
        if (isRegistered) {
            return String.format("✅ %s (%.0f%%)", personName, confidence * 100);
        } else {
            return "❓ Unknown";
        }
    }
}