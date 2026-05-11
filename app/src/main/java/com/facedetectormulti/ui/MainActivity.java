package com.facedetectormulti.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import com.facedetectormulti.detection.DetectionResult;
import com.facedetectormulti.detection.FaceEmbeddingExtractor;
import com.facedetectormulti.detection.FaceResult;
import com.facedetectormulti.detection.MultiFaceDetector;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FaceDetectorMulti";
    private static final int PERMISSION_CAMERA = 100;
    private static final int REQUEST_CODE_SETTINGS = 101;
    private static final int REQUEST_CODE_REGISTRATION = 102;

    // UI components
    private PreviewView previewView;
    private FaceOverlayView faceOverlay;
    private TextView hudTextView;
    private ImageButton switchCameraBtn;
    private ImageButton settingsBtn;    private ImageButton registerBtn;  // ✅ New
    private TextView permissionDeniedText;

    // Camera & detection
    private MultiFaceDetector detector;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;
    
    // Settings
    private SharedPreferences prefs;
    
    // For registration: store latest frame
    private Bitmap latestFrameBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        initViews();
        setupClickListeners();
        initCameraExecutor();
        initDetector();
        applySettingsFromPrefs();

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
        registerBtn = findViewById(R.id.registerBtn);  // ✅ New
        permissionDeniedText = findViewById(R.id.permissionDeniedText);

        updateOverlayMirror();
    }

    private void setupClickListeners() {
        // Camera switch
        switchCameraBtn.setOnClickListener(v -> {            currentCamera = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA
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

        // Settings button
        settingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
        });

        // ✅ Register button: crop largest face and open registration
        registerBtn.setOnClickListener(v -> {
            if (latestFrameBitmap != null && detector != null) {
                // Find largest face in current detection (simplified)
                // In production: store face bounding boxes from last detection
                Toast.makeText(this, "📸 Đang chuẩn bị đăng ký...", Toast.LENGTH_SHORT).show();
                
                // For demo: open registration with placeholder
                // Real implementation: crop face from latestFrameBitmap using last detected box
                Intent intent = new Intent(MainActivity.this, RegistrationActivity.class);
                // intent.putExtra("face_bitmap", croppedFaceBitmap);
                startActivityForResult(intent, REQUEST_CODE_REGISTRATION);
            } else {
                Toast.makeText(this, "⚠ Chưa phát hiện khuôn mặt nào", Toast.LENGTH_SHORT).show();
            }
        });

        // HUD long-click → settings (backup)
        hudTextView.setOnLongClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
            return true;
        });
    }

    private void updateOverlayMirror() {        boolean isFront = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA;
        faceOverlay.setMirrorX(isFront);
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void initDetector() {
        // ✅ Pass context for recognition support
        detector = new MultiFaceDetector(
            (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                faceOverlay.update(results, processingMs);
                updateHud(results.size(), processingMs);
            }),
            this  // Context for Room database
        );
    }

    private void updateHud(int faceCount, long processingMs) {
        long fps = processingMs > 0 ? 1000 / processingMs : 0;
        hudTextView.setText(String.format("Faces: %d | FPS: %d | Time: %dms", 
            faceCount, fps, processingMs));
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
                    Toast.makeText(this, 
                        getString(R.string.error_camera_init, e.getMessage()), 
                        Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder()
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(previewView.getDisplay().getRotation())
            .build();

        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
            // Store latest frame for registration (simplified)
            if (registerBtn != null && registerBtn.isPressed()) {
                // In production: properly convert and store frame
            }
            
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
            Log.e(TAG, "Bind camera failed", e);
            runOnUiThread(() -> 
                Toast.makeText(this, 
                    getString(R.string.error_camera_bind, e.getMessage()), 
                    Toast.LENGTH_SHORT).show());
        }
    }

    // ===== Settings & Registration =====
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK) {
            applySettingsFromPrefs();
        }
        else if (requestCode == REQUEST_CODE_REGISTRATION && resultCode == RESULT_OK) {
            Toast.makeText(this, "✓ Đã đăng ký khuôn mặt mới", Toast.LENGTH_SHORT).show();
            // Re-init detector to refresh registered faces list
            if (detector != null) {
                detector.close();
                initDetector();
            }
        }    }

    private void applySettingsFromPrefs() {
        int frameInterval = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 100);
        if (detector != null) {
            detector.setFrameIntervalMs(frameInterval);
        }

        float minFaceSize = prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.12f);
        float minConfidence = prefs.getFloat(SettingsActivity.KEY_MIN_CONFIDENCE, 0.5f);
        boolean accurateMode = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
        boolean enableRecognition = prefs.getBoolean("pref_enable_recognition", false);
        
        MultiFaceDetector.Config currentConfig = detector != null ? detector.getCurrentConfig() : null;
        
        boolean needReinit = currentConfig == null || 
            Math.abs(currentConfig.minFaceSize - minFaceSize) > 0.01f ||
            Math.abs(currentConfig.minConfidence - minConfidence) > 0.05f ||
            currentConfig.accurateMode != accurateMode ||
            currentConfig.enableRecognition != enableRecognition;
        
        if (needReinit) {
            Log.d(TAG, "Config changed, re-initializing detector...");
            if (detector != null) detector.close();
            
            MultiFaceDetector.Config newConfig = MultiFaceDetector.Config.createDefault()
                .setMinFaceSize(minFaceSize)
                .setMinConfidence(minConfidence)
                .setAccurateMode(accurateMode)
                .setEnableRecognition(enableRecognition)
                .setMinBoxAreaRatio(0.003f)
                .setAspectRatioTolerance(0.6f)
                .setFrameIntervalMs(frameInterval);
            
            detector = new MultiFaceDetector(
                (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                    faceOverlay.update(results, processingMs);
                    updateHud(results.size(), processingMs);
                }),
                this,
                newConfig
            );
            
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                startCamera();
            }
        }
    }
    // ===== Permissions =====
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
                permissionDeniedText.setVisibility(View.GONE);
                startCamera();
            } else {
                permissionDeniedText.setVisibility(View.VISIBLE);
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ===== Lifecycle =====
    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission() && cameraProvider != null) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (latestFrameBitmap != null) {
            latestFrameBitmap.recycle();
            latestFrameBitmap = null;
        }
    }

    @Override
    protected void onDestroy() {        super.onDestroy();
        if (detector != null) detector.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
        if (latestFrameBitmap != null) latestFrameBitmap.recycle();
    }
}