package com.facedetectormulti.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
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
import com.facedetectormulti.detection.DetectionResult;
import com.facedetectormulti.detection.MultiFaceDetector;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FaceDetectorMulti";
    private static final int PERMISSION_CAMERA = 100;

    // UI components
    private PreviewView previewView;
    private FaceOverlayView faceOverlay;
    private TextView hudTextView;
    private ImageButton switchCameraBtn;
    private TextView permissionDeniedText;

    // Camera & detection
    private MultiFaceDetector detector;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;

    @Override
    protected void onCreate(Bundle savedInstanceState) {        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupClickListeners();
        initCameraExecutor();
        initDetector();

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void initViews() {
        previewView = findViewById(R.id.cameraPreview);
        faceOverlay = findViewById(R.id.faceOverlay);
        hudTextView = findViewById(R.id.hudTextView);
        switchCameraBtn = findViewById(R.id.switchCameraBtn);
        permissionDeniedText = findViewById(R.id.permissionDeniedText);

        // Mirror overlay cho front camera
        updateOverlayMirror();
    }

    private void setupClickListeners() {
        switchCameraBtn.setOnClickListener(v -> {
            // Toggle camera
            currentCamera = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA
                ? CameraSelector.DEFAULT_BACK_CAMERA
                : CameraSelector.DEFAULT_FRONT_CAMERA;
            
            // Update UI feedback
            switchCameraBtn.setEnabled(false);
            switchCameraBtn.setAlpha(0.5f);
            
            // Rebind camera với delay nhỏ để tránh race condition
            cameraExecutor.execute(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
                
                runOnUiThread(() -> {
                    updateOverlayMirror();
                    startCamera();
                    switchCameraBtn.setEnabled(true);
                    switchCameraBtn.setAlpha(1.0f);
                });
            });        });
    }

    private void updateOverlayMirror() {
        // Mirror chỉ áp dụng cho front camera để hiển thị tự nhiên như gương
        boolean isFront = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA;
        faceOverlay.setMirrorX(isFront);
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void initDetector() {
        detector = new MultiFaceDetector(result ->
            runOnUiThread(() -> {
                faceOverlay.update(result);
                updateHud(result);
            })
        );
    }

    private void updateHud(DetectionResult result) {
        if (result == null) return;
        int count = result.getFaceCount();
        long time = result.processingMs;
        long fps = time > 0 ? 1000 / time : 0;
        hudTextView.setText(String.format("Faces: %d | FPS: %d | Time: %dms", count, fps, time));
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Failed to start camera", e);
                runOnUiThread(() -> 
                    Toast.makeText(this, "Lỗi khởi tạo camera: " + e.getMessage(), 
                        Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        // Unbind all trước khi bind mới
        cameraProvider.unbindAll();

        // Preview
        Preview preview = new Preview.Builder()
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // ImageAnalysis cho face detection
        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();

        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (detector != null) {
                detector.process(imageProxy);
            } else {
                imageProxy.close();
            }
        });

        // Bind với lifecycle
        try {
            cameraProvider.bindToLifecycle(this, currentCamera, preview, analysis);
            Log.d(TAG, "Camera bound: " + 
                (currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA ? "Front" : "Back"));
        } catch (Exception e) {
            Log.e(TAG, "Bind camera failed", e);
            runOnUiThread(() -> 
                Toast.makeText(this, "Lỗi kết nối camera: " + e.getMessage(), 
                    Toast.LENGTH_SHORT).show());
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            new String[]{Manifest.permission.CAMERA},
            PERMISSION_CAMERA
        );
    }

    @Override    public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                permissionDeniedText.setVisibility(View.GONE);
                startCamera();
            } else {
                permissionDeniedText.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Cần cấp quyền Camera", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume camera nếu đã có permission
        if (hasCameraPermission() && cameraProvider != null) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Unbind camera khi pause để tiết kiệm tài nguyên
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        cameraExecutor.shutdown();
    }
}