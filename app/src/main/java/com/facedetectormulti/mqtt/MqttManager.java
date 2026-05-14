package com.facedetectormulti.mqtt;

import android.util.Log;
import com.facedetectormulti.detection.FaceResult;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MqttManager - Tối ưu hoàn toàn cho Home Assistant (2026)
 */
public class MqttManager {

    private static final String TAG = "MqttManager";

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    public interface StateListener {
        void onStateChanged(State state, String message);
    }

    private MqttAsyncClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private StateListener stateListener;

    private String brokerUrl = "tcp://192.168.1.100:1883";
    private String username = "";
    private String password = "";
    private String baseTopic = "face/detection";
    private int qos = 0;
    private long minPublishIntervalMs = 250;

    private volatile State currentState = State.DISCONNECTED;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private long lastPublishTime = 0;

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public void configure(String brokerInput, String username, String password,
                          String topic, int qos, long publishIntervalMs) {
        
        String url = (brokerInput != null ? brokerInput.trim() : "");
        if (!url.startsWith("tcp://") && !url.startsWith("ws://") && !url.startsWith("ssl://")) {
            if (url.isEmpty()) url = "tcp://192.168.1.100:1883";
            else if (url.contains(":")) url = "tcp://" + url;
            else url = "tcp://" + url + ":1883";
        }

        this.brokerUrl = url;
        this.username = username != null ? username.trim() : "";
        this.password = password != null ? password : "";
        this.baseTopic = topic != null ? topic.trim() : "face/detection";
        this.qos = Math.max(0, Math.min(2, qos));
        this.minPublishIntervalMs = Math.max(100, publishIntervalMs);

        Log.i(TAG, "MQTT Configured → " + this.brokerUrl);
    }

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
                    client.disconnect().waitForCompletion(3000);
                }
            } catch (Exception e) {
                Log.w(TAG, "Disconnect error", e);
            }
            setState(State.DISCONNECTED, "Đã ngắt kết nối");
        });
    }

    private void doConnect() {
        try {
            if (client != null) {
                try { client.close(); } catch (Exception ignored) {}
                client = null;
            }

            String clientId = "FaceDetector-" + System.currentTimeMillis();
            client = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    setState(State.CONNECTED, "Kết nối thành công");
                    Log.i(TAG, "MQTT Connected");
                    executor.execute(MqttManager.this::publishDiscovery);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "Connection lost", cause);
                    if (enabled.get()) setState(State.CONNECTING, "Mất kết nối, đang thử lại...");
                }

                @Override public void messageArrived(String topic, MqttMessage message) {}
                @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            opts.setAutomaticReconnect(true);

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

    private void publishDiscovery() {
        String deviceId = "face_detector_android";

        try {
            // Binary Sensor: Có người
            publishEntity("binary_sensor", "face_detected",
                "{\"device\":{\"identifiers\":[\""+deviceId+"\"]},\"name\":\"Có người\",\"state_topic\":\""+baseTopic+"\",\"value_template\":\"{% if value_json.count > 0 %}on{% else %}off{% endif %}\",\"unique_id\":\"face_detected\",\"icon\":\"mdi:account-multiple\",\"availability_topic\":\""+baseTopic+"/availability\",\"payload_available\":\"online\",\"payload_not_available\":\"offline\"}");

            // Sensor: Số người
            publishEntity("sensor", "person_count",
                "{\"device\":{\"identifiers\":[\""+deviceId+"\"]},\"name\":\"Số người phát hiện\",\"state_topic\":\""+baseTopic+"\",\"value_template\":\"{{ value_json.count }}\",\"unique_id\":\"person_count\",\"unit_of_measurement\":\"người\",\"icon\":\"mdi:account-multiple\",\"availability_topic\":\""+baseTopic+"/availability\",\"payload_available\":\"online\",\"payload_not_available\":\"offline\"}");

            // Sensor: Chi tiết khuôn mặt
            publishEntity("sensor", "face_details",
                "{\"device\":{\"identifiers\":[\""+deviceId+"\"]},\"name\":\"Chi tiết khuôn mặt\",\"state_topic\":\""+baseTopic+"\",\"value_template\":\"{{ value_json.count }}\",\"json_attributes_template\":\"{{ value_json | tojson }}\",\"unique_id\":\"face_details\",\"icon\":\"mdi:face-recognition\",\"availability_topic\":\""+baseTopic+"/availability\",\"payload_available\":\"online\",\"payload_not_available\":\"offline\"}");

            // Publish availability
            MqttMessage availMsg = new MqttMessage("online".getBytes());
            availMsg.setQos(1);
            availMsg.setRetained(true);
            client.publish(baseTopic + "/availability", availMsg);

            Log.i(TAG, "✅ Đã publish đầy đủ Discovery + Availability");

        } catch (Exception e) {
            Log.e(TAG, "Discovery failed", e);
        }
    }

    private void publishEntity(String domain, String objectId, String payload) throws Exception {
        String topic = "homeassistant/" + domain + "/" + objectId + "/config";
        MqttMessage msg = new MqttMessage(payload.getBytes("UTF-8"));
        msg.setQos(1);
        msg.setRetained(true);
        client.publish(topic, msg);
    }

    public void publishDetection(List<? extends FaceResult> faces, long processingMs) {
        if (currentState != State.CONNECTED || client == null || !client.isConnected()) return;

        long now = System.currentTimeMillis();
        if (now - lastPublishTime < minPublishIntervalMs) return;
        lastPublishTime = now;

        final String payload = buildPayload(faces, processingMs, now);

        executor.execute(() -> {
            try {
                MqttMessage msg = new MqttMessage(payload.getBytes("UTF-8"));
                msg.setQos(qos);
                msg.setRetained(false);
                client.publish(baseTopic, msg);
                Log.d(TAG, "Published count = " + faces.size());
            } catch (Exception e) {
                Log.w(TAG, "Publish failed", e);
            }
        });
    }

    private String buildPayload(List<? extends FaceResult> faces, long processingMs, long now) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"ts\":").append(now)
          .append(",\"count\":").append(faces.size())
          .append(",\"ms\":").append(processingMs)
          .append(",\"faces\":[");

        for (int i = 0; i < faces.size(); i++) {
            if (i > 0) sb.append(",");
            FaceResult f = faces.get(i);
            sb.append("{\"id\":").append(f.trackingId)
              .append(",\"cx\":").append(String.format("%.3f", f.centerX()))  // Dùng dấu chấm
              .append(",\"cy\":").append(String.format("%.3f", f.centerY()))
              .append(",\"w\":").append(String.format("%.3f", f.width()))
              .append(",\"h\":").append(String.format("%.3f", f.height()))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void setState(State state, String msg) {
        currentState = state;
        Log.d(TAG, "State: " + state + " | " + msg);
        if (stateListener != null) stateListener.onStateChanged(state, msg);
    }

    public State getState() { return currentState; }
    public boolean isConnected() { return currentState == State.CONNECTED; }

    public void close() {
        disconnect();
        executor.shutdown();
    }
}