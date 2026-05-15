package com.facedetectormulti.detection;

/**
 * Chế độ phát hiện khuôn mặt.
 *
 * SINGLE  – Chỉ hiển thị 1 khung viền cho khuôn mặt được phát hiện đầu tiên.
 *           MQTT payload vẫn mang đủ dữ liệu nhưng field "mode" = "single".
 *
 * MULTI   – Hiển thị tất cả khuôn mặt phát hiện được (hành vi gốc).
 *           MQTT payload field "mode" = "multi".
 */
public enum DetectionMode {
    SINGLE,
    MULTI;

    public String mqttValue() {
        return name().toLowerCase();
    }

    public static DetectionMode fromMqtt(String value) {
        if (value == null) return MULTI;
        return "single".equalsIgnoreCase(value.trim()) ? SINGLE : MULTI;
    }
}
