package com.facedetectormulti.mqtt;

import android.util.Log;

import com.facedetectormulti.detection.DetectionMode;
import com.facedetectormulti.detection.FaceResult;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MqttManager v2 – Hỗ trợ chế độ SINGLE / MULTI
 *
 * Thay đổi so với v1:
 *  1. publishDetection() nhận thêm DetectionMode để payload có field "mode".
 *  2. Trong chế độ SINGLE, payload vẫn trả đủ faces[] nhưng field "mode"="single"
 *     và "display_count"=1 (hoặc 0 nếu không có ai).
 *  3. Thêm MQTT Discovery entity mới:
 *     - select "detection_mode" (HASS select entity): HASS → App (command)
 *       Topic command : baseTopic/mode/set   payload: "single" | "multi"
 *       Topic state   : baseTopic/mode/state (App publish khi đổi mode)
 *  4. App subscribe baseTopic/mode/set để nhận lệnh từ HASS.
 *  5. Callback onModeCommandReceived để MainActivity cập nhật UI khi HASS ra lệnh.
 */
public class MqttManager {

    private static final String TAG = "MqttManager";

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    public interface StateListener {
        void onStateChanged(State state, String message);
    }

    /** Callback khi HASS gửi lệnh đổi mode về app */
    public interface ModeCommandListener {
        void onModeCommand(DetectionMode mode);
    }

    private MqttAsyncClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private StateListener stateListener;
    private ModeCommandListener modeCommandListener;

    private String brokerUrl       = "tcp://192.168.10.2:1883";
    private String username        = "";
    private String password        = "";
    private String baseTopic       = "face/detection";
    private int qos                = 0;
    private long minPublishIntervalMs = 250;

    private volatile State currentState = State.DISCONNECTED;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private long lastPublishTime = 0;

    // Mode hiện tại – dùng để publish state topic khi kết nối lại
    private volatile DetectionMode currentMode = DetectionMode.MULTI;

    // ===================== Config / Listeners =====================

    public void setStateListener(StateListener listener) { this.stateListener = listener; }

    public void setModeCommandListener(ModeCommandListener listener) {
        this.modeCommandListener = listener;
    }

    public void configure(String brokerInput, String username, String password,
                          String topic, int qos, long publishIntervalMs) {
        String url = brokerInput != null ? brokerInput.trim() : "";
        if (!url.startsWith("tcp://") && !url.startsWith("ws://") && !url.startsWith("ssl://")) {
            if (url.isEmpty()) url = "tcp://192.168.10.2:1883";
            else if (url.contains(":")) url = "tcp://" + url;
            else url = "tcp://" + url + ":1883";
        }
        this.brokerUrl        = url;
        this.username         = username != null ? username.trim() : "";
        this.password         = password != null ? password : "";
        this.baseTopic        = topic != null ? topic.trim() : "face/detection";
        this.qos              = Math.max(0, Math.min(2, qos));
        this.minPublishIntervalMs = Math.max(100, publishIntervalMs);
        Log.i(TAG, "MQTT Configured → " + this.brokerUrl);
    }

    // ===================== Connect / Disconnect =====================

    public void connect() {
        if (enabled.getAndSet(true)) return;
        setState(State.CONNECTING, "Đang kết nối " + brokerUrl + "...");
        executor.execute(this::doConnect);
    }

    public void disconnect() {
        enabled.set(false);
        executor.execute(() -> {
            try {
                if (client != null && client.isConnected()) {
                    try {
                        MqttMessage off = new MqttMessage("offline".getBytes("UTF-8"));
                        off.setQos(1); off.setRetained(true);
                        client.publish(baseTopic + "/availability", off).waitForCompletion(2000);
                    } catch (Exception ignored) {}
                    client.disconnect().waitForCompletion(3000);
                }
            } catch (Exception e) { Log.w(TAG, "Disconnect error", e); }
            setState(State.DISCONNECTED, "Đã ngắt kết nối");
        });
    }

    private void doConnect() {
        try {
            if (client != null) { try { client.close(); } catch (Exception ignored) {} client = null; }
            String clientId = "FaceDetector-" + System.currentTimeMillis();
            client = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    setState(State.CONNECTED, "Kết nối thành công");
                    Log.i(TAG, "MQTT Connected");
                    executor.execute(() -> {
                        publishDiscovery();
                        subscribeCommandTopics();
                        publishModeState(currentMode); // restore state sau reconnect
                    });
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "Connection lost", cause);
                    if (enabled.get()) setState(State.CONNECTING, "Mất kết nối, đang thử lại...");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleIncoming(topic, new String(message.getPayload()).trim());
                }

                @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            opts.setAutomaticReconnect(true);
            opts.setWill(baseTopic + "/availability", "offline".getBytes(), 1, true);
            if (!username.isEmpty()) {
                opts.setUserName(username);
                opts.setPassword(password.toCharArray());
            }
            client.connect(opts).waitForCompletion(15000);

        } catch (Exception e) {
            Log.e(TAG, "Connect failed", e);
            setState(State.ERROR, "Lỗi: " + e.getMessage());
        }
    }

    // ===================== Subscribe command topics =====================

    private void subscribeCommandTopics() {
        try {
            // HASS → App: lệnh đổi mode
            client.subscribe(baseTopic + "/mode/set", 1);
            Log.i(TAG, "Subscribed: " + baseTopic + "/mode/set");
        } catch (Exception e) {
            Log.w(TAG, "Subscribe failed", e);
        }
    }

    private void handleIncoming(String topic, String payload) {
        String modeSetTopic = baseTopic + "/mode/set";
        if (modeSetTopic.equals(topic)) {
            DetectionMode newMode = DetectionMode.fromMqtt(payload);
            currentMode = newMode;
            Log.i(TAG, "Mode command from HASS: " + newMode);
            if (modeCommandListener != null) {
                modeCommandListener.onModeCommand(newMode);
            }
            // Echo state lại để HASS select cập nhật UI
            publishModeState(newMode);
        }
    }

    // ===================== Publish =====================

    /**
     * Gọi từ MainActivity mỗi khi user đổi mode trên app (nút toggle).
     * Cập nhật state topic để HASS select entity đồng bộ.
     */
    public void publishModeState(DetectionMode mode) {
        currentMode = mode;
        if (currentState != State.CONNECTED || client == null) return;
        executor.execute(() -> {
            try {
                MqttMessage msg = new MqttMessage(mode.mqttValue().getBytes("UTF-8"));
                msg.setQos(1); msg.setRetained(true);
                client.publish(baseTopic + "/mode/state", msg);
            } catch (Exception e) { Log.w(TAG, "Publish mode state failed", e); }
        });
    }

    public void publishDetection(List<? extends FaceResult> faces,
                                 long processingMs,
                                 DetectionMode mode) {
        if (currentState != State.CONNECTED || client == null || !client.isConnected()) return;
        long now = System.currentTimeMillis();
        if (now - lastPublishTime < minPublishIntervalMs) return;
        lastPublishTime = now;

        final String payload = buildPayload(faces, processingMs, now, mode);
        executor.execute(() -> {
            try {
                MqttMessage msg = new MqttMessage(payload.getBytes("UTF-8"));
                msg.setQos(qos); msg.setRetained(false);
                client.publish(baseTopic, msg);
            } catch (Exception e) { Log.w(TAG, "Publish failed", e); }
        });
    }

    /** Overload tương thích ngược (dùng currentMode) */
    public void publishDetection(List<? extends FaceResult> faces, long processingMs) {
        publishDetection(faces, processingMs, currentMode);
    }

    private String buildPayload(List<? extends FaceResult> faces,
                                long processingMs, long now, DetectionMode mode) {
        int displayCount = (mode == DetectionMode.SINGLE)
                ? (faces.isEmpty() ? 0 : 1)
                : faces.size();

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"ts\":").append(now)
          .append(",\"count\":").append(faces.size())
          .append(",\"display_count\":").append(displayCount)
          .append(",\"mode\":\"").append(mode.mqttValue()).append("\"")
          .append(",\"ms\":").append(processingMs)
          .append(",\"faces\":[");

        for (int i = 0; i < faces.size(); i++) {
            if (i > 0) sb.append(",");
            FaceResult f = faces.get(i);
            sb.append("{\"id\":").append(f.trackingId)
              .append(",\"cx\":").append(String.format(Locale.US, "%.3f", f.centerX()))
              .append(",\"cy\":").append(String.format(Locale.US, "%.3f", f.centerY()))
              .append(",\"w\":").append(String.format(Locale.US, "%.3f", f.width()))
              .append(",\"h\":").append(String.format(Locale.US, "%.3f", f.height()))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ===================== MQTT Discovery =====================

    private void publishDiscovery() {
        final String deviceId = "face_detector_android";
        final String deviceJson = "{"
                + "\"identifiers\":[\"" + deviceId + "\"],"
                + "\"name\":\"Face Detector Android\","
                + "\"model\":\"FaceDetectorMulti\","
                + "\"manufacturer\":\"NQ SmartHome\""
                + "}";
        final String availJson = ","
                + "\"availability_topic\":\"" + baseTopic + "/availability\","
                + "\"payload_available\":\"online\","
                + "\"payload_not_available\":\"offline\"";

        try {
            // 1. Binary Sensor: Person Detect
            publishEntity("binary_sensor", "person_detected",
                    "{"
                    + "\"device\":" + deviceJson + ","
                    + "\"name\":\"Person Detect\","
                    + "\"device_class\":\"occupancy\","
                    + "\"state_topic\":\"" + baseTopic + "\","
                    + "\"value_template\":\"{% if value_json.count > 0 %}ON{% else %}OFF{% endif %}\","
                    + "\"expire_after\":10,"
                    + "\"off_delay\":5,"
                    + "\"unique_id\":\"" + deviceId + "_person_detected\","
                    + "\"icon\":\"mdi:account-multiple\""
                    + availJson
                    + "}");

            // 2. Sensor: Số người phát hiện (total count)
            publishEntity("sensor", "person_count",
                    "{"
                    + "\"device\":" + deviceJson + ","
                    + "\"name\":\"Số người phát hiện\","
                    + "\"state_topic\":\"" + baseTopic + "\","
                    + "\"value_template\":\"{{ value_json.count }}\","
                    + "\"expire_after\":5,"
                    + "\"json_attributes_topic\":\"" + baseTopic + "\","
                    + "\"json_attributes_template\":\"{{ value_json | tojson }}\","
                    + "\"unique_id\":\"" + deviceId + "_person_count\","
                    + "\"unit_of_measurement\":\"người\","
                    + "\"icon\":\"mdi:account-multiple\""
                    + availJson
                    + "}");

            // 3. Sensor: Face Center X
            publishEntity("sensor", "face_center_x",
                    "{"
                    + "\"device\":" + deviceJson + ","
                    + "\"name\":\"Face Center X\","
                    + "\"state_topic\":\"" + baseTopic + "\","
                    + "\"value_template\":\"{{ value_json.faces[0].cx | default(0) }}\","
                    + "\"expire_after\":5,"
                    + "\"unique_id\":\"" + deviceId + "_face_center_x\","
                    + "\"icon\":\"mdi:axis-x-arrow\""
                    + availJson
                    + "}");

            // 4. Sensor: Face Center Y
            publishEntity("sensor", "face_center_y",
                    "{"
                    + "\"device\":" + deviceJson + ","
                    + "\"name\":\"Face Center Y\","
                    + "\"state_topic\":\"" + baseTopic + "\","
                    + "\"value_template\":\"{{ value_json.faces[0].cy | default(0) }}\","
                    + "\"expire_after\":5,"
                    + "\"unique_id\":\"" + deviceId + "_face_center_y\","
                    + "\"icon\":\"mdi:axis-y-arrow\""
                    + availJson
                    + "}");

            // 5. NEW – Select entity: Detection Mode (HASS điều khiển app)
            //    - command_topic : baseTopic/mode/set   (HASS → App)
            //    - state_topic   : baseTopic/mode/state (App → HASS)
            //    - options       : ["multi", "single"]
            publishEntity("select", "detection_mode",
                    "{"
                    + "\"device\":" + deviceJson + ","
                    + "\"name\":\"Detection Mode\","
                    + "\"state_topic\":\"" + baseTopic + "/mode/state\","
                    + "\"command_topic\":\"" + baseTopic + "/mode/set\","
                    + "\"options\":[\"multi\",\"single\"],"
                    + "\"unique_id\":\"" + deviceId + "_detection_mode\","
                    + "\"icon\":\"mdi:account-eye\","
                    + "\"retain\":true"
                    + availJson
                    + "}");

            // Publish availability = online
            MqttMessage avail = new MqttMessage("online".getBytes("UTF-8"));
            avail.setQos(1); avail.setRetained(true);
            client.publish(baseTopic + "/availability", avail);

            Log.i(TAG, "✅ Discovery published: binary_sensor x1, sensor x3, select x1");

        } catch (Exception e) {
            Log.e(TAG, "Discovery failed", e);
        }
    }

    private void publishEntity(String domain, String objectId, String payload) throws Exception {
        String topic = "homeassistant/" + domain + "/" + objectId + "/config";
        MqttMessage msg = new MqttMessage(payload.getBytes("UTF-8"));
        msg.setQos(1); msg.setRetained(true);
        client.publish(topic, msg);
    }

    // ===================== State =====================

    private void setState(State state, String msg) {
        currentState = state;
        Log.d(TAG, "State: " + state + " | " + msg);
        if (stateListener != null) stateListener.onStateChanged(state, msg);
    }

    public State getState()        { return currentState; }
    public boolean isConnected()   { return currentState == State.CONNECTED; }

    public void close() {
        disconnect();
        executor.shutdown();
    }
}
