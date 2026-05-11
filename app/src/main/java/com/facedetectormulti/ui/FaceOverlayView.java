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
import com.facedetectormulti.detection.MultiFaceDetector;
import java.util.Collections;
import java.util.List;

public class FaceOverlayView extends View {

    private static final int[] COLORS = {
        Color.rgb(0,   255, 100),
        Color.rgb(0,   200, 255),
        Color.rgb(255, 120,   0),
        Color.rgb(255,  60, 200),
        Color.rgb(200, 255,   0),
        Color.rgb(180, 100, 255),
    };

    private final Paint boxPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statsPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<FaceResult> faces = Collections.emptyList();
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

    public void update(MultiFaceDetector.DetectionResult result) {
        this.faces = result != null && result.faces != null
                ? result.faces : Collections.emptyList();
        this.processingTimeMs = result != null ? result.processingMs : 0;
        invalidate();
    }

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
        if (faces.isEmpty()) {
            drawStats(canvas, 0);
            return;
        }

        float vw = getWidth();
        float vh = getHeight();

        for (FaceResult face : faces) {
            int color = COLORS[Math.abs(face.trackingId) % COLORS.length];
            boxPaint.setColor(color);
            centerPaint.setColor(color);
            labelBgPaint.setColor(color);

            float left   = face.boxNorm.left   * vw;
            float top    = face.boxNorm.top    * vh;
            float right  = face.boxNorm.right  * vw;
            float bottom = face.boxNorm.bottom * vh;

            if (mirrorX) {
                float tmp = left;
                left  = vw - right;
                right = vw - tmp;
            }

            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(rect, 12f, 12f, boxPaint);

            float cx = (left + right) / 2f;
            float cy = (top + bottom) / 2f;
            canvas.drawCircle(cx, cy, 8f, centerPaint);
            canvas.drawLine(cx - 18, cy, cx + 18, cy, boxPaint);
            canvas.drawLine(cx, cy - 18, cx, cy + 18, boxPaint);

            String label = buildLabel(face);
            float textW  = textPaint.measureText(label);
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

    private String buildLabel(FaceResult face) {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(face.trackingId);
        if (face.smileProbability >= 0) {
            sb.append("  ").append((int)(face.smileProbability * 100)).append("%");
        }
        if (Math.abs(face.eulerY) > 20f) {
            sb.append(face.eulerY > 0 ? " ◀" : " ▶");
        }
        return sb.toString();
    }

    private void drawStats(Canvas canvas, int count) {
        String text = "Faces: " + count + "   " + processingTimeMs + "ms";
        canvas.drawText(text, 20f, 50f, statsPaint);
    }
}