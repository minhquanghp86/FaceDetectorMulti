package com.facedetectormulti.ui;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.facedetectormulti.R;
import com.facedetectormulti.receiver.BootReceiver;
import com.facedetectormulti.service.DetectionService;

public class SettingsActivity extends AppCompatActivity {

    // ── Detection Keys ──────────────────────────────────────────────────
    public static final String KEY_MIN_FACE_SIZE   = "pref_min_face_size";
    public static final String KEY_ACCURATE_MODE   = "pref_accurate_mode";
    public static final String KEY_RESOLUTION      = "pref_resolution";
    public static final String KEY_FRAME_INTERVAL  = "pref_frame_interval";

    // ── MQTT Keys ───────────────────────────────────────────────────────
    public static final String KEY_MQTT_ENABLED          = "pref_mqtt_enabled";
    public static final String KEY_MQTT_BROKER           = "pref_mqtt_broker";
    public static final String KEY_MQTT_USERNAME         = "pref_mqtt_username";
    public static final String KEY_MQTT_PASSWORD         = "pref_mqtt_password";
    public static final String KEY_MQTT_TOPIC            = "pref_mqtt_topic";
    public static final String KEY_MQTT_QOS              = "pref_mqtt_qos";
    public static final String KEY_MQTT_PUBLISH_INTERVAL = "mqtt_publish_interval_ms";

    // ── Background / Boot Keys ──────────────────────────────────────────
    /** Bật service chạy nền khi user thoát app */
    public static final String KEY_BACKGROUND_SERVICE = "pref_background_service";

    // ── Defaults ────────────────────────────────────────────────────────
    private static final float   DEF_MIN_FACE_SIZE          = 0.10f;
    private static final boolean DEF_ACCURATE_MODE          = false;
    private static final String  DEF_RESOLUTION             = "1280";
    private static final int     DEF_FRAME_INTERVAL         = 50;
    private static final boolean DEF_MQTT_ENABLED           = false;
    private static final String  DEF_MQTT_BROKER            = "tcp://192.168.1.100:1883";
    private static final String  DEF_MQTT_USERNAME          = "";
    private static final String  DEF_MQTT_PASSWORD          = "";
    private static final String  DEF_MQTT_TOPIC             = "face/detection";
    private static final int     DEF_MQTT_QOS               = 0;
    private static final int     DEF_MQTT_PUBLISH_INTERVAL  = 250;
    private static final boolean DEF_BACKGROUND_SERVICE     = false;
    private static final boolean DEF_AUTO_START             = false;

    // ── Detection UI ────────────────────────────────────────────────────
    private SeekBar  seekMinFaceSize, seekFrameInterval;
    private TextView tvMinFaceSizeVal, tvFrameIntervalVal;
    private Switch   switchAccurate;
    private RadioGroup rgResolution;
    private RadioButton rbRes640, rbRes1280, rbRes1920;

    // ── MQTT UI ─────────────────────────────────────────────────────────
    private Switch switchMqttEnabled;
    private EditText etMqttBroker, etMqttUsername, etMqttPassword, etMqttTopic;
    private RadioGroup rgMqttQos;
    private RadioButton rbQos0, rbQos1, rbQos2;
    private SeekBar  seekMqttPublishInterval;
    private TextView tvMqttPublishVal;

    // ── Background / Boot UI ─────────────────────────────────────────────
    private Switch  switchBackgroundService;
    private Switch  switchAutoStart;
    private TextView tvAutostartNote;

    private Button btnResetDefaults;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        initViews();
        loadSettings();
        setupListeners();
    }

    private void initViews() {
        // Detection
        seekMinFaceSize    = findViewById(R.id.seek_min_face_size);
        tvMinFaceSizeVal   = findViewById(R.id.tv_min_face_size_val);
        seekFrameInterval  = findViewById(R.id.seek_frame_interval);
        tvFrameIntervalVal = findViewById(R.id.tv_frame_interval_val);
        switchAccurate     = findViewById(R.id.switch_perf_mode);
        rgResolution       = findViewById(R.id.rg_resolution);
        rbRes640           = findViewById(R.id.rb_res_640);
        rbRes1280          = findViewById(R.id.rb_res_1280);
        rbRes1920          = findViewById(R.id.rb_res_1920);

        // MQTT
        switchMqttEnabled        = findViewById(R.id.switch_mqtt_enabled);
        etMqttBroker             = findViewById(R.id.et_mqtt_broker);
        etMqttUsername           = findViewById(R.id.et_mqtt_username);
        etMqttPassword           = findViewById(R.id.et_mqtt_password);
        etMqttTopic              = findViewById(R.id.et_mqtt_topic);
        rgMqttQos                = findViewById(R.id.rg_mqtt_qos);
        rbQos0                   = findViewById(R.id.rb_qos_0);
        rbQos1                   = findViewById(R.id.rb_qos_1);
        rbQos2                   = findViewById(R.id.rb_qos_2);
        seekMqttPublishInterval  = findViewById(R.id.seek_mqtt_publish_interval);
        tvMqttPublishVal         = findViewById(R.id.tv_mqtt_publish_val);

        // Background / Boot
        switchBackgroundService = findViewById(R.id.switch_background_service);
        switchAutoStart         = findViewById(R.id.switch_auto_start);
        tvAutostartNote         = findViewById(R.id.tv_autostart_note);

        btnResetDefaults = findViewById(R.id.btn_reset_defaults);
    }

    private void loadSettings() {
        // Detection
        float mfs = prefs.getFloat(KEY_MIN_FACE_SIZE, DEF_MIN_FACE_SIZE);
        seekMinFaceSize.setProgress(Math.round((mfs - 0.05f) * 100));
        tvMinFaceSizeVal.setText(String.format("%.2f", mfs));

        int fi = prefs.getInt(KEY_FRAME_INTERVAL, DEF_FRAME_INTERVAL);
        seekFrameInterval.setProgress(fi);
        tvFrameIntervalVal.setText(fi + "ms");

        switchAccurate.setChecked(prefs.getBoolean(KEY_ACCURATE_MODE, DEF_ACCURATE_MODE));

        String res = prefs.getString(KEY_RESOLUTION, DEF_RESOLUTION);
        switch (res) {
            case "640":  rbRes640.setChecked(true);  break;
            case "1920": rbRes1920.setChecked(true); break;
            default:     rbRes1280.setChecked(true); break;
        }

        // MQTT
        switchMqttEnabled.setChecked(prefs.getBoolean(KEY_MQTT_ENABLED, DEF_MQTT_ENABLED));
        etMqttBroker.setText(prefs.getString(KEY_MQTT_BROKER, DEF_MQTT_BROKER));
        etMqttUsername.setText(prefs.getString(KEY_MQTT_USERNAME, DEF_MQTT_USERNAME));
        etMqttPassword.setText(prefs.getString(KEY_MQTT_PASSWORD, DEF_MQTT_PASSWORD));
        etMqttTopic.setText(prefs.getString(KEY_MQTT_TOPIC, DEF_MQTT_TOPIC));
        int qos = prefs.getInt(KEY_MQTT_QOS, DEF_MQTT_QOS);
        switch (qos) {
            case 1: rbQos1.setChecked(true); break;
            case 2: rbQos2.setChecked(true); break;
            default: rbQos0.setChecked(true); break;
        }
        int publishInt = prefs.getInt(KEY_MQTT_PUBLISH_INTERVAL, DEF_MQTT_PUBLISH_INTERVAL);
        seekMqttPublishInterval.setProgress(publishInt);
        tvMqttPublishVal.setText(publishInt + "ms");

        // Background / Boot
        boolean bgEnabled   = prefs.getBoolean(KEY_BACKGROUND_SERVICE, DEF_BACKGROUND_SERVICE);
        boolean autoStart   = prefs.getBoolean(BootReceiver.KEY_AUTO_START, DEF_AUTO_START);
        switchBackgroundService.setChecked(bgEnabled);
        switchAutoStart.setChecked(autoStart);

        // Cảnh báo Xiaomi/Huawei nếu auto-start bật
        tvAutostartNote.setVisibility(autoStart ? View.VISIBLE : View.GONE);
    }

    private void setupListeners() {
        // ── Detection ──────────────────────────────────────────────────
        seekMinFaceSize.setOnSeekBarChangeListener(simpleSeekListener(progress -> {
            float v = 0.05f + progress * 0.01f;
            tvMinFaceSizeVal.setText(String.format("%.2f", v));
            prefs.edit().putFloat(KEY_MIN_FACE_SIZE, v).apply();
        }));

        seekFrameInterval.setOnSeekBarChangeListener(simpleSeekListener(progress -> {
            tvFrameIntervalVal.setText(progress + "ms");
            prefs.edit().putInt(KEY_FRAME_INTERVAL, progress).apply();
        }));

        switchAccurate.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_ACCURATE_MODE, checked).apply();
            Toast.makeText(this, "⚠ Cần restart camera để áp dụng", Toast.LENGTH_SHORT).show();
        });

        rgResolution.setOnCheckedChangeListener((g, id) -> {
            String v = id == R.id.rb_res_640 ? "640" : id == R.id.rb_res_1920 ? "1920" : "1280";
            prefs.edit().putString(KEY_RESOLUTION, v).apply();
            Toast.makeText(this, "⚠ Cần restart camera để áp dụng", Toast.LENGTH_SHORT).show();
        });

        // ── MQTT ───────────────────────────────────────────────────────
        switchMqttEnabled.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(KEY_MQTT_ENABLED, checked).apply());
        etMqttBroker.addTextChangedListener(saveText(KEY_MQTT_BROKER));
        etMqttUsername.addTextChangedListener(saveText(KEY_MQTT_USERNAME));
        etMqttPassword.addTextChangedListener(saveText(KEY_MQTT_PASSWORD));
        etMqttTopic.addTextChangedListener(saveText(KEY_MQTT_TOPIC));
        rgMqttQos.setOnCheckedChangeListener((g, id) -> {
            int q = id == R.id.rb_qos_1 ? 1 : id == R.id.rb_qos_2 ? 2 : 0;
            prefs.edit().putInt(KEY_MQTT_QOS, q).apply();
        });
        seekMqttPublishInterval.setOnSeekBarChangeListener(simpleSeekListener(progress -> {
            tvMqttPublishVal.setText(progress + "ms");
            prefs.edit().putInt(KEY_MQTT_PUBLISH_INTERVAL, progress).apply();
        }));

        // ── Background Service ─────────────────────────────────────────
        switchBackgroundService.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_BACKGROUND_SERVICE, checked).apply();
            if (checked) {
                // Bật service ngay nếu đang bật switch từ settings
                DetectionService.start(this);
                Toast.makeText(this,
                        "▶ Service nền đã bật", Toast.LENGTH_SHORT).show();
            } else {
                DetectionService.stop(this);
                Toast.makeText(this,
                        "⏹ Service nền đã tắt", Toast.LENGTH_SHORT).show();
            }
        });

        // ── Auto-start on Boot ─────────────────────────────────────────
        switchAutoStart.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(BootReceiver.KEY_AUTO_START, checked).apply();

            // Hiện cảnh báo Xiaomi/Huawei khi bật
            tvAutostartNote.setVisibility(checked ? View.VISIBLE : View.GONE);

            if (checked) {
                Toast.makeText(this,
                        "✓ Sẽ tự khởi động khi bật máy", Toast.LENGTH_SHORT).show();
                // Gợi ý thêm: trên MIUI cần vào Security > Autostart
                showMiuiHintIfNeeded();
            }
        });

        // ── Reset ──────────────────────────────────────────────────────
        btnResetDefaults.setOnClickListener(v -> {
            prefs.edit()
                .putFloat  (KEY_MIN_FACE_SIZE,          DEF_MIN_FACE_SIZE)
                .putBoolean(KEY_ACCURATE_MODE,           DEF_ACCURATE_MODE)
                .putString (KEY_RESOLUTION,              DEF_RESOLUTION)
                .putInt    (KEY_FRAME_INTERVAL,          DEF_FRAME_INTERVAL)
                .putBoolean(KEY_MQTT_ENABLED,            DEF_MQTT_ENABLED)
                .putString (KEY_MQTT_BROKER,             DEF_MQTT_BROKER)
                .putString (KEY_MQTT_USERNAME,           DEF_MQTT_USERNAME)
                .putString (KEY_MQTT_PASSWORD,           DEF_MQTT_PASSWORD)
                .putString (KEY_MQTT_TOPIC,              DEF_MQTT_TOPIC)
                .putInt    (KEY_MQTT_QOS,                DEF_MQTT_QOS)
                .putInt    (KEY_MQTT_PUBLISH_INTERVAL,   DEF_MQTT_PUBLISH_INTERVAL)
                .putBoolean(KEY_BACKGROUND_SERVICE,      DEF_BACKGROUND_SERVICE)
                .putBoolean(BootReceiver.KEY_AUTO_START, DEF_AUTO_START)
                .apply();
            loadSettings();
            Toast.makeText(this, "✓ Đã reset về mặc định", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Trên Xiaomi MIUI: gợi ý người dùng mở thêm quyền Autostart.
     * Chỉ hiện Toast thông tin, không tự navigate (tránh crash trên ROM khác).
     */
    private void showMiuiHintIfNeeded() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                || manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            Toast.makeText(this,
                    "📌 Thiết bị " + Build.MANUFACTURER
                    + ": Vào Bảo mật → Autostart → bật cho FaceDetector",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    interface ProgressAction { void onProgress(int progress); }

    private SeekBar.OnSeekBarChangeListener simpleSeekListener(ProgressAction action) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { action.onProgress(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    private TextWatcher saveText(String key) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(key, s.toString()).apply();
            }
        };
    }

    @Override public boolean onSupportNavigateUp() { onBackPressed(); return true; }
}
