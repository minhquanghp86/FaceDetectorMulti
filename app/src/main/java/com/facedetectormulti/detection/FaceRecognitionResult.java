package com.facedetectormulti.detection;

/**
 * Extended FaceResult with recognition information.
 */
public class FaceRecognitionResult extends FaceResult {
    public final boolean isRegistered;
    public final String personName;
    public final float confidence;
    public final int registeredFaceId;

    public FaceRecognitionResult(FaceResult base, boolean isRegistered,
                               String personName, float confidence, int registeredFaceId) {
        // ✅ Call parent with all required params using base.boxNorm (float[])
        super(base.trackingId, base.boxNorm, base.smilingProbability,
              base.eulerY, 0f, -1f, -1f, base.timestamp);
        this.isRegistered = isRegistered;
        this.personName = personName;
        this.confidence = confidence;
        this.registeredFaceId = registeredFaceId;
    }

    public String getDisplayLabel() {
        if (isRegistered && personName != null) {
            return String.format("✅ %s (%.0f%%)", personName, confidence * 100);
        }
        return "❓ Unknown";
    }
}