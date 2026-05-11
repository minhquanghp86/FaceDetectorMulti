package com.facedetectormulti.detection;

import android.content.Context;
import android.graphics.Bitmap;
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

public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";
    private static final float RECOGNITION_THRESHOLD = 0.75f;

    public static class Config {
        public float minFaceSize = 0.12f;
        public boolean accurateMode = false;
        public float minConfidence = 0.5f;
        public float minBoxAreaRatio = 0.003f;
        public long frameIntervalMs = 100;
        public boolean enableRecognition = false;

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
        public Config setEnableRecognition(boolean enable) {
            this.enableRecognition = enable;
            return this;
        }
    }

    public interface DetectionCallback {
        void onResult(List<? extends FaceResult> results, long processingMs, int imageWidth, int imageHeight);
    }

    private final FaceDetector mlKitDetector;
    private final DetectionCallback callback;
    private final Context context;
    private Config config;
    private FaceDao faceDao;
    
    private long lastProcessTime = 0;
    private final Object lock = new Object();
    private int nextTempId = 1000;
    private boolean isShutdown = false;

    public MultiFaceDetector(@NonNull DetectionCallback callback, @NonNull Context context, @NonNull Config config) {
        this.callback = callback;
        this.context = context;
        this.config = config;
        if (config.enableRecognition) {
            this.faceDao = FaceDatabase.getInstance(context).faceDao();
        }
        this.mlKitDetector = createMlKitDetector(config);
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
        synchronized (lock) {
            if (now - lastProcessTime < config.frameIntervalMs) {
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

        // ✅ FIX: Create final copies for lambda - ALL must be final
        final boolean doRecognition = config.enable