package com.facedetectormulti.detection;

public class FaceRecognitionResult extends FaceResult {
    public final boolean isRegistered;
    public final String personName;
    public final float confidence;  // 0.0 ~ 1.0
    public final int registeredFaceId;  // ID trong database

    public FaceRecognitionResult(FaceResult base, boolean isRegistered, 
                               String personName, float confidence, int registeredFaceId) {
        super(base.trackingId, base.boxNorm[0], base.boxNorm[1], 
              base.boxNorm[2], base.boxNorm[3], base.smilingProbability, 
              base.eulerY, base.timestamp);
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