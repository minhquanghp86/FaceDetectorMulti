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
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    
    // UI
    private PreviewView cameraPreview;
    private ImageView ivPreview;
    private EditText etName, etDescription;
    private Button btnCapture, btnGallery, btnSave, btnCancel;
    private ImageButton btnSwitchCamera;
    
    // Camera
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_BACK_CAMERA;
    
    // Face detection
    private FaceDetector faceDetector;
    private Bitmap capturedFace;
    private Bitmap latestFrame;
    private boolean isFrontCamera = false;

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
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
    }

    private void initFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build();
        faceDetector = FaceDetection.getClient(options);
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void setupClickListeners() {
        if (btnSwitchCamera != null) {
            btnSwitchCamera.setOnClickListener(v -> switchCamera());
        }
        
        btnCapture.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    == PackageManager.PERMISSION_GRANTED) {
                captureFromCamera();
            } else {
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            }
        });

        btnGallery.setOnClickListener(v -> pickImageFromGallery());
        btnSave.setOnClickListener(v -> saveRegistration());
        btnCancel.setOnClickListener(v -> finish());
    }

    private void switchCamera() {
        isFrontCamera = !isFrontCamera;
        currentCamera = isFrontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        Toast.makeText(this, isFrontCamera ? "📷 Camera trước" : "📷 Camera sau", Toast.LENGTH_SHORT).show();
        
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        startCameraPreview();
    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
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
            // ✅ Chuyển đúng sang Bitmap màu
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap != null) {
                // Lật ảnh nếu camera trước
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
     * ✅ Chuyển ImageProxy (YUV_420_888) → Bitmap RGB
     */
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image image = imageProxy.getImage();
            if (image == null) return null;
            
            int w = imageProxy.getWidth();
            int h = imageProxy.getHeight();
            
            // Lấy các plane Y, U, V
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes.length < 3) {
                image.close();
                return null;
            }
            
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
            
            // Dùng YuvImage để decode
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 90, out);
            byte[] jpegBytes = out.toByteArray();
            
            // Decode JPEG → Bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            image.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "imageProxyToBitmap failed: " + e.getMessage(), e);
            return null;
        }
    }

    private void captureFromCamera() {
        if (latestFrame == null || latestFrame.isRecycled()) {
            Toast.makeText(this, "⚠ Chưa có khung hình", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // ✅ Dùng trực tiếp latestFrame (đã là Bitmap màu)
        detectAndCropFace(latestFrame);
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void detectAndCropFace(Bitmap source) {
        if (source == null || source.isRecycled()) {
            Toast.makeText(this, "⚠ Ảnh không hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }
        
        btnCapture.setEnabled(false);
        btnCapture.setText("⏳ Đang xử lý...");
        
        // ✅ Resize nếu ảnh quá lớn
        final Bitmap processed;
        int maxDim = 1024;
        if (source.getWidth() > maxDim || source.getHeight() > maxDim) {
            float scale = Math.min((float) maxDim / source.getWidth(), (float) maxDim / source.getHeight());
            processed = Bitmap.createScaledBitmap(source, 
                (int)(source.getWidth() * scale), (int)(source.getHeight() * scale), true);
        } else {
            processed = source;
        }
        
        InputImage image = InputImage.fromBitmap(processed, 0);
        final Bitmap original = source;
        
        faceDetector.process(image)
            .addOnSuccessListener(faces -> {
                btnCapture.setEnabled(true);
                btnCapture.setText("📸 Chụp ảnh");
                
                if (faces.isEmpty()) {
                    // ✅ Fallback: không tìm thấy face → hỏi dùng cả ảnh
                    showNoFaceDialog(original);
                    return;
                }
                
                // Tìm face lớn nhất
                Face bestFace = faces.get(0);
                for (Face f : faces) {
                    if (f.getBoundingBox().width() * f.getBoundingBox().height() 
                        > bestFace.getBoundingBox().width() * bestFace.getBoundingBox().height()) {
                        bestFace = f;
                    }
                }
                
                Rect box = bestFace.getBoundingBox();
                // Thêm margin 30%
                int marginW = (int)(box.width() * 0.3f);
                int marginH = (int)(box.height() * 0.3f);
                
                int left = Math.max(0, box.left - marginW);
                int top = Math.max(0, box.top - marginH);
                int right = Math.min(processed.getWidth(), box.right + marginW);
                int bottom = Math.min(processed.getHeight(), box.bottom + marginH);
                
                try {
                    Bitmap cropped = Bitmap.createBitmap(processed, left, top, right - left, bottom - top);
                    setCapturedFace(cropped);
                    Toast.makeText(this, "✅ Đã crop khuôn mặt", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Crop error: " + e.getMessage());
                    showNoFaceDialog(original);
                }
            })
            .addOnFailureListener(e -> {
                btnCapture.setEnabled(true);
                btnCapture.setText("📸 Chụp ảnh");
                Log.e(TAG, "Detection failed: " + e.getMessage());
                showNoFaceDialog(original);
            });
    }
    
    private void showNoFaceDialog(Bitmap original) {
        new AlertDialog.Builder(this)
            .setTitle("Không tìm thấy khuôn mặt")
            .setMessage("Không phát hiện được khuôn mặt trong ảnh. Dùng toàn bộ ảnh để đăng ký?")
            .setPositiveButton("Dùng ảnh này", (d, w) -> {
                Bitmap copy = original.copy(Bitmap.Config.ARGB_8888, true);
                setCapturedFace(copy);
            })
            .setNegativeButton("Thử lại", null)
            .show();
    }

    private void setCapturedFace(Bitmap face) {
        if (capturedFace != null && !capturedFace.isRecycled() && capturedFace != face) {
            capturedFace.recycle();
        }
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
        if (name.isEmpty()) {
            Toast.makeText(this, "⚠ Vui lòng nhập tên", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (capturedFace == null || capturedFace.isRecycled()) {
            Toast.makeText(this, "⚠ Vui lòng chụp hoặc chọn ảnh", Toast.LENGTH_SHORT).show();
            return;
        }
        
        btnSave.setEnabled(false);
        btnSave.setText("⏳ Đang lưu...");
        
        new Thread(() -> {
            try {
                float[] embedding = FaceEmbeddingExtractor.extract(capturedFace);
                String avatarBase64 = FaceEmbeddingExtractor.toBase64(capturedFace);
                
                RegisteredFace newFace = new RegisteredFace(name, embedding, avatarBase64);
                newFace.description = etDescription.getText().toString().trim();
                
                FaceDao dao = FaceDatabase.getInstance(this).faceDao();
                
                // Xóa face cũ nếu trùng tên
                List<RegisteredFace> existing = dao.getAllFaces();
                for (RegisteredFace f : existing) {
                    if (f.name.equalsIgnoreCase(name)) {
                        dao.delete(f);
                    }
                }
                
                long id = dao.insert(newFace);
                
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("💾 Lưu");
                    
                    if (id > 0) {
                        Toast.makeText(this, "✓ Đã đăng ký: " + name, Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        Toast.makeText(this, "❌ Lỗi lưu dữ liệu", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Save error: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("💾 Lưu");
                    Toast.makeText(this, "❌ Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                try {
                    // ✅ Đọc ảnh từ gallery an toàn (giới hạn kích thước)
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    InputStream is = getContentResolver().openInputStream(uri);
                    BitmapFactory.decodeStream(is, null, opts);
                    is.close();
                    
                    // Tính sample size để ảnh không quá lớn
                    opts.inSampleSize = calculateInSampleSize(opts, 1024, 1024);
                    opts.inJustDecodeBounds = false;
                    
                    is = getContentResolver().openInputStream(uri);
                    Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
                    is.close();
                    
                    if (bitmap != null) {
                        detectAndCropFace(bitmap);
                    } else {
                        Toast.makeText(this, "❌ Không thể đọc ảnh", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Gallery error: " + e.getMessage(), e);
                    Toast.makeText(this, "❌ Lỗi đọc ảnh", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
    
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.length > 0 
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            captureFromCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (capturedFace != null && !capturedFace.isRecycled()) capturedFace.recycle();
        if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
        if (faceDetector != null) faceDetector.close();
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) cameraExecutor.shutdown();
    }
}
