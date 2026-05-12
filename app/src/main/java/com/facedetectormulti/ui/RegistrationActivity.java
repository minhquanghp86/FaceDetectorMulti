package com.facedetectormulti.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import android.graphics.YuvImage;
import com.facedetectormulti.detection.FaceDao;
import com.facedetectormulti.detection.FaceDatabase;
import com.facedetectormulti.detection.FaceEmbeddingExtractor;
import com.facedetectormulti.detection.FaceResult;
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
    private static final int REQUEST_PICK_IMAGE = 103;
    
    // UI Components
    private PreviewView cameraPreview;
    private ImageView ivPreview;
    private EditText etName, etDescription;
    private Button btnCapture, btnGallery, btnSave, btnCancel;
    private ImageButton btnSwitchCamera;  // ✅ Nút chuyển camera
    
    // Camera
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_BACK_CAMERA; // ✅ Mặc định camera sau
    
    // Face detection
    private FaceDetector faceDetector;
    private Bitmap capturedFace;
    private Bitmap latestFrame;
    private boolean isFrontCamera = false; // ✅ Theo dõi camera hiện tại

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);
        
        initViews();
        initFaceDetector();
        initCameraExecutor();
        setupClickListeners();
        startCameraPreview();
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
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera); // ✅ Nút chuyển camera
        
        // Ẩn nút switch nếu không có trong layout
        if (btnSwitchCamera == null) {
            Log.w(TAG, "btnSwitchCamera not found in layout, using default back camera");
        }
    }

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
        // ✅ Chuyển camera trước/sau
        if (btnSwitchCamera != null) {
            btnSwitchCamera.setOnClickListener(v -> {
                switchCamera();
            });
        }
        
        // Chụp ảnh từ camera
        btnCapture.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    == PackageManager.PERMISSION_GRANTED) {
                captureFromCamera();
            } else {
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            }
        });

        // Chọn ảnh từ gallery
        btnGallery.setOnClickListener(v -> pickImageFromGallery());

        // Lưu đăng ký
        btnSave.setOnClickListener(v -> saveRegistration());

        // Hủy
        btnCancel.setOnClickListener(v -> finish());
    }

    // ✅ Method chuyển camera
    private void switchCamera() {
        isFrontCamera = !isFrontCamera;
        
        if (isFrontCamera) {
            currentCamera = CameraSelector.DEFAULT_FRONT_CAMERA;
            Toast.makeText(this, "📷 Camera trước", Toast.LENGTH_SHORT).show();
        } else {
            currentCamera = CameraSelector.DEFAULT_BACK_CAMERA;
            Toast.makeText(this, "📷 Camera sau", Toast.LENGTH_SHORT).show();
        }
        
        // Khởi động lại camera
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        startCameraPreview();
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
        cameraProvider.unbindAll();
        
        Preview preview = new Preview.Builder()
            .setTargetRotation(cameraPreview.getDisplay().getRotation())
            .build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
        
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(cameraPreview.getDisplay().getRotation())
            .build();
        
        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            // ✅ Sửa: chuyển đúng sang ảnh màu
            Bitmap bitmap = imageProxyToColorBitmap(imageProxy);
            if (bitmap != null) {
                if (isFrontCamera) {
                    Matrix matrix = new Matrix();
                    matrix.preScale(-1f, 1f);
                    Bitmap mirrored = Bitmap.createBitmap(bitmap, 0, 0, 
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (latestFrame != null && !latestFrame.isRecycled()) {
                        latestFrame.recycle();
                    }
                    latestFrame = mirrored;
                    bitmap.recycle();
                } else {
                    if (latestFrame != null && !latestFrame.isRecycled()) {
                        latestFrame.recycle();
                    }
                    latestFrame = bitmap;
                }
            }
            imageProxy.close();
        });
        
        try {
            cameraProvider.bindToLifecycle(this, currentCamera, preview, imageAnalysis);
            Log.d(TAG, "Camera bound: " + (isFrontCamera ? "Front" : "Back"));
        } catch (Exception e) {
            Log.e(TAG, "Camera bind failed", e);
        }
    }

    /**
     * Chuyển ImageProxy thành Bitmap màu (YUV → RGB)
     */
    private Bitmap imageProxyToColorBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image image = imageProxy.getImage();
            if (image == null) return null;
        
            int w = imageProxy.getWidth();
            int h = imageProxy.getHeight();
        
            // Dùng YuvImage để chuyển đúng
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes.length < 3) {
                // Fallback: ảnh grayscale
                return imageProxyToBitmap(imageProxy);
            }
        
            java.nio.ByteBuffer yBuffer = planes[0].getBuffer();
            java.nio.ByteBuffer uBuffer = planes[1].getBuffer();
            java.nio.ByteBuffer vBuffer = planes[2].getBuffer();
        
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
        
            byte[] nv21 = new byte[ySize + uSize + vSize];
        
            // Copy Y
            yBuffer.get(nv21, 0, ySize);
            // Copy V
            vBuffer.get(nv21, ySize, vSize);
            // Copy U
            uBuffer.get(nv21, ySize + vSize, uSize);
        
            YuvImage yuvImage = new YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 90, out);
            byte[] imageBytes = out.toByteArray();
        
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            image.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Color conversion failed: " + e.getMessage(), e);
            return imageProxyToBitmap(imageProxy);
        }
    }

            
    private void captureFromCamera() {
        if (latestFrame == null || latestFrame.isRecycled()) {
            Toast.makeText(this, "⚠ Chưa có khung hình, thử lại sau", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Phát hiện và crop face từ frame mới nhất
        detectAndCropFace(latestFrame);
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void detectAndCropFace(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Toast.makeText(this, "⚠ Ảnh không hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }
        
        btnCapture.setEnabled(false);
        btnCapture.setText("⏳ Đang xử lý...");
        
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        
        faceDetector.process(image)
            .addOnSuccessListener(faces -> {
                btnCapture.setEnabled(true);
                btnCapture.setText("📸 Chụp ảnh");
                
                if (faces.isEmpty()) {
                    Toast.makeText(this, "❌ Không tìm thấy khuôn mặt trong ảnh", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Lấy face lớn nhất
                Face largestFace = faces.get(0);
                for (Face face : faces) {
                    if (face.getBoundingBox().width() * face.getBoundingBox().height() 
                        > largestFace.getBoundingBox().width() * largestFace.getBoundingBox().height()) {
                        largestFace = face;
                    }
                }
                
                // Crop face với margin
                Rect box = largestFace.getBoundingBox();
                int margin = (int)(Math.min(box.width(), box.height()) * 0.3f);
                
                int left = Math.max(0, box.left - margin);
                int top = Math.max(0, box.top - margin);
                int right = Math.min(bitmap.getWidth(), box.right + margin);
                int bottom = Math.min(bitmap.getHeight(), box.bottom + margin);
                
                int cropW = right - left;
                int cropH = bottom - top;
                
                if (cropW <= 0 || cropH <= 0) {
                    Toast.makeText(this, "❌ Không thể crop ảnh", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                try {
                    Bitmap cropped = Bitmap.createBitmap(bitmap, left, top, cropW, cropH);
                    setCapturedFace(cropped);
                    Toast.makeText(this, "✅ Đã phát hiện và crop khuôn mặt", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Crop failed: " + e.getMessage());
                    Toast.makeText(this, "❌ Lỗi crop ảnh", Toast.LENGTH_SHORT).show();
                }
            })
            .addOnFailureListener(e -> {
                btnCapture.setEnabled(true);
                btnCapture.setText("📸 Chụp ảnh");
                Log.e(TAG, "Face detection failed", e);
                Toast.makeText(this, "❌ Lỗi phát hiện face: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    private void setCapturedFace(Bitmap face) {
        // Giải phóng bitmap cũ
        if (capturedFace != null && !capturedFace.isRecycled()) {
            capturedFace.recycle();
        }
        this.capturedFace = face;
        
        // Hiển thị ảnh đã chụp
        ivPreview.setImageBitmap(face);
        cameraPreview.setVisibility(View.GONE);
        ivPreview.setVisibility(View.VISIBLE);
        
        // Cập nhật trạng thái nút
        btnCapture.setEnabled(false);
        btnCapture.setText("✅ Đã chụp");
        btnGallery.setEnabled(false);
        
        // Focus vào ô tên
        etName.requestFocus();
    }

    private void saveRegistration() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập tên", Toast.LENGTH_SHORT).show();
            etName.requestFocus();
            return;
        }
        
        if (capturedFace == null || capturedFace.isRecycled()) {
            Toast.makeText(this, "Vui lòng chụp hoặc chọn ảnh face", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Kiểm tra tên đã tồn tại
        new Thread(() -> {
            boolean exists = FaceDatabase.getInstance(this).faceDao().getAllFaces()
                .stream().anyMatch(f -> f.name.equalsIgnoreCase(name));
            
            if (exists) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                        .setTitle("Tên đã tồn tại")
                        .setMessage("Tên \"" + name + "\" đã được đăng ký. Bạn có muốn ghi đè?")
                        .setPositiveButton("Ghi đè", (dialog, which) -> doSave(name))
                        .setNegativeButton("Hủy", null)
                        .show();
                });
            } else {
                doSave(name);
            }
        }).start();
    }
    
    private void doSave(String name) {
        btnSave.setEnabled(false);
        btnSave.setText("⏳ Đang lưu...");
        
        new Thread(() -> {
            try {
                float[] embedding = FaceEmbeddingExtractor.extract(capturedFace);
                String avatarBase64 = FaceEmbeddingExtractor.toBase64(capturedFace);
                
                RegisteredFace newFace = new RegisteredFace(name, embedding, avatarBase64);
                newFace.description = etDescription.getText().toString().trim();
                
                // Xóa face cũ nếu trùng tên
                FaceDao dao = FaceDatabase.getInstance(this).faceDao();
                List<RegisteredFace> existing = dao.getAllFaces();
                for (RegisteredFace f : existing) {
                    if (f.name.equalsIgnoreCase(name)) {
                        dao.delete(f);
                    }
                }
                
                long id = dao.insert(newFace);
                
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
                Log.e(TAG, "Save failed: " + e.getMessage(), e);
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
            
            int w = imageProxy.getWidth();
            int h = imageProxy.getHeight();
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) { 
                image.close(); 
                return null; 
            }
            
            java.nio.ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[w * h];
            
            buffer.rewind();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int offset = y * rowStride + x * pixelStride;
                    if (offset < buffer.capacity()) {
                        int gray = buffer.get(offset) & 0xFF;
                        pixels[y * w + x] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
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
                if (imageUri != null) {
                    try {
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                        detectAndCropFace(bitmap);
                    } catch (IOException e) {
                        Toast.makeText(this, "❌ Lỗi đọc ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
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
                captureFromCamera();
            } else {
                Toast.makeText(this, "❌ Cần quyền Camera", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cleanup bitmaps
        if (capturedFace != null && !capturedFace.isRecycled()) {
            capturedFace.recycle();
        }
        if (latestFrame != null && !latestFrame.isRecycled()) {
            latestFrame.recycle();
        }
        if (faceDetector != null) {
            faceDetector.close();
        }
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
    }
}
