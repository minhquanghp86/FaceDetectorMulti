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
    
    private static final long DEFAULT_MIN_FRAME_INTERVAL_MS = 100;
    private long minFrameIntervalMs = DEFAULT_MIN_FRAME_INTERVAL_MS;
    
    private final FaceDetector faceDetector;
    private final DetectionCallback callback;
    
    private long lastProcessTime = 0;
    private final Object lock = new Object();
    
    private final Map<Integer, Integer> temporaryIdMap = new HashMap<>();
    private int nextTempId = 1000;
    
    private final AtomicBoolean isReady = new AtomicBoolean(false);
    private boolean isShutdown = false;

    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this.callback = callback;
        
        // ✅ Fix: Builder pattern phải kết thúc bằng .build()
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .enableTracking()
            .setMinFaceSize(0.1f)
            .build(); // ✅ Quan trọng: đóng builder
            
        faceDetector = FaceDetection.getClient(options);
        isReady.set(true);
    }

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
        
        synchronized (lock) {
            if (currentTime - lastProcessTime < minFrameIntervalMs) {
                imageProxy.close();
                return;
            }
            lastProcessTime = currentTime;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
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
                .addOnFailureListener(e -> {                    Log.e(TAG, "Face detection failed", e);
                    if (callback != null) {
                        callback.onDetectionError(e.getMessage());
                    }
                })
                .addOnCompleteListener(task -> {
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
            
            int trackingId = getStableTrackingId(face);
            
            float leftNorm = (float) bounds.left / 1000f;
            float topNorm = (float) bounds.top / 1000f;
            float rightNorm = (float) bounds.right / 1000f;
            float bottomNorm = (float) bounds.bottom / 1000f;
            
            FaceResult faceResult = new FaceResult(
                trackingId,
                leftNorm, topNorm, rightNorm, bottomNorm,
                face.getSmilingProbability() != null ? face.getSmilingProbability() : -1f,
                face.getHeadEulerAngleY() != null ? face.getHeadEulerAngleY() : 0f,
                currentTime
            );
            results.add(faceResult);
        }
        
        long fps = processingTimeMs > 0 ? 1000 / processingTimeMs : 0;
        
        return new DetectionResult(results, processingTimeMs, fps);
    }

    private int getStableTrackingId(@NonNull Face face) {        Integer trackingId = face.getTrackingId();
        
        if (trackingId != null) {
            temporaryIdMap.values().removeIf(tempId -> tempId == trackingId);
            return trackingId;
        } else {
            Rect bounds = face.getBoundingBox();
            if (bounds != null) {
                int positionHash = (bounds.centerX() * 31 + bounds.centerY()) % 1000;
                
                if (!temporaryIdMap.containsKey(positionHash)) {
                    temporaryIdMap.put(positionHash, nextTempId++);
                    if (temporaryIdMap.size() > 50) {
                        temporaryIdMap.remove(temporaryIdMap.keySet().iterator().next());
                    }
                }
                return temporaryIdMap.get(positionHash);
            }
            return Math.abs(face.hashCode() % 10000) + 10000;
        }
    }

    public void shutdown() {
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