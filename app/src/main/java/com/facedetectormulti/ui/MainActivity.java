package com.example.facedetectormulti.ui;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.example.facedetectormulti.R;
import com.example.facedetectormulti.detection.DetectionCallback;
import com.example.facedetectormulti.detection.DetectionResult;
import com.example.facedetectormulti.detection.MultiFaceDetector;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements DetectionCallback {

    private static final String TAG = "FaceDetectorMulti";
    private static final int REQUEST_CODE_PERMISSIONS = 100;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};
    
    private static final String PREF_CAMERA_FACING = "pref_camera_facing";
    private int currentLensFacing = CameraSelector.LENS_FACING_FRONT;
    
    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private TextView hudTextView;
    private ImageButton switchCameraBtn;
    
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageAnalysis analysis;
    private MultiFaceDetector detector;
    
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        loadPreferences();
        
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
        
        setupCameraSwitchListener();
        detector = new MultiFaceDetector(this);
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        hudTextView = findViewById(R.id.hudTextView);
        switchCameraBtn = findViewById(R.id.switchCameraBtn);
        
        overlayView.setDetectionCallback(this);
    }

    private void loadPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String facing = prefs.getString(PREF_CAMERA_FACING, "front");
        currentLensFacing = "front".equals(facing) 
            ? CameraSelector.LENS_FACING_FRONT 
            : CameraSelector.LENS_FACING_BACK;
    }

    private void saveCameraPreference() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
            .putString(PREF_CAMERA_FACING, 
                currentLensFacing == CameraSelector.LENS_FACING_FRONT ? "front" : "back")
            .apply();
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                         @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera();
        } else {
            Toast.makeText(this, "Cần quyền camera để hoạt động", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
            ProcessCameraProvider.getInstance(this);
        
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera", e);
                runOnUiThread(() -> 
                    Toast.makeText(this, "Lỗi khởi tạo camera: " + e.getMessage(), 
                        Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        
        cameraProvider.unbindAll();
        
        CameraSelector cameraSelector = new CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build();
            
        if (!cameraProvider.hasCamera(cameraSelector)) {
            Log.w(TAG, "Camera không khả dụng, thử fallback");
            currentLensFacing = currentLensFacing == CameraSelector.LENS_FACING_FRONT 
                ? CameraSelector.LENS_FACING_BACK 
                : CameraSelector.LENS_FACING_FRONT;
            cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .build();
        }
                preview = new Preview.Builder()
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        
        analysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();
        
        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (detector.isReady()) {
                detector.process(imageProxy);
            } else {
                imageProxy.close();
            }
        });
        
        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis);
            saveCameraPreference();
            Log.d(TAG, "Camera bound successfully");
        } catch (Exception e) {
            Log.e(TAG, "Bind camera failed", e);
            runOnUiThread(() -> 
                Toast.makeText(this, "Lỗi kết nối camera: " + e.getMessage(), 
                    Toast.LENGTH_SHORT).show());
        }
    } // ✅ ĐÓNG bindCameraUseCases() - Đây là chỗ thiếu trong code cũ!

    private void setupCameraSwitchListener() {
        switchCameraBtn.setOnClickListener(v -> {
            currentLensFacing = currentLensFacing == CameraSelector.LENS_FACING_FRONT 
                ? CameraSelector.LENS_FACING_BACK 
                : CameraSelector.LENS_FACING_FRONT;
            
            switchCameraBtn.setEnabled(false);
            switchCameraBtn.setAlpha(0.5f);
            
            cameraExecutor.execute(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
                
                runOnUiThread(() -> {
                    bindCameraUseCases();
                    switchCameraBtn.setEnabled(true);
                    switchCameraBtn.setAlpha(1.0f);
                });
            });        });
    } // ✅ ĐÓNG setupCameraSwitchListener()

    @Override
    protected void onPause() {
        super.onPause();
        if (analysis != null) {
            analysis.clearAnalyzer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraProvider != null && analysis != null && detector.isReady()) {
            analysis.setAnalyzer(cameraExecutor, imageProxy -> detector.process(imageProxy));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (detector != null) {
            detector.shutdown();
        }
    }

    @Override
    public void onDetectionResult(DetectionResult result) {
        runOnUiThread(() -> {
            overlayView.setResults(result.getFaces());
            overlayView.invalidate();
            
            long fps = result.getFps();
            int faceCount = result.getFaces().size();
            hudTextView.setText(String.format("Faces: %d | FPS: %d | Time: %dms", 
                faceCount, fps, result.getProcessingTimeMs()));
        });
    }

    @Override
    public void onDetectionError(String error) {
        runOnUiThread(() -> {
            Log.e(TAG, "Detection error: " + error);
            Toast.makeText(this, "Lỗi phát hiện: " + error, Toast.LENGTH_SHORT).show();
        });
    }
} // ✅ ĐÓNG class MainActivity