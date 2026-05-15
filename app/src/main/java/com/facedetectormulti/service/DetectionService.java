package com.facedetectormulti.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.preference.PreferenceManager;

import com.facedetectormulti.R;
import com.facedetectormulti.detection.DetectionMode;
import com.facedetectormulti.detection.FaceResult;
import com.facedetectormulti.detection.MultiFaceDetector;
import com.facedetectormulti.mqtt.MqttManager;
import com.facedetectormulti.ui.MainActivity;
import com.facedetectormulti.ui.SettingsActivity;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetectionService extends Service implements LifecycleOwner {

    private static final String TAG = "DetectionService";

    public static final String ACTION_START    = "com.facedetectormulti.START";
    public static final String ACTION_STOP     = "com.facedetectormulti.STOP";
    public static final String ACTION_SET_MODE = "com.facedetectormulti.SET_MODE";
    public static final String EXTRA_MODE      = "mode";

    public static final String BROADCAST_STATUS = "com.facedetectormulti.SERVICE_STATUS";
    public static final String EXTRA_FACE_COUNT = "face_count";
    public static final String EXTRA_IS_RUNNING = "running";

    private static final String CHANNEL_ID = "face_detection_channel";
    private static final int    NOTIF_ID   = 1001;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

    private MultiFaceDetector detector;
    private MqttManager mqttManager;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private SharedPreferences prefs;

    private DetectionMode currentMode = DetectionMode.MULTI;
    private volatile boolean running  = false;
    private int lastFaceCount         = -1;

    // ======================================================================
    // Service lifecycle
    // ======================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        createNotificationChannel();
        Log.i(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null && intent.getAction() != null)
                ? intent.getAction() : ACTION_START;

        switch (action) {
            case ACTION_STOP:
                Log.i(TAG, "ACTION_STOP");
                stopSelf();
                return START_NOT_STICKY;

            case ACTION_SET_MODE:
                DetectionMode m = DetectionMode.fromMqtt(
                        intent.getStringExtra(EXTRA_MODE));
                applyMode(m);
                return START_STICKY;

            case ACTION_START:
            default:
                if (!running) startDetection();
                return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed");
        running = false;
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);

        if (cameraProvider != null) cameraProvider.unbindAll();
        if (detector != null) detector.close();
        if (mqttManager != null) mqttManager.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown())
            cameraExecutor.shutdown();

        broadcastStatus(false, 0);
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public LifecycleRegistry getLifecycle() { return lifecycle; }

    // ======================================================================
    // Khởi động detection
    // ======================================================================

    private void startDetection() {
        running = true;

        String saved = prefs.getString("detection_mode", DetectionMode.MULTI.name());
        try { currentMode = DetectionMode.valueOf(saved); }
        catch (Exception e) { currentMode = DetectionMode.MULTI; }

        startForeground(NOTIF_ID, buildNotification("Đang khởi động...", 0));
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        lifecycle.setCurrentState(Lifecycle.State.RESUMED);

        cameraExecutor = Executors.newSingleThreadExecutor();
        initMqtt();
        initCamera();
    }

    // ======================================================================
    // Camera + Detector
    // ======================================================================

    private void initCamera() {
        try {
            MultiFaceDetector.Config cfg = MultiFaceDetector.Config.createDefault();
            cfg.frameIntervalMs = prefs.getInt(SettingsActivity.KEY_FRAME_INTERVAL, 80);
            cfg.accurateMode    = prefs.getBoolean(SettingsActivity.KEY_ACCURATE_MODE, false);
            cfg.setMinFaceSize(prefs.getFloat(SettingsActivity.KEY_MIN_FACE_SIZE, 0.10f));

            detector = new MultiFaceDetector(
                    (results, processingMs, w, h) -> onResult(results, processingMs),
                    cfg
            );
        } catch (Exception e) {
            Log.e(TAG, "Detector init error", e);
            updateNotification("Lỗi detector", 0);
            return;
        }

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "CameraProvider error", e);
                updateNotification("Lỗi camera: " + e.getMessage(), 0);
            }
        }, getMainExecutor());
    }

    private void bindCamera() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(cameraExecutor, imageProxy -> {
            if (detector != null && detector.isReady()) detector.process(imageProxy);
            else imageProxy.close();
        });

        try {
            cameraProvider.bindToLifecycle(this,
                    CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
            Log.i(TAG, "Camera bound (background)");
            updateNotification("Đang theo dõi...", 0);
        } catch (Exception e) {
            Log.e(TAG, "bindCamera failed", e);
            updateNotification("Lỗi bind camera", 0);
        }
    }

    // ======================================================================
    // Detection result
    // ======================================================================

    private void onResult(List<? extends FaceResult> results, long processingMs) {
        if (mqttManager != null) {
            mqttManager.publishDetection(results, processingMs, currentMode);
        }

        int count = (currentMode == DetectionMode.SINGLE)
                ? (results.isEmpty() ? 0 : 1)
                : results.size();

        if (count != lastFaceCount) {
            lastFaceCount = count;
            String text = count == 0 ? "Không có ai" : "Phát hiện " + count + " người";
            updateNotification(text, count);
            broadcastStatus(true, count);
        }
    }

    // ======================================================================
    // Mode
    // ======================================================================

    public void applyMode(DetectionMode mode) {
        currentMode = mode;
        prefs.edit().putString("detection_mode", mode.name()).apply();
        if (mqttManager != null) mqttManager.publishModeState(mode);
        Log.i(TAG, "Mode applied: " + mode);
    }

    // ======================================================================
    // MQTT
    // ======================================================================

    private void initMqtt() {
        mqttManager = new MqttManager();
        mqttManager.setModeCommandListener(mode -> {
            applyMode(mode);
            broadcastStatus(running, lastFaceCount);
        });

        boolean enabled = prefs.getBoolean(SettingsActivity.KEY_MQTT_ENABLED, false);
        if (!enabled) return;

        mqttManager.configure(
                prefs.getString(SettingsActivity.KEY_MQTT_BROKER, "tcp://192.168.1.100:1883"),
                prefs.getString(SettingsActivity.KEY_MQTT_USERNAME, ""),
                prefs.getString(SettingsActivity.KEY_MQTT_PASSWORD, ""),
                prefs.getString(SettingsActivity.KEY_MQTT_TOPIC, "face/detection"),
                prefs.getInt(SettingsActivity.KEY_MQTT_QOS, 0),
                prefs.getInt(SettingsActivity.KEY_MQTT_PUBLISH_INTERVAL, 300)
        );
        mqttManager.connect();
    }

    // ======================================================================
    // Broadcast
    // ======================================================================

    private void broadcastStatus(boolean isRunning, int faceCount) {
        Intent i = new Intent(BROADCAST_STATUS);
        i.putExtra(EXTRA_IS_RUNNING, isRunning);
        i.putExtra(EXTRA_FACE_COUNT, faceCount);
        sendBroadcast(i);
    }

    // ======================================================================
    // Notification
    // ======================================================================

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Face Detection Service",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Theo dõi khuôn mặt trong nền");
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String statusText, int faceCount) {
        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, DetectionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String modeLabel = currentMode == DetectionMode.SINGLE ? "1 người" : "nhiều người";
        String sub = faceCount > 0
                ? "👤 " + faceCount + " người  |  Chế độ: " + modeLabel
                : "Chế độ: " + modeLabel + "  |  Đang chờ...";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mode_single)
                .setContentTitle("Face Detector")
                .setContentText(statusText)
                .setSubText(sub)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "Dừng", stopPi)
                .setOngoing(true)
                .setSilent(true)
                .setForegroundServiceBehavior(
                        NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void updateNotification(String statusText, int faceCount) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(statusText, faceCount));
    }

    // ======================================================================
    // Static helpers – gọi từ MainActivity / BootReceiver
    // ======================================================================

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, DetectionService.class)
                .setAction(ACTION_START));
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, DetectionService.class)
                .setAction(ACTION_STOP));
    }

    public static void setMode(Context ctx, DetectionMode mode) {
        ctx.startService(new Intent(ctx, DetectionService.class)
                .setAction(ACTION_SET_MODE)
                .putExtra(EXTRA_MODE, mode.mqttValue()));
    }
}