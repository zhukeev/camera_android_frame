# camera\_android_frame

The Android implementation of the [`camera`](https://pub.dev/packages/camera) plugin.

This package provides low-level camera access for the Android platform, used internally by the `camera` plugin.

---

## 🚀 New Feature: Capture Preview Frame

This version introduces a new platform method: `capturePreviewFrame()`, which allows retrieving a single JPEG frame from the camera preview without taking a full-resolution photo.

### ✅ Use Cases

- Fast frame grabs for processing or analysis
- Lightweight snapshot preview without shutter delay
- ML/AI scanning without full capture overhead

### 🛠 How It Works

- Internally uses an additional `ImageReader` with YUV format.
- Converts one preview frame to JPEG on request.
- Maintains low latency (~10–50ms) compared to `takePicture()`.

### 🔧 Flutter Usage (via Pigeon)

```dart
final data = await cameraController.capturePreviewFrame();
// `data` is a Uint8List (JPEG encoded image)
```

## Limitation of testing video recording on emulators

`MediaRecorder` does not work properly on emulators, as stated in [the documentation][5]. Specifically,
when recording a video with sound enabled and trying to play it back, the duration won't be correct and
you will only see the first frame.

[5]: https://developer.android.com/reference/android/media/MediaRecorder
