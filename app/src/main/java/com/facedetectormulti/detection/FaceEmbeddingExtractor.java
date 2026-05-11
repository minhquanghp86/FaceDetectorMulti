package com.facedetectormulti.detection;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

/**
 * Simple face embedding extractor using perceptual hash + lightweight features.
 * For production: replace with MediaPipe Face Embedding or custom CNN model.
 */
public class FaceEmbeddingExtractor {
    
    private static final String TAG = "FaceEmbedding";
    private static final int EMBEDDING_SIZE = 128;  // Vector size
    private static final int FACE_SIZE = 112;        // Resize face to 112x112 for consistency
    
    /**
     * Extract 128-dim embedding from face bitmap.
     * Returns normalized vector (L2 norm = 1) for cosine similarity.
     */
    public static float[] extract(Bitmap faceBitmap) {
        if (faceBitmap == null) return new float[EMBEDDING_SIZE];
        
        // 1. Resize & normalize face to 112x112
        Bitmap resized = Bitmap.createScaledBitmap(faceBitmap, FACE_SIZE, FACE_SIZE, true);
        
        // 2. Convert to grayscale float array
        float[] gray = new float[FACE_SIZE * FACE_SIZE];
        for (int y = 0; y < FACE_SIZE; y++) {
            for (int x = 0; x < FACE_SIZE; x++) {
                int pixel = resized.getPixel(x, y);
                // Convert RGB to grayscale using luminance formula
                float r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                gray[y * FACE_SIZE + x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
            }
        }
        
        // 3. Simple feature extraction: DCT-like + random projection
        // (For demo; replace with real face recognition model in production)
        float[] embedding = new float[EMBEDDING_SIZE];
        
        // Block averaging + random projection (simplified)
        int blockSize = FACE_SIZE / 8;  // 14x14 blocks
        for (int i = 0; i < EMBEDDING_SIZE; i++) {
            float sum = 0f;
            int bx = (i % 8) * blockSize;
            int by = (i / 8) * blockSize;
                        for (int dy = 0; dy < blockSize; dy++) {
                for (int dx = 0; dx < blockSize; dx++) {
                    int x = Math.min(bx + dx, FACE_SIZE - 1);
                    int y = Math.min(by + dy, FACE_SIZE - 1);
                    // Apply simple pattern (alternating +/-)
                    float weight = ((x + y) % 2 == 0) ? 1f : -1f;
                    sum += gray[y * FACE_SIZE + x] * weight;
                }
            }
            embedding[i] = sum / (blockSize * blockSize);
        }
        
        // 4. L2 normalize for cosine similarity
        float norm = 0f;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < EMBEDDING_SIZE; i++) {
                embedding[i] /= norm;
            }
        }
        
        resized.recycle();
        return embedding;
    }
    
    /**
     * Crop face region from original bitmap using normalized box.
     */
    public static Bitmap cropFace(Bitmap original, FaceResult face, int marginPx) {
        int w = original.getWidth(), h = original.getHeight();
        float left = Math.max(0, face.boxNorm[0] * w - marginPx);
        float top = Math.max(0, face.boxNorm[1] * h - marginPx);
        float right = Math.min(w, face.boxNorm[2] * w + marginPx);
        float bottom = Math.min(h, face.boxNorm[3] * h + marginPx);
        
        int cropW = (int)(right - left);
        int cropH = (int)(bottom - top);
        if (cropW <= 0 || cropH <= 0) return null;
        
        return Bitmap.createBitmap(original, (int)left, (int)top, cropW, cropH);
    }
    
    /**
     * Convert bitmap to base64 string for storage (optional).
     */
    public static String toBase64(Bitmap bitmap) {
        if (bitmap == null) return null;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);        byte[] bytes = baos.toByteArray();
        return android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT);
    }
    
    /**
     * Convert base64 string back to bitmap.
     */
    public static Bitmap fromBase64(String base64) {
        if (base64 == null) return null;
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode base64", e);
            return null;
        }
    }
}