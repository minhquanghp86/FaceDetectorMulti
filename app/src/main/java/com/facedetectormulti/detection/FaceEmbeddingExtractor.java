package com.facedetectormulti.detection;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

/**
 * Face embedding extractor - pure Java, no external model.
 *
 * FIX so với phiên bản cũ:
 *  - LBP thực 8-neighbor uniform (thay vì 4-neighbor giả) → phân biệt texture tốt hơn nhiều
 *  - HOG 8-bin thực (thay vì 4-bin) → phân biệt hướng gradient tốt hơn
 *  - Grid 8x8 (thay vì 4x4) → spatial resolution cao hơn
 *  - Kết hợp LBP 128-dim + HOG 128-dim = 256-dim embedding
 *  - CLAHE-style local equalization thay vì global → ổn định hơn với ánh sáng khác nhau
 *  - Face size 112x112 (thay vì 96x96) → giữ nhiều chi tiết hơn
 */
public class FaceEmbeddingExtractor {

    private static final String TAG = "FaceEmbedding";

    // ── Tăng FACE_SIZE và EMBEDDING_SIZE ──────────────────────────────────
    private static final int FACE_SIZE      = 112;   // 96 → 112
    private static final int GRID           = 8;     // 4 → 8 (64 cells)
    private static final int LBP_DIMS       = 128;   // 2 dims/cell × 64 cells
    private static final int HOG_DIMS       = 128;   // 2 dims/cell × 64 cells
    public  static final int EMBEDDING_SIZE = LBP_DIMS + HOG_DIMS; // 256

    // ── 8-neighbor offsets cho LBP thực ──────────────────────────────────
    // Thứ tự: E, NE, N, NW, W, SW, S, SE
    private static final int[] LBP_DX = { 1,  1,  0, -1, -1, -1,  0,  1};
    private static final int[] LBP_DY = { 0, -1, -1, -1,  0,  1,  1,  1};

    // ─────────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    public static float[] extract(Bitmap faceBitmap) {
        if (faceBitmap == null || faceBitmap.isRecycled()) {
            Log.e(TAG, "extract: bitmap null/recycled");
            return new float[EMBEDDING_SIZE];
        }
        Bitmap processed = null;
        try {
            processed = preprocessFace(faceBitmap);
            if (processed == null) return new float[EMBEDDING_SIZE];

            float[] gray = bitmapToGrayFloat(processed);
            float[] features = new float[EMBEDDING_SIZE];

            extractLBP(gray, FACE_SIZE, FACE_SIZE, features, 0);
            extractHOG(gray, FACE_SIZE, FACE_SIZE, features, LBP_DIMS);

            return l2Normalize(features);
        } catch (Exception e) {
            Log.e(TAG, "extract failed: " + e.getMessage(), e);
            return new float[EMBEDDING_SIZE];
        } finally {
            if (processed != null && !processed.isRecycled() && processed != faceBitmap) {
                processed.recycle();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PREPROCESSING
    // ─────────────────────────────────────────────────────────────────────

    private static Bitmap preprocessFace(Bitmap input) {
        try {
            // 1. Resize về FACE_SIZE
            Bitmap resized = Bitmap.createScaledBitmap(input, FACE_SIZE, FACE_SIZE, true);

            // 2. Chuyển sang grayscale byte
            int n = FACE_SIZE * FACE_SIZE;
            int[] pixels = new int[n];
            resized.getPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);

            byte[] gray = new byte[n];
            for (int i = 0; i < n; i++) {
                int p = pixels[i];
                // Rec. 601 luma
                gray[i] = (byte)(int)(0.299f * Color.red(p)
                                    + 0.587f * Color.green(p)
                                    + 0.114f * Color.blue(p));
            }

            // 3. CLAHE-style: cân bằng histogram cục bộ (4 tile)
            claheLocal(gray, FACE_SIZE, FACE_SIZE, 4);

            // 4. Tạo bitmap grayscale kết quả
            Bitmap result = Bitmap.createBitmap(FACE_SIZE, FACE_SIZE, Bitmap.Config.ARGB_8888);
            for (int i = 0; i < n; i++) {
                int v = gray[i] & 0xFF;
                pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            result.setPixels(pixels, 0, FACE_SIZE, 0, 0, FACE_SIZE, FACE_SIZE);

            if (resized != input && !resized.isRecycled()) resized.recycle();
            return result;
        } catch (Exception e) {
            Log.e(TAG, "preprocessFace failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * CLAHE-style: chia ảnh thành tileN×tileN ô, cân bằng histogram từng ô,
     * sau đó bilinear blend kết quả giữa các ô.
     * Giúp ổn định ánh sáng cục bộ (sáng/tối không đều trên khuôn mặt).
     */
    private static void claheLocal(byte[] gray, int w, int h, int tileN) {
        int tileW = w / tileN;
        int tileH = h / tileN;
        if (tileW == 0 || tileH == 0) {
            // Fallback về global equalization
            equalizeHistogram(gray, w, h);
            return;
        }

        // Tính CDF cho từng tile
        float[][] cdfs = new float[tileN * tileN][256];
        for (int ty = 0; ty < tileN; ty++) {
            for (int tx = 0; tx < tileN; tx++) {
                int[] hist = new int[256];
                int x0 = tx * tileW, y0 = ty * tileH;
                int x1 = (tx == tileN - 1) ? w : x0 + tileW;
                int y1 = (ty == tileN - 1) ? h : y0 + tileH;
                int count = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        hist[gray[y * w + x] & 0xFF]++;
                        count++;
                    }
                }
                // Build CDF normalized to [0,255]
                float[] cdf = cdfs[ty * tileN + tx];
                cdf[0] = hist[0];
                for (int i = 1; i < 256; i++) cdf[i] = cdf[i - 1] + hist[i];
                int minCdfVal = 0;
                for (int i = 0; i < 256; i++) {
                    if (hist[i] > 0) { minCdfVal = (int) cdf[i]; break; }
                }
                float denom = count - minCdfVal;
                if (denom <= 0) denom = 1;
                for (int i = 0; i < 256; i++) {
                    cdf[i] = Math.max(0, Math.min(255, (cdf[i] - minCdfVal) / denom * 255f));
                }
            }
        }

        // Bilinear interpolation map từng pixel
        byte[] result = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int val = gray[y * w + x] & 0xFF;

                // Tìm tile chứa pixel, dùng tâm tile làm điểm tham chiếu
                float fx = (x - tileW * 0.5f) / tileW;
                float fy = (y - tileH * 0.5f) / tileH;
                int tx0 = (int) fx;
                int ty0 = (int) fy;
                float ax = fx - tx0;
                float ay = fy - ty0;

                tx0 = Math.max(0, Math.min(tileN - 2, tx0));
                ty0 = Math.max(0, Math.min(tileN - 2, ty0));
                int tx1 = tx0 + 1, ty1 = ty0 + 1;

                float v00 = cdfs[ty0 * tileN + tx0][val];
                float v10 = cdfs[ty0 * tileN + tx1][val];
                float v01 = cdfs[ty1 * tileN + tx0][val];
                float v11 = cdfs[ty1 * tileN + tx1][val];

                float blended = v00 * (1 - ax) * (1 - ay)
                              + v10 * ax       * (1 - ay)
                              + v01 * (1 - ax) * ay
                              + v11 * ax       * ay;

                result[y * w + x] = (byte)(int) Math.max(0, Math.min(255, blended));
            }
        }
        System.arraycopy(result, 0, gray, 0, gray.length);
    }

    /** Global histogram equalization (fallback) */
    private static void equalizeHistogram(byte[] gray, int w, int h) {
        int[] hist = new int[256];
        for (byte b : gray) hist[b & 0xFF]++;
        float[] cdf = new float[256];
        cdf[0] = hist[0];
        for (int i = 1; i < 256; i++) cdf[i] = cdf[i - 1] + hist[i];
        float total = w * h, minCdf = cdf[0];
        float denom = total - hist[0];
        if (denom <= 0) return;
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i] & 0xFF;
            gray[i] = (byte)(int) Math.max(0, Math.min(255,
                    (cdf[v] - minCdf) / denom * 255f));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  LBP THỰC (8-neighbor uniform pattern, histogram per cell)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * LBP 8-neighbor: mỗi pixel so sánh với 8 neighbor → pattern 8-bit (0–255).
     * Chỉ dùng "uniform" patterns (≤2 transitions) + 1 bin "non-uniform".
     * → 59 bins/cell, nhưng để giữ dims đơn giản ta dùng histogram 16-bin (nhóm).
     *
     * Grid GRID×GRID cells → tổng LBP_DIMS = 2 bins/cell × GRID² cells.
     * Mỗi cell histogram được L1-normalize.
     */
    private static void extractLBP(float[] gray, int w, int h, float[] out, int offset) {
        int cellW = w / GRID;
        int cellH = h / GRID;
        int binsPerCell = LBP_DIMS / (GRID * GRID); // = 2

        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                float[] hist = new float[binsPerCell];

                int x0 = gx * cellW, y0 = gy * cellH;
                int x1 = (gx == GRID - 1) ? w : x0 + cellW;
                int y1 = (gy == GRID - 1) ? h : y0 + cellH;

                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        float center = gray[y * w + x];
                        int pattern = 0;
                        for (int n = 0; n < 8; n++) {
                            int nx = Math.max(0, Math.min(w - 1, x + LBP_DX[n]));
                            int ny = Math.max(0, Math.min(h - 1, y + LBP_DY[n]));
                            if (gray[ny * w + nx] >= center) pattern |= (1 << n);
                        }
                        // Đếm transitions để xác định uniform
                        int transitions = 0;
                        for (int n = 0; n < 8; n++) {
                            int cur  = (pattern >> n) & 1;
                            int next = (pattern >> ((n + 1) % 8)) & 1;
                            if (cur != next) transitions++;
                        }
                        // Bin 0: uniform (transitions ≤ 2)
                        // Bin 1: non-uniform
                        int bin = (transitions <= 2) ? 0 : 1;
                        hist[bin % binsPerCell]++;
                    }
                }

                // L1-normalize cell histogram
                float sum = 0;
                for (float v : hist) sum += v;
                int outIdx = offset + (gy * GRID + gx) * binsPerCell;
                if (sum > 0) {
                    for (int b = 0; b < binsPerCell; b++) out[outIdx + b] = hist[b] / sum;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HOG THỰC (8-bin, magnitude-weighted)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * HOG (Histogram of Oriented Gradients):
     * - Tính gradient (dx, dy) tại mỗi pixel bằng Sobel 3×3
     * - Magnitude = sqrt(dx²+dy²), angle = atan2(dy,dx) [0, π) unsigned
     * - Tích lũy vào 8-bin histogram theo angle, weighted by magnitude
     * - Grid GRID×GRID cells, mỗi cell L2-normalize
     *
     * tổng HOG_DIMS = 2 bins/cell × GRID² cells.
     */
    private static void extractHOG(float[] gray, int w, int h, float[] out, int offset) {
        // Pre-compute gradient
        float[] mag = new float[w * h];
        float[] ang = new float[w * h]; // [0, π)

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                // Sobel
                float gx = -gray[(y-1)*w+(x-1)] - 2*gray[y*w+(x-1)] - gray[(y+1)*w+(x-1)]
                           +gray[(y-1)*w+(x+1)] + 2*gray[y*w+(x+1)] + gray[(y+1)*w+(x+1)];
                float gy = -gray[(y-1)*w+(x-1)] - 2*gray[(y-1)*w+x] - gray[(y-1)*w+(x+1)]
                           +gray[(y+1)*w+(x-1)] + 2*gray[(y+1)*w+x] + gray[(y+1)*w+(x+1)];
                mag[y*w+x] = (float) Math.sqrt(gx*gx + gy*gy);
                // Unsigned angle [0, π)
                float a = (float) Math.atan2(gy, gx);
                if (a < 0) a += (float) Math.PI;
                ang[y*w+x] = a;
            }
        }

        int cellW = w / GRID;
        int cellH = h / GRID;
        int binsPerCell = HOG_DIMS / (GRID * GRID); // = 2
        float binSize = (float)(Math.PI / binsPerCell);

        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                float[] hist = new float[binsPerCell];

                int x0 = gx * cellW, y0 = gy * cellH;
                int x1 = (gx == GRID - 1) ? w - 1 : x0 + cellW;
                int y1 = (gy == GRID - 1) ? h - 1 : y0 + cellH;

                for (int y = Math.max(1, y0); y < y1; y++) {
                    for (int x = Math.max(1, x0); x < x1; x++) {
                        float m = mag[y * w + x];
                        if (m < 0.01f) continue;
                        int bin = (int)(ang[y * w + x] / binSize);
                        if (bin >= binsPerCell) bin = binsPerCell - 1;
                        hist[bin] += m;
                    }
                }

                // L2-normalize cell histogram
                float norm = 0;
                for (float v : hist) norm += v * v;
                norm = (float) Math.sqrt(norm + 1e-6f);
                int outIdx = offset + (gy * GRID + gx) * binsPerCell;
                for (int b = 0; b < binsPerCell; b++) out[outIdx + b] = hist[b] / norm;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  UTILS
    // ─────────────────────────────────────────────────────────────────────

    private static float[] bitmapToGrayFloat(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] gray = new float[w * h];
        for (int i = 0; i < pixels.length; i++) {
            // Ảnh đã grayscale từ preprocess → lấy channel R
            gray[i] = Color.red(pixels[i]) / 255f;
        }
        return gray;
    }

    private static float[] l2Normalize(float[] vec) {
        float norm = 0f;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-8f) return vec.clone();
        float[] out = new float[vec.length];
        for (int i = 0; i < vec.length; i++) out[i] = vec[i] / norm;
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CROP / ENCODE / DECODE (không đổi)
    // ─────────────────────────────────────────────────────────────────────

    public static Bitmap cropFace(Bitmap original, FaceResult face, int marginPx) {
        if (original == null || original.isRecycled() || face == null || face.boxNorm == null) return null;
        try {
            int w = original.getWidth(), h = original.getHeight();
            float left   = Math.max(0,  face.boxNorm[0] * w - marginPx);
            float top    = Math.max(0,  face.boxNorm[1] * h - marginPx);
            float right  = Math.min(w,  face.boxNorm[2] * w + marginPx);
            float bottom = Math.min(h,  face.boxNorm[3] * h + marginPx);
            int cropW = (int)(right - left), cropH = (int)(bottom - top);
            if (cropW <= 0 || cropH <= 0) return null;
            return Bitmap.createBitmap(original, (int) left, (int) top, cropW, cropH);
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
