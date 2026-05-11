package com.facedetectormulti.ui;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.facedetectormulti.R;
import com.facedetectormulti.detection.FaceDatabase;
import com.facedetectormulti.detection.FaceEmbeddingExtractor;
import com.facedetectormulti.detection.RegisteredFace;

public class RegistrationActivity extends AppCompatActivity {
    
    private ImageView ivPreview;
    private EditText etName, etDescription;
    private Button btnSave, btnCancel;
    
    private Bitmap capturedFace;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);
        
        initViews();
        
        // Nhận face bitmap từ Intent
        capturedFace = getIntent().getParcelableExtra("face_bitmap");
        if (capturedFace != null) {
            ivPreview.setImageBitmap(capturedFace);
        }
        
        btnSave.setOnClickListener(v -> saveRegistration());
        btnCancel.setOnClickListener(v -> finish());
    }
    
    private void initViews() {
        ivPreview = findViewById(R.id.ivPreview);
        etName = findViewById(R.id.etName);
        etDescription = findViewById(R.id.etDescription);
        btnSave = findViewById(R.id.btnSave);
        btnCancel = findViewById(R.id.btnCancel);
    }
    
    private void saveRegistration() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập tên", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (capturedFace == null) {
            Toast.makeText(this, "Không có ảnh mặt", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Extract embedding
        float[] embedding = FaceEmbeddingExtractor.extract(capturedFace);
        String avatarBase64 = FaceEmbeddingExtractor.toBase64(capturedFace);
        
        // Save to database (async)
        new Thread(() -> {
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
                }
            });
        }).start();
    }
}