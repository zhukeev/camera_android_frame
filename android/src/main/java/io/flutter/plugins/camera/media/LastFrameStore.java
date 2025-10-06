package io.flutter.plugins.camera.media;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import io.flutter.plugins.camera.types.CameraCaptureProperties;
import android.media.Image;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public final class LastFrameStore {
    private static final String TAG = "LastFrameStore";

    /** Listener invoked on every successful accept(). Called on the same thread as accept(). */
    public static interface OnFrameListener {
        void onFrame(Map<String, Object> frame);
    }

    // Ring buffer for NV21 bytes to avoid stomping while encoding
    private static final int RING = 3;
    private final byte[][] ring = new byte[RING][];
    private int ringIdx = 0;
    private int ringW = -1, ringH = -1;


    private final Object snapshotLock = new Object();
    private byte[] snapshotBuf;
    private byte[] rotatedBuf;

    // Workspace for libyuv rotations/conversions
    private final YuvWorkspace ws = new YuvWorkspace();

    /** Lightweight snapshot of the latest frame. */
    private static final class Nv21Frame {
        final byte[] nv21;
        final int width, height;
        final long tsNs;
        Nv21Frame(byte[] b, int w, int h, long t) { nv21 = b; width = w; height = h; tsNs = t; }
    }

    // Latest frame published for cross-thread reads
    private volatile Nv21Frame last;

    // Throttling: default ~5 fps
    private static final long DEFAULT_MIN_INTERVAL_NS = 200_000_000L;
    private long lastAcceptTsNs = 0L;

    private long defaultMinIntervalNs = DEFAULT_MIN_INTERVAL_NS;

    public void setFrameFps(@Nullable Integer fps) {
        if (fps != null && fps > 0) {
            defaultMinIntervalNs = 1_000_000_000L / fps;
        } else {
            defaultMinIntervalNs = 0L;
        }
        lastAcceptTsNs = 0L;
    }

    // Optional listener for streaming
    @Nullable private volatile OnFrameListener onFrameListener;
    private volatile boolean copyBytesForCallback = true;

    /** Register a listener to receive a Map on every accepted frame. Pass null to clear. */
    public void setOnFrameListener(@Nullable OnFrameListener listener, boolean copyBytesForCallback) {
        this.onFrameListener = listener;
        this.copyBytesForCallback = copyBytesForCallback;
    }

    /** Clear previously registered listener. */
    public void clearOnFrameListener() {
        this.onFrameListener = null;
    }

    /** Accept with default throttling. Image is ALWAYS closed. */
    public void accept(Image image) { accept(image, defaultMinIntervalNs); }

    /**
     * Accept a YUV_420_888 Image and convert to NV21 into ring buffer.
     * Returns true if a frame was accepted and published. Image is ALWAYS closed.
     */
    public boolean accept(Image image, long minIntervalNs) {
        if (image == null) return false;
        try {
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            if (now - lastAcceptTsNs < minIntervalNs) return false;

            final int w = image.getWidth();
            final int h = image.getHeight();
            byte[] buf = ensureRingBuf(w, h);

            // Fast path: YUV_420_888 -> NV21 into ring buffer
            Nv21Result res = FastYuv.imageToNv21(image, buf);

            last = new Nv21Frame(res.getData(), res.getWidth(), res.getHeight(), now);
            ringIdx = (ringIdx + 1) % RING;
            lastAcceptTsNs = now;

            // Fire listener (same thread). Keep it lightweight.
            final OnFrameListener l = onFrameListener;
            if (l != null) {
                Map<String, Object> map = getPreviewFrameMap(copyBytesForCallback);
                if (map != null) {
                    try { l.onFrame(map); } catch (Throwable t) {
                        Log.w(TAG, "onFrame listener failed", t);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "accept failed", t);
            return false;
        } finally {
            try { image.close(); } catch (Exception ignore) {}
        }
    }

    /** Save latest NV21 frame as JPEG without EXIF (rotation applied via pixels). */
    public String writeJpeg(String outputPath, int rotationDegrees, int quality) throws IOException {
        Nv21Frame f = last;
        if (f == null || f.nv21 == null) throw new IOException("No frame available");

        final byte[] src;
        final int w = f.width, h = f.height;
        synchronized (snapshotLock) {
            if (snapshotBuf == null || snapshotBuf.length != f.nv21.length) {
                snapshotBuf = new byte[f.nv21.length];
            }
            System.arraycopy(f.nv21, 0, snapshotBuf, 0, f.nv21.length);
            src = snapshotBuf;
        }

        final boolean rotate = ((rotationDegrees % 360) + 360) % 360 != 0;
        byte[] toCompress = src;
        int cw = w, ch = h;

        if (rotate) {
            int rw = (rotationDegrees % 180 == 0) ? w : h;
            int rh = (rotationDegrees % 180 == 0) ? h : w;
            int need = nv21Size(rw, rh);
            if (rotatedBuf == null || rotatedBuf.length != need) {
                rotatedBuf = new byte[need];
            }
            ws.rotateNv21(src, w, h, rotationDegrees, rotatedBuf);
            toCompress = rotatedBuf;
            cw = ws.getRotatedWidth();
            ch = ws.getRotatedHeight();
        }

        YuvImage yuv = new YuvImage(toCompress, ImageFormat.NV21, cw, ch, null);
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            if (!yuv.compressToJpeg(new Rect(0, 0, cw, ch), quality, fos)) {
                throw new IOException("compressToJpeg failed");
            }
        }
        return outputPath;
    }

    /** Whether there is a frame available. */
    public boolean hasFrame() { return last != null && last.nv21 != null; }

    /** Simple NV21-as-single-plane map for Dart side. */
    @Nullable
    public Map<String, Object> getPreviewFrameMap(boolean copyBytes) {
        Nv21Frame cur = last;
        if (cur == null || cur.nv21 == null) return null;

        byte[] data = copyBytes ? Arrays.copyOf(cur.nv21, cur.nv21.length) : cur.nv21;

        Map<String, Object> out = new HashMap<>(8);
        out.put("format", ImageFormat.NV21);
        out.put("width", cur.width);
        out.put("height", cur.height);

        Map<String, Object> plane = new HashMap<>(6);
        plane.put("bytes", data);
        plane.put("bytesPerRow", cur.width);
        plane.put("bytesPerPixel", 1);
        plane.put("width", cur.width);
        plane.put("height", cur.height);
        out.put("planes", Collections.singletonList(plane));

        // Legacy metadata keys (kept for compatibility; null by default)
        out.put("lensAperture", null);
        out.put("sensorExposureTime", null);
        out.put("sensorSensitivity", null);

        return out;
    }

    /** Legacy wrapper to keep existing call sites working. */
    @Nullable
    public Map<String, Object> getPreviewFrameMap(
        @Nullable CameraCaptureProperties props,
        boolean copyBytes
    ) {
        Map<String, Object> map = getPreviewFrameMap(copyBytes);
        if (map == null) return null;
        if (props != null) {
            map.put("lensAperture", props.getLastLensAperture());
            map.put("sensorExposureTime", props.getLastSensorExposureTime());
            Integer iso = props.getLastSensorSensitivity();
            map.put("sensorSensitivity", iso == null ? null : (double) iso);
        }
        return map;
    }

    // ----------------- helpers -----------------

    private static int nv21Size(int w, int h) {
        int y = w * h;
        return y + (y / 2);
    }

    private byte[] ensureRingBuf(int w, int h) {
        final int need = nv21Size(w, h);
        byte[] buf = ring[ringIdx];
        if (buf == null || buf.length != need || w != ringW || h != ringH) {
            buf = new byte[need];
            ring[ringIdx] = buf;
            ringW = w; ringH = h;
        }
        return buf;
    }
}
