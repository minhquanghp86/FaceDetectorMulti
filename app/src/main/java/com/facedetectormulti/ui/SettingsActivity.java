package com.facedetectormulti.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.facedetectormulti.R;

public class SettingsActivity extends AppCompatActivity {

    // Preference keys
    public static final String KEY_MIN_FACE_SIZE = "pref_min_face_size";
    public static final String KEY_MIN_CONFIDENCE = "pref_min_confidence";
    public static final String KEY_ACCURATE_MODE = "pref_accurate_mode";
    public static final String KEY_RESOLUTION = "pref_resolution";
    public static final String KEY_FRAME_INTERVAL = "pref_frame_interval";

    // Default values
    private static final float DEFAULT_MIN_FACE_SIZE = 0.12f;
    private static final float DEFAULT_MIN_CONFIDENCE = 0.5f;
    private static final boolean DEFAULT_ACCURATE_MODE = false;
    private static final String DEFAULT_RESOLUTION = "1280";
    private static final int DEFAULT_FRAME_INTERVAL = 100;

    // UI components
    private SeekBar seekMinFaceSize, seekMinConfidence, seekFrameInterval;
    private TextView tvMinFaceSizeVal, tvMinConfidenceVal, tvFrameIntervalVal;
    private Switch switchPerfMode;
    private RadioGroup rgResolution;
    private RadioButton rbRes640, rbRes1280, rbRes1920;
    private Button btnResetDefaults;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        initViews();        loadSettings();
        setupListeners();
    }

    private void initViews() {
        seekMinFaceSize = findViewById(R.id.seek_min_face_size);
        seekMinConfidence = findViewById(R.id.seek_min_confidence);
        seekFrameInterval = findViewById(R.id.seek_frame_interval);
        
        tvMinFaceSizeVal = findViewById(R.id.tv_min_face_size_val);
        tvMinConfidenceVal = findViewById(R.id.tv_min_confidence_val);
        tvFrameIntervalVal = findViewById(R.id.tv_frame_interval_val);
        
        switchPerfMode = findViewById(R.id.switch_perf_mode);
        rgResolution = findViewById(R.id.rg_resolution);
        rbRes640 = findViewById(R.id.rb_res_640);
        rbRes1280 = findViewById(R.id.rb_res_1280);
        rbRes1920 = findViewById(R.id.rb_res_1920);
        
        btnResetDefaults = findViewById(R.id.btn_reset_defaults);
    }

    private void loadSettings() {
        // Min Face Size: 0.05~0.30 → SeekBar 0~25 (step 0.01)
        float minFaceSize = prefs.getFloat(KEY_MIN_FACE_SIZE, DEFAULT_MIN_FACE_SIZE);
        int faceSizeProgress = Math.round((minFaceSize - 0.05f) * 100);
        seekMinFaceSize.setProgress(faceSizeProgress);
        tvMinFaceSizeVal.setText(String.format("%.2f", minFaceSize));

        // Min Confidence: 0.0~1.0 → SeekBar 0~100
        float minConf = prefs.getFloat(KEY_MIN_CONFIDENCE, DEFAULT_MIN_CONFIDENCE);
        seekMinConfidence.setProgress(Math.round(minConf * 100));
        tvMinConfidenceVal.setText(String.format("%.2f", minConf));

        // Frame Interval: 0~200ms
        int frameInterval = prefs.getInt(KEY_FRAME_INTERVAL, DEFAULT_FRAME_INTERVAL);
        seekFrameInterval.setProgress(frameInterval);
        tvFrameIntervalVal.setText(frameInterval + "ms");

        // Performance Mode
        boolean accurate = prefs.getBoolean(KEY_ACCURATE_MODE, DEFAULT_ACCURATE_MODE);
        switchPerfMode.setChecked(accurate);

        // Resolution
        String resolution = prefs.getString(KEY_RESOLUTION, DEFAULT_RESOLUTION);
        switch (resolution) {
            case "640": rbRes640.setChecked(true); break;
            case "1920": rbRes1920.setChecked(true); break;
            default: rbRes1280.setChecked(true); break;
        }    }

    private void setupListeners() {
        // Min Face Size SeekBar
        seekMinFaceSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.05f + progress * 0.01f;
                tvMinFaceSizeVal.setText(String.format("%.2f", value));
                prefs.edit().putFloat(KEY_MIN_FACE_SIZE, value).apply();
                // Áp dụng ngay cho detector (nếu có callback)
                notifySettingsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Min Confidence SeekBar
        seekMinConfidence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress / 100f;
                tvMinConfidenceVal.setText(String.format("%.2f", value));
                prefs.edit().putFloat(KEY_MIN_CONFIDENCE, value).apply();
                notifySettingsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Frame Interval SeekBar
        seekFrameInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvFrameIntervalVal.setText(progress + "ms");
                prefs.edit().putInt(KEY_FRAME_INTERVAL, progress).apply();
                notifySettingsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Performance Mode Switch
        switchPerfMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_ACCURATE_MODE, isChecked).apply();
            Toast.makeText(this, "⚠ Cần restart camera để áp dụng", Toast.LENGTH_SHORT).show();
        });

        // Resolution RadioGroup
        rgResolution.setOnCheckedChangeListener((group, checkedId) -> {
            String value;
            if (checkedId == R.id.rb_res_640) value = "640";
            else if (checkedId == R.id.rb_res_1920) value = "1920";            else value = "1280";
            prefs.edit().putString(KEY_RESOLUTION, value).apply();
            Toast.makeText(this, "⚠ Cần restart camera để áp dụng", Toast.LENGTH_SHORT).show();
        });

        // Reset Defaults Button
        btnResetDefaults.setOnClickListener(v -> {
            prefs.edit()
                .putFloat(KEY_MIN_FACE_SIZE, DEFAULT_MIN_FACE_SIZE)
                .putFloat(KEY_MIN_CONFIDENCE, DEFAULT_MIN_CONFIDENCE)
                .putBoolean(KEY_ACCURATE_MODE, DEFAULT_ACCURATE_MODE)
                .putString(KEY_RESOLUTION, DEFAULT_RESOLUTION)
                .putInt(KEY_FRAME_INTERVAL, DEFAULT_FRAME_INTERVAL)
                .apply();
            loadSettings();
            notifySettingsChanged();
            Toast.makeText(this, "✓ Đã reset về mặc định", Toast.LENGTH_SHORT).show();
        });
    }

    // ✅ Notify MainActivity để áp dụng settings runtime (cho params có thể update)
    private void notifySettingsChanged() {
        // Dùng LocalBroadcastManager hoặc callback interface để thông báo
        // Đơn giản: dùng EventBus pattern qua SharedPreferences listener
        // Hoặc: MainActivity có thể register SharedPreferences.OnSharedPreferenceChangeListener
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}