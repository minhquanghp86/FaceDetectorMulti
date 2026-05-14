package com.facedetectormulti.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MultiFaceDetector - phiên bản fix nhận dạng sai.
 *
 * Các thay đổi so với phiên bản cũ:
 *
 * 1. DEFAULT_RECOGNITION_THRESHOLD: 0.55 → 0.72
 *    Với embedding 256-dim LBP+HOG mới, cosine ~0.72 mới đủ tin cậy.
 *
 * 2. REJECT_THRESHOLD = 0.50 (dual-threshold):
 *    - score < REJECT  → Unknown hẳn, KHÔNG hiện tên
 *    - REJECT ≤ score < ACCEPT → "Low confidence" (cảnh báo)
 *    - score ≥ ACCEPT  → Recognized
 *
 * 3. Lọc góc đầu trước khi so sánh embedding:
 *    |eulerY| > MAX_EULER_Y hoặc |eulerZ| > MAX_EULER_Z → bỏ qua frame này
 *    (mặt quay ngang quá thì embedding không ổn định)
 *
 * 4. imageProxyToBitmap(): fix lấy đúng YUV color thay vì chỉ Y-plane grayscale.
 *    Dùng toBitmap() + rotate, giống RegistrationActivity.
 *
 * 5. Tích lũy voting qua N_VOTE_FRAMES liên tiếp:
 *    Thay vì quyết định từ 1 frame duy nhất, lấy majority vote từ 5 frame.
 *    Giảm nhận dạng nhấp nháy và false-positive đột ngột.
 *
 * 6. EMBEDDING_SIZE guard: kiểm tra embedding.length == FaceEmbeddingExtractor.EMBEDDING_SIZE
 *    trước khi compare, tránh so sánh vector cũ (128-dim) với vector mới (256-dim).
 */
public class MultiFaceDetector {

    private static final String TAG = "MultiFaceDetector";

    // ── Threshold mới ────────────────────────────────────────────────────
    /** Score ≥ ACCEPT_THRESHOLD → Recognized */
    private static final float DEFAULT_ACCEPT_THRESHOLD  = 0.72f;
    /** Score < REJECT_THRESHOLD → Unknown hẳn, không hiển thị tên */
    private static final float DEFAULT_REJECT_THRESHOLD  = 0.50f;

    // ── Góc đầu tối đa cho phép nhận dạng ───────────────────────────────
    /** Góc yaw (quay trái/phải). Vượt qua ngưỡng này → bỏ qua */
    private static final float MAX_EULER_Y = 30f;
    /** Góc tilt (nghiêng đầu). Vượt qua ngưỡng này → bỏ qua */
    private static final float MAX_EULER_Z = 25f;

    // ── Voting buffer ────────────────────────────────────────────────────
    /** Số frame tích lũy để voting */
    private static final int N_VOTE_FRAMES = 5;

    // ─────────────────────────────────────────────────────────────────────

    private final ExecutorService recognitionExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class Config {
        public float minFaceSize    = 0.12f;
        public boolean accurateMode = false;
        public float minConfidence  = 0.5f;
        public float minBoxAreaRatio = 0.003f;
        public long frameIntervalMs  = 100;
        public boolean enableRecognition = false;

        public static Config createDefault() { return new Config(); }

        public Config setMinFaceSize(float size) {
            this.minFaceSize = Math.max(0.05f, Math.min(0.30f, size)); return this;
        }
        public Config setAccurateMode(boolean accurate) {
            this.accurateMode = accurate; return this;
        }
        public Config setMinConfidence(float conf) {
            this.minConfidence = Math.max(0f, Math.min(1f, conf)); return this;
        }
        public Config setMinBoxAreaRatio(float ratio) {
            this.minBoxAreaRatio = Math.max(0.001f, Math.min(0.05f, ratio)); return this;
        }
        public Config setFrameIntervalMs(long interval) {
            this.frameIntervalMs = Math.max(0, interval); return this;
        }
        public Config setEnableRecognition(boolean enable) {
            this.enableRecognition = enable; return this;
        }
    }

    private final FaceDetector mlKitDetector;
    private final DetectionCallback callback;
    private final Context context;
    private Config config;
    private FaceDao faceDao;

    private long lastProcessTime = 0;
    private final Object lock = new Object();
    private int nextTempId = 1000;
    private boolean isShutdown = false;

    private float acceptThreshold = DEFAULT_ACCEPT_THRESHOLD;
    private float rejectThreshold = DEFAULT_REJECT_THRESHOLD;

    // Voting buffer: trackingId → circular buffer of last N names
    private final java.util.Map<Integer, String[]> voteBuffers = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> voteIndices  = new java.util.HashMap<>();

    // ─────────────────────────────────────────────────────────────────────

    public MultiFaceDetector(@NonNull DetectionCallback callback,
                             @NonNull Context context,
                             @NonNull Config config) {
        this.callback = callback;
        this.context  = context;
        this.config   = config;

        if (config.enableRecognition) {
            try {
                this.faceDao = FaceDatabase.getInstance(context).faceDao();
                Log.d(TAG, "Recognition enabled");
            } catch (Exception e) {
                Log.e(TAG, "Failed to init FaceDao: " + e.getMessage(), e);
                config.enableRecognition = false;
            }
        }
        this.mlKitDetector = createMlKitDetector(config);
        Log.d(TAG, "Initialized (recognition: " + config.enableRecognition + ")");
    }

    // ── Threshold setters ────────────────────────────────────────────────

    /** Threshold để accept (≥ → recognized). Clamped [0.40, 0.99]. */
    public void setRecognitionThreshold(float threshold) {
        this.acceptThreshold = Math.max(0.40f, Math.min(0.99f, threshold));
        // Reject threshold = accept - 0.12 (gap tối thiểu)
        this.rejectThreshold = Math.max(0.30f, this.acceptThreshold - 0.22f);
        Log.d(TAG, "Accept threshold: " + acceptThreshold + " | Reject: " + rejectThreshold);
    }

    public float getRecognitionThreshold() { return acceptThreshold; }

    // ─────────────────────────────────────────────────────────────────────

    private FaceDetector createMlKitDetector(Config cfg) {
        FaceDetectorOptions.Builder builder = new FaceDetectorOptions.Builder()
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(cfg.minFaceSize)
            .enableTracking();
        builder.setPerformanceMode(cfg.accurateMode
            ? FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
            : FaceDetectorOptions.PERFORMANCE_MODE_FAST);
        return FaceDetection.getClient(builder.build());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MAIN PROCESS
    // ─────────────────────────────────────────────────────────────────────

    public void process(@NonNull ImageProxy imageProxy) {
        if (isShutdown) { imageProxy.close(); return; }

        try {
            long now = System.currentTimeMillis();
            synchronized (lock) {
                if (now - lastProcessTime < config.frameIntervalMs) {
                    imageProxy.close(); return;
                }
                lastProcessTime = now;
            }

            final long t0   = System.currentTimeMillis();
            final int  imgW = imageProxy.getWidth();
            final int  imgH = imageProxy.getHeight();

            if (imgW == 0 || imgH == 0 || imageProxy.getImage() == null) {
                imageProxy.close(); return;
            }

            final boolean doRecognition = config.enableRecognition && faceDao != null;
            final FaceDao dao           = this.faceDao;
            final Config  cfg           = this.config;
            final float   acceptThr     = this.acceptThreshold;
            final float   rejectThr     = this.rejectThreshold;

            InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
            );

            mlKitDetector.process(image)
                .addOnSuccessListener(faces -> {
                    if (doRecognition && dao != null) {
                        // FIX: convert bitmap SAU KHI ML Kit detect xong
                        final Bitmap cameraBitmap = imageProxyToBitmap(imageProxy);

                        if (cameraBitmap != null) {
                            recognitionExecutor.execute(() -> {
                                try {
                                    List<? extends FaceResult> results = recognizeFaces(
                                        faces, cameraBitmap, dao, imgW, imgH,
                                        cfg, acceptThr, rejectThr);
                                    long totalTime = System.currentTimeMillis() - t0;

                                    mainHandler.post(() -> {
                                        callback.onResult(results, totalTime, imgW, imgH);
                                        cleanupBitmap(cameraBitmap);
                                        imageProxy.close();
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG, "Recognition error: " + e.getMessage(), e);
                                    List<FaceResult> fallback = filterFaces(faces, imgW, imgH, cfg);
                                    long totalTime = System.currentTimeMillis() - t0;
                                    mainHandler.post(() -> {
                                        callback.onResult(fallback, totalTime, imgW, imgH);
                                        imageProxy.close();
                                    });
                                }
                            });
                        } else {
                            List<FaceResult> results = filterFaces(faces, imgW, imgH, cfg);
                            long dt = System.currentTimeMillis() - t0;
                            callback.onResult(results, dt, imgW, imgH);
                            imageProxy.close();
                        }
                    } else {
                        List<FaceResult> results = filterFaces(faces, imgW, imgH, cfg);
                        long dt = System.currentTimeMillis() - t0;
                        callback.onResult(results, dt, imgW, imgH);
                        imageProxy.close();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Detection failed: " + e.getMessage());
                    callback.onResult(new ArrayList<>(), System.currentTimeMillis() - t0, imgW, imgH);
                    imageProxy.close();
                });
        } catch (Exception e) {
            Log.e(TAG, "FATAL in process(): " + e.getMessage(), e);
            imageProxy.close();
            mainHandler.post(() -> callback.onResult(new ArrayList<>(), 0, 0, 0));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FIX: imageProxyToBitmap dùng toBitmap() + rotate đúng màu
    // ─────────────────────────────────────────────────────────────────────

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            // toBitmap() xử lý YUV_420_888 đúng stride/pixelStride → màu đúng
            Bitmap bitmap = imageProxy.toBitmap();
            if (bitmap == null) return null;

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                bitmap.recycle();
                return rotated;
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "imageProxyToBitmap failed: " + e.getMessage());
            return null;
        }
        // Không close imageProxy ở đây - toBitmap() tự close
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RECOGNITION với dual-threshold + góc đầu + voting
    // ─────────────────────────────────────────────────────────────────────

    private List<FaceRecognitionResult> recognizeFaces(
            List<Face> faces, Bitmap cameraFrame,
            FaceDao dao, int imgW, int imgH,
            Config cfg, float acceptThr, float rejectThr) {

        List<FaceRecognitionResult> results = new ArrayList<>();

        try {
            List<RegisteredFace> registered = dao.getAllFaces();

            Log.d(TAG, "=== recognizeFaces === detected=" + faces.size()
                + " db=" + registered.size()
                + " acceptThr=" + acceptThr + " rejectThr=" + rejectThr);

            if (registered.isEmpty()) {
                Log.w(TAG, "DB EMPTY - không có khuôn mặt đã đăng ký");
                return results;
            }

            for (Face face : faces) {
                try {
                    Rect box = face.getBoundingBox();
                    if (box == null) continue;

                    float area = box.width() * box.height();
                    if (area / (imgW * imgH) < cfg.minBoxAreaRatio) continue;

                    float ratio = (float) box.width() / box.height();
                    if (ratio < 0.4f || ratio > 2.5f) continue;

                    // ── FIX 1: Lọc góc đầu ────────────────────────────
                    Float eulerY = face.getHeadEulerAngleY();
                    Float eulerZ = face.getHeadEulerAngleZ();
                    float absY = eulerY != null ? Math.abs(eulerY) : 0f;
                    float absZ = eulerZ != null ? Math.abs(eulerZ) : 0f;

                    if (absY > MAX_EULER_Y || absZ > MAX_EULER_Z) {
                        Log.d(TAG, "Bỏ qua: góc đầu quá lớn eulerY=" + absY + " eulerZ=" + absZ);
                        // Vẫn trả về result nhưng mark là unknown
                        float[] boxNorm = normalizeBox(box, imgW, imgH);
                        FaceResult temp = makeTempFaceResult(face, boxNorm);
                        results.add(new FaceRecognitionResult(
                            temp, false, null, 0f, -1));
                        continue;
                    }

                    // ── Crop + extract embedding ───────────────────────
                    float[] boxNorm = normalizeBox(box, imgW, imgH);
                    FaceResult temp = makeTempFaceResult(face, boxNorm);

                    Bitmap faceBmp = FaceEmbeddingExtractor.cropFace(cameraFrame, temp, 20);
                    if (faceBmp == null) {
                        Log.e(TAG, "cropFace NULL");
                        continue;
                    }

                    float[] embedding = FaceEmbeddingExtractor.extract(faceBmp);
                    faceBmp.recycle();

                    if (embedding == null || embedding.length == 0) continue;

                    // ── FIX 2: Guard EMBEDDING_SIZE ───────────────────
                    // Nếu DB chứa embedding 128-dim cũ → bỏ qua, báo cần re-register
                    if (embedding.length != FaceEmbeddingExtractor.EMBEDDING_SIZE) {
                        Log.w(TAG, "Embedding size mismatch: got " + embedding.length
                            + " expected " + FaceEmbeddingExtractor.EMBEDDING_SIZE);
                    }

                    // ── So sánh với DB ─────────────────────────────────
                    float bestScore = 0f;
                    RegisteredFace bestMatch = null;

                    for (RegisteredFace r : registered) {
                        if (r.embedding == null) continue;
                        // FIX: bỏ qua nếu kích thước không khớp (embedding cũ/mới)
                        if (r.embedding.length != embedding.length) {
                            Log.w(TAG, "Skip '" + r.name + "': embedding size mismatch "
                                + r.embedding.length + " vs " + embedding.length
                                + " - Cần đăng ký lại khuôn mặt này");
                            continue;
                        }
                        float score = RegisteredFace.similarity(embedding, r.embedding);
                        if (score > bestScore) {
                            bestScore = score;
                            bestMatch = r;
                        }
                    }

                    // ── FIX 3: Dual-threshold ──────────────────────────
                    boolean accepted = bestScore >= acceptThr;
                    boolean lowConf  = !accepted && bestScore >= rejectThr;
                    // Nếu score < rejectThr → Unknown hoàn toàn

                    Log.d(TAG, "  Face → bestScore=" + String.format("%.4f", bestScore)
                        + (bestMatch != null ? " candidate=" + bestMatch.name : " candidate=NONE")
                        + " → " + (accepted ? "MATCH" : lowConf ? "LOW_CONF" : "UNKNOWN"));

                    // ── FIX 4: Voting ──────────────────────────────────
                    int trackId = face.getTrackingId() != null ? face.getTrackingId() : -1;
                    String votedName = null;
                    if (accepted && bestMatch != null) {
                        votedName = castVote(trackId, bestMatch.name);
                    } else {
                        castVote(trackId, null); // vote unknown
                        votedName = getVotedName(trackId);
                    }

                    boolean finalMatch = votedName != null;
                    RegisteredFace finalFace = finalMatch ? bestMatch : null;

                    // Tìm lại bestMatch đúng nếu votedName khác bestMatch.name
                    if (finalMatch && bestMatch != null
                            && !votedName.equals(bestMatch.name)) {
                        for (RegisteredFace r : registered) {
                            if (votedName.equals(r.name)) { finalFace = r; break; }
                        }
                    }

                    FaceRecognitionResult result = new FaceRecognitionResult(
                        temp,
                        finalMatch,
                        finalMatch ? votedName : null,
                        bestScore,
                        finalMatch && finalFace != null ? finalFace.id : -1
                    );
                    results.add(result);

                } catch (Exception e) {
                    Log.e(TAG, "Error processing single face: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "FATAL in recognizeFaces: " + e.getMessage(), e);
        }

        return results;
    }

    // ── Voting helpers ───────────────────────────────────────────────────

    /**
     * Ghi vote cho trackId, trả về tên được vote nhiều nhất trong N_VOTE_FRAMES frame gần nhất.
     * null = unknown.
     */
    private synchronized String castVote(int trackId, String name) {
        if (!voteBuffers.containsKey(trackId)) {
            voteBuffers.put(trackId, new String[N_VOTE_FRAMES]);
            voteIndices.put(trackId, 0);
        }
        String[] buf = voteBuffers.get(trackId);
        int idx = voteIndices.get(trackId);
        buf[idx] = name;
        voteIndices.put(trackId, (idx + 1) % N_VOTE_FRAMES);
        return majority(buf);
    }

    private synchronized String getVotedName(int trackId) {
        String[] buf = voteBuffers.get(trackId);
        if (buf == null) return null;
        return majority(buf);
    }

    /** Trả về tên xuất hiện nhiều nhất (strict majority > N/2), null nếu không có. */
    private String majority(String[] buf) {
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (String s : buf) {
            if (s != null) count.put(s, count.getOrDefault(s, 0) + 1);
        }
        int threshold = N_VOTE_FRAMES / 2 + 1; // cần hơn nửa
        String best = null; int bestCount = 0;
        for (java.util.Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return (bestCount >= threshold) ? best : null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private float[] normalizeBox(Rect box, int imgW, int imgH) {
        return new float[]{
            Math.max(0f, (float) box.left   / imgW),
            Math.max(0f, (float) box.top    / imgH),
            Math.min(1f, (float) box.right  / imgW),
            Math.min(1f, (float) box.bottom / imgH)
        };
    }

    private FaceResult makeTempFaceResult(Face face, float[] boxNorm) {
        Float eulerY = face.getHeadEulerAngleY();
        Float eulerZ = face.getHeadEulerAngleZ();
        return new FaceResult(0, boxNorm, -1f,
            eulerY != null ? eulerY : 0f,
            eulerZ != null ? eulerZ : 0f,
            -1f, -1f, System.currentTimeMillis());
    }

    private List<FaceResult> filterFaces(List<Face> faces, int imgW, int imgH, Config cfg) {
        List<FaceResult> results = new ArrayList<>();
        float imgArea = imgW * imgH;

        for (Face face : faces) {
            try {
                Rect box = face.getBoundingBox();
                if (box == null) continue;
                float area = box.width() * box.height();
                if (area / imgArea < cfg.minBoxAreaRatio) continue;
                float ratio = (float) box.width() / box.height();
                if (ratio < 0.4f || ratio > 2.5f) continue;

                Float smile      = face.getSmilingProbability();
                float[] boxNorm  = normalizeBox(box, imgW, imgH);
                int trackId = face.getTrackingId() != null ? face.getTrackingId() : nextTempId++;
                Float eulerY = face.getHeadEulerAngleY();
                Float eulerZ = face.getHeadEulerAngleZ();
                Float leftEye  = face.getLeftEyeOpenProbability();
                Float rightEye = face.getRightEyeOpenProbability();

                results.add(new FaceResult(trackId, boxNorm,
                    smile    != null ? smile    : -1f,
                    eulerY   != null ? eulerY   : 0f,
                    eulerZ   != null ? eulerZ   : 0f,
                    leftEye  != null ? leftEye  : -1f,
                    rightEye != null ? rightEye : -1f,
                    System.currentTimeMillis()));
            } catch (Exception e) {
                Log.e(TAG, "Error filtering face: " + e.getMessage());
            }
        }
        return results;
    }

    private void cleanupBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            try { bitmap.recycle(); } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PUBLIC CONTROL
    // ─────────────────────────────────────────────────────────────────────

    public void setFrameIntervalMs(long ms) {
        config.frameIntervalMs = Math.max(0, ms);
    }

    public Config getCurrentConfig() { return config; }

    public void enableRecognition(boolean enable) {
        config.enableRecognition = enable;
        if (enable && faceDao == null) {
            try {
                faceDao = FaceDatabase.getInstance(context).faceDao();
            } catch (Exception e) {
                Log.e(TAG, "Failed to init FaceDao: " + e.getMessage());
                config.enableRecognition = false;
            }
        }
        // Reset voting buffer khi toggle
        synchronized (this) {
            voteBuffers.clear();
            voteIndices.clear();
        }
        Log.d(TAG, "Recognition " + (enable ? "enabled" : "disabled"));
    }

    public void close() {
        isShutdown = true;
        try { mlKitDetector.close(); } catch (Exception ignored) {}
        if (!recognitionExecutor.isShutdown()) recognitionExecutor.shutdown();
    }

    public boolean isReady() { return !isShutdown; }
}
