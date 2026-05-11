package com.facedetectormulti.detection;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;

@Entity(tableName = "registered_faces")
public class RegisteredFace {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String name;           // Tên người đăng ký
    public String description;    // Ghi chú (optional)
    
    // Face embedding: vector 128 floats (đã convert sang string để lưu)
    @TypeConverters(FloatArrayConverter.class)
    public float[] embedding;
    
    // Avatar thumbnail (base64 string)
    public String avatarBase64;
    
    // ✅ FIX: Dùng long timestamp thay vì Date (Room hỗ trợ native)
    public long registeredAt;     // Timestamp: System.currentTimeMillis()
    
    public int detectionCount;    // Số lần phát hiện (để thống kê)

    public RegisteredFace(String name, float[] embedding, String avatarBase64) {
        this.name = name;
        this.embedding = embedding;
        this.avatarBase64 = avatarBase64;
        this.registeredAt = System.currentTimeMillis();  // ✅ Dùng timestamp
        this.detectionCount = 0;
    }
    
    // Helper: convert timestamp → Date khi cần hiển thị
    public java.util.Date getRegisteredDate() {
        return new java.util.Date(registeredAt);
    }

    // Helper: tính độ tương đồng cosine similarity
    public static float similarity(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA > 0 && normB > 0 ? dot / (float)(Math.sqrt(normA) * Math.sqrt(normB)) : 0f;
    }
}