package com.facedetectormulti.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.facedetectormulti.R;
import com.facedetectormulti.detection.MultiFaceDetector;
import com.google.common.util.concurrent.ListenableFuture;  // ✅ Từ Guava

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_CAMERA = 100;

    private PreviewView previewView;
    private FaceOverlayView faceOverlay;  // ✅ Đã import đúng
    private MultiFaceDetector detector;
    private ExecutorService cameraExecutor;

    // Default camera - có thể đổi thành BACK nếu cần
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.cameraPreview);
        faceOverlay = findViewById(R.id.faceOverlay);

        // Mirror overlay cho front camera
        faceOverlay.setMirrorX(currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA);

        cameraExecutor = Executors.newSingleThreadExecutor();
        // ✅ FIX: Callback đúng signature với MultiFaceDetector.DetectionCallback
        detector = new MultiFaceDetector(result ->
            runOnUiThread(() -> faceOverlay.update(result))
        );

        if (hasCameraPermission()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                PERMISSION_CAMERA
            );
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (detector != null) {
                        detector.process(imageProxy);
                    } else {
                        imageProxy.close();
                    }
                });

                provider.unbindAll();
                provider.bindToLifecycle(this, currentCamera, preview, analysis);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this,
                    "Không khởi động được camera: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_CAMERA
            && results.length > 0
            && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Cần cấp quyền Camera", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ✅ Optional: Method để switch camera (gọi từ UI button nếu cần)
    public void switchCamera() {
        currentCamera = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA
            ? CameraSelector.DEFAULT_BACK_CAMERA
            : CameraSelector.DEFAULT_FRONT_CAMERA;
        faceOverlay.setMirrorX(currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA);
        startCamera(); // Re-bind với camera mới
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        cameraExecutor.shutdown();
    }
}