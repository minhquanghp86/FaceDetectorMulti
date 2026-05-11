# FaceDetectorMulti 🎯

Android app phát hiện **nhiều khuôn mặt** cùng lúc bằng Google ML Kit + CameraX.

![Demo GIF](https://via.placeholder.com/300x500/000000/FFFFFF?text=FaceDetector+Demo)
*Demo: Phát hiện đa khuôn mặt với tracking ID bền vững*

## ✨ Tính năng

- ✅ Phát hiện nhiều khuôn mặt đồng thời (không giới hạn)
- ✅ Bounding box màu sắc riêng per tracking ID
- ✅ Tracking ID bền vững qua các frame (ML Kit + fallback)
- ✅ Hiển thị smile probability & head euler angle
- ✅ HUD realtime: face count, FPS, processing time
- ✅ **Camera switching**: Chuyển đổi front/back camera
- ✅ Performance throttling: Điều chỉnh tần suất xử lý
- ✅ Build APK tự động qua GitHub Actions

## 🚀 Cài đặt nhanh

```bash
# Clone repo
git clone https://github.com/minhquanghp86/FaceDetectorMulti.git
cd FaceDetectorMulti

# Mở bằng Android Studio hoặc build CLI
./gradlew assembleDebug

# APK tại: app/build/outputs/apk/debug/app-debug.apk
```

## ⚙️ Cấu hình Performance

Điều chỉnh qua `SharedPreferences` hoặc code:

```java
// Trong MainActivity hoặc qua Settings UI
detector.setFrameIntervalMs(100); // 10 FPS max (100ms/frame)
// Giá trị khuyến nghị:
// - Device mạnh: 50ms (20 FPS)
// - Device trung bình: 100ms (10 FPS)  
// - Device cũ: 200ms (5 FPS)
```

## 📊 Benchmark tham khảo

| Device | Resolution | Avg Time/Frame | FPS | Notes |
|--------|-----------|----------------|-----|-------|
| Pixel 6 | 1920x1080 | 45ms | ~22 | ML Kit fast mode |
| Samsung A52 | 1280x720 | 78ms | ~13 | Good balance |
| Xiaomi Redmi 9 | 720p | 156ms | ~6 | Cần throttle |

## 🔧 Troubleshooting

### Camera không khởi động?
```bash
adb logcat | grep FaceDetectorMulti
# Kiểm tra permission và camera hardware availability
```

### Performance chậm?
- Giảm `minFaceSize` trong `MultiFaceDetector` (default: 0.1f)
- Tăng `minFrameIntervalMs` để giảm tần suất xử lý
- Dùng `PERFORMANCE_MODE_FAST` (đã mặc định)

### Tracking ID bị nhảy?
- Đảm bảo `enableTracking()` trong FaceDetectorOptions
- Fallback mechanism sẽ giữ ID ổn định khi ML Kit trả null

## 🗺️ Roadmap

- [ ] Face Recognition (nhận dạng người cụ thể)
- [ ] Export dữ liệu qua USB Serial → ESP32 *(coming soon)*
- [ ] People counting với zone detection
- [ ] Settings UI với PreferenceFragment

## 🤝 Đóng góp

1. Fork repo
2. Tạo feature branch: `git checkout -b feature/amazing-feature`
3. Commit changes: `git commit -m 'Add amazing feature'`
4. Push to branch: `git push origin feature/amazing-feature`
5. Mở Pull Request

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.