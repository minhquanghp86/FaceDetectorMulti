package com.facedetectormulti.detection;

import android.content.Context;
import android.graphics.Bitmap;
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
 * Multi-face detector with optional face recognition support.
 * Uses ML Kit for detection + simple embedding for recognition (demo).
 */
public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    // ===== Config Class =====
    public static class Config {
        public float minFaceSize = 0.12f;
        public boolean accurateMode = false;
        public float minConfidence = 0.5f;
        public float minBoxAreaRatio = 0.003f;
        public float aspectRatioTolerance = 0.6f;
        public long frameIntervalMs = 100;
        public boolean enableRecognition = false;  // Toggle face recognition

        public static Config createDefault() {
            return new Config();
        }

        public static Config createSensitive() {
            return new Config()
                .setMinFaceSize(0.08f)
                .setMinConfidence(0.3f)
                .setMinBoxAreaRatio(0.002f);
        }

        public static Config createStrict() {            return new Config()
                .setMinFaceSize(0.20f)
                .setAccurateMode(true)
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
    
    // Recognition
    private FaceDao faceDao;
    private final float recognitionThreshold = 0.75f;
        // Throttling
    private long lastProcessTime = 0;
    private final Object lock = new Object();
    private int nextTempId = 1000;
    private boolean isShutdown = false;
    private int imageWidth = 0, imageHeight = 0;

    // Constructor without recognition (backward compatible)
    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this(callback, null, Config.createDefault());
    }

    // Constructor with context for recognition support
    public MultiFaceDetector(@NonNull DetectionCallback callback, @NonNull Context context) {
        this(callback, context, Config.createDefault());
    }

    // Full constructor
    public MultiFaceDetector(@NonNull DetectionCallback callback, @NonNull Context context, @NonNull Config config) {
        this.callback = callback;
        this.context = context;
        this.config = config;
        
        if (context != null && config.enableRecognition) {
            this.faceDao = FaceDatabase.getInstance(context).faceDao();
            Log.d(TAG, "Face recognition enabled");
        }
        
        this.mlKitDetector = createMlKitDetector(config);
        Log.d(TAG, "Initialized: minFaceSize=" + config.minFaceSize + 
                   ", accurate=" + config.accurateMode + 
                   ", recognition=" + config.enableRecognition);
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

    public void process(@NonNull ImageProxy imageProxy) {        if (isShutdown) {
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

        // Convert ImageProxy to Bitmap for face cropping (only if recognition enabled)
        Bitmap cameraBitmap = null;
        if (config.enableRecognition && context != null) {
            cameraBitmap = imageProxyToBitmap(imageProxy);
        }

        InputImage image = InputImage.fromMediaImage(
            imageProxy.getImage(),
            imageProxy.getImageInfo().getRotationDegrees()
        );

        mlKitDetector.process(image)
            .addOnSuccessListener(faces -> {
                long dt = System.currentTimeMillis() - t0;
                
                List<? extends FaceResult> results;
                if (config.enableRecognition && faceDao != null && cameraBitmap != null) {
                    results = recognizeFaces(faces, cameraBitmap);
                } else {
                    results = filterFaces(faces, imageWidth, imageHeight);
                }
                
                callback.onResult(results, dt, imageWidth, imageHeight);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "ML Kit detection failed", e);                callback.onResult(new ArrayList<>(), System.currentTimeMillis() - t0, imageWidth, imageHeight);
            })
            .addOnCompleteListener(task -> {
                if (cameraBitmap != null) cameraBitmap.recycle();
                imageProxy.close();
            });
    }

    // Convert ImageProxy to Bitmap (for face cropping)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image image = imageProxy.getImage();
            if (image == null) return null;
            
            // Simple conversion: YUV_420_888 to Bitmap
            // For production: use RenderScript or GPU acceleration
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());
            image.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e);
            return null;
        }
    }

    // Recognition: find registered faces
    private List<FaceRecognitionResult> recognizeFaces(List<Face> faces, Bitmap cameraFrame) {
        List<FaceRecognitionResult> results = new ArrayList<>();
        List<RegisteredFace> registeredList = faceDao != null ? faceDao.getAllFaces() : new ArrayList<>();
        
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (box == null) continue;

            // Basic filters
            float boxArea = box.width() * box.height();
            float imgArea = imageWidth * imageHeight;
            if (boxArea / imgArea < config.minBoxAreaRatio) continue;

            float aspectRatio = (float) box.width() / box.height();
            if (aspectRatio < 0.4f || aspectRatio > 2.5f) continue;

            // Create temp FaceResult for cropping
            FaceResult tempFace = new FaceResult(0, 
                (float)box.left/imageWidth, (float)box.top/imageHeight,
                (float)box.right/imageWidth, (float)box.bottom/imageHeight,                -1f, 0f, System.currentTimeMillis());
            
            // Crop face
            Bitmap faceBitmap = FaceEmbeddingExtractor.cropFace(cameraFrame, tempFace, 20);
            if (faceBitmap == null) continue;
            
            // Extract embedding
            float[] embedding = FaceEmbeddingExtractor.extract(faceBitmap);
            
            // Find best match
            float bestScore = 0f;
            RegisteredFace bestMatch = null;
            
            for (RegisteredFace registered : registeredList) {
                if (registered.embedding != null && registered.embedding.length == embedding.length) {
                    float score = RegisteredFace.similarity(embedding, registered.embedding);
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = registered;
                    }
                }
            }
            
            // Create result
            boolean isRegistered = bestScore >= recognitionThreshold;
            FaceRecognitionResult result = new FaceRecognitionResult(
                tempFace,
                isRegistered,
                isRegistered ? bestMatch.name : null,
                bestScore,
                isRegistered ? bestMatch.id : -1
            );
            results.add(result);
            
            // Increment detection count
            if (isRegistered && bestMatch != null && faceDao != null) {
                faceDao.incrementDetectionCount(bestMatch.id);
            }
            
            faceBitmap.recycle();
        }
        return results;
    }

    // Filter: basic detection without recognition
    private List<FaceResult> filterFaces(List<Face> faces, int imgW, int imgH) {
        List<FaceResult> results = new ArrayList<>();
        float imgArea = imgW * imgH;
        
        for (Face face : faces) {            Rect box = face.getBoundingBox();
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
            int trackId = face.getTrackingId() != null ? face.getTrackingId() : nextTempId++;

            results.add(new FaceResult(
                trackId, normBox,
                face.getHeadEulerAngleY(),
                face.getHeadEulerAngleZ(),
                smileProb != null ? smileProb : -1f,
                face.getLeftEyeOpenProbability() != null ? face.getLeftEyeOpenProbability() : -1f,
                face.getRightEyeOpenProbability() != null ? face.getRightEyeOpenProbability() : -1f
            ));
        }
        return results;
    }

    // ===== Public methods =====
    public void setFrameIntervalMs(long intervalMs) {
        this.config.frameIntervalMs = Math.max(0, intervalMs);
    }

    public Config getCurrentConfig() {
        return config;
    }

    public void enableRecognition(boolean enable) {
        this.config.enableRecognition = enable;
        if (enable && context != null && faceDao == null) {
            faceDao = FaceDatabase.getInstance(context).faceDao();
        }
    }

    public void close() {
        isShutdown = true;
        try { mlKitDetector.close(); } catch (Exception e) { Log.e(TAG, "Error closing", e); }
    }

    public boolean isReady() {
        return !isShutdown;
    }
}