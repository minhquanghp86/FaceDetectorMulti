package com.facedetectormulti.detection;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * DetectionResult
 * Kết quả phát hiện khuôn mặt của một frame.
 */
public class DetectionResult {

    public final List<FaceResult> faces;
    public final long   processingMs;   // thời gian xử lý frame này
    public final int    imageWidth;
    public final int    imageHeight;

    public DetectionResult(List<FaceResult> faces, long processingMs,
                           int imageWidth, int imageHeight) {
        this.faces        = faces;
        this.processingMs = processingMs;
        this.imageWidth   = imageWidth;
        this.imageHeight  = imageHeight;
    }

    public int count() {
        return faces.size();
    }

    /**
     * Trả về khuôn mặt lớn nhất (gần camera nhất).
     * Trả null nếu không có khuôn mặt.
     */
    public FaceResult getLargest() {
        if (faces.isEmpty()) return null;
        return Collections.max(faces, Comparator.comparingDouble(FaceResult::area));
    }
}
