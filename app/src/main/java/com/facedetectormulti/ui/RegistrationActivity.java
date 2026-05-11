package com.facedetectormulti.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
import com.facedetectormulti.detection.FaceDatabase;
import com.facedetectormulti.detection.FaceEmbeddingExtractor;
import com.facedetectormulti.detection.RegisteredFace;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegistrationActivity extends AppCompatActivity {    
    private static final String TAG = "RegistrationActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 101;
    private static final int REQUEST_IMAGE_CAPTURE = 102;
    private static final int REQUEST_PICK_IMAGE = 103;
    
    // UI Components
    private PreviewView cameraPreview;
    private ImageView ivPreview;
    private EditText etName, etDescription;
    private Button btnCapture, btnGallery, btnSave, btnCancel;
    
    // Camera
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private boolean isCameraActive = false;
    
    // Face detection
    private FaceDetector faceDetector;
    private Bitmap capturedFace;
    private Bitmap latestFrame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);
        
        initViews();
        initFaceDetector();
        initCameraExecutor();
        setupClickListeners();
        
        // Check if face bitmap passed from MainActivity
        Bitmap passedFace = getIntent().getParcelableExtra("face_bitmap");
        if (passedFace != null) {
            setCapturedFace(passedFace);
        } else {
            startCameraPreview();
        }
    }

    private void initViews() {
        cameraPreview = findViewById(R.id.cameraPreview);
        ivPreview = findViewById(R.id.ivPreview);
        etName = findViewById(R.id.etName);
        etDescription = findViewById(R.id.etDescription);
        btnCapture = findViewById(R.id.btnCapture);
        btnGallery = findViewById(R.id.btnGallery);
        btnSave = findViewById(R.id.btnSave);
        btnCancel = findViewById(R.id.btnCancel);    }

    private void initFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build();
        faceDetector = FaceDetection.getClient(options);
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void setupClickListeners() {
        // Capture button
        btnCapture.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    == PackageManager.PERMISSION_GRANTED) {
                takePhoto();
            } else {
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            }
        });

        // Gallery button
        btnGallery.setOnClickListener(v -> pickImageFromGallery());

        // Save button
        btnSave.setOnClickListener(v -> saveRegistration());

        // Cancel button
        btnCancel.setOnClickListener(v -> finish());
    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
            ProcessCameraProvider.getInstance(this);
        
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }
    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
        
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        
        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            latestFrame = imageProxyToBitmap(imageProxy);
            imageProxy.close();
        });
        
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        isCameraActive = true;
    }

    private void takePhoto() {
        if (latestFrame == null) {
            Toast.makeText(this, "⚠ Chưa có khung hình", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Detect face in latest frame
        detectAndCropFace(latestFrame);
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void detectAndCropFace(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        
        faceDetector.process(image)
            .addOnSuccessListener(faces -> {
                if (faces.isEmpty()) {
                    Toast.makeText(this, "❌ Không tìm thấy khuôn mặt trong ảnh", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Get largest face
                Face largestFace = faces.get(0);
                for (Face face : faces) {                    if (face.getBoundingBox().width() * face.getBoundingBox().height() 
                        > largestFace.getBoundingBox().width() * largestFace.getBoundingBox().height()) {
                        largestFace = face;
                    }
                }
                
                // Crop face
                Rect box = largestFace.getBoundingBox();
                Bitmap cropped = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height());
                setCapturedFace(cropped);
                
                Toast.makeText(this, "✅ Đã crop face thành công", Toast.LENGTH_SHORT).show();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Face detection failed", e);
                Toast.makeText(this, "❌ Lỗi phát hiện face: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    private void setCapturedFace(Bitmap face) {
        if (capturedFace != null && !capturedFace.isRecycled()) {
            capturedFace.recycle();
        }
        this.capturedFace = face;
        ivPreview.setImageBitmap(face);
        cameraPreview.setVisibility(android.view.View.GONE);
        ivPreview.setVisibility(android.view.View.VISIBLE);
        
        // Disable camera buttons after capture
        btnCapture.setEnabled(false);
        btnGallery.setEnabled(false);
    }

    private void saveRegistration() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập tên", Toast.LENGTH_SHORT).show();
            etName.requestFocus();
            return;
        }
        
        if (capturedFace == null) {
            Toast.makeText(this, "Vui lòng chụp hoặc chọn ảnh face", Toast.LENGTH_SHORT).show();
            return;
        }
        
        btnSave.setEnabled(false);
        btnSave.setText("Đang lưu...");
        
        new Thread(() -> {            try {
                float[] embedding = FaceEmbeddingExtractor.extract(capturedFace);
                String avatarBase64 = FaceEmbeddingExtractor.toBase64(capturedFace);
                
                RegisteredFace newFace = new RegisteredFace(name, embedding, avatarBase64);
                newFace.description = etDescription.getText().toString().trim();
                
                long id = FaceDatabase.getInstance(this).faceDao().insert(newFace);
                
                runOnUiThread(() -> {
                    if (id > 0) {
                        Toast.makeText(this, "✓ Đã đăng ký: " + name, Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        Toast.makeText(this, "❌ Lỗi lưu database", Toast.LENGTH_SHORT).show();
                        btnSave.setEnabled(true);
                        btnSave.setText("💾 Lưu");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "❌ Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    btnSave.setEnabled(true);
                    btnSave.setText("💾 Lưu");
                });
            }
        }).start();
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image image = imageProxy.getImage();
            if (image == null) return null;
            
            int w = imageProxy.getWidth(), h = imageProxy.getHeight();
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) { image.close(); return null; }
            
            java.nio.ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride(), rowStride = planes[0].getRowStride();
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[w * h];
            
            buffer.rewind();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int offset = y * rowStride + x * pixelStride;
                    if (offset < buffer.capacity()) {
                        int gray = buffer.get(offset) & 0xFF;                        pixels[y * w + x] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                    }
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            image.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "ImageProxy conversion failed", e);
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == REQUEST_PICK_IMAGE) {
                Uri imageUri = data.getData();
                try {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                    detectAndCropFace(bitmap);
                } catch (IOException e) {
                    Toast.makeText(this, "❌ Lỗi đọc ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takePhoto();
            } else {
                Toast.makeText(this, "❌ Cần quyền Camera", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (capturedFace != null && !capturedFace.isRecycled()) {
            capturedFace.recycle();
        }
        if (latestFrame != null && !latestFrame.isRecycled()) {            latestFrame.recycle();
        }
        faceDetector.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
    }
}