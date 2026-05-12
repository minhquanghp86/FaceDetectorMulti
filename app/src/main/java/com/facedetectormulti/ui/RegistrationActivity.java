package com.facedetectormulti.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.facedetectormulti.R;
import com.facedetectormulti.detection.FaceDao;
import com.facedetectormulti.detection.FaceDatabase;
import com.facedetectormulti.detection.FaceEmbeddingExtractor;
import com.facedetectormulti.detection.RegisteredFace;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegistrationActivity extends AppCompatActivity {
    
    private static final String TAG = "RegistrationActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 101;
    private static final int REQUEST_PICK_IMAGE = 103;
    
    private PreviewView cameraPreview;
    private ImageView ivPreview;
    private EditText etName, etDescription;
    private Button btnCapture, btnGallery, btnSave, btnCancel;
    private ImageButton btnSwitchCamera;
    private TextView tvDebugLog;
    
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_BACK_CAMERA;
    private Bitmap capturedFace;
    private Bitmap latestFrame;
    private boolean isFrontCamera = false;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);
        initViews();
        initCameraExecutor();
        setupClickListeners();
        startCameraPreview();
        addLog("✅ Sẵn sàng - Chụp hoặc chọn ảnh");
    }

    private void initViews() {
        cameraPreview = findViewById(R.id.cameraPreview);
        ivPreview = findViewById(R.id.ivPreview);
        etName = findViewById(R.id.etName);
        etDescription = findViewById(R.id.etDescription);
        btnCapture = findViewById(R.id.btnCapture);
        btnGallery = findViewById(R.id.btnGallery);
        btnSave = findViewById(R.id.btnSave);
        btnCancel = findViewById(R.id.btnCancel);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        tvDebugLog = findViewById(R.id.tvDebugLog);
    }
    
    private void addLog(String msg) {
        Log.d(TAG, msg);
        logBuilder.append(msg).append("\n");
        if (logBuilder.length() > 5000) logBuilder.delete(0, logBuilder.length() - 4000);
        runOnUiThread(() -> { if (tvDebugLog != null) tvDebugLog.setText(logBuilder.toString()); });
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void setupClickListeners() {
        if (btnSwitchCamera != null) btnSwitchCamera.setOnClickListener(v -> switchCamera());
        
        btnCapture.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                captureFromCamera();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            }
        });
        btnGallery.setOnClickListener(v -> pickImageFromGallery());
        btnSave.setOnClickListener(v -> saveRegistration());
        btnCancel.setOnClickListener(v -> finish());
    }

    private void switchCamera() {
        isFrontCamera = !isFrontCamera;
        currentCamera = isFrontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        if (cameraProvider != null) cameraProvider.unbindAll();
        startCameraPreview();
    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                addLog("❌ Camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();
        
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
        
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        
        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            Bitmap bitmap = yuvToRgb(imageProxy);
            if (bitmap != null) {
                if (isFrontCamera) {
                    Matrix matrix = new Matrix();
                    matrix.preScale(-1f, 1f);
                    Bitmap mirrored = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
                    latestFrame = mirrored;
                    bitmap.recycle();
                } else {
                    if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
                    latestFrame = bitmap;
                }
            }
            imageProxy.close();
        });
        
        cameraProvider.bindToLifecycle(this, currentCamera, preview, imageAnalysis);
    }

    private Bitmap yuvToRgb(ImageProxy imageProxy) {
        try {
            android.media.Image image = imageProxy.getImage();
            if (image == null) return null;
            int w = imageProxy.getWidth(), h = imageProxy.getHeight();
            
            // Đảm bảo chia hết cho 4
            int safeW = w - (w % 4);
            int safeH = h - (h % 4);
            if (safeW < 4 || safeH < 4) { image.close(); return null; }
            
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes.length < 3) { image.close(); return null; }
            
            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            
            int ySize = yBuf.remaining();
            int uSize = uBuf.remaining();
            int vSize = vBuf.remaining();
            
            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuf.get(nv21, 0, ySize);
            vBuf.get(nv21, ySize, vSize);
            uBuf.get(nv21, ySize + vSize, uSize);
            
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21, safeW, safeH, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, safeW, safeH), 95, out);
            
            byte[] jpegBytes = out.toByteArray();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, opts);
            image.close();
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void captureFromCamera() {
        cameraPreview.postDelayed(() -> {
            if (latestFrame == null || latestFrame.isRecycled()) {
                Toast.makeText(this, "⚠ Chưa có khung hình", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // ✅ Cắt vùng giữa ảnh (mặt thường ở giữa)
            int w = latestFrame.getWidth();
            int h = latestFrame.getHeight();
            int size = (int)(Math.min(w, h) * 0.6f); // 60% kích thước
            int left = (w - size) / 2;
            int top = (h - size) / 2;
            
            Bitmap cropped = Bitmap.createBitmap(latestFrame, left, top, size, size);
            addLog("📸 Đã chụp: " + cropped.getWidth() + "x" + cropped.getHeight());
            setCapturedFace(cropped);
        }, 200);
    }

    private void pickImageFromGallery() {
        startActivityForResult(new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType("image/*"), REQUEST_PICK_IMAGE);
    }

    private void setCapturedFace(Bitmap face) {
        if (capturedFace != null && !capturedFace.isRecycled() && capturedFace != face) capturedFace.recycle();
        this.capturedFace = face;
        ivPreview.setImageBitmap(face);
        cameraPreview.setVisibility(View.GONE);
        ivPreview.setVisibility(View.VISIBLE);
        btnCapture.setEnabled(false);
        btnCapture.setText("✅ Đã chụp");
        btnGallery.setEnabled(false);
        etName.requestFocus();
    }

    private void saveRegistration() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) { Toast.makeText(this, "⚠ Nhập tên", Toast.LENGTH_SHORT).show(); return; }
        if (capturedFace == null || capturedFace.isRecycled()) { Toast.makeText(this, "⚠ Chưa có ảnh", Toast.LENGTH_SHORT).show(); return; }
        
        btnSave.setEnabled(false);
        btnSave.setText("⏳...");
        
        new Thread(() -> {
            try {
                float[] embedding = FaceEmbeddingExtractor.extract(capturedFace);
                String avatarBase64 = FaceEmbeddingExtractor.toBase64(capturedFace);
                RegisteredFace newFace = new RegisteredFace(name, embedding, avatarBase64);
                newFace.description = etDescription.getText().toString().trim();
                
                FaceDao dao = FaceDatabase.getInstance(this).faceDao();
                for (RegisteredFace f : dao.getAllFaces()) {
                    if (f.name.equalsIgnoreCase(name)) dao.delete(f);
                }
                
                long id = dao.insert(newFace);
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("💾 Lưu");
                    if (id > 0) {
                        Toast.makeText(this, "✓ Đã lưu: " + name, Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        Toast.makeText(this, "❌ Lỗi", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("💾 Lưu");
                    Toast.makeText(this, "❌ " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && requestCode == REQUEST_PICK_IMAGE) {
            Uri uri = data.getData();
            if (uri != null) {
                new Thread(() -> {
                    try {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        InputStream is = getContentResolver().openInputStream(uri);
                        BitmapFactory.decodeStream(is, null, opts);
                        is.close();
                        
                        // Resize về max 640
                        opts.inSampleSize = calculateInSampleSize(opts, 640, 640);
                        opts.inJustDecodeBounds = false;
                        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        
                        is = getContentResolver().openInputStream(uri);
                        final Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
                        is.close();
                        
                        runOnUiThread(() -> {
                            if (bitmap != null) {
                                addLog("📷 Gallery: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                                setCapturedFace(bitmap);
                            } else {
                                Toast.makeText(this, "❌ Lỗi đọc ảnh", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "❌ " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }
        }
    }
    
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        int height = options.outHeight, width = options.outWidth;
        if (height > reqHeight || width > reqWidth) {
            while ((height / inSampleSize) >= reqHeight && (width / inSampleSize) >= reqWidth) inSampleSize *= 2;
        }
        return inSampleSize;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            captureFromCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (capturedFace != null && !capturedFace.isRecycled()) capturedFace.recycle();
        if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}