package com.facedetectormulti.detection;

import android.graphics.Rect;
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
 * Simple face detector using ML Kit.
 * boxNorm: float[4] = {left, top, right, bottom} normalized to [0,1]
 */
public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    public static class Config {
        public float minFaceSize = 0.12f;
        public boolean accurateMode = false;
        public float minConfidence = 0.5f;
        public float minBoxAreaRatio = 0.003f;
        public long frameIntervalMs = 100;

        public static Config createDefault() { return new Config(); }

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
        }        public Config setFrameIntervalMs(long interval) { 
            this.frameIntervalMs = Math.max(0, interval); 
            return this; 
        }
    }

    public interface DetectionCallback {
        void onResult(List<FaceResult> results, long processingMs, int imageWidth, int imageHeight);
    }

    private final FaceDetector mlKitDetector;
    private final DetectionCallback callback;
    private Config config;
    
    private long lastProcessTime = 0;
    private final Object lock = new Object();
    private int nextTempId = 1000;
    private boolean isShutdown = false;

    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this(callback, Config.createDefault());
    }

    public MultiFaceDetector(@NonNull DetectionCallback callback, @NonNull Config config) {
        this.callback = callback;
        this.config = config;
        this.mlKitDetector = createMlKitDetector(config);
        Log.d(TAG, "Initialized");
    }

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

    public void process(@NonNull ImageProxy imageProxy) {
        if (isShutdown) { imageProxy.close(); return; }

        // Throttling
        long now = System.currentTimeMillis();
        synchronized (lock) {            if (now - lastProcessTime < config.frameIntervalMs) {
                imageProxy.close(); return;
            }
            lastProcessTime = now;
        }

        final long t0 = System.currentTimeMillis();
        final int imgW = imageProxy.getWidth();
        final int imgH = imageProxy.getHeight();

        if (imgW == 0 || imgH == 0 || imageProxy.getImage() == null) {
            imageProxy.close(); return;
        }

        InputImage image = InputImage.fromMediaImage(
            imageProxy.getImage(),
            imageProxy.getImageInfo().getRotationDegrees()
        );

        // ✅ FIX: Dùng final copies cho lambda
        final Config cfg = this.config;
        final int w = imgW, h = imgH;
        final long start = t0;

        mlKitDetector.process(image)
            .addOnSuccessListener(faces -> {
                List<FaceResult> results = filterFaces(faces, w, h, cfg);
                long dt = System.currentTimeMillis() - start;
                callback.onResult(results, dt, w, h);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Detection failed", e);
                callback.onResult(new ArrayList<>(), System.currentTimeMillis() - t0, imgW, imgH);
            })
            .addOnCompleteListener(task -> imageProxy.close());
    }

    private List<FaceResult> filterFaces(List<Face> faces, int imgW, int imgH, Config cfg) {
        List<FaceResult> results = new ArrayList<>();
        float imgArea = imgW * imgH;

        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (box == null) continue;

            // Filter by size
            float boxArea = box.width() * box.height();
            if (boxArea / imgArea < cfg.minBoxAreaRatio) continue;

            // Filter by aspect ratio            float ratio = (float) box.width() / box.height();
            if (ratio < 0.4f || ratio > 2.5f) continue;

            // Normalize box to [0,1]
            float[] boxNorm = new float[]{
                Math.max(0f, (float) box.left / imgW),
                Math.max(0f, (float) box.top / imgH),
                Math.min(1f, (float) box.right / imgW),
                Math.min(1f, (float) box.bottom / imgH)
            };

            int trackId = face.getTrackingId() != null ? face.getTrackingId() : nextTempId++;
            Float smile = face.getSmilingProbability();

            results.add(new FaceResult(
                trackId, boxNorm,
                smile != null ? smile : -1f,
                face.getHeadEulerAngleY(),
                face.getHeadEulerAngleZ(),
                face.getLeftEyeOpenProbability() != null ? face.getLeftEyeOpenProbability() : -1f,
                face.getRightEyeOpenProbability() != null ? face.getRightEyeOpenProbability() : -1f,
                System.currentTimeMillis()
            ));
        }
        return results;  // ✅ Đóng method filterFaces
    }  // ✅ Đóng class MultiFaceDetector

    // ===== Public methods =====
    public void setFrameIntervalMs(long ms) {
        config.frameIntervalMs = Math.max(0, ms);
    }

    public Config getCurrentConfig() {
        return config;
    }

    public void close() {
        isShutdown = true;
        try { mlKitDetector.close(); } catch (Exception e) { Log.e(TAG, "Close error", e); }
    }

    public boolean isReady() {
        return !isShutdown;
    }
}  // ✅ Đóng class - QUAN TRỌNG!