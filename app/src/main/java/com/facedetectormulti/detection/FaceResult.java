package com.facedetectormulti.detection;

import android.graphics.RectF;

/**
 * FaceResult
 * Thông tin của một khuôn mặt được phát hiện trong một frame.
 */
public class FaceResult {

    public final int     trackingId;      // ML Kit tracking ID (bền vững qua frame)
    public final RectF   boxNorm;         // bounding box normalize [0,1]
    public final float   centerX;         // tâm X normalize [0,1]
    public final float   centerY;         // tâm Y normalize [0,1]
    public final float   eulerY;          // góc trái/phải (độ), + = nhìn trái
    public final float   eulerZ;          // góc nghiêng (độ)
    public final float   smileProbability;      // [0,1] hoặc -1 nếu không có
    public final float   leftEyeOpenProbability;
    public final float   rightEyeOpenProbability;

    public FaceResult(int trackingId, RectF boxNorm,
                      float eulerY, float eulerZ,
                      float smileProb, float leftEyeProb, float rightEyeProb) {
        this.trackingId   = trackingId;
        this.boxNorm      = boxNorm;
        this.centerX      = (boxNorm.left + boxNorm.right)  / 2f;
        this.centerY      = (boxNorm.top  + boxNorm.bottom) / 2f;
        this.eulerY       = eulerY;
        this.eulerZ       = eulerZ;
        this.smileProbability        = smileProb;
        this.leftEyeOpenProbability  = leftEyeProb;
        this.rightEyeOpenProbability = rightEyeProb;
    }

    /** Diện tích box (để tìm khuôn mặt lớn nhất / gần nhất) */
    public float area() {
        return (boxNorm.right - boxNorm.left) * (boxNorm.bottom - boxNorm.top);
    }

    /** Đang nhìn thẳng vào camera không */
    public boolean isFacingCamera(float thresholdDeg) {
        return Math.abs(eulerY) < thresholdDeg;
    }
}
