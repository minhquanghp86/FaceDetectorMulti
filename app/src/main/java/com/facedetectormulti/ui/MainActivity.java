package com.facedetectormulti.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
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
import com.facedetectormulti.mqtt.MqttManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_CAMERA = 100;
    private static final int REQUEST_SETTINGS = 101;

    // UI
    private PreviewView previewView;
    private FaceOverlayView faceOverlay;
    private ImageButton switchCameraBtn, settingsBtn;
    private TextView permissionDeniedText;
    private View mqttStatusDot;
    private TextView mqttStatusText;

    // Core
    private MultiFaceDetector detector;
    private MqttManager mqttManager;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;
    private SharedPreferences prefs;
    private boolean detectorReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        initViews();
        setupClickListeners();
        cameraExecutor = Executors.newSingleThreadExecutor();
        initDetector();
        initMqtt();

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void initViews() {
        previewView = findViewById(R.id.cameraPreview);
        faceOverlay = findViewById(R.id.faceOverlay);
        switchCameraBtn = findViewById(R.id.switchCameraBtn);
        settingsBtn = findViewById(R.id.settingsBtn);
        permissionDeniedText = findViewById(R.id.permissionDeniedText);
        mqttStatusDot = findViewById(R.id.mqttStatusDot);
        mqttStatusText = findViewById(R.id.mqttStatusText);
        updateOverlayMirror();
    }

    private void setupClickListeners() {
        switchCameraBtn.setOnClickListener(v -> {
            currentCamera = (currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA)
                    ? CameraSelector.DEFAULT_BACK_CAMERA
                    : CameraSelector.DEFAULT_FRONT_CAMERA;
            switchCameraBtn.setEnabled(false);
            switchCameraBtn.setAlpha(0.5f);
            cameraExecutor.execute(() -> {
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                runOnUiThread(() -> {
                    updateOverlayMirror();
                    startCamera();
                    switchCameraBtn.setEnabled(true);
                    switchCameraBtn.setAlpha(1f);
                });
            });
        });

        settingsBtn.setOnClickListener(v ->
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS));
    }

    private void updateOverlayMirror() {
        faceOverlay.setMirrorX(currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA);
    }

    // ==================== DETECTOR ====================

    private void initDetector() {
        try {
            MultiFaceDetector.Config cfg = MultiFaceDetector.Config.createDefault();
            cfg.frameIntervalMs = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 50);
            cfg.accurateMode = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
            float mfs = prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.10f);
            cfg.setMinFaceSize(mfs);

            detector = new MultiFaceDetector(
                    (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                        faceOverlay.update(results, processingMs);
                        if (mqttManager != null) {
                            mqttManager.publishDetection(results, processingMs);
                        }
                    }),
                    cfg
            );
            detectorReady = true;
            Log.d(TAG, "Detector initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Detector init failed", e);
            detectorReady = false;
        }
    }

    // ==================== MQTT ====================

    private void initMqtt() {
        mqttManager = new MqttManager();
        mqttManager.setStateListener((state, msg) -> runOnUiThread(() -> updateMqttStatus(state, msg)));
        applyMqttSettings();
    }

    private void applyMqttSettings() {
        if (mqttManager == null) return;

        boolean enabled = prefs.getBoolean(SettingsActivity.KEY_MQTT_ENABLED, false);
        String broker = prefs.getString(SettingsActivity.KEY_MQTT_BROKER, "tcp://192.168.1.100:1883");
        String username = prefs.getString(SettingsActivity.KEY_MQTT_USERNAME, "");
        String password = prefs.getString(SettingsActivity.KEY_MQTT_PASSWORD, "");
        String topic = prefs.getString(SettingsActivity.KEY_MQTT_TOPIC, "face/detection");
        int qos = prefs.getInt(SettingsActivity.KEY_MQTT_QOS, 0);
        int publishInterval = prefs.getInt(SettingsActivity.KEY_MQTT_PUBLISH_INTERVAL, 250);

        mqttManager.configure(broker, username, password, topic, qos, publishInterval);

        if (enabled) {
            if (!mqttManager.isConnected()) {
                mqttManager.connect();
            }
        } else {
            mqttManager.disconnect();
            updateMqttStatus(MqttManager.State.DISCONNECTED, "MQTT đã tắt");
        }
    }

    private void updateMqttStatus(MqttManager.State state, String msg) {
        if (mqttStatusDot == null || mqttStatusText == null) return;

        int color;
        switch (state) {
            case CONNECTED:
                color = 0xFF00FF64;
                break;
            case CONNECTING:
                color = 0xFFFFAA00;
                break;
            case ERROR:
                color = 0xFFFF3333;
                break;
            default:
                color = 0xFF666666;
                break;
        }
        mqttStatusDot.setBackgroundColor(color);

        String display = msg.length() > 35 ? msg.substring(0, 32) + "..." : msg;
        mqttStatusText.setText("MQTT: " + display);
    }

    // ==================== CAMERA ====================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Lỗi camera: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        String res = prefs.getString(SettingsActivity.KEY_RESOLUTION, "1280");
        Size targetSize;
        switch (res) {
            case "640":
                targetSize = new Size(640, 480);
                break;
            case "1920":
                targetSize = new Size(1920, 1080);
                break;
            default:
                targetSize = new Size(1280, 720);
                break;
        }

        Preview preview = new Preview.Builder()
                .setTargetRotation(previewView.getDisplay().getRotation())
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
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
        } catch (Exception e) {
            Log.e(TAG, "Bind camera failed", e);
        }
    }

    // ==================== LIFECYCLE ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            // Cập nhật detector
            if (detector != null) {
                MultiFaceDetector.Config cfg = detector.getCurrentConfig();
                cfg.frameIntervalMs = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 50);
                cfg.accurateMode = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
                cfg.setMinFaceSize(prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.10f));
                detector.applyConfig(cfg);
            }
            // Cập nhật MQTT
            applyMqttSettings();

            // Restart camera nếu resolution thay đổi
            if (cameraProvider != null) {
                startCamera();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission() && cameraProvider != null) {
            startCamera();
        }
        applyMqttSettings();
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
        if (mqttManager != null) mqttManager.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
    }

    // ==================== PERMISSION ====================

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERMISSION_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (permissionDeniedText != null) permissionDeniedText.setVisibility(View.GONE);
                startCamera();
            } else {
                if (permissionDeniedText != null) permissionDeniedText.setVisibility(View.VISIBLE);
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }
}