package com.facedetectormulti.detection;

import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * MultiFaceDetector
 * Nhận ImageProxy từ CameraX, phát hiện nhiều khuôn mặt bằng ML Kit,
 * trả kết quả qua callback DetectionCallback.
 *
 * Cách dùng:
 *   MultiFaceDetector detector = new MultiFaceDetector(result -> {
 *       runOnUiThread(() -> overlay.update(result));
 *   });
 *   // Trong ImageAnalysis.Analyzer:
 *   detector.process(imageProxy);
 */
public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    public interface DetectionCallback {
        void onResult(DetectionResult result);
    }

    private final com.google.mlkit.vision.face.FaceDetector mlKitDetector;
    private final DetectionCallback callback;

    public MultiFaceDetector(@NonNull DetectionCallback callback) {
        this.callback = callback;

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                // Bật để lấy smile/eye open probability
                .setMinFaceSize(0.1f)
                .enableTracking()
                // Tracking ID bền vững qua các frame
                .build();

        mlKitDetector = FaceDetection.getClient(options);
    }

    /**
     * Gọi trong ImageAnalysis.Analyzer.analyze().
     * imageProxy.close() được gọi tự động sau khi ML Kit xử lý xong.
     */
    public void process(@NonNull ImageProxy imageProxy) {
        final long t0 = System.currentTimeMillis();
        final int w = imageProxy.getWidth();
        final int h = imageProxy.getHeight();

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        mlKitDetector.process(image)
                .addOnSuccessListener(faces -> {
                    long dt = System.currentTimeMillis() - t0;
                    List<FaceResult> results = new ArrayList<>();

                    for (Face face : faces) {
                        android.graphics.Rect box = face.getBoundingBox();

                        // Clamp để tránh tọa độ âm khi mặt bị cắt ở rìa
                        float left   = Math.max(0f, (float) box.left   / w);
                        float top    = Math.max(0f, (float) box.top    / h);
                        float right  = Math.min(1f, (float) box.right  / w);
                        float bottom = Math.min(1f, (float) box.bottom / h);

                        RectF normBox = new RectF(left, top, right, bottom);

                        int trackId = face.getTrackingId() != null
                                ? face.getTrackingId() : results.size();

                        float smile    = face.getSmilingProbability()         != null
                                ? face.getSmilingProbability()         : -1f;
                        float leftEye  = face.getLeftEyeOpenProbability()  != null
                                ? face.getLeftEyeOpenProbability()  : -1f;
                        float rightEye = face.getRightEyeOpenProbability() != null
                                ? face.getRightEyeOpenProbability() : -1f;

                        results.add(new FaceResult(
                                trackId, normBox,
                                face.getHeadEulerAngleY(),
                                face.getHeadEulerAngleZ(),
                                smile, leftEye, rightEye
                        ));
                    }

                    callback.onResult(new DetectionResult(results, dt, w, h));
                })
                .addOnFailureListener(e -> Log.e(TAG, "ML Kit error", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    public void close() {
        mlKitDetector.close();
    }
}
