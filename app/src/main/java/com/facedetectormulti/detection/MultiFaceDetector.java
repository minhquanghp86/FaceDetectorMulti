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

public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    public interface DetectionCallback {
        void onResult(DetectionResult result);
    }

    private final FaceDetector mlKitDetector;
    private final DetectionCallback callback;

    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this.callback = callback;

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build();

        mlKitDetector = FaceDetection.getClient(options);
    }

    public void process(@NonNull ImageProxy imageProxy) {
        final long t0 = System.currentTimeMillis();
        final int w = imageProxy.getWidth();
        final int h = imageProxy.getHeight();

        if (w == 0 || h == 0 || imageProxy.getImage() == null) {
            imageProxy.close();
            return;        }

        InputImage image = InputImage.fromMediaImage(
            imageProxy.getImage(),
            imageProxy.getImageInfo().getRotationDegrees()
        );

        mlKitDetector.process(image)
            .addOnSuccessListener(faces -> {
                long dt = System.currentTimeMillis() - t0;
                // ✅ FIX: Dùng generic List<FaceResult> thay vì raw type
                List<FaceResult> results = new ArrayList<>();

                for (Face face : faces) {
                    Rect box = face.getBoundingBox();
                    if (box == null) continue;

                    // Normalize coordinates [0, 1]
                    float left = Math.max(0f, (float) box.left / w);
                    float top = Math.max(0f, (float) box.top / h);
                    float right = Math.min(1f, (float) box.right / w);
                    float bottom = Math.min(1f, (float) box.bottom / h);

                    RectF normBox = new RectF(left, top, right, bottom);

                    // Fallback trackingId nếu null
                    Integer trackIdObj = face.getTrackingId();
                    int trackId = trackIdObj != null ? trackIdObj : results.size();

                    // Safe get probabilities (có thể null)
                    Float smileObj = face.getSmilingProbability();
                    Float leftEyeObj = face.getLeftEyeOpenProbability();
                    Float rightEyeObj = face.getRightEyeOpenProbability();

                    results.add(new FaceResult(
                        trackId,
                        normBox,
                        face.getHeadEulerAngleY(),  // primitive float, luôn có giá trị
                        face.getHeadEulerAngleZ(),
                        smileObj != null ? smileObj : -1f,
                        leftEyeObj != null ? leftEyeObj : -1f,
                        rightEyeObj != null ? rightEyeObj : -1f
                    ));
                }

                callback.onResult(new DetectionResult(results, dt, w, h));
            })
            .addOnFailureListener(e -> Log.e(TAG, "ML Kit detection failed", e))
            .addOnCompleteListener(task -> imageProxy.close());
    }
    public void close() {
        mlKitDetector.close();
    }
}