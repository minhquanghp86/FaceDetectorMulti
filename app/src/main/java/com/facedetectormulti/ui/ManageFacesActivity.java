package com.facedetectormulti.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.facedetectormulti.R;
import com.facedetectormulti.detection.FaceDatabase;
import com.facedetectormulti.detection.FaceEmbeddingExtractor;
import com.facedetectormulti.detection.RegisteredFace;

import java.util.List;

public class ManageFacesActivity extends AppCompatActivity {
    
    private RecyclerView recyclerView;
    private FacesAdapter adapter;
    private List<RegisteredFace> facesList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_faces);
        
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        loadFaces();
    }

    private void loadFaces() {
        new Thread(() -> {
            facesList = FaceDatabase.getInstance(this).faceDao().getAllFaces();
            runOnUiThread(() -> {
                adapter = new FacesAdapter(facesList);
                recyclerView.setAdapter(adapter);
            });
        }).start();
    }
    private class FacesAdapter extends RecyclerView.Adapter<FacesAdapter.ViewHolder> {
        private List<RegisteredFace> faces;
        
        ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_registered_face, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RegisteredFace face = faces.get(position);
            holder.tvName.setText(face.name);
            holder.tvDescription.setText(face.description != null ? face.description : "Không có ghi chú");
            holder.tvCount.setText("Đã phát hiện: " + face.detectionCount + " lần");
            
            // Load avatar
            if (face.avatarBase64 != null) {
                Bitmap avatar = FaceEmbeddingExtractor.fromBase64(face.avatarBase64);
                holder.ivAvatar.setImageBitmap(avatar);
            }
            
            // Delete button
            holder.btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(ManageFacesActivity.this)
                    .setTitle("Xóa khuôn mặt")
                    .setMessage("Bạn có chắc muốn xóa \"" + face.name + "\"?")
                    .setPositiveButton("Xóa", (dialog, which) -> {
                        new Thread(() -> {
                            FaceDatabase.getInstance(ManageFacesActivity.this).faceDao().delete(face);
                            runOnUiThread(() -> {
                                faces.remove(position);
                                notifyItemRemoved(position);
                                Toast.makeText(ManageFacesActivity.this, 
                                    "✓ Đã xóa", Toast.LENGTH_SHORT).show();
                            });
                        }).start();
                    })
                    .setNegativeButton("Hủy", null)
                    .show();
            });
        }

        @Override
        public int getItemCount() {
            return faces.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {            ImageView ivAvatar;
            TextView tvName, tvDescription, tvCount;
            Button btnDelete;
            
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivAvatar = itemView.findViewById(R.id.ivAvatar);
                tvName = itemView.findViewById(R.id.tvName);
                tvDescription = itemView.findViewById(R.id.tvDescription);
                tvCount = itemView.findViewById(R.id.tvCount);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFaces(); // Refresh list
    }
}