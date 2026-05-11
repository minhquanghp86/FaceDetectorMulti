package com.minhquanghp86.faceservotracker.detection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * FaceOverlayView
 * ===============
 * Custom View trong suốt đặt chồng lên camera preview.
 * Nhận DetectionResult, vẽ bounding box + label cho từng khuôn mặt.
 *
 * Layout XML:
 *   <com.minhquanghp86.faceservotracker.detection.FaceOverlayView
 *       android:id="@+id/faceOverlay"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent" />
 *
 * Trong Activity:
 *   overlay.update(result);   // gọi từ UI thread
 */
public class FaceOverlayView extends View {

    // Bảng màu cho từng khuôn mặt (xoay vòng)
    private static final int[] COLORS = {
        Color.rgb(0,   255, 100),   // xanh lá
        Color.rgb(0,   200, 255),   // xanh cyan
        Color.rgb(255, 120,   0),   // cam
        Color.rgb(255,  60, 200),   // hồng
        Color.rgb(200, 255,   0),   // vàng-lục
        Color.rgb(180, 100, 255),   // tím
    };

    private final Paint boxPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statsPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<MultiFaceDetector.FaceData> faces = Collections.emptyList();
    private long processingTimeMs = 0;

    // Có mirror không (front camera thường mirror)
    private boolean mirrorX = false;

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

        textPaint.setTextSize(32f);
        textPaint.setColor(Color.BLACK);
        textPaint.setFakeBoldText(true);

        centerPaint.setStyle(Paint.Style.FILL);

        statsPaint.setTextSize(36f);
        statsPaint.setColor(Color.WHITE);
        statsPaint.setFakeBoldText(true);
        statsPaint.setShadowLayer(4f, 0, 0, Color.BLACK);
    }

    /** Gọi từ UI thread khi có kết quả mới */
    public void update(MultiFaceDetector.DetectionResult result) {
        this.faces = result.faces;
        this.processingTimeMs = result.processingTimeMs;
        invalidate();   // trigger onDraw
    }

    /** Xoá overlay (khi không còn khuôn mặt nào) */
    public void clear() {
        this.faces = Collections.emptyList();
        invalidate();
    }

    public void setMirrorX(boolean mirror) {
        this.mirrorX = mirror;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (faces == null || faces.isEmpty()) {
            drawStats(canvas, 0);
            return;
        }

        float vw = getWidth();
        float vh = getHeight();

        for (MultiFaceDetector.FaceData face : faces) {
            int color = COLORS[face.id % COLORS.length];
            boxPaint.setColor(color);
            centerPaint.setColor(color);
            labelBgPaint.setColor(color);

            // Tọa độ thực trên view (đã scale từ normalize 0.0–1.0)
            float left   = face.boundingBox.left   * vw;
            float top    = face.boundingBox.top    * vh;
            float right  = face.boundingBox.right  * vw;
            float bottom = face.boundingBox.bottom * vh;

            // Mirror nếu front camera
            if (mirrorX) {
                float tmp = left;
                left  = vw - right;
                right = vw - tmp;
            }

            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(rect, 12f, 12f, boxPaint);

            // Điểm center
            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            canvas.drawCircle(cx, cy, 8f, centerPaint);
            canvas.drawLine(cx - 18, cy, cx + 18, cy, boxPaint);
            canvas.drawLine(cx, cy - 18, cx, cy + 18, boxPaint);

            // Label: "#0  😊 90%"
            String label = buildLabel(face);
            float textW = textPaint.measureText(label);
            float labelH = 44f;
            float lx = left;
            float ly = top - labelH;
            if (ly < 0) ly = bottom;

            canvas.drawRoundRect(
                new RectF(lx, ly, lx + textW + 16f, ly + labelH),
                8f, 8f, labelBgPaint
            );
            canvas.drawText(label, lx + 8f, ly + labelH - 10f, textPaint);
        }

        drawStats(canvas, faces.size());
    }

    private String buildLabel(MultiFaceDetector.FaceData face) {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(face.id);
        if (face.smilingProb >= 0) {
            sb.append("  ").append((int)(face.smilingProb * 100)).append("%");
        }
        if (Math.abs(face.headEulerY) > 20f) {
            sb.append(face.headEulerY > 0 ? " ◀" : " ▶");
        }
        return sb.toString();
    }

    private void drawStats(Canvas canvas, int count) {
        String text = "Faces: " + count + "   " + processingTimeMs + "ms";
        canvas.drawText(text, 20f, 50f, statsPaint);
    }
}
