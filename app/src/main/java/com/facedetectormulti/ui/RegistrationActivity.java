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
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

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
    
    // UI
    private PreviewView cameraPreview;
    private ImageView ivPreview;
    private EditText etName, etDescription;
    private Button btnCapture, btnGallery, btnSave, btnCancel;
    private ImageButton btnSwitchCamera;
    private TextView tvDebugLog;
    
    // Camera
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector currentCamera = CameraSelector.DEFAULT_BACK_CAMERA;
    
    // Face detection
    private FaceDetector faceDetector;
    private Bitmap capturedFace;
    private Bitmap latestFrame;
    private boolean isFrontCamera = false;
    
    // Debug log
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);
        
        initViews();
        initFaceDetector();
        initCameraExecutor();
        setupClickListeners();
        startCameraPreview();
        
        addLog("✅ onCreate done");
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
        if (logBuilder.length() > 5000) {
            logBuilder.delete(0, logBuilder.length() - 4000);
        }
        runOnUiThread(() -> {
            if (tvDebugLog != null) {
                tvDebugLog.setText(logBuilder.toString());
            }
        });
    }

    private void initFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build();
        faceDetector = FaceDetection.getClient(options);
        addLog("✅ FaceDetector ready");
    }

    private void initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void setupClickListeners() {
        if (btnSwitchCamera != null) {
            btnSwitchCamera.setOnClickListener(v -> switchCamera());
        }
        
        btnCapture.setOnClickListener(v -> {
            addLog("📸 Capture clicked");
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
                addLog("❌ Camera error: " + e.getMessage());
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
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap != null) {
                if (isFrontCamera) {
                    Matrix matrix = new Matrix();
                    matrix.preScale(-1f, 1f);
                    Bitmap mirrored = Bitmap.createBitmap(bitmap, 0, 0, 
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
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

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            android.media.Image image = imageProxy.getImage();
            if (image == null) return null;
            int w = imageProxy.getWidth(), h = imageProxy.getHeight();
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
                nv21, android.graphics.ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 90, out);
            byte[] jpegBytes = out.toByteArray();
            
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            image.close();
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void captureFromCamera() {
        // ✅ Đợi 500ms cho ảnh sẵn sàng
        cameraPreview.postDelayed(() -> {
            if (latestFrame == null || latestFrame.isRecycled()) {
                addLog("❌ latestFrame not ready");
                Toast.makeText(this, "⚠ Chưa có khung hình, thử lại", Toast.LENGTH_SHORT).show();
                return;
            }
            addLog("📸 latestFrame: " + latestFrame.getWidth() + "x" + latestFrame.getHeight());
            detectAndCropFace(latestFrame);
        }, 500);
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void detectAndCropFace(Bitmap source) {
        if (source == null || source.isRecycled()) {
            addLog("❌ source is null/recycled");
            Toast.makeText(this, "⚠ Ảnh không hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }
        
        addLog("🔍 detectAndCropFace: " + source.getWidth() + "x" + source.getHeight());
        btnCapture.setEnabled(false);
        btnCapture.setText("⏳ Đang xử lý...");
        
        // ✅ Resize về kích thước vừa phải để ML Kit detect nhanh
        final Bitmap processed;
        int maxDim = 640;
        if (source.getWidth() > maxDim || source.getHeight() > maxDim) {
            float scale = Math.min((float) maxDim / source.getWidth(), (float) maxDim / source.getHeight());
            processed = Bitmap.createScaledBitmap(source, 
                (int)(source.getWidth() * scale), (int)(source.getHeight() * scale), true);
        } else {
            processed = source;
        }
        
        // ✅ Tạo bản copy an toàn để fallback
        final Bitmap fallbackCopy;
        try {
            fallbackCopy = processed.copy(Bitmap.Config.ARGB_8888, true);
        } catch (Exception e) {
            addLog("❌ Copy failed: " + e.getMessage());
            btnCapture.setEnabled(true);
            btnCapture.setText("📸 Chụp ảnh");
            return;
        }
        
        InputImage image = InputImage.fromBitmap(processed, 0);
        addLog("🚀 Calling ML Kit...");
        
        faceDetector.process(image)
            .addOnSuccessListener(faces -> {
                addLog("✅ ML Kit done, faces: " + faces.size());
                btnCapture.setEnabled(true);
                btnCapture.setText("📸 Chụp ảnh");
                
                if (faces.isEmpty()) {
                    addLog("❌ No face detected");
                    showNoFaceDialog(fallbackCopy);
                    return;
                }
                
                // Tìm face lớn nhất
                Face best = faces.get(0);
                for (Face f : faces) {
                    if (f.getBoundingBox().width() * f.getBoundingBox().height() 
                        > best.getBoundingBox().width() * best.getBoundingBox().height()) {
                        best = f;
                    }
                }
                
                Rect box = best.getBoundingBox();
                int mw = (int)(box.width() * 0.25f);
                int mh = (int)(box.height() * 0.25f);
                int l = Math.max(0, box.left - mw);
                int t = Math.max(0, box.top - mh);
                int r = Math.min(processed.getWidth(), box.right + mw);
                int b = Math.min(processed.getHeight(), box.bottom + mh);
                
                try {
                    Bitmap cropped = Bitmap.createBitmap(processed, l, t, r - l, b - t);
                    addLog("✅ Cropped: " + cropped.getWidth() + "x" + cropped.getHeight());
                    setCapturedFace(cropped);
                    Toast.makeText(this, "✅ Đã crop khuôn mặt", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    addLog("❌ Crop error: " + e.getMessage());
                    showNoFaceDialog(fallbackCopy);
                }
            })
            .addOnFailureListener(e -> {
                addLog("❌ ML Kit failed: " + e.getMessage());
                btnCapture.setEnabled(true);
                btnCapture.setText("📸 Chụp ảnh");
                showNoFaceDialog(fallbackCopy);
            });
    }
    
    private void showNoFaceDialog(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            addLog("❌ showNoFaceDialog: bitmap invalid");
            Toast.makeText(this, "❌ Không có ảnh để dùng", Toast.LENGTH_SHORT).show();
            return;
        }
        
        addLog("💬 No face dialog");
        new AlertDialog.Builder(this)
            .setTitle("Không tìm thấy khuôn mặt")
            .setMessage("ML Kit không phát hiện được khuôn mặt.\nDùng toàn bộ ảnh?")
            .setPositiveButton("Dùng ảnh này", (d, w) -> {
                try {
                    Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                    if (copy != null) {
                        setCapturedFace(copy);
                    } else {
                        Toast.makeText(this, "❌ Lỗi xử lý ảnh", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    addLog("❌ Dialog copy error: " + e.getMessage());
                    Toast.makeText(this, "❌ Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
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
                List<RegisteredFace> existing = dao.getAllFaces();
                for (RegisteredFace f : existing) {
                    if (f.name.equalsIgnoreCase(name)) dao.delete(f);
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
                        Toast.makeText(this, "❌ Lỗi lưu", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
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
                new Thread(() -> {
                    try {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        InputStream is = getContentResolver().openInputStream(uri);
                        BitmapFactory.decodeStream(is, null, opts);
                        is.close();
                        
                        opts.inSampleSize = calculateInSampleSize(opts, 640, 640);
                        opts.inJustDecodeBounds = false;
                        
                        is = getContentResolver().openInputStream(uri);
                        final Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
                        is.close();
                        
                        runOnUiThread(() -> {
                            if (bitmap != null) detectAndCropFace(bitmap);
                            else Toast.makeText(this, "❌ Không đọc được ảnh", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "❌ Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
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
        faceDetector.close();
        cameraExecutor.shutdown();
    }
}
