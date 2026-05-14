package com.facedetectormulti.mqtt;

import android.util.Log;
import com.facedetectormulti.detection.FaceResult;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private long minPublishIntervalMs = 250;   // ← Đã thay đổi thành biến

    private volatile State currentState = State.DISCONNECTED;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private long lastPublishTime = 0;

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public void configure(String brokerUrl, String username, String password,
                          String topic, int qos, long publishIntervalMs) {
        this.brokerUrl = brokerUrl != null ? brokerUrl.trim() : "tcp://localhost:1883";
        this.username = username != null ? username.trim() : "";
        this.password = password != null ? password : "";
        this.topic = topic != null ? topic.trim() : "face/detection";
        this.qos = Math.max(0, Math.min(2, qos));
        this.minPublishIntervalMs = Math.max(100, publishIntervalMs); // tối thiểu 100ms
    }

    // Các phương thức connect, disconnect, doConnect giữ nguyên như cũ...

    public void publishDetection(List<? extends FaceResult> faces, long processingMs) {
        if (currentState != State.CONNECTED) return;
        if (client == null || !client.isConnected()) return;

        long now = System.currentTimeMillis();
        if (now - lastPublishTime < minPublishIntervalMs) return;
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

    // buildPayload, setState, close... giữ nguyên như file cũ của bạn
}