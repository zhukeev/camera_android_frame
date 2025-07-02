# camera_android

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
- Maintains low latency (~10–50ms) compared to `takePicture()`.
- Avoids interrupting the preview or recording session

### 🔧 Flutter Usage (via Pigeon)

```dart
final data = await cameraController.capturePreviewFrame();
// `data` is a CamereImageData
```

### 🔹 startListenFrames() / stopListenFrames

```dart
   await cameraController.startFrameStream((frame) {
    // process
      });
```
