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
import com.facedetectormulti.detection.DetectionMode;
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
    private static final int REQUEST_SETTINGS  = 101;

    // Preference key lưu mode khi thoát app
    private static final String PREF_DETECTION_MODE = "detection_mode";

    // ===================== UI =====================
    private PreviewView previewView;
    private FaceOverlayView faceOverlay;
    private ImageButton switchCameraBtn;
    private ImageButton settingsBtn;
    private ImageButton toggleModeBtn;   // NÚT MỚI: chuyển đổi SINGLE / MULTI
    private TextView permissionDeniedText;
    private View mqttStatusDot;
    private TextView mqttStatusText;

    // ===================== Core =====================
    private MultiFaceDetector detector;
    private MqttManager mqttManager;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;
    private SharedPreferences prefs;
    private boolean detectorReady = false;

    // Mode hiện tại – nguồn sự thật duy nhất
    private DetectionMode detectionMode = DetectionMode.MULTI;

    // ===================== Lifecycle =====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Khôi phục mode từ lần trước
        String savedMode = prefs.getString(PREF_DETECTION_MODE, DetectionMode.MULTI.name());
        detectionMode = DetectionMode.valueOf(savedMode);

        initViews();
        setupClickListeners();
        applyDetectionMode(detectionMode, false); // không publish MQTT khi init

        cameraExecutor = Executors.newSingleThreadExecutor();
        initDetector();
        initMqtt();

        if (hasCameraPermission()) startCamera();
        else requestCameraPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission() && cameraProvider != null) startCamera();
        applyMqttSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        if (mqttManager != null) mqttManager.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) cameraExecutor.shutdown();
    }

    // ===================== Views =====================

    private void initViews() {
        previewView          = findViewById(R.id.cameraPreview);
        faceOverlay          = findViewById(R.id.faceOverlay);
        switchCameraBtn      = findViewById(R.id.switchCameraBtn);
        settingsBtn          = findViewById(R.id.settingsBtn);
        toggleModeBtn        = findViewById(R.id.toggleModeBtn);
        permissionDeniedText = findViewById(R.id.permissionDeniedText);
        mqttStatusDot        = findViewById(R.id.mqttStatusDot);
        mqttStatusText       = findViewById(R.id.mqttStatusText);
        updateOverlayMirror();
    }

    private void setupClickListeners() {
        // Chuyển camera trước/sau
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

        // Cài đặt
        settingsBtn.setOnClickListener(v ->
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS));

        // Toggle mode SINGLE ↔ MULTI
        toggleModeBtn.setOnClickListener(v -> {
            DetectionMode next = (detectionMode == DetectionMode.MULTI)
                    ? DetectionMode.SINGLE
                    : DetectionMode.MULTI;
            applyDetectionMode(next, true); // publish MQTT khi user bấm nút
        });
    }

    private void updateOverlayMirror() {
        faceOverlay.setMirrorX(currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA);
    }

    // ===================== Detection Mode =====================

    /**
     * Áp dụng chế độ phát hiện mới.
     *
     * @param mode        chế độ mới
     * @param publishMqtt true nếu cần thông báo ngay lên HASS
     */
    private void applyDetectionMode(DetectionMode mode, boolean publishMqtt) {
        detectionMode = mode;

        // Cập nhật overlay
        faceOverlay.setDetectionMode(mode);

        // Cập nhật icon/tint nút toggle
        updateToggleModeButton(mode);

        // Lưu preference
        prefs.edit().putString(PREF_DETECTION_MODE, mode.name()).apply();

        // Đồng bộ MQTT nếu cần
        if (publishMqtt && mqttManager != null) {
            mqttManager.publishModeState(mode);
        }

        Log.i(TAG, "Detection mode → " + mode);
    }

    /**
     * Cập nhật giao diện nút toggle theo mode hiện tại.
     * - MULTI → icon người nhiều, tint xanh cyan (đang ở chế độ multi, nhấn để chuyển sang single)
     * - SINGLE → icon người đơn, tint vàng (đang ở chế độ single, nhấn để chuyển sang multi)
     */
    private void updateToggleModeButton(DetectionMode mode) {
        if (toggleModeBtn == null) return;
        if (mode == DetectionMode.MULTI) {
            // Đang MULTI → icon gợi ý "nhiều người"
            toggleModeBtn.setImageResource(R.drawable.ic_mode_multi);
            toggleModeBtn.setColorFilter(0xFF00C8FF); // xanh cyan
            toggleModeBtn.setContentDescription("Đang: nhiều người – nhấn để chuyển sang 1 người");
        } else {
            // Đang SINGLE → icon gợi ý "1 người"
            toggleModeBtn.setImageResource(R.drawable.ic_mode_single);
            toggleModeBtn.setColorFilter(0xFFFFCC00); // vàng
            toggleModeBtn.setContentDescription("Đang: 1 người – nhấn để chuyển sang nhiều người");
        }
    }

    // ===================== Detector =====================

    private void initDetector() {
        try {
            MultiFaceDetector.Config cfg = MultiFaceDetector.Config.createDefault();
            cfg.frameIntervalMs = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 50);
            cfg.accurateMode    = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
            cfg.setMinFaceSize(prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.10f));

            detector = new MultiFaceDetector(
                    (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                        faceOverlay.update(results, processingMs);
                        if (mqttManager != null) {
                            mqttManager.publishDetection(results, processingMs, detectionMode);
                        }
                    }),
                    cfg
            );
            detectorReady = true;
            Log.d(TAG, "Detector initialized");
        } catch (Exception e) {
            Log.e(TAG, "Detector init failed", e);
            detectorReady = false;
        }
    }

    // ===================== MQTT =====================

    private void initMqtt() {
        mqttManager = new MqttManager();
        mqttManager.setStateListener((state, msg) ->
                runOnUiThread(() -> updateMqttStatus(state, msg)));

        // Lắng nghe lệnh đổi mode từ HASS
        mqttManager.setModeCommandListener(mode ->
                runOnUiThread(() -> applyDetectionMode(mode, false)));
        // false: không publish lại MQTT khi nhận lệnh từ HASS
        // (MqttManager đã tự echo state trong handleIncoming)

        applyMqttSettings();
    }

    private void applyMqttSettings() {
        if (mqttManager == null) return;
        boolean enabled   = prefs.getBoolean(SettingsActivity.KEY_MQTT_ENABLED, false);
        String broker     = prefs.getString(SettingsActivity.KEY_MQTT_BROKER, "tcp://192.168.1.100:1883");
        String username   = prefs.getString(SettingsActivity.KEY_MQTT_USERNAME, "");
        String password   = prefs.getString(SettingsActivity.KEY_MQTT_PASSWORD, "");
        String topic      = prefs.getString(SettingsActivity.KEY_MQTT_TOPIC, "face/detection");
        int qos           = prefs.getInt(SettingsActivity.KEY_MQTT_QOS, 0);
        int interval      = prefs.getInt(SettingsActivity.KEY_MQTT_PUBLISH_INTERVAL, 250);

        mqttManager.configure(broker, username, password, topic, qos, interval);
        if (enabled) {
            if (!mqttManager.isConnected()) mqttManager.connect();
        } else {
            mqttManager.disconnect();
            updateMqttStatus(MqttManager.State.DISCONNECTED, "MQTT đã tắt");
        }
    }

    private void updateMqttStatus(MqttManager.State state, String msg) {
        if (mqttStatusDot == null || mqttStatusText == null) return;
        int color;
        switch (state) {
            case CONNECTED:   color = 0xFF00FF64; break;
            case CONNECTING:  color = 0xFFFFAA00; break;
            case ERROR:       color = 0xFFFF3333; break;
            default:          color = 0xFF666666; break;
        }
        mqttStatusDot.setBackgroundColor(color);
        String display = msg.length() > 35 ? msg.substring(0, 32) + "..." : msg;
        mqttStatusText.setText("MQTT: " + display);
    }

    // ===================== Camera =====================

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
            case "640":  targetSize = new Size(640,  480);  break;
            case "1920": targetSize = new Size(1920, 1080); break;
            default:     targetSize = new Size(1280, 720);  break;
        }

        Preview preview = new Preview.Builder()
                .setTargetRotation(previewView.getDisplay().getRotation()).build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.getDisplay().getRotation())
                .build();

        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (detector != null && detector.isReady()) detector.process(imageProxy);
            else imageProxy.close();
        });

        try {
            cameraProvider.bindToLifecycle(this, currentCamera, preview, analysis);
        } catch (Exception e) {
            Log.e(TAG, "Bind camera failed", e);
        }
    }

    // ===================== Activity Result =====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            if (detector != null) {
                MultiFaceDetector.Config cfg = detector.getCurrentConfig();
                cfg.frameIntervalMs = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 50);
                cfg.accurateMode    = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
                cfg.setMinFaceSize(prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.10f));
                detector.applyConfig(cfg);
            }
            applyMqttSettings();
            if (cameraProvider != null) startCamera();
        }
    }

    // ===================== Permission =====================

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERMISSION_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (permissionDeniedText != null)
                    permissionDeniedText.setVisibility(View.GONE);
                startCamera();
            } else {
                if (permissionDeniedText != null)
                    permissionDeniedText.setVisibility(View.VISIBLE);
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }
}
