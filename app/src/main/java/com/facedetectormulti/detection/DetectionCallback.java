package com.example.facedetectormulti.detection;

public interface DetectionCallback {
    void onDetectionResult(DetectionResult result);
    void onDetectionError(String error);
}