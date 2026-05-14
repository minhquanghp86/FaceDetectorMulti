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
 * MqttManager - Kết nối và publish kết quả phát hiện khuôn mặt
 * Hỗ trợ EMQX + MQTT Discovery cho Home Assistant
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

    // Config
    private String brokerUrl = "tcp://192.168.1.100:1883";
    private String username = "";
    private String password = "";
    private String topic = "face/detection";
    private int qos = 0;
    private long minPublishIntervalMs = 250;

    private volatile State currentState = State.DISCONNECTED;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private long lastPublishTime = 0;

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    /**
     * Cấu hình MQTT - Tự động sửa URL cho EMQX
     */
    public void configure(String brokerInput, String username, String password,
                          String topic, int qos, long publishIntervalMs) {
        
        String url = (brokerInput != null ? brokerInput.trim() : "");
        
        // Tự động thêm scheme nếu thiếu
        if (!url.startsWith("tcp://") && !url.startsWith("ws://") && !url.startsWith("ssl://")) {
            if (url.isEmpty()) {
                url = "tcp://192.168.1.100:1883";
            } else if (url.contains(":")) {
                url = "tcp://" + url;
            } else {
                url = "tcp://" + url + ":1883";
            }
        }

        this.brokerUrl = url;
        this.username = username != null ? username.trim() : "";
        this.password = password != null ? password : "";
        this.topic = topic != null ? topic.trim() : "face/detection";
        this.qos = Math.max(0, Math.min(2, qos));
        this.minPublishIntervalMs = Math.max(100, publishIntervalMs);

        Log.i(TAG, "MQTT Configured → " + this.brokerUrl 
                   + " | QoS=" + this.qos 
                   + " | Interval=" + this.minPublishIntervalMs + "ms");
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
                    setState(State.CONNECTED, "Kết nối thành công " + serverURI);
                    Log.i(TAG, "MQTT Connected: " + serverURI);
                    
                    // Publish Discovery cho Home Assistant
                    executor.execute(MqttManager.this::publishDiscovery);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "Connection lost", cause);
                    if (enabled.get()) {
                        setState(State.CONNECTING, "Mất kết nối, đang thử lại...");
                    } else {
                        setState(State.DISCONNECTED, "Ngắt kết nối");
                    }
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

            Log.d(TAG, "Attempting to connect to: " + brokerUrl);
            client.connect(opts).waitForCompletion(15000);

        } catch (Exception e) {
            Log.e(TAG, "Connect failed", e);
            setState(State.ERROR, "Lỗi kết nối: " + e.getMessage());
        }
    }

    /**
     * Publish MQTT Discovery để tạo thiết bị trong Home Assistant
     */
    private void publishDiscovery() {
        try {
            String deviceName = android.os.Build.MODEL;
            String uniqueId = "face_detector_" + System.currentTimeMillis();

            String discoveryTopic = "homeassistant/sensor/face_detector_count/config";

            String discoveryPayload = "{\n" +
                    "  \"device\": {\n" +
                    "    \"identifiers\": [\"face_detector_android\"],\n" +
                    "    \"name\": \"Face Detector\",\n" +
                    "    \"manufacturer\": \"FaceDetectorMulti\",\n" +
                    "    \"model\": \"" + deviceName + "\",\n" +
                    "    \"sw_version\": \"1.0\"\n" +
                    "  },\n" +
                    "  \"name\": \"Số người phát hiện\",\n" +
                    "  \"state_topic\": \"" + topic + "\",\n" +
                    "  \"value_template\": \"{{ value_json.count }}\",\n" +
                    "  \"unique_id\": \"" + uniqueId + "\",\n" +
                    "  \"icon\": \"mdi:account-multiple\",\n" +
                    "  \"unit_of_measurement\": \"người\",\n" +
                    "  \"availability_topic\": \"" + topic + "/availability\",\n" +
                    "  \"payload_available\": \"online\",\n" +
                    "  \"payload_not_available\": \"offline\"\n" +
                    "}";

            MqttMessage msg = new MqttMessage(discoveryPayload.getBytes("UTF-8"));
            msg.setQos(1);
            msg.setRetained(true);
            
            client.publish(discoveryTopic, msg);
            Log.i(TAG, "✅ Đã publish MQTT Discovery cho Home Assistant");

        } catch (Exception e) {
            Log.e(TAG, "Publish discovery failed", e);
        }
    }

    public void publishDetection(List<? extends FaceResult> faces, long processingMs) {
        if (currentState != State.CONNECTED || client == null || !client.isConnected()) return;

        long now = System.currentTimeMillis();
        if (now - lastPublishTime < minPublishIntervalMs) return;
        lastPublishTime = now;

        final String payload = buildPayload(faces, processingMs, now);
        final int pubQos = this.qos;
        final String pubTopic = this.topic;

        executor.execute(() -> {
            try {
                MqttMessage msg = new MqttMessage(payload.getBytes("UTF-8"));
                msg.setQos(pubQos);
                msg.setRetained(false);
                client.publish(pubTopic, msg);
            } catch (Exception e) {
                Log.w(TAG, "Publish failed", e);
            }
        });
    }

    private String buildPayload(List<? extends FaceResult> faces, long processingMs, long now) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ts\":").append(now)
          .append(",\"count\":").append(faces.size())
          .append(",\"ms\":").append(processingMs)
          .append(",\"faces\":[");

        for (int i = 0; i < faces.size(); i++) {
            if (i > 0) sb.append(",");
            FaceResult f = faces.get(i);
            sb.append("{\"id\":").append(f.trackingId)
              .append(",\"left\":").append(String.format("%.3f", f.boxNorm[0]))
              .append(",\"top\":").append(String.format("%.3f", f.boxNorm[1]))
              .append(",\"right\":").append(String.format("%.3f", f.boxNorm[2]))
              .append(",\"bottom\":").append(String.format("%.3f", f.boxNorm[3]))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void setState(State state, String msg) {
        currentState = state;
        Log.d(TAG, "State: " + state + " | " + msg);
        if (stateListener != null) {
            stateListener.onStateChanged(state, msg);
        }
    }

    public State getState() { return currentState; }
    public boolean isConnected() { return currentState == State.CONNECTED; }

    public void close() {
        disconnect();
        executor.shutdown();
    }
}