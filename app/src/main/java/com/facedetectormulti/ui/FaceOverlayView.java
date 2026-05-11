package com.facedetectormulti.ui;  // ✅ Đúng package

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;

// ✅ Import đúng package
import com.facedetectormulti.detection.DetectionCallback;
import com.facedetectormulti.detection.FaceResult;

public class FaceOverlayView extends View {
    
    // Dynamic UI values từ resources
    private final float boxStrokeWidth;
    private final float textSize;
    private final int[] faceColors;
    
    // State
    private final List<FaceResult> faces = new ArrayList<>();
    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final RectF boxRect = new RectF();
    
    private DetectionCallback callback;
    private int currentLensFacing = CameraSelector.LENS_FACING_FRONT;

    public FaceOverlayView(Context context) {
        this(context, null);
    }

    public FaceOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FaceOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        
        // Load dynamic values từ theme/resources
        TypedValue outValue = new TypedValue();        context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, outValue, true);
        
        // Default values
        boxStrokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, 
            getResources().getDisplayMetrics());
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, 
            getResources().getDisplayMetrics());
            
        // Color array cho faces (dùng từ colors.xml)
        faceColors = new int[] {
            Color.parseColor("#FF4081"), // Pink
            Color.parseColor("#3F51B5"), // Indigo  
            Color.parseColor("#009688"), // Teal
            Color.parseColor("#FF9800"), // Orange
            Color.parseColor("#9C27B0")  // Purple
        };
        
        initPaints();
    }

    private void initPaints() {
        // Box paint
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(boxStrokeWidth);
        boxPaint.setAntiAlias(true);
        
        // Text paint
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(textSize);
        textPaint.setAntiAlias(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK);
    }

    /**
     * Set camera facing để xử lý mirror cho front camera
     */
    public void setLensFacing(int lensFacing) {
        this.currentLensFacing = lensFacing;
        invalidate();
    }

    public void setResults(List<FaceResult> newFaces) {
        synchronized (faces) {
            faces.clear();
            if (newFaces != null) {
                faces.addAll(newFaces);
            }
        }
        invalidate();    }

    public void setDetectionCallback(DetectionCallback callback) {
        this.callback = callback;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (faces.isEmpty()) return;
        
        synchronized (faces) {
            for (FaceResult face : faces) {
                drawFace(canvas, face);
            }
        }
    }

    private void drawFace(Canvas canvas, FaceResult face) {
        // Convert normalized coordinates [0,1] sang pixel
        float left = face.boxNorm[0] * getWidth();
        float top = face.boxNorm[1] * getHeight();
        float right = face.boxNorm[2] * getWidth();
        float bottom = face.boxNorm[3] * getHeight();
        
        // Mirror handling cho front camera
        if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
            float tempLeft = left;
            left = getWidth() - right;
            right = getWidth() - tempLeft;
        }
        
        boxRect.set(left, top, right, bottom);
        
        // Chọn màu theo trackingId
        int colorIndex = Math.abs(face.trackingId) % faceColors.length;
        boxPaint.setColor(faceColors[colorIndex]);
        
        // Vẽ bounding box
        canvas.drawRect(boxRect, boxPaint);
        
        // Vẽ label với thông tin face
        String label = String.format("#%d", face.trackingId);
        if (face.smilingProbability >= 0) {
            label += String.format(" 😊%.0f%%", face.smilingProbability * 100);
        }
        if (Math.abs(face.eulerY) > 15) {
            label += String.format(" ↕%.0f°", face.eulerY);
        }        
        // Vẽ text background cho dễ đọc
        float textWidth = textPaint.measureText(label);
        float textHeight = textPaint.getTextSize();
        canvas.drawRect(left, top - textHeight - 8, 
                       left + textWidth + 16, top, boxPaint);
        
        // Vẽ text
        canvas.drawText(label, left + 8, top - 4, textPaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Không cần làm gì thêm, coordinates được tính realtime trong onDraw
    }
}