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
        final boolean doRecognition = config.enableRecognition;
        final FaceDao dao = this.faceDao;
        final Config cfg = this.config;
        
        // ✅ KEY FIX: Single assignment with ternary + final keyword
        final Bitmap cameraBitmap = doRecognition ? imageProxyToBitmap(imageProxy) : null;

        InputImage image = InputImage.fromMediaImage(
            imageProxy.getImage(),
            imageProxy.getImageInfo().getRotationDegrees()
        );

        mlKitDetector.process(image)
            .addOnSuccessListener(faces -> {
                long dt = System.currentTimeMillis() - t0;
                List<? extends FaceResult> results;
                
                if (doRecognition && dao != null && cameraBitmap != null) {
                    results = recognizeFaces(faces, cameraBitmap, dao, imgW, imgH, cfg);
                } else {
                    results = filterFaces(faces, imgW, imgH, cfg);
                }
                callback.onResult(results, dt, imgW, imgH);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Detection failed", e);
                callback.onResult(new ArrayList<>(), System.currentTimeMillis() - t0, imgW, imgH);
            })
            .addOnCompleteListener(task -> {
                // ✅ cameraBitmap is final, safe to use in lambda
                if (cameraBitmap != null) cameraBitmap.recycle();                imageProxy.close();
            });
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image image = imageProxy.getImage();
            if (image == null) return null;
            int w = imageProxy.getWidth(), h = imageProxy.getHeight();
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) { image.close(); return null; }
            
            java.nio.ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride(), rowStride = planes[0].getRowStride();
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[w * h];
            buffer.rewind();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int offset = y * rowStride + x * pixelStride;
                    if (offset < buffer.capacity()) {
                        int gray = buffer.get(offset) & 0xFF;
                        pixels[y * w + x] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                    }
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            image.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Bitmap conversion failed", e);
            return null;
        }
    }

    private List<FaceRecognitionResult> recognizeFaces(List<Face> faces, Bitmap cameraFrame,
                                                       FaceDao dao, int imgW, int imgH, Config cfg) {
        List<FaceRecognitionResult> results = new ArrayList<>();
        List<RegisteredFace> registered = dao.getAllFaces();
        
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (box == null) continue;
            
            float area = box.width() * box.height();
            if (area / (imgW * imgH) < cfg.minBoxAreaRatio) continue;
            
            float ratio = (float) box.width() / box.height();
            if (ratio < 0.4f || ratio > 2.5f) continue;
            float[] boxNorm = new float[]{
                (float)box.left/imgW, (float)box.top/imgH,
                (float)box.right/imgW, (float)box.bottom/imgH
            };
            FaceResult temp = new FaceResult(0, boxNorm, -1f, 
                face.getHeadEulerAngleY(), face.getHeadEulerAngleZ(), -1f, -1f, System.currentTimeMillis());
            
            Bitmap faceBmp = FaceEmbeddingExtractor.cropFace(cameraFrame, temp, 20);
            if (faceBmp == null) continue;
            
            float[] embedding = FaceEmbeddingExtractor.extract(faceBmp);
            float bestScore = 0f;
            RegisteredFace bestMatch = null;
            
            for (RegisteredFace r : registered) {
                if (r.embedding != null && r.embedding.length == embedding.length) {
                    float score = RegisteredFace.similarity(embedding, r.embedding);
                    if (score > bestScore) { bestScore = score; bestMatch = r; }
                }
            }
            
            boolean matched = bestScore >= RECOGNITION_THRESHOLD;
            results.add(new FaceRecognitionResult(temp, matched,
                matched ? bestMatch.name : null, bestScore, matched ? bestMatch.id : -1));
            
            if (matched && bestMatch != null) dao.incrementDetectionCount(bestMatch.id);
            faceBmp.recycle();
        }
        return results;
    }

    private List<FaceResult> filterFaces(List<Face> faces, int imgW, int imgH, Config cfg) {
        List<FaceResult> results = new ArrayList<>();
        float imgArea = imgW * imgH;
        
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (box == null) continue;
            
            float area = box.width() * box.height();
            if (area / imgArea < cfg.minBoxAreaRatio) continue;
            
            float ratio = (float) box.width() / box.height();
            if (ratio < 0.4f || ratio > 2.5f) continue;
            
            Float smile = face.getSmilingProbability();
            if (cfg.minConfidence > 0.9f && smile != null && smile < 0.1f) continue;
            
            float[] boxNorm = new float[]{
                Math.max(0f, (float)box.left/imgW), Math.max(0f, (float)box.top/imgH),                Math.min(1f, (float)box.right/imgW), Math.min(1f, (float)box.bottom/imgH)
            };
            
            int trackId = face.getTrackingId() != null ? face.getTrackingId() : nextTempId++;
            results.add(new FaceResult(trackId, boxNorm,
                smile != null ? smile : -1f, face.getHeadEulerAngleY(), face.getHeadEulerAngleZ(),
                face.getLeftEyeOpenProbability() != null ? face.getLeftEyeOpenProbability() : -1f,
                face.getRightEyeOpenProbability() != null ? face.getRightEyeOpenProbability() : -1f,
                System.currentTimeMillis()));
        }
        return results;
    }

    // ===== Public API =====
    public void setFrameIntervalMs(long ms) { config.frameIntervalMs = Math.max(0, ms); }
    public Config getCurrentConfig() { return config; }
    
    public void enableRecognition(boolean enable) {
        config.enableRecognition = enable;
        if (enable && context != null && faceDao == null) {
            faceDao = FaceDatabase.getInstance(context).faceDao();
        }
    }
    
    public void close() {
        isShutdown = true;
        try { mlKitDetector.close(); } catch (Exception e) { Log.e(TAG, "Close error", e); }
    }
    public boolean isReady() { return !isShutdown; }
}