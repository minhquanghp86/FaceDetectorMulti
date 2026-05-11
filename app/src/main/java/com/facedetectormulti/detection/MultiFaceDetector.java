package com.example.facedetectormulti.detection;

import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";
    
    // Performance throttling config (có thể điều chỉnh qua SharedPreferences)
    private static final long DEFAULT_MIN_FRAME_INTERVAL_MS = 100; // 10 FPS max
    private long minFrameIntervalMs = DEFAULT_MIN_FRAME_INTERVAL_MS;
    
    private final FaceDetector faceDetector;
    private final DetectionCallback callback;
    
    // Throttling state
    private long lastProcessTime = 0;
    private final Object lock = new Object();
    
    // Tracking ID fallback map để xử lý null trackingId
    private final Map<Integer, Integer> temporaryIdMap = new HashMap<>();
    private int nextTempId = 1000; // Bắt đầu từ 1000 để tránh trùng với ML Kit IDs
    
    // State flag
    private final AtomicBoolean isReady = new AtomicBoolean(false);
    private boolean isShutdown = false;

    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this.callback = callback;
        
        // Cấu hình ML Kit tối ưu cho multi-face + tracking        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .enableTracking() // Quan trọng: bật tracking để có trackingId bền vững
            .setMinFaceSize(0.1f) // Phát hiện face chiếm ít nhất 10% khung hình
            .build();
            
        faceDetector = FaceDetection.getClient(options);
        isReady.set(true);
    }

    /**
     * Set minimum interval between frame processing (ms)
     * Gọi từ UI thread để điều chỉnh performance realtime
     */
    public void setFrameIntervalMs(long intervalMs) {
        this.minFrameIntervalMs = Math.max(0, intervalMs);
    }

    public boolean isReady() {
        return isReady.get() && !isShutdown;
    }

    public void process(@NonNull ImageProxy imageProxy) {
        if (isShutdown || !isReady.get()) {
            imageProxy.close();
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Throttle: bỏ qua frame nếu quá nhanh
        synchronized (lock) {
            if (currentTime - lastProcessTime < minFrameIntervalMs) {
                imageProxy.close();
                return;
            }
            lastProcessTime = currentTime;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Convert ImageProxy sang InputImage với rotation handling
            InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(), 
                imageProxy.getImageInfo().getRotationDegrees()
            );            
            faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    long processTime = System.currentTimeMillis() - startTime;
                    DetectionResult result = convertToDetectionResult(faces, processTime);
                    if (callback != null) {
                        callback.onDetectionResult(result);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Face detection failed", e);
                    if (callback != null) {
                        callback.onDetectionError(e.getMessage());
                    }
                })
                .addOnCompleteListener(task -> {
                    // Luôn close imageProxy để tránh memory leak
                    imageProxy.close();
                });
                
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
            imageProxy.close();
            if (callback != null) {
                callback.onDetectionError("Process error: " + e.getMessage());
            }
        }
    }

    @NonNull
    private DetectionResult convertToDetectionResult(@NonNull List<Face> faces, long processingTimeMs) {
        List<FaceResult> results = new ArrayList<>(faces.size());
        long currentTime = System.currentTimeMillis();
        
        for (Face face : faces) {
            Rect bounds = face.getBoundingBox();
            if (bounds == null) continue;
            
            // Xử lý trackingId: fallback an toàn khi null
            int trackingId = getStableTrackingId(face);
            
            // Normalize coordinates [0, 1] để overlay độc lập resolution
            float leftNorm = (float) bounds.left / 1000f; // Sẽ được scale lại ở overlay
            float topNorm = (float) bounds.top / 1000f;
            float rightNorm = (float) bounds.right / 1000f;
            float bottomNorm = (float) bounds.bottom / 1000f;
            
            FaceResult faceResult = new FaceResult(
                trackingId,
                leftNorm, topNorm, rightNorm, bottomNorm,                face.getSmilingProbability() != null ? face.getSmilingProbability() : -1f,
                face.getHeadEulerAngleY() != null ? face.getHeadEulerAngleY() : 0f,
                currentTime
            );
            results.add(faceResult);
        }
        
        // Tính FPS dựa trên processing time
        long fps = processingTimeMs > 0 ? 1000 / processingTimeMs : 0;
        
        return new DetectionResult(results, processingTimeMs, fps);
    }

    /**
     * Lấy trackingId ổn định, fallback khi ML Kit trả về null
     * Dùng temporary map để giữ ID nhất quán trong session
     */
    private int getStableTrackingId(@NonNull Face face) {
        Integer trackingId = face.getTrackingId();
        
        if (trackingId != null) {
            // Nếu có trackingId từ ML Kit, dùng luôn và cleanup temporary map nếu cần
            temporaryIdMap.values().removeIf(tempId -> tempId == trackingId);
            return trackingId;
        } else {
            // Fallback: tạo temporary ID dựa trên position hash
            // Giúp giữ ID ổn định cho face cùng vị trí trong vài frame
            Rect bounds = face.getBoundingBox();
            if (bounds != null) {
                int positionHash = (bounds.centerX() * 31 + bounds.centerY()) % 1000;
                
                // Kiểm tra xem đã có temporary ID cho position này chưa
                if (!temporaryIdMap.containsKey(positionHash)) {
                    temporaryIdMap.put(positionHash, nextTempId++);
                    // Giới hạn kích thước map để tránh memory leak
                    if (temporaryIdMap.size() > 50) {
                        // Xóa entry cũ nhất (đơn giản: xóa entry đầu tiên)
                        temporaryIdMap.remove(temporaryIdMap.keySet().iterator().next());
                    }
                }
                return temporaryIdMap.get(positionHash);
            }
            // Fallback cuối cùng: dùng hashcode của face object
            return Math.abs(face.hashCode() % 10000) + 10000;
        }
    }

    /**
     * Cleanup resources khi activity destroy
     */    public void shutdown() {
        isReady.set(false);
        isShutdown = true;
        try {
            faceDetector.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing detector", e);
        }
        synchronized (lock) {
            temporaryIdMap.clear();
        }
    }
}