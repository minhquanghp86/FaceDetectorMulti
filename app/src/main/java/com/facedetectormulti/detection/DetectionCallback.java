package com.facedetectormulti.detection;

import java.util.List;

/**
 * Callback interface for face detection results.
 * Được implement bởi MainActivity để nhận kết quả detection.
 */
public interface DetectionCallback {
    /**
     * Called when faces are detected successfully
     * @param faces danh sách các face đã detect (có thể là FaceResult hoặc FaceRecognitionResult)
     * @param processingTimeMs thời gian xử lý (milliseconds)
     * @param imageWidth chiều rộng ảnh gốc
     * @param imageHeight chiều cao ảnh gốc
     */
    void onResult(List<? extends FaceResult> faces, long processingTimeMs, int imageWidth, int imageHeight);
    
    /**
     * Called when detection fails
     * @param error thông báo lỗi
     */
    void onError(String error);
}
