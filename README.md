# camera_android

The Android implementation of the [`camera`](https://pub.dev/packages/camera) plugin.

This package provides low-level camera access for the Android platform, used internally by the `camera` plugin.

---

## 🚀 New Feature: Capture Preview Frame (JPEG)

This version introduces a new platform method: `capturePreviewFrameJpeg()` for retrieving a single JPEG-compressed frame directly from the preview stream, without interrupting camera operation.

### ✅ Use Cases

- Fast frame grabs for lightweight processing
- Preview snapshot without full shutter/photo delay
- Efficient ML/AI input for scanning or inference
- Save current preview frame to file instantly

---

## 🧪 Streaming Preview Frames (YUV)

You can also subscribe to a continuous stream of preview frames in YUV format:

### 🔹 Start / Stop Streaming

```dart
await cameraController.startFrameStream((frame) {
  // `frame` is CameraImageData with YUV planes
  process(frame);
});

await cameraController.stopFrameStream();
```

---

## 📸 One-time Preview Frame (YUV)

To retrieve a single preview frame in YUV format:

```dart
final CameraImageData frame = await cameraController.capturePreviewFrame();
// You can access .planes and .width/.height for further processing
```

---

## 🖼 One-time Preview Frame (JPEG)

To capture and save a single JPEG preview frame:

```dart
final String savedPath = await cameraController.capturePreviewFrameJpeg('/path/to/file.jpg');
```

---

## 🛠 How It Works

- Uses an additional background `ImageReader` (YUV_420_888)
- JPEG conversion is done via `YuvImage.compressToJpeg`
- Frames are acquired non-blocking to avoid preview freezing
- Safe for use during video recording or preview-only mode

---

## ❗️Notes

- `capturePreviewFrameJpeg()` uses internal `ImageReader`, does **not** trigger shutter or autofocus
- JPEG quality defaults to 90
- Requires Android API 21+
