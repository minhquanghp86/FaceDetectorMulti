package com.facedetectormulti.detection;

public interface DetectionCallback {
    void onDetectionResult(DetectionResult result);
    void onDetectionError(String error);
}