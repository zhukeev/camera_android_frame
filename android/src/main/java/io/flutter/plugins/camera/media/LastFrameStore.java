package io.flutter.plugins.camera.media;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import io.flutter.plugins.camera.media.FastYuv;
import io.flutter.plugins.camera.media.Nv21Result;
import androidx.exifinterface.media.ExifInterface;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class LastFrameStore {
    private static final String TAG = "LastFrameStore";

    // Кольцо из 3 буферов (чтобы не перетирать кадр во время записи JPEG)
    private static final int RING = 3;
    private final byte[][] ring = new byte[RING][];
    private int ringIdx = 0;
    private int ringW = -1, ringH = -1;

    // последний «снимок»
    private static final class Nv21Frame {
        final byte[] nv21;
        final int width, height;
        final long tsNs;
        Nv21Frame(byte[] b, int w, int h, long t) { nv21 = b; width = w; height = h; tsNs = t; }
    }

    private volatile Nv21Frame last; // публикация для чтения из другого потока

    // Троттлинг (необязателен): 5 fps = 200ms
    private static final long DEFAULT_MIN_INTERVAL_NS = 200_000_000L;
    private long lastAcceptTsNs = 0L;

    /** Быстрый снимок YUV_420_888 -> NV21, с троттлингом 5fps. Image ВСЕГДА закрывается. */
    public void accept(Image image) {
        accept(image, DEFAULT_MIN_INTERVAL_NS);
    }

    /** Быстрый снимок YUV_420_888 -> NV21, с заданным минимальным интервалом. Возвращает true, если кадр принят. */
    public boolean accept(Image image, long minIntervalNs) {
        if (image == null)
            return false;
        try {
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            if (now - lastAcceptTsNs < minIntervalNs)
                return false;

            final int w = image.getWidth();
            final int h = image.getHeight();
            byte[] buf = ensureRingBuf(w, h); // твой метод: w*h*3/2

            // libyuv путь: YUV_420_888 -> I420 -> NV21 в buf
            Nv21Result res = FastYuv.imageToNv21(image, buf);

            last = new Nv21Frame(res.getData(), res.getWidth(), res.getHeight(), now);
            ringIdx = (ringIdx + 1) % RING;
            lastAcceptTsNs = now;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "accept failed", t);
            return false;
        } finally {
            try {
                image.close();
            } catch (Exception ignore) {
            }
        }
    }

    /** Сохранить последний кадр как JPEG с EXIF-ориентацией. Возвращает outputPath. */
    public String writeJpeg(String outputPath, int rotationDegrees, int quality) throws IOException {
        Nv21Frame f = last;
        if (f == null || f.nv21 == null) throw new IOException("No frame available");
        // одна компрессия NV21 -> JPEG
        YuvImage yuv = new YuvImage(f.nv21, ImageFormat.NV21, f.width, f.height, null);
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            Rect rect = new Rect(0, 0, f.width, f.height);
            if (!yuv.compressToJpeg(rect, quality, fos)) {
                throw new IOException("compressToJpeg failed");
            }
        }
        FastYuv.imageToNv21Rotated(f.nv21, f.width, f.height, rotationDegrees); 
        return outputPath;
    }

    /** Есть ли кадр. */
    public boolean hasFrame() { return last != null && last.nv21 != null; }

    // ---------- private helpers ----------

    private byte[] ensureRingBuf(int w, int h) {
        final int need = w * h * 3 / 2;
        byte[] buf = ring[ringIdx];
        if (buf == null || buf.length != need || w != ringW || h != ringH) {
            buf = new byte[need];
            ring[ringIdx] = buf;
            ringW = w; ringH = h;
        }
        return buf;
    }

    private static void yuv420ToNv21(Image image, byte[] out, int width, int height) {
        final Image.Plane[] planes = image.getPlanes();

        // ---------- Y ----------
        final ByteBuffer yBuf = planes[0].getBuffer().duplicate();
        final int yRowStride = planes[0].getRowStride();
        final int yPixStride = planes[0].getPixelStride(); // обычно 1
        final int yLimit = yBuf.limit();

        int dstY = 0;
        if (yPixStride == 1 && yRowStride == width) {
            yBuf.position(0);
            yBuf.get(out, 0, width * height);
            dstY = width * height;
        } else {
            byte[] row = new byte[yRowStride];
            for (int r = 0; r < height; r++) {
                int pos = r * yRowStride;
                if (pos >= yLimit) {
                    // строка отсутствует — паддинг нулями
                    for (int c = 0; c < width; c++)
                        out[dstY++] = 0;
                    continue;
                }
                int rowBytes = Math.min(yRowStride, yLimit - pos);
                yBuf.position(pos);
                yBuf.get(row, 0, rowBytes);

                int cols = Math.min(width, (rowBytes + yPixStride - 1) / yPixStride);
                for (int c = 0; c < cols; c++) {
                    out[dstY++] = row[c * yPixStride];
                }
                // добиваем недостающие пиксели повтором последнего (или 0)
                byte pad = cols > 0 ? row[(cols - 1) * yPixStride] : 0;
                for (int c = cols; c < width; c++)
                    out[dstY++] = pad;
            }
        }

        // ---------- UV -> NV21 (VU interleaved) ----------
        final ByteBuffer uBuf = planes[1].getBuffer().duplicate();
        final ByteBuffer vBuf = planes[2].getBuffer().duplicate();
        final int uRowStride = planes[1].getRowStride();
        final int vRowStride = planes[2].getRowStride();
        final int uPixStride = planes[1].getPixelStride();
        final int vPixStride = planes[2].getPixelStride();
        final int uLimit = uBuf.limit();
        final int vLimit = vBuf.limit();

        int dstUV = width * height;
        byte[] uRow = new byte[uRowStride];
        byte[] vRow = new byte[vRowStride];

        final int halfH = height / 2;
        final int halfW = width / 2;

        for (int r = 0; r < halfH; r++) {
            int upos = r * uRowStride;
            int vpos = r * vRowStride;

            int uBytes = 0, vBytes = 0;
            if (upos < uLimit) {
                uBytes = Math.min(uRowStride, uLimit - upos);
                uBuf.position(upos);
                uBuf.get(uRow, 0, uBytes);
            }
            if (vpos < vLimit) {
                vBytes = Math.min(vRowStride, vLimit - vpos);
                vBuf.position(vpos);
                vBuf.get(vRow, 0, vBytes);
            }

            int uCols = (uBytes <= 0) ? 0 : (uBytes + uPixStride - 1) / uPixStride;
            int vCols = (vBytes <= 0) ? 0 : (vBytes + vPixStride - 1) / vPixStride;
            int cols = Math.min(halfW, Math.min(uCols, vCols));

            // доступные пары VU
            for (int c = 0; c < cols; c++) {
                out[dstUV++] = vRow[c * vPixStride]; // V
                out[dstUV++] = uRow[c * uPixStride]; // U
            }
            // добиваем до конца строки нейтральной хромой (128, 128) — серый без смещения
            for (int c = cols; c < halfW; c++) {
                out[dstUV++] = (byte) 128; // V
                out[dstUV++] = (byte) 128; // U
            }
        }
    }

    private static void setExifOrientation(String outputPath, int rotationDegrees) {
        int exif;
        switch ((rotationDegrees % 360 + 360) % 360) {
            case 90:  exif = ExifInterface.ORIENTATION_ROTATE_90;  break;
            case 180: exif = ExifInterface.ORIENTATION_ROTATE_180; break;
            case 270: exif = ExifInterface.ORIENTATION_ROTATE_270; break;
            case 0:   exif = ExifInterface.ORIENTATION_NORMAL;     break;
            default:  exif = ExifInterface.ORIENTATION_UNDEFINED;  break;
        }
        if (exif == ExifInterface.ORIENTATION_UNDEFINED) return;
        try {
            ExifInterface ei = new ExifInterface(outputPath);
            ei.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exif));
            ei.saveAttributes();
        } catch (IOException e) {
            Log.w(TAG, "setExifOrientation failed", e);
        }
    }
}
