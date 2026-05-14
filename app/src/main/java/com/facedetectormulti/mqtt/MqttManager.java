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
 * MqttManager - kết nối MQTT và publish kết quả phát hiện khuôn mặt.
 *
 * Payload JSON mẫu:
 * {
 *   "ts": 1715671234567,
 *   "count": 2,
 *   "ms": 45,
 *   "faces": [
 *     {"id": 1, "left": 0.12, "top": 0.20, "right": 0.38, "bottom": 0.68,
 *      "cx": 0.25, "cy": 0.44, "w": 0.26, "h": 0.48}
 *   ]
 * }
 */
public class MqttManager {

    private static final String TAG = "MqttManager";

    /** Khoảng thời gian tối thiểu giữa 2 lần publish (ms). */
    private static final long MIN_PUBLISH_INTERVAL_MS = 250; // ~4 msgs/sec max

    // ── Trạng thái kết nối ───────────────────────────────────────────────

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    public interface StateListener {
        void onStateChanged(State state, String message);
    }

    // ─────────────────────────────────────────────────────────────────────

    private MqttAsyncClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private StateListener stateListener;

    // Config
    private String brokerUrl   = "tcp://192.168.1.100:1883";
    private String username    = "";
    private String password    = "";
    private String topic       = "face/detection";
    private int    qos         = 0;

    private volatile State  currentState = State.DISCONNECTED;
    private final AtomicBoolean enabled  = new AtomicBoolean(false);
    private long lastPublishTime = 0;

    // ─────────────────────────────────────────────────────────────────────

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public void configure(String brokerUrl, String username, String password,
                          String topic, int qos) {
        this.brokerUrl = brokerUrl != null ? brokerUrl.trim() : "tcp://localhost:1883";
        this.username  = username  != null ? username.trim()  : "";
        this.password  = password  != null ? password         : "";
        this.topic     = topic     != null ? topic.trim()     : "face/detection";
        this.qos       = Math.max(0, Math.min(2, qos));
    }

    // ── Connect / Disconnect ─────────────────────────────────────────────

    public void connect() {
        if (enabled.getAndSet(true)) return; // Already connecting/connected
        setState(State.CONNECTING, "Đang kết nối " + brokerUrl + "...");
        executor.execute(this::doConnect);
    }

    public void disconnect() {
        enabled.set(false);
        executor.execute(() -> {
            try {
                if (client != null) {
                    if (client.isConnected()) client.disconnect().waitForCompletion(3000);
                    client.close();
                    client = null;
                }
            } catch (Exception e) {
                Log.w(TAG, "Disconnect: " + e.getMessage());
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

            String clientId = "FaceDetector-" + (System.currentTimeMillis() % 100000);
            client = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    setState(State.CONNECTED,
                        (reconnect ? "Kết nối lại " : "Kết nối ") + serverURI);
                    Log.d(TAG, "connectComplete reconnect=" + reconnect);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "connectionLost: " + (cause != null ? cause.getMessage() : "?"));
                    if (enabled.get()) {
                        setState(State.CONNECTING, "Mất kết nối, đang thử lại...");
                    } else {
                        setState(State.DISCONNECTED, "Ngắt kết nối");
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {}

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
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

            client.connect(opts).waitForCompletion(12000);
            // setState sẽ được gọi qua callback connectComplete

        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
            setState(State.ERROR, "Lỗi: " + e.getMessage());
        }
    }

    // ── Publish ──────────────────────────────────────────────────────────

    /**
     * Publish kết quả phát hiện mặt lên MQTT.
     * Throttled theo MIN_PUBLISH_INTERVAL_MS.
     */
    public void publishDetection(List<? extends FaceResult> faces, long processingMs) {
        if (currentState != State.CONNECTED) return;
        if (client == null || !client.isConnected()) return;

        long now = System.currentTimeMillis();
        if (now - lastPublishTime < MIN_PUBLISH_INTERVAL_MS) return;
        lastPublishTime = now;

        final String payload = buildPayload(faces, processingMs, now);
        final int pubQos = this.qos;
        final String pubTopic = this.topic;

        executor.execute(() -> {
            try {
                if (client != null && client.isConnected()) {
                    MqttMessage msg = new MqttMessage(payload.getBytes("UTF-8"));
                    msg.setQos(pubQos);
                    msg.setRetained(false);
                    client.publish(pubTopic, msg);
                }
            } catch (Exception e) {
                Log.w(TAG, "Publish failed: " + e.getMessage());
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
            FaceResult f = faces.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"id\":").append(f.trackingId).append(",")
              .append("\"left\":").append(fmt(f.boxNorm[0])).append(",")
              .append("\"top\":").append(fmt(f.boxNorm[1])).append(",")
              .append("\"right\":").append(fmt(f.boxNorm[2])).append(",")
              .append("\"bottom\":").append(fmt(f.boxNorm[3])).append(",")
              .append("\"cx\":").append(fmt(f.centerX())).append(",")
              .append("\"cy\":").append(fmt(f.centerY())).append(",")
              .append("\"w\":").append(fmt(f.width())).append(",")
              .append("\"h\":").append(fmt(f.height()))
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String fmt(float v) {
        return String.format("%.3f", v);
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public State getState() { return currentState; }
    public boolean isConnected() { return currentState == State.CONNECTED; }
    public String getBrokerUrl() { return brokerUrl; }
    public String getTopic() { return topic; }

    // ── Internal ─────────────────────────────────────────────────────────

    private void setState(State state, String msg) {
        currentState = state;
        Log.d(TAG, "State: " + state + " | " + msg);
        if (stateListener != null) {
            stateListener.onStateChanged(state, msg);
        }
    }

    public void close() {
        disconnect();
        executor.shutdown();
    }
}
