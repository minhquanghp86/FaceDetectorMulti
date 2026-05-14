package com.facedetectormulti.detection;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MultiFaceDetector - tối ưu phát hiện khuôn mặt.
 * Loại bỏ hoàn toàn recognition, chỉ giữ detection.
 */
public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    public static class Config {
        public float minFaceSize     = 0.10f;
        public boolean accurateMode  = false;
        public float minBoxAreaRatio = 0.003f;
        public long frameIntervalMs  = 50L;

        public static Config createDefault() { return new Config(); }

        public Config setMinFaceSize(float s)       { minFaceSize = Math.max(0.05f, Math.min(0.30f, s)); return this; }
        public Config setAccurateMode(boolean a)    { accurateMode = a; return this; }
        public Config setMinBoxAreaRatio(float r)   { minBoxAreaRatio = Math.max(0.001f, Math.min(0.05f, r)); return this; }
        public Config setFrameIntervalMs(long ms)   { frameIntervalMs = Math.max(0, ms); return this; }
    }

    private FaceDetector mlKitDetector;
    private final DetectionCallback callback;
    private Config config;
    private long lastProcessTime = 0;
    private final Object timeLock = new Object();
    private int nextTempId = 1000;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public MultiFaceDetector(@NonNull DetectionCallback callback,
                             @NonNull Config config) {
        this.callback  = callback;
        this.config    = config;
        this.mlKitDetector = buildDetector(config);
        Log.d(TAG, "Init: accurate=" + config.accurateMode + " minFace=" + config.minFaceSize);
    }

    private FaceDetector buildDetector(Config cfg) {
        return FaceDetection.getClient(new FaceDetectorOptions.Builder()
            .setPerformanceMode(cfg.accurateMode
                ? FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                : FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(cfg.accurateMode
                ? FaceDetectorOptions.CLASSIFICATION_MODE_ALL
                : FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(cfg.minFaceSize)
            .enableTracking()
            .build());
    }

    public void process(@NonNull ImageProxy imageProxy) {
        if (isShutdown.get()) { imageProxy.close(); return; }

        final long now = System.currentTimeMillis();
        synchronized (timeLock) {
            if (config.frameIntervalMs > 0 && now - lastProcessTime < config.frameIntervalMs) {
                imageProxy.close(); return;
            }
            lastProcessTime = now;
        }

        final int imgW = imageProxy.getWidth();
        final int imgH = imageProxy.getHeight();
        if (imgW == 0 || imgH == 0 || imageProxy.getImage() == null) { imageProxy.close(); return; }

        final long t0  = now;
        final Config c = this.config;

        try {
            InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees());

            mlKitDetector.process(image)
                .addOnSuccessListener(faces -> {
                    try {
                        List<FaceResult> results = filterAndBuild(faces, imgW, imgH, c);
                        callback.onResult(results, System.currentTimeMillis() - t0, imgW, imgH);
                    } finally {
                        imageProxy.close();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "detect failed: " + e.getMessage());
                    callback.onResult(new ArrayList<>(), System.currentTimeMillis() - t0, imgW, imgH);
                    imageProxy.close();
                });
        } catch (Exception e) {
            Log.e(TAG, "process error: " + e.getMessage());
            imageProxy.close();
            mainHandler.post(() -> callback.onResult(new ArrayList<>(), 0, 0, 0));
        }
    }

    private List<FaceResult> filterAndBuild(List<Face> faces, int imgW, int imgH, Config cfg) {
        List<FaceResult> out = new ArrayList<>(faces.size());
        float imgArea = imgW * imgH;
        for (Face face : faces) {
            try {
                Rect box = face.getBoundingBox();
                if (box == null) continue;
                float area = (float) box.width() * box.height();
                if (area / imgArea < cfg.minBoxAreaRatio) continue;
                float ratio = (float) box.width() / box.height();
                if (ratio < 0.35f || ratio > 2.8f) continue;

                float[] bn = {
                    clamp((float) box.left   / imgW),
                    clamp((float) box.top    / imgH),
                    clamp((float) box.right  / imgW),
                    clamp((float) box.bottom / imgH)
                };
                int tid = face.getTrackingId() != null ? face.getTrackingId() : nextTempId++;
                Float smile = face.getSmilingProbability();
                Float ey    = face.getLeftEyeOpenProbability();
                Float ez    = face.getRightEyeOpenProbability();
                Float euY   = face.getHeadEulerAngleY();
                Float euZ   = face.getHeadEulerAngleZ();
                out.add(new FaceResult(tid, bn,
                    smile != null ? smile : -1f,
                    euY   != null ? euY   : 0f,
                    euZ   != null ? euZ   : 0f,
                    ey    != null ? ey    : -1f,
                    ez    != null ? ez    : -1f,
                    System.currentTimeMillis()));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    public void applyConfig(Config newConfig) {
        boolean rebuild = newConfig.accurateMode != config.accurateMode
            || newConfig.minFaceSize != config.minFaceSize;
        this.config = newConfig;
        if (rebuild && !isShutdown.get()) {
            FaceDetector old = mlKitDetector;
            mlKitDetector = buildDetector(newConfig);
            try { old.close(); } catch (Exception ignored) {}
        }
    }

    public void setFrameIntervalMs(long ms) { config.frameIntervalMs = Math.max(0, ms); }
    public Config getCurrentConfig() { return config; }
    public boolean isReady() { return !isShutdown.get(); }
    public void close() {
        isShutdown.set(true);
        try { mlKitDetector.close(); } catch (Exception ignored) {}
    }
}
