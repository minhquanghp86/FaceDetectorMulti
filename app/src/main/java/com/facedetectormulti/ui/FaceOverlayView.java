package com.facedetectormulti.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import com.facedetectormulti.detection.FaceRecognitionResult;
import com.facedetectormulti.detection.FaceResult;

import java.util.Collections;
import java.util.List;

public class FaceOverlayView extends View {

    private static final String TAG = "FaceOverlayView";

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
    private final Paint debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<? extends FaceResult> faces = Collections.emptyList();
    private long processingTimeMs = 0;
    private boolean mirrorX = false;
    private int frameCount = 0;
    private static final int LOG_INTERVAL = 30;

    // Cache số lượng face trong DB để tránh gọi DB liên tục
    private String dbInfoCache = "DB: loading...";
    private int dbInfoFrameCounter = 0;
    private static final int DB_INFO_REFRESH_INTERVAL = 60; // Refresh mỗi 60 frame
    private boolean dbInfoLoaded = false; // Đánh dấu đã load DB info chưa

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

        debugPaint.setTextSize(24f);
        debugPaint.setColor(Color.YELLOW);
        debugPaint.setFakeBoldText(true);
        debugPaint.setShadowLayer(3f, 0, 0, Color.BLACK);
        
        // Load DB info ngay khi khởi tạo
        refreshDbInfo();
    }

    public void update(List<? extends FaceResult> newFaces, long processingMs) {
        this.faces = newFaces != null ? newFaces : Collections.emptyList();
        this.processingTimeMs = processingMs;

        frameCount++;
        
        // Log định kỳ
        if (frameCount % LOG_INTERVAL == 0) {
            logFaceDetails();
        }
        
        // Refresh DB info định kỳ (không gọi trong onDraw)
        dbInfoFrameCounter++;
        if (dbInfoFrameCounter >= DB_INFO_REFRESH_INTERVAL) {
            dbInfoFrameCounter = 0;
            refreshDbInfo();
        }
        
        postInvalidate();
    }

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

    // ✅ Lấy DB info an toàn - CHẠY TRÊN BACKGROUND THREAD
    private void refreshDbInfo() {
        try {
            final Context ctx = getContext();
            if (ctx != null) {
                // Chạy trên background thread
                new Thread(() -> {
                    try {
                        int dbCount = com.facedetectormulti.detection.FaceDatabase
                            .getInstance(ctx).faceDao().getCount();
                        dbInfoCache = "DB: " + dbCount + " registered faces";
                        dbInfoLoaded = true;
                        Log.d(TAG, "DB count refreshed: " + dbCount);
                    } catch (Exception e) {
                        dbInfoCache = "DB: error - " + e.getMessage();
                        Log.e(TAG, "Error getting DB count: " + e.getMessage());
                    }
                    // Cập nhật UI sau khi có kết quả
                    postInvalidate();
                }).start();
            }
        } catch (Exception e) {
            dbInfoCache = "DB: init error - " + e.getMessage();
            Log.e(TAG, "Error starting DB thread: " + e.getMessage());
        }
    }

    private void logFaceDetails() {
        Log.d(TAG, "========== FRAME " + frameCount + " ==========");
        Log.d(TAG, "Total faces: " + faces.size() + " | Time: " + processingTimeMs + "ms");
        Log.d(TAG, "DB Status: " + dbInfoCache);

        boolean hasRecognition = false;
        for (FaceResult face : faces) {
            if (face instanceof FaceRecognitionResult) {
                hasRecognition = true;
                FaceRecognitionResult rec = (FaceRecognitionResult) face;
                Log.d(TAG, "  Face #" + face.trackingId + ": " + rec.getDisplayLabel());
                Log.d(TAG, "    Confidence: " + String.format("%.4f", rec.confidence));
                Log.d(TAG, "    IsRegistered: " + rec.isRegistered);
                Log.d(TAG, "    PersonName: " + (rec.personName != null ? rec.personName : "NULL"));
            } else {
                Log.d(TAG, "  Face #" + face.trackingId + ": FaceResult (NO RECOGNITION)");
            }
        }
        if (!hasRecognition) {
            Log.w(TAG, "⚠ NO FaceRecognitionResult found! Recognition might be DISABLED!");
        }
        Log.d(TAG, "==========================================");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float vw = getWidth();
        float vh = getHeight();

        // Vẽ debug center
        drawDebugCenter(canvas, vw, vh);

        if (faces.isEmpty()) {
            drawStats(canvas, 0);
            return;
        }

        for (FaceResult face : faces) {
            drawFace(canvas, face, vw, vh);
        }
        drawStats(canvas, faces.size());
    }

    private void drawDebugCenter(Canvas canvas, float vw, float vh) {
        float centerX = vw / 2f;
        float centerY = vh / 2f;

        boolean hasRecognition = false;
        String status = "RECOGNITION: OFF";
        int statusColor = Color.RED;

        if (!faces.isEmpty()) {
            for (FaceResult face : faces) {
                if (face instanceof FaceRecognitionResult) {
                    hasRecognition = true;
                    FaceRecognitionResult rec = (FaceRecognitionResult) face;
                    if (rec.isRegistered) {
                        status = "✓ MATCHED: " + rec.personName + " (" + 
                                String.format("%.1f", rec.confidence * 100) + "%)";
                        statusColor = Color.GREEN;
                    } else {
                        status = "✗ NO MATCH (best: " + 
                                String.format("%.3f", rec.confidence) + ")";
                        statusColor = Color.YELLOW;
                    }
                    break;
                }
            }
        }

        if (!hasRecognition && !faces.isEmpty()) {
            status = "RECOGNITION: OFF (FaceResult only)";
            statusColor = Color.RED;
        } else if (faces.isEmpty()) {
            status = "NO FACE DETECTED";
            statusColor = Color.WHITE;
        }

        // Vẽ nền cho status
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(180, 0, 0, 0));
        float textW = debugPaint.measureText(status);
        canvas.drawRoundRect(
            new RectF(centerX - textW / 2f - 20f, centerY - 40f, 
                      centerX + textW / 2f + 20f, centerY + 10f),
            10f, 10f, bgPaint);

        // Vẽ chữ status
        debugPaint.setColor(statusColor);
        canvas.drawText(status, centerX - textW / 2f, centerY, debugPaint);

        // Vẽ DB info (dùng cache - không gọi DB trực tiếp)
        Paint smallPaint = new Paint(debugPaint);
        smallPaint.setTextSize(18f);
        smallPaint.setColor(Color.CYAN);
        float dbTextW = smallPaint.measureText(dbInfoCache);
        
        // Vẽ nền cho DB info
        Paint dbBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dbBgPaint.setColor(Color.argb(150, 0, 0, 0));
        canvas.drawRoundRect(
            new RectF(centerX - dbTextW / 2f - 12f, centerY + 18f,
                      centerX + dbTextW / 2f + 12f, centerY + 42f),
            6f, 6f, dbBgPaint);
        
        canvas.drawText(dbInfoCache, centerX - dbTextW / 2f, centerY + 36f, smallPaint);
    }

    private void drawFace(Canvas canvas, FaceResult face, float vw, float vh) {
        int colorIndex = Math.abs(face.trackingId) % COLORS.length;
        boolean isRegistered = face instanceof FaceRecognitionResult &&
                ((FaceRecognitionResult) face).isRegistered;

        boxPaint.setColor(isRegistered ? Color.parseColor("#00FF50") : COLORS[colorIndex]);
        centerPaint.setColor(boxPaint.getColor());

        float left = face.boxNorm[0] * vw;
        float top = face.boxNorm[1] * vh;
        float right = face.boxNorm[2] * vw;
        float bottom = face.boxNorm[3] * vh;

        if (mirrorX) {
            float tmpLeft = left;
            left = vw - right;
            right = vw - tmpLeft;
        }

        RectF rect = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(rect, 12f, 12f, boxPaint);

        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        canvas.drawCircle(cx, cy, 8f, centerPaint);
        canvas.drawLine(cx - 18, cy, cx + 18, cy, boxPaint);
        canvas.drawLine(cx, cy - 18, cx, cy + 18, boxPaint);

        String label = buildLabel(face);
        float textW = textPaint.measureText(label);
        float labelH = 44f;
        float lx = left;
        float ly = top - labelH;
        if (ly < 0) ly = bottom;

        labelBgPaint.setColor(isRegistered ? Color.parseColor("#006622") : Color.parseColor("#444444"));
        canvas.drawRoundRect(new RectF(lx, ly, lx + textW + 16f, ly + labelH), 8f, 8f, labelBgPaint);
        canvas.drawText(label, lx + 8f, ly + labelH - 10f, textPaint);
    }

    private String buildLabel(FaceResult face) {
        if (face instanceof FaceRecognitionResult) {
            FaceRecognitionResult rec = (FaceRecognitionResult) face;
            if (rec.isRegistered) {
                return String.format("✅ %s (%.0f%%)", rec.personName, rec.confidence * 100);
            } else {
                return String.format("❓ %.3f", rec.confidence);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(face.trackingId);
        if (face.smilingProbability >= 0) {
            sb.append(" ").append((int) (face.smilingProbability * 100)).append("%");
        }
        if (Math.abs(face.eulerY) > 20f) {
            sb.append(face.eulerY > 0 ? " ◀" : " ▶");
        }
        return sb.toString();
    }

    private void drawStats(Canvas canvas, int count) {
        String text1 = "Faces: " + count + " | " + processingTimeMs + "ms";
        canvas.drawText(text1, 20f, 50f, statsPaint);

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
                text2 = "Best score: " + String.format("%.3f", bestScore);
            }

            Paint statsPaint2 = new Paint(statsPaint);
            statsPaint2.setTextSize(28f);
            statsPaint2.setColor(recognizedCount > 0 ? Color.GREEN : Color.YELLOW);
            canvas.drawText(text2, 20f, 90f, statsPaint2);
        }
    }
}
