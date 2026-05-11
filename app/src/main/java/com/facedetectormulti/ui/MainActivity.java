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

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FaceDetectorMulti";
    private static final int PERMISSION_CAMERA = 100;
    private static final int REQUEST_CODE_SETTINGS = 101;

    private PreviewView previewView;
    private FaceOverlayView faceOverlay;
    private TextView hudTextView;
    private ImageButton switchCameraBtn, settingsBtn, registerBtn;
    private TextView permissionDeniedText;

    private MultiFaceDetector detector;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        initViews();
        setupClickListeners();
        initCameraExecutor();
        initDetector();  // ✅ Uses 3-param constructor
        applySettingsFromPrefs();

        if (hasCameraPermission()) startCamera();
        else requestCameraPermission();
    }

    private void initViews() {
        previewView = findViewById(R.id.cameraPreview);
        faceOverlay = findViewById(R.id.faceOverlay);
        hudTextView = findViewById(R.id.hudTextView);
        switchCameraBtn = findViewById(R.id.switchCameraBtn);
        settingsBtn = findViewById(R.id.settingsBtn);
        registerBtn = findViewById(R.id.registerBtn);
        permissionDeniedText = findViewById(R.id.permissionDeniedText);
        updateOverlayMirror();
    }

    private void setupClickListeners() {
        switchCameraBtn.setOnClickListener(v -> {
            currentCamera = currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA
                ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
            switchCameraBtn.setEnabled(false); switchCameraBtn.setAlpha(0.5f);
            cameraExecutor.execute(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                runOnUiThread(() -> {
                    updateOverlayMirror(); startCamera();
                    switchCameraBtn.setEnabled(true); switchCameraBtn.setAlpha(1.0f);
                });
            });
        });
        settingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
        });
        registerBtn.setOnClickListener(v -> {
            Toast.makeText(this, "📸 Register feature coming soon", Toast.LENGTH_SHORT).show();
        });
        hudTextView.setOnLongClickListener(v -> {            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
            return true;
        });
    }

    private void updateOverlayMirror() {
        faceOverlay.setMirrorX(currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA);
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    // ✅ Uses 3-param constructor: (callback, context, config)
    private void initDetector() {
        detector = new MultiFaceDetector(
            (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                faceOverlay.update(results, processingMs);
                updateHud(results.size(), processingMs);
            }),
            this,  // Context
            MultiFaceDetector.Config.createDefault()  // Config
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
                Log.e(TAG, "Camera start failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder()
            .setTargetRotation(previewView.getDisplay().getRotation()).build();        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(previewView.getDisplay().getRotation()).build();
        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (detector != null && detector.isReady()) detector.process(imageProxy);
            else imageProxy.close();
        });

        try {
            cameraProvider.bindToLifecycle(this, currentCamera, preview, analysis);
            Log.d(TAG, "Camera bound: " + (currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA ? "Front" : "Back"));
        } catch (Exception e) {
            Log.e(TAG, "Bind failed", e);
            runOnUiThread(() -> Toast.makeText(this, "Bind error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK) {
            applySettingsFromPrefs();
        }
    }

    private void applySettingsFromPrefs() {
        int frameInterval = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 100);
        if (detector != null) detector.setFrameIntervalMs(frameInterval);

        float minFaceSize = prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.12f);
        float minConf = prefs.getFloat(SettingsActivity.KEY_MIN_CONFIDENCE, 0.5f);
        boolean accurate = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
        boolean enableRec = prefs.getBoolean("pref_enable_recognition", false);  // ✅ Added

        MultiFaceDetector.Config current = detector != null ? detector.getCurrentConfig() : null;
        boolean needReinit = current == null ||
            Math.abs(current.minFaceSize - minFaceSize) > 0.01f ||
            Math.abs(current.minConfidence - minConf) > 0.05f ||
            current.accurateMode != accurate ||
            current.enableRecognition != enableRec;  // ✅ Added check

        if (needReinit) {
            Log.d(TAG, "Re-init detector with new config");
            if (detector != null) detector.close();
            
            MultiFaceDetector.Config newCfg = MultiFaceDetector.Config.createDefault()
                .setMinFaceSize(minFaceSize)
                .setMinConfidence(minConf)
                .setAccurateMode(accurate)
                .setEnableRecognition(enableRec)
                // ✅ Added setter
                .setMinBoxAreaRatio(0.003f)
                .setFrameIntervalMs(frameInterval);
            
            // ✅ Re-create with 3-param constructor
            detector = new MultiFaceDetector(
                (results, ms, w, h) -> runOnUiThread(() -> {
                    faceOverlay.update(results, ms);
                    updateHud(results.size(), ms);
                }),
                this, newCfg
            );
            if (cameraProvider != null) { cameraProvider.unbindAll(); startCamera(); }
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
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

    @Override protected void onResume() {
        super.onResume();
        if (hasCameraPermission() && cameraProvider != null) startCamera();
    }

    @Override protected void onPause() {
        super.onPause();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) cameraExecutor.shutdown();    }
}
