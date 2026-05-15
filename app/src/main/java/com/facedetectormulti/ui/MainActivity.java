package com.facedetectormulti.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
import com.facedetectormulti.service.DetectionService;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERM_CAMERA       = 100;
    private static final int PERM_NOTIFICATION = 101;
    private static final int REQUEST_SETTINGS  = 200;
    private static final String PREF_DETECTION_MODE          = "detection_mode";
    // [FIX] Flag: user chủ động dừng service (không auto-start lại)
    private static final String PREF_SERVICE_STOPPED_BY_USER = "service_stopped_by_user";

    // ── UI ────────────────────────────────────────────────────────────
    private PreviewView previewView;
    private FaceOverlayView faceOverlay;
    private ImageButton switchCameraBtn;
    private ImageButton settingsBtn;
    private ImageButton toggleModeBtn;
    private ImageButton toggleServiceBtn;
    private TextView permissionDeniedText;
    private View mqttStatusDot;
    private TextView mqttStatusText;

    // ── Core ──────────────────────────────────────────────────────────
    private MultiFaceDetector detector;
    private MqttManager mqttManager;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;
    private SharedPreferences prefs;

    // [FIX] Giữ ref use case của Activity để unbind chính xác (không unbindAll)
    private Preview activityPreview;
    private ImageAnalysis activityAnalysis;

    // ── State ─────────────────────────────────────────────────────────
    private DetectionMode detectionMode = DetectionMode.MULTI;
    private boolean serviceRunning = false;

    private final BroadcastReceiver serviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            boolean running = intent.getBooleanExtra(DetectionService.EXTRA_IS_RUNNING, false);
            int faceCount   = intent.getIntExtra(DetectionService.EXTRA_FACE_COUNT, 0);
            serviceRunning  = running;
            updateServiceButton(running);
            Log.d(TAG, "Service broadcast: running=" + running + " faces=" + faceCount);
        }
    };

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String saved = prefs.getString(PREF_DETECTION_MODE, DetectionMode.MULTI.name());
        try { detectionMode = DetectionMode.valueOf(saved); }
        catch (Exception e) { detectionMode = DetectionMode.MULTI; }

        initViews();
        setupClickListeners();
        applyDetectionMode(detectionMode, false);

        cameraExecutor = Executors.newSingleThreadExecutor();
        initDetector();
        initMqtt();

        if (hasCameraPermission()) {
            startCamera();
            // [FIX] Xin battery optimization ngay khi đã có camera permission
            requestBatteryOptimizationIfNeeded();
        } else {
            requestCameraPermission();
        }

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter(DetectionService.BROADCAST_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceStatusReceiver, filter);
        }

        if (hasCameraPermission()) {
            if (serviceRunning) {
                // [FIX] Service đang giữ camera → bảo nó nhả ra, đợi 200ms rồi mới bind
                DetectionService.releaseCamera(this);
                new Handler(Looper.getMainLooper()).postDelayed(this::startCamera, 200);
            } else {
                startCamera();
            }
        }

        applyMqttSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(serviceStatusReceiver); } catch (Exception ignored) {}

        // [FIX] CHỈ unbind use case của activity, KHÔNG unbindAll().
        // unbindAll() sẽ xóa cả camera binding của DetectionService!
        if (cameraProvider != null && activityPreview != null && activityAnalysis != null) {
            try {
                cameraProvider.unbind(activityPreview, activityAnalysis);
                Log.d(TAG, "Activity camera use cases unbound");
            } catch (Exception e) {
                Log.w(TAG, "unbind error", e);
            }
        }
        activityPreview  = null;
        activityAnalysis = null;

        // [FIX] Auto-start service khi app vào nền (trừ khi user đã tắt tay)
        boolean stoppedByUser = prefs.getBoolean(PREF_SERVICE_STOPPED_BY_USER, false);
        if (serviceRunning) {
            // Service đang chạy → bảo nó lấy lại camera
            DetectionService.acquireCamera(this);
        } else if (!stoppedByUser && hasCameraPermission()) {
            // Service chưa chạy và user không chủ động tắt → auto-start
            Log.i(TAG, "App going to background → auto-starting DetectionService");
            DetectionService.start(this);
            serviceRunning = true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        if (mqttManager != null) mqttManager.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) cameraExecutor.shutdown();
    }

    // =====================================================================
    // Views & Click listeners
    // =====================================================================

    private void initViews() {
        previewView          = findViewById(R.id.cameraPreview);
        faceOverlay          = findViewById(R.id.faceOverlay);
        switchCameraBtn      = findViewById(R.id.switchCameraBtn);
        settingsBtn          = findViewById(R.id.settingsBtn);
        toggleModeBtn        = findViewById(R.id.toggleModeBtn);
        toggleServiceBtn     = findViewById(R.id.toggleServiceBtn);
        permissionDeniedText = findViewById(R.id.permissionDeniedText);
        mqttStatusDot        = findViewById(R.id.mqttStatusDot);
        mqttStatusText       = findViewById(R.id.mqttStatusText);
        updateOverlayMirror();
        updateServiceButton(serviceRunning);
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

        toggleModeBtn.setOnClickListener(v -> {
            DetectionMode next = (detectionMode == DetectionMode.MULTI)
                    ? DetectionMode.SINGLE : DetectionMode.MULTI;
            applyDetectionMode(next, true);
            if (serviceRunning) DetectionService.setMode(this, next);
        });

        toggleServiceBtn.setOnClickListener(v -> {
            if (serviceRunning) {
                stopDetectionService();
            } else {
                startDetectionService();
            }
        });
    }

    private void updateOverlayMirror() {
        faceOverlay.setMirrorX(currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA);
    }

    // =====================================================================
    // Background Service control
    // =====================================================================

    private void startDetectionService() {
        if (!hasCameraPermission()) {
            Toast.makeText(this, "Cần cấp quyền Camera trước", Toast.LENGTH_SHORT).show();
            return;
        }
        // [FIX] Xóa flag "stopped by user" khi user chủ động bật lại
        prefs.edit().putBoolean(PREF_SERVICE_STOPPED_BY_USER, false).apply();
        DetectionService.start(this);
        serviceRunning = true;
        updateServiceButton(true);
        Toast.makeText(this, "▶ Service đã bật – chạy trong nền", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "DetectionService started");
    }

    private void stopDetectionService() {
        // [FIX] Đánh dấu user chủ động dừng → không auto-start lại khi onPause
        prefs.edit().putBoolean(PREF_SERVICE_STOPPED_BY_USER, true).apply();
        DetectionService.stop(this);
        serviceRunning = false;
        updateServiceButton(false);
        Toast.makeText(this, "⏹ Service đã tắt", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "DetectionService stopped");
    }

    private void updateServiceButton(boolean running) {
        if (toggleServiceBtn == null) return;
        if (running) {
            toggleServiceBtn.setImageResource(android.R.drawable.ic_media_pause);
            toggleServiceBtn.setColorFilter(0xFFFF4444);
            toggleServiceBtn.setContentDescription("Service đang chạy – nhấn để dừng");
        } else {
            toggleServiceBtn.setImageResource(android.R.drawable.ic_media_play);
            toggleServiceBtn.setColorFilter(0xFF44FF88);
            toggleServiceBtn.setContentDescription("Service đang dừng – nhấn để bật nền");
        }
    }

    // =====================================================================
    // Detection Mode
    // =====================================================================

    private void applyDetectionMode(DetectionMode mode, boolean publishMqtt) {
        detectionMode = mode;
        faceOverlay.setDetectionMode(mode);
        updateToggleModeButton(mode);
        prefs.edit().putString(PREF_DETECTION_MODE, mode.name()).apply();
        if (publishMqtt && mqttManager != null) mqttManager.publishModeState(mode);
        Log.i(TAG, "Detection mode → " + mode);
    }

    private void updateToggleModeButton(DetectionMode mode) {
        if (toggleModeBtn == null) return;
        if (mode == DetectionMode.MULTI) {
            toggleModeBtn.setImageResource(R.drawable.ic_mode_multi);
            toggleModeBtn.setColorFilter(0xFF00C8FF);
            toggleModeBtn.setContentDescription("Đang: nhiều người – nhấn để chuyển sang 1 người");
        } else {
            toggleModeBtn.setImageResource(R.drawable.ic_mode_single);
            toggleModeBtn.setColorFilter(0xFFFFCC00);
            toggleModeBtn.setContentDescription("Đang: 1 người – nhấn để chuyển sang nhiều người");
        }
    }

    // =====================================================================
    // Detector
    // =====================================================================

    private void initDetector() {
        try {
            MultiFaceDetector.Config cfg = MultiFaceDetector.Config.createDefault();
            cfg.frameIntervalMs = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 50);
            cfg.accurateMode    = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
            cfg.setMinFaceSize(prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.10f));

            detector = new MultiFaceDetector(
                    (results, processingMs, imgW, imgH) -> runOnUiThread(() -> {
                        faceOverlay.update(results, processingMs);
                        if (mqttManager != null && !serviceRunning) {
                            // Chỉ publish từ activity khi service KHÔNG chạy
                            mqttManager.publishDetection(results, processingMs, detectionMode);
                        }
                    }),
                    cfg
            );
        } catch (Exception e) {
            Log.e(TAG, "Detector init failed", e);
        }
    }

    // =====================================================================
    // MQTT
    // =====================================================================

    private void initMqtt() {
        mqttManager = new MqttManager();
        mqttManager.setStateListener((state, msg) ->
                runOnUiThread(() -> updateMqttStatus(state, msg)));
        mqttManager.setModeCommandListener(mode ->
                runOnUiThread(() -> applyDetectionMode(mode, false)));
        applyMqttSettings();
    }

    private void applyMqttSettings() {
        if (mqttManager == null) return;
        boolean enabled = prefs.getBoolean(SettingsActivity.KEY_MQTT_ENABLED, false);
        mqttManager.configure(
                prefs.getString(SettingsActivity.KEY_MQTT_BROKER, "tcp://192.168.1.100:1883"),
                prefs.getString(SettingsActivity.KEY_MQTT_USERNAME, ""),
                prefs.getString(SettingsActivity.KEY_MQTT_PASSWORD, ""),
                prefs.getString(SettingsActivity.KEY_MQTT_TOPIC, "face/detection"),
                prefs.getInt(SettingsActivity.KEY_MQTT_QOS, 0),
                prefs.getInt(SettingsActivity.KEY_MQTT_PUBLISH_INTERVAL, 250)
        );
        if (enabled) { if (!mqttManager.isConnected()) mqttManager.connect(); }
        else { mqttManager.disconnect(); updateMqttStatus(MqttManager.State.DISCONNECTED, "MQTT đã tắt"); }
    }

    private void updateMqttStatus(MqttManager.State state, String msg) {
        if (mqttStatusDot == null || mqttStatusText == null) return;
        int color;
        switch (state) {
            case CONNECTED:  color = 0xFF00FF64; break;
            case CONNECTING: color = 0xFFFFAA00; break;
            case ERROR:      color = 0xFFFF3333; break;
            default:         color = 0xFF666666; break;
        }
        mqttStatusDot.setBackgroundColor(color);
        String display = msg.length() > 35 ? msg.substring(0, 32) + "..." : msg;
        mqttStatusText.setText("MQTT: " + display);
    }

    // =====================================================================
    // Camera
    // =====================================================================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        // [FIX] Chỉ unbind use case CŨ của activity, không unbindAll
        if (activityPreview != null && activityAnalysis != null) {
            try { cameraProvider.unbind(activityPreview, activityAnalysis); }
            catch (Exception ignored) {}
        }

        String res = prefs.getString(SettingsActivity.KEY_RESOLUTION, "1280");
        Size targetSize;
        switch (res) {
            case "640":  targetSize = new Size(640, 480);   break;
            case "1920": targetSize = new Size(1920, 1080); break;
            default:     targetSize = new Size(1280, 720);  break;
        }

        // [FIX] Lưu ref vào field của Activity
        activityPreview = new Preview.Builder()
                .setTargetRotation(previewView.getDisplay().getRotation()).build();
        activityPreview.setSurfaceProvider(previewView.getSurfaceProvider());

        activityAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.getDisplay().getRotation())
                .build();
        activityAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (detector != null && detector.isReady()) detector.process(imageProxy);
            else imageProxy.close();
        });

        try {
            cameraProvider.bindToLifecycle(this, currentCamera, activityPreview, activityAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "Bind camera failed", e);
        }
    }

    // =====================================================================
    // Permissions
    // =====================================================================

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, PERM_CAMERA);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERM_NOTIFICATION);
            }
        }
    }

    /**
     * [FIX] Xin miễn trừ Battery Optimization.
     * Nếu không có, Android Doze/Battery Saver có thể kill service sau vài phút.
     * Chỉ hỏi 1 lần, lưu flag để không hỏi lại.
     */
    private void requestBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        boolean asked = prefs.getBoolean("battery_opt_asked", false);
        if (asked) return;
        prefs.edit().putBoolean("battery_opt_asked", true).apply();

        new AlertDialog.Builder(this)
                .setTitle("Cho phép chạy nền liên tục")
                .setMessage(
                        "Để Face Detector hoạt động ổn định khi tắt màn hình, " +
                        "hãy tắt Battery Optimization cho app này.\n\n" +
                        "Chọn \"Không hạn chế\" ở màn hình tiếp theo.")
                .setPositiveButton("Cài đặt ngay", (d, w) -> {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Bỏ qua", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (permissionDeniedText != null) permissionDeniedText.setVisibility(View.GONE);
                startCamera();
                requestBatteryOptimizationIfNeeded();
            } else {
                if (permissionDeniedText != null) permissionDeniedText.setVisibility(View.VISIBLE);
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    // =====================================================================
    // Activity Result
    // =====================================================================

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
            startCamera();
        }
    }
}