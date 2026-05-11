package com.facedetectormulti.detection;

import android.graphics.RectF;

public class FaceResult {
    public final int trackingId;
    public final RectF boxNorm;  // normalized [0,1]
    public final float eulerY;   // head rotation Y
    public final float eulerZ;   // head rotation Z
    public final float smileProbability;
    public final float leftEyeOpenProbability;
    public final float rightEyeOpenProbability;

    public FaceResult(int trackingId, RectF boxNorm, float eulerY, float eulerZ,
                     float smileProbability, float leftEyeOpen, float rightEyeOpen) {
        this.trackingId = trackingId;
        this.boxNorm = boxNorm;
        this.eulerY = eulerY;
        this.eulerZ = eulerZ;
        this.smileProbability = smileProbability;
        this.leftEyeOpenProbability = leftEyeOpen;
        this.rightEyeOpenProbability = rightEyeOpen;
    }

    public float centerX() { return (boxNorm.left + boxNorm.right) / 2f; }
    public float centerY() { return (boxNorm.top + boxNorm.bottom) / 2f; }
}