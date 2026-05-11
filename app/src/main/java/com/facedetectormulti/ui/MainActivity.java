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
    private ImageButton manageFacesBtn;    private TextView permissionDeniedText;  // ✅ Nullable - an toàn nếu layout thiếu

    // Core Objects
    private MultiFaceDetector detector;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;
    private SharedPreferences prefs;

    // State
    private List<FaceResult> lastDetectedFaces = new ArrayList<>();

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
        registerBtn = findViewById(R.id.registerBtn);
        
        // ✅ Nullable: return null nếu ID không có trong layout
        manageFacesBtn = findViewById(R.id.manageFacesBtn);
        permissionDeniedText = findViewById(R.id.permissionDeniedText);  // ✅ Cũng nullable
        
        updateOverlayMirror();
    }

    private void setupClickListeners() {
        // 1. Switch Camera
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

        // 2. Open Settings
        settingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
        });

        // 3. Register Face
        registerBtn.setOnClickListener(v -> {
            if (lastDetectedFaces.isEmpty()) {
                Toast.makeText(this, "⚠ Chưa phát hiện khuôn mặt nào", Toast.LENGTH_SHORT).show();
                return;
            }
            
            FaceResult largestFace = null;
            float maxArea = 0f;
            for (FaceResult face : lastDetectedFaces) {
                float area = face.width() * face.height();
                if (area > maxArea) {
                    maxArea = area;
                    largestFace = face;
                }
            }
            
            if (largestFace != null) {
                Toast.makeText(this, "📸 Đang chuẩn bị đăng ký...", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(MainActivity.this, RegistrationActivity.class);
                startActivityForResult(intent, REQUEST_CODE_REGISTRATION);
            } else {
                Toast.makeText(this, "⚠ Không tìm thấy khuôn mặt hợp lệ", Toast.LENGTH_SHORT).show();
            }
        });
        // 4. Manage Faces - nullable check
        if (manageFacesBtn != null) {
            manageFacesBtn.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, ManageFacesActivity.class);
                startActivity(intent);
            });
        }

        // 5. HUD Long-Click → Quick Settings
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
        detector = new MultiFaceDetector(
            (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                lastDetectedFaces = new ArrayList<>(results);
                faceOverlay.update(results, processingMs);
                updateHud(results.size(), processingMs);
            }),
            this,
            MultiFaceDetector.Config.createDefault()
        );
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
                Log.e(TAG, "Camera start failed", e);                runOnUiThread(() -> 
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

    private void applySettingsFromPrefs() {
        int frameInterval = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 100);
        if (detector != null) detector.setFrameIntervalMs(frameInterval);

        float minFaceSize = prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.12f);
        float minConf = prefs.getFloat(SettingsActivity.KEY_MIN_CONFIDENCE, 0.5f);
        boolean accurate = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
        boolean enableRec = prefs.getBoolean("pref_enable_recognition", false);

        MultiFaceDetector.Config current = detector != null ? detector.getCurrentConfig() : null;
        boolean needReinit = current == null ||
            Math.abs(current.minFaceSize - minFaceSize) > 0.01f ||            Math.abs(current.minConfidence - minConf) > 0.05f ||
            current.accurateMode != accurate ||
            current.enableRecognition != enableRec;

        if (needReinit) {
            Log.d(TAG, "Re-init detector with new config");
            if (detector != null) detector.close();
            
            MultiFaceDetector.Config newCfg = MultiFaceDetector.Config.createDefault()
                .setMinFaceSize(minFaceSize)
                .setMinConfidence(minConf)
                .setAccurateMode(accurate)
                .setEnableRecognition(enableRec)
                .setMinBoxAreaRatio(0.003f)
                .setFrameIntervalMs(frameInterval);
            
            detector = new MultiFaceDetector(
                (results, ms, w, h) -> runOnUiThread(() -> {
                    lastDetectedFaces = new ArrayList<>(results);
                    faceOverlay.update(results, ms);
                    updateHud(results.size(), ms);
                }),
                this,
                newCfg
            );
            
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                startCamera();
            }
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
            if (detector != null) {
                detector.close();
                initDetector();
            }
        }
    }

    private boolean hasCameraPermission() {        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
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
                // ✅ Nullable check: chỉ set visibility nếu view tồn tại
                if (permissionDeniedText != null) {
                    permissionDeniedText.setVisibility(View.GONE);
                }
                startCamera();
            } else {
                // ✅ Nullable check
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
        super.onDestroy();        if (detector != null) detector.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
    }
}