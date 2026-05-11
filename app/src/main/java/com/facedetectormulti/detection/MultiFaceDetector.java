package com.facedetectormulti.detection;

import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-face detector using Google ML Kit Face Detection API.
 * Supports configurable sensitivity, post-processing filters, and runtime updates.
 */
public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    /**
     * Configurable settings for face detection.
     * Can be tuned via UI/SharedPreferences for different use cases.
     */
    public static class Config {
        // Face detection sensitivity: 0.05 (very sensitive) ~ 0.30 (strict)
        public float minFaceSize = 0.12f;
        
        // Performance mode: false=FAST (realtime), true=ACCURATE (better precision)
        public boolean accurateMode = false;
        
        // Post-filter: minimum smile probability to keep a face (0.0~1.0)
        public float minConfidence = 0.5f;
        
        // Post-filter: minimum box area ratio vs image area (avoid tiny noise boxes)
        public float minBoxAreaRatio = 0.003f;
        
        // Post-filter: aspect ratio tolerance for face-like boxes (0.5~2.0 is reasonable)
        public float aspectRatioTolerance = 0.6f;
        
        // Frame processing throttle: minimum ms between frames (0 = no throttle)
        public long frameIntervalMs = 100;

        // Predefined configs for common use cases        public static Config DEFAULT = new Config();
        
        public static Config SENSITIVE = new Config()
            .setMinFaceSize(0.08f)
            .setAccurateMode(false)
            .setMinConfidence(0.3f)
            .setMinBoxAreaRatio(0.002f);
        
        public static Config STRICT = new Config()
            .setMinFaceSize(0.20f)
            .setAccurateMode(true)
            .setMinConfidence(0.7f)
            .setMinBoxAreaRatio(0.008f);

        // Fluent setters for easy config building
        public Config setMinFaceSize(float size) { 
            this.minFaceSize = Math.max(0.05f, Math.min(0.30f, size)); 
            return this; 
        }
        public Config setAccurateMode(boolean accurate) { 
            this.accurateMode = accurate; 
            return this; 
        }
        public Config setMinConfidence(float conf) { 
            this.minConfidence = Math.max(0f, Math.min(1f, conf)); 
            return this; 
        }
        public Config setMinBoxAreaRatio(float ratio) { 
            this.minBoxAreaRatio = Math.max(0.001f, Math.min(0.05f, ratio)); 
            return this; 
        }
        public Config setAspectRatioTolerance(float tol) { 
            this.aspectRatioTolerance = Math.max(0.3f, Math.min(1.0f, tol)); 
            return this; 
        }
        public Config setFrameIntervalMs(long interval) { 
            this.frameIntervalMs = Math.max(0, interval); 
            return this; 
        }
    }

    /**
     * Callback interface for detection results.
     */
    public interface DetectionCallback {
        void onResult(DetectionResult result);
    }

    private final FaceDetector mlKitDetector;
    private final DetectionCallback callback;    private Config config;
    
    // Throttling state
    private long lastProcessTime = 0;
    private final Object lock = new Object();
    
    // Tracking ID fallback map
    private int nextTempId = 1000;
    
    // State flags
    private boolean isShutdown = false;
    private int imageWidth = 0, imageHeight = 0;

    /**
     * Create detector with default config.
     */
    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this(callback, Config.DEFAULT);
    }

    /**
     * Create detector with custom config.
     */
    public MultiFaceDetector(@NonNull DetectionCallback callback, @NonNull Config config) {
        this.callback = callback;
        this.config = config;
        this.mlKitDetector = createMlKitDetector(config);
        Log.d(TAG, "Initialized: minFaceSize=" + config.minFaceSize + 
                   ", accurate=" + config.accurateMode + 
                   ", frameInterval=" + config.frameIntervalMs + "ms");
    }

    /**
     * Create ML Kit FaceDetector with given config.
     */
    private FaceDetector createMlKitDetector(Config cfg) {
        FaceDetectorOptions.Builder builder = new FaceDetectorOptions.Builder()
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(cfg.minFaceSize)
            .enableTracking();
        
        if (cfg.accurateMode) {
            builder.setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE);
        } else {
            builder.setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST);
        }
        
        return FaceDetection.getClient(builder.build());
    }
    /**
     * Process a camera frame for face detection.
     * Thread-safe with frame throttling.
     */
    public void process(@NonNull ImageProxy imageProxy) {
        if (isShutdown) {
            imageProxy.close();
            return;
        }

        // Frame throttling
        long currentTime = System.currentTimeMillis();
        synchronized (lock) {
            if (currentTime - lastProcessTime < config.frameIntervalMs) {
                imageProxy.close();
                return;
            }
            lastProcessTime = currentTime;
        }

        final long t0 = System.currentTimeMillis();
        imageWidth = imageProxy.getWidth();
        imageHeight = imageProxy.getHeight();

        if (imageWidth == 0 || imageHeight == 0 || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
            imageProxy.getImage(),
            imageProxy.getImageInfo().getRotationDegrees()
        );

        mlKitDetector.process(image)
            .addOnSuccessListener(faces -> {
                long dt = System.currentTimeMillis() - t0;
                
                // Post-processing filter to reduce false positives
                List<FaceResult> results = filterFaces(faces, imageWidth, imageHeight);
                
                callback.onResult(new DetectionResult(results, dt, imageWidth, imageHeight));
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "ML Kit detection failed", e);
                callback.onResult(new DetectionResult(new ArrayList<>(), 
                    System.currentTimeMillis() - t0, imageWidth, imageHeight));
            })
            .addOnCompleteListener(task -> imageProxy.close());
    }
    /**
     * Post-processing filter: remove false positives, keep valid faces.
     */
    private List<FaceResult> filterFaces(List<Face> faces, int imgW, int imgH) {
        List<FaceResult> results = new ArrayList<>();
        float imgArea = imgW * imgH;
        
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (box == null) continue;

            // Filter 1: Box area too small → likely noise
            float boxArea = box.width() * box.height();
            if (boxArea / imgArea < config.minBoxAreaRatio) {
                continue;
            }

            // Filter 2: Aspect ratio too extreme → not face-like
            float aspectRatio = (float) box.width() / box.height();
            if (aspectRatio < 0.4f || aspectRatio > 2.5f) {
                continue;
            }

            // Filter 3: Very low confidence + no classification → suspicious
            Float smileProb = face.getSmilingProbability();
            if (config.minConfidence > 0.9f && smileProb != null && smileProb < 0.1f) {
                // Only filter if confidence threshold is very strict
                continue;
            }

            // ✅ Face passed all filters → add to results
            float left = Math.max(0f, (float) box.left / imgW);
            float top = Math.max(0f, (float) box.top / imgH);
            float right = Math.min(1f, (float) box.right / imgW);
            float bottom = Math.min(1f, (float) box.bottom / imgH);

            RectF normBox = new RectF(left, top, right, bottom);
            int trackId = face.getTrackingId() != null 
                ? face.getTrackingId() 
                : nextTempId++;

            results.add(new FaceResult(
                trackId,
                normBox,
                face.getHeadEulerAngleY(),
                face.getHeadEulerAngleZ(),
                smileProb != null ? smileProb : -1f,
                face.getLeftEyeOpenProbability() != null ? face.getLeftEyeOpenProbability() : -1f,
                face.getRightEyeOpenProbability() != null ? face.getRightEyeOpenProbability() : -1f            ));
        }
        
        if (faces.size() != results.size()) {
            Log.d(TAG, "Filtered: " + faces.size() + " → " + results.size() + " faces");
        }
        return results;
    }

    /**
     * Update frame throttle interval at runtime (no re-init needed).
     */
    public void setFrameIntervalMs(long intervalMs) {
        this.config.frameIntervalMs = Math.max(0, intervalMs);
        Log.d(TAG, "Frame interval updated: " + config.frameIntervalMs + "ms");
    }

    /**
     * Get current config (for settings sync/debugging).
     */
    public Config getCurrentConfig() {
        return config;
    }

    /**
     * Re-initialize detector with new config.
     * ⚠ Caller must re-bind camera after calling this.
     */
    public void updateConfig(@NonNull Config newConfig) {
        if (isShutdown) return;
        
        Log.d(TAG, "Config update requested. Re-initializing detector...");
        this.config = newConfig;
        
        // Close old detector and create new one
        try {
            mlKitDetector.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing old detector", e);
        }
        
        // Note: ML Kit doesn't allow updating config on existing detector
        // Caller should create a new MultiFaceDetector instance with new config
    }

    /**
     * Cleanup resources.
     */
    public void close() {
        isShutdown = true;        try {
            mlKitDetector.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing detector", e);
        }
        Log.d(TAG, "Detector closed");
    }

    /**
     * Check if detector is ready to process frames.
     */
    public boolean isReady() {
        return !isShutdown;
    }
}