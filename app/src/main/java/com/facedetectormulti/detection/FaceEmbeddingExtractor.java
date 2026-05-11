package com.facedetectormulti.detection;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

/**
 * Face embedding extractor using hybrid features.
 * NO external model required - pure Java/Android.
 * Returns 128-dim L2-normalized vector for cosine similarity.
 */
public class FaceEmbeddingExtractor {
    
    private static final String TAG = "FaceEmbedding";
    private static final int EMBEDDING_SIZE = 128;
    private static final int FACE_SIZE = 96;

    public static float[] extract(Bitmap faceBitmap) {
        if (faceBitmap == null || faceBitmap.isRecycled()) {
            return new float[EMBEDDING_SIZE];
        }
        
        try {
            // 1. Preprocess: resize + grayscale
            Bitmap processed = preprocessFace(faceBitmap);
            
            // 2. Extract features
            float[] embedding = extractFeatures(processed);
            
            // 3. L2 normalize
            float[] normalized = l2Normalize(embedding);
            
            processed.recycle();
            return normalized;
            
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed", e);
            return new float[EMBEDDING_SIZE];
        }
    }

    private static Bitmap preprocessFace(Bitmap input) {
        Bitmap resized = Bitmap.createScaledBitmap(input, FACE_SIZE, FACE_SIZE, true);
        
        // Convert to grayscale
        int[] pixels = new int[FACE_SIZE * FACE_SIZE];
        byte[] gray = new byte[FACE_SIZE * FACE_SIZE];
        resized.getPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
        
        for (int i = 0; i < pixels.length; i++) {            int pixel = pixels[i];
            int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
            gray[i] = (byte) (0.299f * r + 0.587f * g + 0.114f * b);
        }
        
        // Simple histogram equalization
        equalizeHistogram(gray, FACE_SIZE, FACE_SIZE);
        
        // Convert back to Bitmap
        Bitmap result = Bitmap.createBitmap(FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < pixels.length; i++) {
            int g = gray[i] & 0xFF;
            pixels[i] = Color.argb(255, g, g, g);
        }
        result.setPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
        
        if (resized != input && !resized.isRecycled()) {
            resized.recycle();
        }
        return result;
    }

    private static void equalizeHistogram(byte[] gray, int w, int h) {
        int[] hist = new int[256];
        for (byte b : gray) hist[b & 0xFF]++;
        
        float[] cdf = new float[256];
        cdf[0] = hist[0];
        for (int i = 1; i < 256; i++) {
            cdf[i] = cdf[i-1] + hist[i];
        }
        
        float minCdf = cdf[0];
        float total = w * h;
        for (int i = 0; i < gray.length; i++) {
            int val = gray[i] & 0xFF;
            float eq = (cdf[val] - minCdf) / (total - hist[0]) * 255f;
            gray[i] = (byte) Math.max(0, Math.min(255, (int) eq));
        }
    }

    private static float[] extractFeatures(Bitmap face) {
        float[] features = new float[EMBEDDING_SIZE];
        int w = face.getWidth(), h = face.getHeight();
        
        // Get grayscale values
        int[] pixels = new int[w * h];
        face.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] gray = new float[w * h];
        for (int i = 0; i < pixels.length; i++) {            gray[i] = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3f / 255f;
        }
        
        // Part 1: Simple LBP-like features (64 dims)
        extractSimpleLBP(gray, w, h, features, 0, 64);
        
        // Part 2: Gradient features (64 dims)
        extractSimpleGradient(gray, w, h, features, 64, 64);
        
        return features;
    }

    private static void extractSimpleLBP(float[] gray, int w, int h, float[] out, int offset, int dims) {
        int grid = 4;
        int cellW = w / grid, cellH = h / grid;
        int dimsPerCell = dims / (grid * grid);
        
        for (int gy = 0; gy < grid; gy++) {
            for (int gx = 0; gx < grid; gx++) {
                int cellOffset = (gy * cellH) * w + (gx * cellW);
                int outIdx = offset + (gy * grid + gx) * dimsPerCell;
                
                for (int s = 0; s < dimsPerCell && s < 4; s++) {
                    int sx = (s % 2) * (cellW / 2);
                    int sy = (s / 2) * (cellH / 2);
                    int idx = cellOffset + sy * w + sx;
                    
                    if (idx < gray.length) {
                        float center = gray[idx];
                        float pattern = 0f;
                        
                        int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
                        for (int n = 0; n < 4; n++) {
                            int nx = Math.max(0, Math.min(w-1, (gx * cellW) + sx + dx[n]));
                            int ny = Math.max(0, Math.min(h-1, (gy * cellH) + sy + dy[n]));
                            int nidx = ny * w + nx;
                            if (gray[nidx] >= center) pattern |= (1 << n);
                        }
                        out[outIdx + s] = pattern / 15f;
                    }
                }
            }
        }
    }

    private static void extractSimpleGradient(float[] gray, int w, int h, float[] out, int offset, int dims) {
        int grid = 4;
        int cellW = w / grid, cellH = h / grid;
        int bins = dims / (grid * grid);
                for (int gy = 0; gy < grid; gy++) {
            for (int gx = 0; gx < grid; gx++) {
                int outIdx = offset + (gy * grid + gx) * bins;
                float[] hist = new float[bins];
                
                for (int y = gy * cellH; y < (gy + 1) * cellH - 1; y++) {
                    for (int x = gx * cellW; x < (gx + 1) * cellW - 1; x++) {
                        int idx = y * w + x;
                        
                        float dx = gray[idx + 1] - gray[idx];
                        float dy = gray[idx + w] - gray[idx];
                        float mag = (float) Math.sqrt(dx * dx + dy * dy);
                        float angle = (float) Math.atan2(dy, dx);
                        
                        if (mag > 0.05f) {
                            int bin = (int) ((angle + Math.PI) / (Math.PI / 2)) % bins;
                            hist[bin] += mag;
                        }
                    }
                }
                
                float sum = 0f;
                for (float v : hist) sum += v;
                if (sum > 0) {
                    for (int b = 0; b < bins; b++) {
                        out[outIdx + b] = hist[b] / sum;
                    }
                }
            }
        }
    }

    private static float[] l2Normalize(float[] vec) {
        float norm = 0f;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        
        if (norm < 1e-8f) return vec.clone();
        
        float[] normalized = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            normalized[i] = vec[i] / norm;
        }
        return normalized;
    }

    public static Bitmap cropFace(Bitmap original, FaceResult face, int marginPx) {
        if (original == null || face == null || face.boxNorm == null) return null;
        
        int w = original.getWidth(), h = original.getHeight();        float left = Math.max(0, face.boxNorm[0] * w - marginPx);
        float top = Math.max(0, face.boxNorm[1] * h - marginPx);
        float right = Math.min(w, face.boxNorm[2] * w + marginPx);
        float bottom = Math.min(h, face.boxNorm[3] * h + marginPx);
        
        int cropW = (int)(right - left), cropH = (int)(bottom - top);
        if (cropW <= 0 || cropH <= 0) return null;
        
        try {
            return Bitmap.createBitmap(original, (int)left, (int)top, cropW, cropH);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String toBase64(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap fromBase64(String base64) {
        if (base64 == null) return null;
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }
}