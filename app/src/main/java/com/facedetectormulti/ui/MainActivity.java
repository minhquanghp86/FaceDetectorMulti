package com.facedetectormulti.ui;

import android.Manifest;
import android.content.Intent;
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
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.facedetectormulti.R;
import com.facedetectormulti.detection.FaceResult;
import com.facedetectormulti.detection.MultiFaceDetector;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FaceDetectorMulti";
    private static final int PERMISSION_CAMERA = 100;
    private static final int REQUEST_CODE_SETTINGS = 101;
    private static final int REQUEST_CODE_REGISTRATION = 102;

    // UI Components
    private PreviewView previewView;
    private FaceOverlayView faceOverlay;
    private TextView hudTextView;
    private ImageButton switchCameraBtn;
    private ImageButton settingsBtn;
    private ImageButton registerBtn;
    private ImageButton manageFacesBtn;
    private TextView permissionDeniedText;

    // Core Objects
    private MultiFaceDetector detector;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;
    private SharedPreferences prefs;

    // State
    private List<FaceResult> lastDetectedFaces = new ArrayList<>();
    
    // ✅ Cờ đánh dấu đã init detector xong chưa
    private boolean detectorReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        initViews();
        setupClickListeners();
        initCameraExecutor();
        initDetector();
        applySettingsFromPrefs(); // ✅ Sẽ chạy an toàn vì detector đã được tạo

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
        settingsBtn = findViewById(R.id.settingsBtn);
        registerBtn = findViewById(R.id.registerBtn);
        
        manageFacesBtn = findViewById(R.id.manageFacesBtn);
        permissionDeniedText = findViewById(R.id.permissionDeniedText);
        
        updateOverlayMirror();
    }

    private void setupClickListeners() {
        switchCameraBtn.setOnClickListener(v -> {
            currentCamera = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA
                ? CameraSelector.DEFAULT_BACK_CAMERA
                : CameraSelector.DEFAULT_FRONT_CAMERA;
            
            switchCameraBtn.setEnabled(false);
            switchCameraBtn.setAlpha(0.5f);
            
            cameraExecutor.execute(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                runOnUiThread(() -> {
                    updateOverlayMirror();
                    startCamera();
                    switchCameraBtn.setEnabled(true);
                    switchCameraBtn.setAlpha(1.0f);
                });
            });
        });

        settingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
        });

        registerBtn.setOnClickListener(v -> {
        // ✅ Luôn cho phép vào đăng ký, không cần kiểm tra face
            Intent intent = new Intent(MainActivity.this, RegistrationActivity.class);
            startActivityForResult(intent, REQUEST_CODE_REGISTRATION);
        });

        if (manageFacesBtn != null) {
            manageFacesBtn.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, ManageFacesActivity.class);
                startActivity(intent);
            });
        }

        hudTextView.setOnLongClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
            return true;
        });
    }

    private void updateOverlayMirror() {
        boolean isFront = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA;
        faceOverlay.setMirrorX(isFront);
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void initDetector() {
        try {
            MultiFaceDetector.Config config = MultiFaceDetector.Config.createDefault()
                .setEnableRecognition(true); // ✅ Bật recognition mặc định
            
            detector = new MultiFaceDetector(
                (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                    lastDetectedFaces = new ArrayList<>(results);
                    faceOverlay.update(results, processingMs);
                    updateHud(results.size(), processingMs);
                }),
                this,
                config
            );
            detectorReady = true;
            Log.d(TAG, "Detector initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init detector: " + e.getMessage(), e);
            detectorReady = false;
        }
    }

    private void updateHud(int count, long ms) {
        long fps = ms > 0 ? 1000 / ms : 0;
        hudTextView.setText(String.format("Faces: %d | FPS: %d | Time: %dms", count, fps, ms));
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
                runOnUiThread(() -> 
                    Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder()
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();
        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (detector != null && detector.isReady()) {
                detector.process(imageProxy);
            } else {
                imageProxy.close();
            }
        });

        try {
            cameraProvider.bindToLifecycle(this, currentCamera, preview, analysis);
            Log.d(TAG, "Camera bound: " + 
                (currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA ? "Front" : "Back"));
        } catch (Exception e) {
            Log.e(TAG, "Bind failed", e);
            runOnUiThread(() -> 
                Toast.makeText(this, "Bind error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    // ✅ SỬA: An toàn tuyệt đối
    private void applySettingsFromPrefs() {
        if (!detectorReady || detector == null) {
            Log.w(TAG, "applySettingsFromPrefs: detector not ready, skipping");
            return;
        }
        
        try {
            int frameInterval = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 100);
            detector.setFrameIntervalMs(frameInterval);

            // ✅ Đọc recognition threshold
            float recThreshold = prefs.getFloat(SettingsActivity.KEY_RECOGNITION_THRESHOLD, 0.55f);
            detector.setRecognitionThreshold(recThreshold);
            
            // ✅ Đọc enable recognition
            boolean enableRec = prefs.getBoolean(SettingsActivity.KEY_ENABLE_RECOGNITION, true);
            
            // ✅ An toàn: kiểm tra config trước
            MultiFaceDetector.Config config = detector.getCurrentConfig();
            if (config != null) {
                if (config.enableRecognition != enableRec) {
                    detector.enableRecognition(enableRec);
                    Log.d(TAG, "Recognition toggled to: " + enableRec);
                }
            } else {
                Log.w(TAG, "getCurrentConfig() returned null, cannot toggle recognition");
            }
            
            Log.d(TAG, "Settings applied: threshold=" + recThreshold + ", enableRec=" + enableRec);
        } catch (Exception e) {
            Log.e(TAG, "Error applying settings: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK) {
            applySettingsFromPrefs();
        }
        else if (requestCode == REQUEST_CODE_REGISTRATION && resultCode == RESULT_OK) {
            Toast.makeText(this, "✓ Đã đăng ký khuôn mặt mới", Toast.LENGTH_SHORT).show();
            // Làm mới detector
            if (detector != null) {
                detector.close();
            }
            initDetector();
            applySettingsFromPrefs();
            if (cameraProvider != null) {
                startCamera();
            }
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, 
                                         @NonNull String[] permissions, 
                                         @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (permissionDeniedText != null) {
                    permissionDeniedText.setVisibility(View.GONE);
                }
                startCamera();
            } else {
                if (permissionDeniedText != null) {
                    permissionDeniedText.setVisibility(View.VISIBLE);
                }
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission() && cameraProvider != null) {
            startCamera();
        }
        applySettingsFromPrefs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
    }
}
