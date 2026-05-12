package com.facedetectormulti.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;  // ✅ Thêm import
import android.view.View;

import androidx.annotation.Nullable;

import com.facedetectormulti.detection.FaceRecognitionResult;
import com.facedetectormulti.detection.FaceResult;

import java.util.Collections;
import java.util.List;

public class FaceOverlayView extends View {

    private static final String TAG = "FaceOverlayView";  // ✅ Thêm TAG để log

    private static final int[] COLORS = {
        Color.rgb(0, 255, 100),
        Color.rgb(0, 200, 255),
        Color.rgb(255, 120, 0),
        Color.rgb(255, 60, 200),
        Color.rgb(200, 255, 0),
        Color.rgb(180, 100, 255),
    };

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    
    // ✅ Thêm paint cho debug info
    private final Paint debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<? extends FaceResult> faces = Collections.emptyList();
    private long processingTimeMs = 0;
    private boolean mirrorX = false;
    
    // ✅ Đếm frame để log định kỳ (tránh spam)
    private int frameCount = 0;
    private static final int LOG_INTERVAL = 30; // Log mỗi 30 frame

    public FaceOverlayView(Context context) {
        super(context);
        init();
    }

    public FaceOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);
        labelBgPaint.setStyle(Paint.Style.FILL);
        labelBgPaint.setColor(Color.argb(200, 0, 0, 0));
        textPaint.setTextSize(32f);
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);
        centerPaint.setStyle(Paint.Style.FILL);
        statsPaint.setTextSize(36f);
        statsPaint.setColor(Color.WHITE);
        statsPaint.setFakeBoldText(true);
        statsPaint.setShadowLayer(4f, 0, 0, Color.BLACK);
        
        // ✅ Khởi tạo debug paint (chữ nhỏ màu vàng để dễ đọc)
        debugPaint.setTextSize(24f);
        debugPaint.setColor(Color.YELLOW);
        debugPaint.setFakeBoldText(true);
        debugPaint.setShadowLayer(3f, 0, 0, Color.BLACK);
    }

    public void update(List<? extends FaceResult> newFaces, long processingMs) {
        this.faces = newFaces != null ? newFaces : Collections.emptyList();
        this.processingTimeMs = processingMs;
        
        // ✅ Log chi tiết mỗi LOG_INTERVAL frame
        frameCount++;
        if (frameCount % LOG_INTERVAL == 0) {
            logFaceDetails();
        }
        
        postInvalidate();
    }

    // Backward compatible
    public void update(com.facedetectormulti.detection.DetectionResult result) {
        if (result != null) {
            update(result.faces, result.processingMs);
        }
    }

    public void clear() {
        this.faces = Collections.emptyList();
        postInvalidate();
    }

    public void setMirrorX(boolean mirror) {
        this.mirrorX = mirror;
        postInvalidate();
    }

    // ✅ Thêm method log chi tiết
    private void logFaceDetails() {
        Log.d(TAG, "========== FRAME " + frameCount + " ==========");
        Log.d(TAG, "Total faces detected: " + faces.size() + " | Processing: " + processingTimeMs + "ms");
        
        for (int i = 0; i < faces.size(); i++) {
            FaceResult face = faces.get(i);
            if (face instanceof FaceRecognitionResult) {
                FaceRecognitionResult rec = (FaceRecognitionResult) face;
                Log.d(TAG, "  Face #" + i + ": " + rec.getDisplayLabel());
                Log.d(TAG, "    - Confidence: " + String.format("%.4f", rec.confidence));
                Log.d(TAG, "    - Registered: " + rec.isRegistered);
                Log.d(TAG, "    - Person: " + (rec.personName != null ? rec.personName : "NULL"));
                Log.d(TAG, "    - Face ID: " + rec.registeredFaceId);
                Log.d(TAG, "    - Tracking ID: " + rec.trackingId);
                Log.d(TAG, "    - Box: [" + String.format("%.2f, %.2f, %.2f, %.2f", 
                    rec.boxNorm[0], rec.boxNorm[1], rec.boxNorm[2], rec.boxNorm[3]) + "]");
            } else {
                Log.d(TAG, "  Face #" + i + ": Basic FaceResult (no recognition)");
                Log.d(TAG, "    - Tracking ID: " + face.trackingId);
                Log.d(TAG, "    - Box: [" + String.format("%.2f, %.2f, %.2f, %.2f", 
                    face.boxNorm[0], face.boxNorm[1], face.boxNorm[2], face.boxNorm[3]) + "]");
            }
        }
        Log.d(TAG, "==========================================");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (faces.isEmpty()) {
            drawStats(canvas, 0);
            return;
        }

        float vw = getWidth();
        float vh = getHeight();

        for (FaceResult face : faces) {
            drawFace(canvas, face, vw, vh);
        }
        drawStats(canvas, faces.size());
    }

    private void drawFace(Canvas canvas, FaceResult face, float vw, float vh) {
        int colorIndex = Math.abs(face.trackingId) % COLORS.length;
        boolean isRegistered = face instanceof FaceRecognitionResult && 
                              ((FaceRecognitionResult) face).isRegistered;
        
        boxPaint.setColor(isRegistered ? Color.parseColor("#00FF50") : COLORS[colorIndex]);
        centerPaint.setColor(boxPaint.getColor());

        // Access boxNorm as float array: [left, top, right, bottom]
        float left = face.boxNorm[0] * vw;
        float top = face.boxNorm[1] * vh;
        float right = face.boxNorm[2] * vw;
        float bottom = face.boxNorm[3] * vh;

        // Mirror for front camera
        if (mirrorX) {
            float tmpLeft = left;
            left = vw - right;
            right = vw - tmpLeft;
        }

        RectF rect = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(rect, 12f, 12f, boxPaint);

        // Center point + crosshair
        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        canvas.drawCircle(cx, cy, 8f, centerPaint);
        canvas.drawLine(cx - 18, cy, cx + 18, cy, boxPaint);
        canvas.drawLine(cx, cy - 18, cx, cy + 18, boxPaint);

        // Label
        String label = buildLabel(face);
        float textW = textPaint.measureText(label);
        float labelH = 44f;
        float lx = left;
        float ly = top - labelH;
        if (ly < 0) ly = bottom;

        labelBgPaint.setColor(isRegistered ? Color.parseColor("#006622") : Color.parseColor("#444444"));
        canvas.drawRoundRect(new RectF(lx, ly, lx + textW + 16f, ly + labelH), 8f, 8f, labelBgPaint);
        canvas.drawText(label, lx + 8f, ly + labelH - 10f, textPaint);
        
        // ✅ VẼ THÊM DEBUG INFO: similarity score phía dưới bounding box
        if (face instanceof FaceRecognitionResult) {
            FaceRecognitionResult rec = (FaceRecognitionResult) face;
            String debugText;
            if (rec.isRegistered) {
                debugText = String.format("✓ %s (%.1f%%)", rec.personName, rec.confidence * 100);
            } else {
                // Hiển thị best score kể cả khi không match
                debugText = String.format("? best:%.3f", rec.confidence);
            }
            
            float debugY = bottom + 30f;
            if (debugY > vh - 10f) debugY = top - 50f; // Tránh vẽ ra ngoài màn hình
            
            // Vẽ nền debug
            float debugW = debugPaint.measureText(debugText);
            Paint debugBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            debugBg.setColor(Color.argb(200, 0, 0, 0));
            canvas.drawRoundRect(new RectF(lx, debugY - 26f, lx + debugW + 16f, debugY + 8f), 
                6f, 6f, debugBg);
            
            // Vẽ chữ debug
            canvas.drawText(debugText, lx + 8f, debugY, debugPaint);
        }
    }

    private String buildLabel(FaceResult face) {
        if (face instanceof FaceRecognitionResult) {
            FaceRecognitionResult rec = (FaceRecognitionResult) face;
            if (rec.isRegistered) {
                return String.format("✅ %s", rec.personName);
            } else {
                // ✅ Hiển thị confidence score cho face không nhận dạng được
                return String.format("❓ (%.2f)", rec.confidence);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(face.trackingId);
        if (face.smilingProbability >= 0) {
            sb.append(" ").append((int)(face.smilingProbability * 100)).append("%");
        }
        if (Math.abs(face.eulerY) > 20f) {
            sb.append(face.eulerY > 0 ? " ◀" : " ▶");
        }
        return sb.toString();
    }

    // ✅ Sửa drawStats để hiển thị thêm thông tin debug
    private void drawStats(Canvas canvas, int count) {
        // Dòng 1: Stats cơ bản
        String text1 = "Faces: " + count + " | " + processingTimeMs + "ms | Frame: " + frameCount;
        canvas.drawText(text1, 20f, 50f, statsPaint);
        
        // Dòng 2: Recognition status (nếu có)
        if (!faces.isEmpty()) {
            int recognizedCount = 0;
            float bestScore = 0f;
            for (FaceResult face : faces) {
                if (face instanceof FaceRecognitionResult) {
                    FaceRecognitionResult rec = (FaceRecognitionResult) face;
                    if (rec.isRegistered) recognizedCount++;
                    if (rec.confidence > bestScore) bestScore = rec.confidence;
                }
            }
            
            String text2;
            if (recognizedCount > 0) {
                text2 = "✓ Recognized: " + recognizedCount + "/" + count;
            } else {
                text2 = "❌ No match | Best score: " + String.format("%.3f", bestScore);
            }
            
            Paint statsPaint2 = new Paint(statsPaint);
            statsPaint2.setTextSize(28f);
            statsPaint2.setColor(recognizedCount > 0 ? Color.GREEN : Color.YELLOW);
            canvas.drawText(text2, 20f, 90f, statsPaint2);
        }
    }
}
