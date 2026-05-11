package com.facedetectormulti.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.facedetectormulti.detection.FaceRecognitionResult;
import com.facedetectormulti.detection.FaceResult;

import java.util.Collections;
import java.util.List;

public class FaceOverlayView extends View {

    private static final int[] COLORS = {
        Color.rgb(0, 255, 100),   // Green - registered
        Color.rgb(0, 200, 255),   // Cyan
        Color.rgb(255, 120, 0),   // Orange
        Color.rgb(255, 60, 200),  // Pink
        Color.rgb(200, 255, 0),   // Lime
        Color.rgb(180, 100, 255), // Purple
    };

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<? extends FaceResult> faces = Collections.emptyList();
    private long processingTimeMs = 0;
    private boolean mirrorX = false;

    public FaceOverlayView(Context context) {
        super(context);
        init();
    }

    public FaceOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {        boxPaint.setStyle(Paint.Style.STROKE);
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
    }

    public void update(List<? extends FaceResult> newFaces, long processingMs) {
        this.faces = newFaces != null ? newFaces : Collections.emptyList();
        this.processingTimeMs = processingMs;
        postInvalidate();
    }

    // Backward compatible overload
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
        // Select color based on registered status
        int colorIndex = Math.abs(face.trackingId) % COLORS.length;
        boolean isRegistered = face instanceof FaceRecognitionResult && 
                              ((FaceRecognitionResult) face).isRegistered;
        
        boxPaint.setColor(isRegistered ? Color.parseColor("#00FF50") : COLORS[colorIndex]);
        centerPaint.setColor(boxPaint.getColor());

        // Convert normalized [0,1] to pixels
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

        // Draw center point + crosshair
        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        canvas.drawCircle(cx, cy, 8f, centerPaint);
        canvas.drawLine(cx - 18, cy, cx + 18, cy, boxPaint);
        canvas.drawLine(cx, cy - 18, cx, cy + 18, boxPaint);

        // Build label
        String label = buildLabel(face);
        float textW = textPaint.measureText(label);
        float labelH = 44f;
        float lx = left;
        float ly = top - labelH;
        if (ly < 0) ly = bottom;
        // Draw label background (green for registered, gray for unknown)
        labelBgPaint.setColor(isRegistered ? Color.parseColor("#006622") : Color.parseColor("#444444"));
        canvas.drawRoundRect(
            new RectF(lx, ly, lx + textW + 16f, ly + labelH),
            8f, 8f, labelBgPaint
        );
        
        // Draw text
        canvas.drawText(label, lx + 8f, ly + labelH - 10f, textPaint);
    }

    private String buildLabel(FaceResult face) {
        if (face instanceof FaceRecognitionResult) {
            return ((FaceRecognitionResult) face).getDisplayLabel();
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

    private void drawStats(Canvas canvas, int count) {
        String text = "Faces: " + count + " | " + processingTimeMs + "ms";
        canvas.drawText(text, 20f, 50f, statsPaint);
    }
}