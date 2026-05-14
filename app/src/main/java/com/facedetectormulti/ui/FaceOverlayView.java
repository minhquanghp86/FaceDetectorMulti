package com.facedetectormulti.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.facedetectormulti.detection.FaceResult;

import java.util.Collections;
import java.util.List;

/**
 * FaceOverlayView - vẽ bounding box phát hiện khuôn mặt.
 * Tối giản, không có recognition code.
 */
public class FaceOverlayView extends View {

    private static final int[] BOX_COLORS = {
        0xFF00FF64, // xanh lá
        0xFF00C8FF, // xanh cyan
        0xFFFF7800, // cam
        0xFFFF3CC8, // hồng
        0xFFC8FF00, // vàng xanh
        0xFFB464FF, // tím
    };

    private final Paint boxPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statsPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<? extends FaceResult> faces = Collections.emptyList();
    private long processingTimeMs = 0;
    private boolean mirrorX = false;

    public FaceOverlayView(Context context) { super(context); init(); }
    public FaceOverlayView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3.5f);

        labelBgPaint.setStyle(Paint.Style.FILL);

        textPaint.setTextSize(30f);
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);

        statsPaint.setTextSize(34f);
        statsPaint.setColor(Color.WHITE);
        statsPaint.setFakeBoldText(true);
        statsPaint.setShadowLayer(4f, 0, 0, Color.BLACK);

        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(6f);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void update(List<? extends FaceResult> newFaces, long processingMs) {
        this.faces = newFaces != null ? newFaces : Collections.emptyList();
        this.processingTimeMs = processingMs;
        postInvalidate();
    }

    public void clear() {
        this.faces = Collections.emptyList();
        postInvalidate();
    }

    public void setMirrorX(boolean mirror) {
        this.mirrorX = mirror;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float vw = getWidth();
        float vh = getHeight();

        for (FaceResult face : faces) {
            drawFace(canvas, face, vw, vh);
        }
        drawStats(canvas, faces.size());
    }

    private void drawFace(Canvas canvas, FaceResult face, float vw, float vh) {
        int colorInt = BOX_COLORS[Math.abs(face.trackingId) % BOX_COLORS.length];
        boxPaint.setColor(colorInt);
        cornerPaint.setColor(colorInt);

        float left   = face.boxNorm[0] * vw;
        float top    = face.boxNorm[1] * vh;
        float right  = face.boxNorm[2] * vw;
        float bottom = face.boxNorm[3] * vh;

        if (mirrorX) {
            float tmp = left;
            left  = vw - right;
            right = vw - tmp;
        }

        // Vẽ box chính (mờ)
        boxPaint.setAlpha(140);
        canvas.drawRoundRect(new RectF(left, top, right, bottom), 10f, 10f, boxPaint);
        boxPaint.setAlpha(255);

        // Vẽ góc nổi bật (corner brackets)
        float cs = Math.min((right - left), (bottom - top)) * 0.18f; // corner size
        cs = Math.max(12f, Math.min(cs, 40f));
        drawCorners(canvas, left, top, right, bottom, cs);

        // Label
        String label = buildLabel(face);
        float textW = textPaint.measureText(label);
        float lbH   = 40f;
        float lx    = left;
        float ly    = top - lbH - 2f;
        if (ly < 0) ly = bottom + 2f;

        labelBgPaint.setColor(Color.argb(180, 0, 0, 0));
        canvas.drawRoundRect(new RectF(lx, ly, lx + textW + 16f, ly + lbH), 8f, 8f, labelBgPaint);

        textPaint.setColor(colorInt);
        canvas.drawText(label, lx + 8f, ly + lbH - 10f, textPaint);
    }

    private void drawCorners(Canvas canvas, float l, float t, float r, float b, float cs) {
        // Top-left
        canvas.drawLine(l, t + cs, l, t, cornerPaint);
        canvas.drawLine(l, t, l + cs, t, cornerPaint);
        // Top-right
        canvas.drawLine(r - cs, t, r, t, cornerPaint);
        canvas.drawLine(r, t, r, t + cs, cornerPaint);
        // Bottom-left
        canvas.drawLine(l, b - cs, l, b, cornerPaint);
        canvas.drawLine(l, b, l + cs, b, cornerPaint);
        // Bottom-right
        canvas.drawLine(r - cs, b, r, b, cornerPaint);
        canvas.drawLine(r, b - cs, r, b, cornerPaint);
    }

    private String buildLabel(FaceResult face) {
        StringBuilder sb = new StringBuilder("#").append(face.trackingId);
        if (face.smilingProbability >= 0) {
            if (face.smilingProbability > 0.7f) sb.append(" 😊");
        }
        if (face.leftEyeOpenProbability >= 0 && face.rightEyeOpenProbability >= 0) {
            if (face.leftEyeOpenProbability < 0.3f && face.rightEyeOpenProbability < 0.3f) {
                sb.append(" 😴");
            }
        }
        if (Math.abs(face.eulerY) > 25f) {
            sb.append(face.eulerY > 0 ? " ◀" : " ▶");
        }
        return sb.toString();
    }

    private void drawStats(Canvas canvas, int count) {
        long fps = processingTimeMs > 0 ? Math.min(99, 1000 / processingTimeMs) : 0;
        String txt = "👤 " + count + "  |  " + fps + " fps  |  " + processingTimeMs + "ms";
        float textW = statsPaint.measureText(txt);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(160, 0, 0, 0));
        canvas.drawRoundRect(new RectF(8, 8, textW + 32, 56), 8, 8, bg);
        canvas.drawText(txt, 16f, 44f, statsPaint);
    }
}
