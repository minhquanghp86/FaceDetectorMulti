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
            Log.e(TAG, "extract: bitmap is null or recycled!");
            return new float[EMBEDDING_SIZE];
        }
        
        Bitmap processed = null;
        try {
            processed = preprocessFace(faceBitmap);
            if (processed == null || processed.isRecycled()) {
                Log.e(TAG, "preprocessFace failed!");
                return new float[EMBEDDING_SIZE];
            }
            
            float[] embedding = extractFeatures(processed);
            if (embedding == null) {
                Log.e(TAG, "extractFeatures returned null!");
                return new float[EMBEDDING_SIZE];
            }
            
            float[] normalized = l2Normalize(embedding);
            return normalized;
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed: " + e.getMessage(), e);
            return new float[EMBEDDING_SIZE];
        } finally {
            // Cleanup bitmap
            if (processed != null && !processed.isRecycled() && processed != faceBitmap) {
                try {
                    processed.recycle();
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to recycle bitmap: " + ex.getMessage());
                }
            }
        }
    }

    private static Bitmap preprocessFace(Bitmap input) {
        if (input == null || input.isRecycled()) {
            Log.e(TAG, "preprocessFace: input is null or recycled!");
            return null;
        }
        
        try {
            // Resize về kích thước chuẩn
            Bitmap resized = Bitmap.createScaledBitmap(input, FACE_SIZE, FACE_SIZE, true);
            
            int[] pixels = new int[FACE_SIZE * FACE_SIZE];
            byte[] gray = new byte[FACE_SIZE * FACE_SIZE];
            
            resized.getPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
            
            // Chuyển sang grayscale
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                gray[i] = (byte) (0.299f * r + 0.587f * g + 0.114f * b);
            }
            
            // Cân bằng histogram
            equalizeHistogram(gray, FACE_SIZE, FACE_SIZE);
            
            // Tạo bitmap kết quả
            Bitmap result = Bitmap.createBitmap(FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888);
            for (int i = 0; i < pixels.length; i++) {
                int g = gray[i] & 0xFF;
                pixels[i] = Color.argb(255, g, g, g);
            }
            result.setPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);
            
            // Giải phóng bitmap resize nếu khác input
            if (resized != input && !resized.isRecycled()) {
                resized.recycle();
            }
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "preprocessFace failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static void equalizeHistogram(byte[] gray, int w, int h) {
        if (gray == null || gray.length == 0) return;
        
        try {
            int[] hist = new int[256];
            for (byte b : gray) {
                hist[b & 0xFF]++;
            }
            
            float[] cdf = new float[256];
            cdf[0] = hist[0];
            for (int i = 1; i < 256; i++) {
                cdf[i] = cdf[i-1] + hist[i];
            }
            
            float total = w * h;
            float minCdf = cdf[0];
            
            for (int i = 0; i < gray.length; i++) {
                int val = gray[i] & 0xFF;
                float eq = (cdf[val] - minCdf) / (total - hist[0]) * 255f;
                gray[i] = (byte) Math.max(0, Math.min(255, (int) eq));
            }
        } catch (Exception e) {
            Log.e(TAG, "equalizeHistogram failed: " + e.getMessage());
        }
    }

    private static float[] extractFeatures(Bitmap face) {
        if (face == null || face.isRecycled()) {
            Log.e(TAG, "extractFeatures: face is null or recycled!");
            return new float[EMBEDDING_SIZE];
        }
        
        try {
            float[] features = new float[EMBEDDING_SIZE];
            int w = face.getWidth();
            int h = face.getHeight();
            
            if (w == 0 || h == 0) {
                Log.e(TAG, "Face has zero dimensions!");
                return features;
            }
            
            int[] pixels = new int[w * h];
            face.getPixels(pixels, 0, w, 0, 0, w, h);
            
            float[] gray = new float[w * h];
            for (int i = 0; i < pixels.length; i++) {
                gray[i] = (Color.red(pixels[i]) + Color.green(pixels[i]) + Color.blue(pixels[i])) / 3f / 255f;
            }
            
            extractSimpleLBP(gray, w, h, features, 0, 64);
            extractSimpleGradient(gray, w, h, features, 64, 64);
            
            return features;
        } catch (Exception e) {
            Log.e(TAG, "extractFeatures failed: " + e.getMessage(), e);
            return new float[EMBEDDING_SIZE];
        }
    }

    private static void extractSimpleLBP(float[] gray, int w, int h, float[] out, int offset, int dims) {
        if (gray == null || out == null) return;
        
        try {
            int grid = 4;
            int cellW = w / grid;
            int cellH = h / grid;
            
            if (cellW == 0 || cellH == 0) return;
            
            int dimsPerCell = dims / (grid * grid);
            if (dimsPerCell == 0) return;
            
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
                            int pattern = 0;
                            int[] dx = {1, 0, -1, 0};
                            int[] dy = {0, 1, 0, -1};
                            
                            for (int n = 0; n < 4; n++) {
                                int nx = Math.max(0, Math.min(w-1, (gx * cellW) + sx + dx[n]));
                                int ny = Math.max(0, Math.min(h-1, (gy * cellH) + sy + dy[n]));
                                int nidx = ny * w + nx;
                                
                                if (gray[nidx] >= center) {
                                    pattern |= (1 << n);
                                }
                            }
                            out[outIdx + s] = pattern / 15f;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "extractSimpleLBP failed: " + e.getMessage());
        }
    }

    private static void extractSimpleGradient(float[] gray, int w, int h, float[] out, int offset, int dims) {
        if (gray == null || out == null) return;
        
        try {
            int grid = 4;
            int cellW = w / grid;
            int cellH = h / grid;
            
            if (cellW == 0 || cellH == 0) return;
            
            int bins = dims / (grid * grid);
            if (bins == 0) return;
            
            for (int gy = 0; gy < grid; gy++) {
                for (int gx = 0; gx < grid; gx++) {
                    int outIdx = offset + (gy * grid + gx) * bins;
                    float[] hist = new float[bins];
                    
                    for (int y = gy * cellH; y < (gy + 1) * cellH - 1 && y < h - 1; y++) {
                        for (int x = gx * cellW; x < (gx + 1) * cellW - 1 && x < w - 1; x++) {
                            int idx = y * w + x;
                            float dx = gray[idx + 1] - gray[idx];
                            float dy = gray[idx + w] - gray[idx];
                            float mag = (float) Math.sqrt(dx * dx + dy * dy);
                            float angle = (float) Math.atan2(dy, dx);
                            
                            if (mag > 0.05f) {
                                int bin = (int) ((angle + Math.PI) / (Math.PI / 2)) % bins;
                                if (bin >= 0 && bin < bins) {
                                    hist[bin] += mag;
                                }
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
        } catch (Exception e) {
            Log.e(TAG, "extractSimpleGradient failed: " + e.getMessage());
        }
    }

    private static float[] l2Normalize(float[] vec) {
        if (vec == null) return new float[EMBEDDING_SIZE];
        
        try {
            float norm = 0f;
            for (float v : vec) {
                norm += v * v;
            }
            norm = (float) Math.sqrt(norm);
            
            if (norm < 1e-8f) {
                return vec.clone();
            }
            
            float[] normalized = new float[vec.length];
            for (int i = 0; i < vec.length; i++) {
                normalized[i] = vec[i] / norm;
            }
            return normalized;
        } catch (Exception e) {
            Log.e(TAG, "l2Normalize failed: " + e.getMessage());
            return vec.clone();
        }
    }

    public static Bitmap cropFace(Bitmap original, FaceResult face, int marginPx) {
        if (original == null || original.isRecycled() || face == null || face.boxNorm == null) {
            Log.e(TAG, "cropFace: invalid input");
            return null;
        }
        
        try {
            int w = original.getWidth();
            int h = original.getHeight();
            
            float left = Math.max(0, face.boxNorm[0] * w - marginPx);
            float top = Math.max(0, face.boxNorm[1] * h - marginPx);
            float right = Math.min(w, face.boxNorm[2] * w + marginPx);
            float bottom = Math.min(h, face.boxNorm[3] * h + marginPx);
            
            int cropW = (int)(right - left);
            int cropH = (int)(bottom - top);
            
            if (cropW <= 0 || cropH <= 0) {
                Log.e(TAG, "cropFace: invalid dimensions " + cropW + "x" + cropH);
                return null;
            }
            
            return Bitmap.createBitmap(original, (int)left, (int)top, cropW, cropH);
        } catch (Exception e) {
            Log.e(TAG, "cropFace failed: " + e.getMessage());
            return null;
        }
    }

    public static String toBase64(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "toBase64 failed: " + e.getMessage());
            return null;
        }
    }

    public static Bitmap fromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            Log.e(TAG, "fromBase64 failed: " + e.getMessage());
            return null;
        }
    }
}
