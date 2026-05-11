# FaceDetectorMulti

Android app phát hiện **nhiều khuôn mặt** cùng lúc bằng Google ML Kit + CameraX.  
Tách riêng từ [face-servo-tracker](https://github.com/minhquanghp86/face-servo-tracker) — chỉ tập trung vào **phát hiện**, không điều khiển servo.

## Tính năng

- Phát hiện nhiều khuôn mặt đồng thời (không giới hạn số lượng)
- Bounding box màu sắc riêng cho từng khuôn mặt
- Tracking ID bền vững qua các frame (ML Kit built-in)
- Hiển thị xác suất đang cười, hướng đầu (eulerY)
- HUD: số khuôn mặt + thời gian xử lý mỗi frame (ms)
- Build APK tự động qua GitHub Actions

## Cấu trúc

```
FaceDetectorMulti/
├── .github/workflows/build.yml      # CI/CD
├── app/src/main/
│   ├── java/com/facedetectormulti/
│   │   ├── detection/
│   │   │   ├── FaceResult.java          # data class 1 khuôn mặt
│   │   │   ├── DetectionResult.java     # kết quả 1 frame
│   │   │   └── MultiFaceDetector.java   # core: ML Kit wrapper
│   │   └── ui/
│   │       ├── FaceOverlayView.java     # custom view vẽ overlay
│   │       └── MainActivity.java        # CameraX + ghép lại
│   └── res/layout/activity_main.xml
├── app/build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Stack kỹ thuật

| Thư viện | Version | Vai trò |
|---|---|---|
| ML Kit face-detection | 16.1.5 | Phát hiện khuôn mặt |
| CameraX | 1.3.1 | Camera preview + frame capture |
| Android Gradle Plugin | 8.1.0 | Build |
| Gradle | 8.2 | Build runner (GitHub Actions) |
| minSdk | 26 | Android 8.0+ |

## Tạo repo và push lên GitHub

```bash
# 1. Tạo repo mới trên GitHub (tên: FaceDetectorMulti, public/private tuỳ ý)

# 2. Clone về hoặc init local
git init FaceDetectorMulti
cd FaceDetectorMulti

# 3. Copy toàn bộ file vào thư mục này

# 4. Commit và push
git add .
git commit -m "init: multi-face detection app"
git branch -M main
git remote add origin https://github.com/<username>/FaceDetectorMulti.git
git push -u origin main
```

GitHub Actions sẽ tự build APK sau khi push.  
APK tải về tại tab **Actions → Build APK → Artifacts**.

## Mở rộng tiếp theo

- [ ] Thêm face recognition (nhận dạng từng người)
- [ ] Export danh sách khuôn mặt qua USB Serial về ESP32
- [ ] Đếm người trong khung hình theo thời gian thực
