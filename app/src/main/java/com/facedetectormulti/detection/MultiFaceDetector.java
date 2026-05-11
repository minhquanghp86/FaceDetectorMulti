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
 */
public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    // ===== Config Class (static nested) =====
    public static class Config {
        public float minFaceSize = 0.12f;
        public boolean accurateMode = false;
        public float minConfidence = 0.5f;
        public float minBoxAreaRatio = 0.003f;
        public float aspectRatioTolerance = 0.6f;
        public long frameIntervalMs = 100;

        // ✅ DEFAULT phải được khai báo SAU khi class Config đã hoàn tất định nghĩa
        // Dùng phương thức factory để tránh circular initialization
        public static Config createDefault() {
            return new Config();
        }

        public static Config createSensitive() {
            return new Config()
                .setMinFaceSize(0.08f)
                .setMinConfidence(0.3f)
                .setMinBoxAreaRatio(0.002f);
        }

        public static Config createStrict() {
            return new Config()
                .setMinFaceSize(0.20f)                .setAccurateMode(true)
                .setMinConfidence(0.7f)
                .setMinBoxAreaRatio(0.008f);
        }

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

    public interface DetectionCallback {
        void onResult(DetectionResult result);
    }

    private final FaceDetector mlKitDetector;
    private final DetectionCallback callback;
    private Config config;
    
    private long lastProcessTime = 0;
    private final Object lock = new Object();
    private int nextTempId = 1000;
    private boolean isShutdown = false;
    private int imageWidth = 0, imageHeight = 0;

    // ✅ Constructor mặc định dùng factory method thay vì static field
    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this(callback, Config.createDefault());
    }
    public MultiFaceDetector(@NonNull DetectionCallback callback, @NonNull Config config) {
        this.callback = callback;
        this.config = config;
        this.mlKitDetector = createMlKitDetector(config);
        Log.d(TAG, "Initialized: minFaceSize=" + config.minFaceSize + 
                   ", accurate=" + config.accurateMode);
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
        if (isShutdown) {
            imageProxy.close();
            return;
        }

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
            imageProxy.getImageInfo().getRotationDegrees()        );

        mlKitDetector.process(image)
            .addOnSuccessListener(faces -> {
                long dt = System.currentTimeMillis() - t0;
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

    private List<FaceResult> filterFaces(List<Face> faces, int imgW, int imgH) {
        List<FaceResult> results = new ArrayList<>();
        float imgArea = imgW * imgH;
        
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (box == null) continue;

            float boxArea = box.width() * box.height();
            if (boxArea / imgArea < config.minBoxAreaRatio) continue;

            float aspectRatio = (float) box.width() / box.height();
            if (aspectRatio < 0.4f || aspectRatio > 2.5f) continue;

            Float smileProb = face.getSmilingProbability();
            if (config.minConfidence > 0.9f && smileProb != null && smileProb < 0.1f) continue;

            float left = Math.max(0f, (float) box.left / imgW);
            float top = Math.max(0f, (float) box.top / imgH);
            float right = Math.min(1f, (float) box.right / imgW);
            float bottom = Math.min(1f, (float) box.bottom / imgH);

            RectF normBox = new RectF(left, top, right, bottom);
            int trackId = face.getTrackingId() != null 
                ? face.getTrackingId() 
                : nextTempId++;

            results.add(new FaceResult(
                trackId, normBox,
                face.getHeadEulerAngleY(),
                face.getHeadEulerAngleZ(),
                smileProb != null ? smileProb : -1f,
                face.getLeftEyeOpenProbability() != null ? face.getLeftEyeOpenProbability() : -1f,
                face.getRightEyeOpenProbability() != null ? face.getRightEyeOpenProbability() : -1f            ));
        }
        return results;
    }

    public void setFrameIntervalMs(long intervalMs) {
        this.config.frameIntervalMs = Math.max(0, intervalMs);
    }

    public Config getCurrentConfig() {
        return config;
    }

    public void close() {
        isShutdown = true;
        try { mlKitDetector.close(); } catch (Exception e) { Log.e(TAG, "Error closing", e); }
    }

    public boolean isReady() {
        return !isShutdown;
    }
}