package com.facedetectormulti.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.facedetectormulti.detection.DetectionMode;
import com.facedetectormulti.detection.FaceResult;

import java.util.Collections;
import java.util.List;

/**
 * FaceOverlayView – vẽ bounding box phát hiện khuôn mặt.
 *
 * Hỗ trợ 2 chế độ:
 *  MULTI  – vẽ tất cả khuôn mặt, mỗi face một màu riêng (hành vi gốc).
 *  SINGLE – chỉ vẽ 1 khung cho khuôn mặt đầu tiên trong danh sách,
 *           màu xanh lá cố định, viền dày hơn để nổi bật.
 *           Stats bar hiển thị "1/N" khi có nhiều face nhưng chỉ show 1.
 */
public class FaceOverlayView extends View {

    private static final int[] BOX_COLORS = {
        0xFF00FF64, 0xFF00C8FF, 0xFFFF7800,
        0xFFFF3CC8, 0xFFC8FF00, 0xFFB464FF,
    };
    private static final int SINGLE_COLOR = 0xFF00FF64;

    private final Paint boxPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statsPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint modeBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint modeTextPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<? extends FaceResult> faces = Collections.emptyList();
    private long processingTimeMs = 0;
    private boolean mirrorX = false;
    private DetectionMode mode = DetectionMode.MULTI;

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

        modeBadgePaint.setStyle(Paint.Style.FILL);
        modeTextPaint.setTextSize(26f);
        modeTextPaint.setFakeBoldText(true);
        modeTextPaint.setShadowLayer(3f, 0, 0, Color.BLACK);
    }

    // ===================== Public API =====================

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

    public void setDetectionMode(DetectionMode newMode) {
        this.mode = newMode;
        postInvalidate();
    }

    public DetectionMode getDetectionMode() {
        return mode;
    }

    // ===================== Drawing =====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float vw = getWidth();
        float vh = getHeight();

        if (mode == DetectionMode.SINGLE) {
            if (!faces.isEmpty()) drawFaceSingle(canvas, faces.get(0), vw, vh);
            drawStats(canvas, faces.isEmpty() ? 0 : 1);
        } else {
            for (FaceResult face : faces) drawFaceMulti(canvas, face, vw, vh);
            drawStats(canvas, faces.size());
        }
        drawModeBadge(canvas);
    }

    private void drawFaceSingle(Canvas canvas, FaceResult face, float vw, float vh) {
        boxPaint.setColor(SINGLE_COLOR);
        cornerPaint.setColor(SINGLE_COLOR);
        boxPaint.setStrokeWidth(4.5f);
        cornerPaint.setStrokeWidth(8f);

        float[] c = mapCoords(face, vw, vh);
        boxPaint.setAlpha(160);
        canvas.drawRoundRect(new RectF(c[0], c[1], c[2], c[3]), 12f, 12f, boxPaint);
        boxPaint.setAlpha(255);
        drawCorners(canvas, c[0], c[1], c[2], c[3], cornerSize(c));

        drawLabel(canvas, buildSingleLabel(face), c[0], c[1], c[3], SINGLE_COLOR);

        // reset
        boxPaint.setStrokeWidth(3.5f);
        cornerPaint.setStrokeWidth(6f);
    }

    private void drawFaceMulti(Canvas canvas, FaceResult face, float vw, float vh) {
        int colorInt = BOX_COLORS[Math.abs(face.trackingId) % BOX_COLORS.length];
        boxPaint.setColor(colorInt);
        cornerPaint.setColor(colorInt);

        float[] c = mapCoords(face, vw, vh);
        boxPaint.setAlpha(140);
        canvas.drawRoundRect(new RectF(c[0], c[1], c[2], c[3]), 10f, 10f, boxPaint);
        boxPaint.setAlpha(255);
        drawCorners(canvas, c[0], c[1], c[2], c[3], cornerSize(c));
        drawLabel(canvas, buildMultiLabel(face), c[0], c[1], c[3], colorInt);
    }

    // ---- Helpers ----

    private float[] mapCoords(FaceResult face, float vw, float vh) {
        float left   = face.boxNorm[0] * vw;
        float top    = face.boxNorm[1] * vh;
        float right  = face.boxNorm[2] * vw;
        float bottom = face.boxNorm[3] * vh;
        if (mirrorX) {
            float tmp = left; left = vw - right; right = vw - tmp;
        }
        return new float[]{left, top, right, bottom};
    }

    private float cornerSize(float[] c) {
        float cs = Math.min(c[2] - c[0], c[3] - c[1]) * 0.18f;
        return Math.max(12f, Math.min(cs, 40f));
    }

    private void drawLabel(Canvas canvas, String label,
                           float left, float top, float bottom, int color) {
        float tw = textPaint.measureText(label);
        float lbH = 40f, lx = left;
        float ly = top - lbH - 2f;
        if (ly < 0) ly = bottom + 2f;
        labelBgPaint.setColor(Color.argb(180, 0, 0, 0));
        canvas.drawRoundRect(new RectF(lx, ly, lx + tw + 16f, ly + lbH), 8f, 8f, labelBgPaint);
        textPaint.setColor(color);
        canvas.drawText(label, lx + 8f, ly + lbH - 10f, textPaint);
    }

    private void drawCorners(Canvas canvas, float l, float t, float r, float b, float cs) {
        canvas.drawLine(l, t + cs, l, t, cornerPaint); canvas.drawLine(l, t, l + cs, t, cornerPaint);
        canvas.drawLine(r - cs, t, r, t, cornerPaint); canvas.drawLine(r, t, r, t + cs, cornerPaint);
        canvas.drawLine(l, b - cs, l, b, cornerPaint); canvas.drawLine(l, b, l + cs, b, cornerPaint);
        canvas.drawLine(r - cs, b, r, b, cornerPaint); canvas.drawLine(r, b - cs, r, b, cornerPaint);
    }

    private String buildMultiLabel(FaceResult face) {
        StringBuilder sb = new StringBuilder("#").append(face.trackingId);
        appendEmoji(sb, face); return sb.toString();
    }

    private String buildSingleLabel(FaceResult face) {
        StringBuilder sb = new StringBuilder("👤");
        appendEmoji(sb, face); return sb.toString();
    }

    private void appendEmoji(StringBuilder sb, FaceResult face) {
        if (face.smilingProbability > 0.7f) sb.append(" 😊");
        if (face.leftEyeOpenProbability >= 0 && face.rightEyeOpenProbability >= 0
                && face.leftEyeOpenProbability < 0.3f && face.rightEyeOpenProbability < 0.3f)
            sb.append(" 😴");
        if (Math.abs(face.eulerY) > 25f) sb.append(face.eulerY > 0 ? " ◀" : " ▶");
    }

    private void drawStats(Canvas canvas, int displayCount) {
        long fps = processingTimeMs > 0 ? Math.min(99, 1000 / processingTimeMs) : 0;
        String txt;
        if (mode == DetectionMode.SINGLE && faces.size() > 1)
            txt = "👤 1/" + faces.size() + "  |  " + fps + " fps  |  " + processingTimeMs + "ms";
        else
            txt = "👤 " + displayCount + "  |  " + fps + " fps  |  " + processingTimeMs + "ms";

        float tw = statsPaint.measureText(txt);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(160, 0, 0, 0));
        canvas.drawRoundRect(new RectF(8, 8, tw + 32, 56), 8, 8, bg);
        canvas.drawText(txt, 16f, 44f, statsPaint);
    }

    /**
     * Badge ở top-center cho biết chế độ hiện tại.
     * SINGLE → nền vàng "👤 1 NGƯỜI"
     * MULTI  → nền xanh "👥 NHIỀU NGƯỜI"
     */
    private void drawModeBadge(Canvas canvas) {
        float vw = getWidth();
        String label;
        int bgColor;
        if (mode == DetectionMode.SINGLE) {
            label = "  👤 1 NGƯỜI  ";
            bgColor = Color.argb(200, 180, 120, 0);
            modeTextPaint.setColor(0xFFFFE566);
        } else {
            label = "  👥 NHIỀU NGƯỜI  ";
            bgColor = Color.argb(200, 0, 70, 130);
            modeTextPaint.setColor(0xFF00CCFF);
        }
        float tw = modeTextPaint.measureText(label);
        float bx = (vw - tw) / 2f - 4f, by = 66f;
        modeBadgePaint.setColor(bgColor);
        canvas.drawRoundRect(new RectF(bx, by, bx + tw + 8f, by + 40f), 12f, 12f, modeBadgePaint);
        canvas.drawText(label, bx + 4f, by + 29f, modeTextPaint);
    }
}
