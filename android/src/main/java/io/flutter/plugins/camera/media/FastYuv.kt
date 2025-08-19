@file:JvmName("FastYuv")
package io.flutter.plugins.camera.media

import android.graphics.ImageFormat
import android.media.Image
import io.github.crow_misia.libyuv.I420Buffer
import io.github.crow_misia.libyuv.Nv21Buffer
import io.github.crow_misia.libyuv.RotateMode
import java.nio.ByteBuffer
import kotlin.math.min

data class Nv21Result(val data: ByteArray, val width: Int, val height: Int)

/** Image(YUV_420_888) -> I420 (ручная копия) -> NV21 (libyuv convertTo) */
@JvmOverloads
fun imageToNv21(image: Image, reuse: ByteArray? = null): Nv21Result {
    require(image.format == ImageFormat.YUV_420_888) { "Image must be YUV_420_888" }

    val i420 = imageToI420(image)      // наша копия плоскостей с учетом stride/pixelStride
    val w = i420.width
    val h = i420.height
    val need = w * h * 3 / 2

    // I420 -> NV21 через libyuv
    val nv21Buf = Nv21Buffer.allocate(w, h)
    i420.convertTo(nv21Buf)
    i420.close()

    // Снять данные в byte[]
    val out = if (reuse != null && reuse.size == need) reuse else ByteArray(need)
    val bb: ByteBuffer = nv21Buf.asBuffer().duplicate()
    bb.position(0)
    bb.get(out, 0, need)
    nv21Buf.close()

    return Nv21Result(out, w, h)
}

/** Ручная конверсия YUV_420_888 -> I420Buffer без ext. */
fun imageToI420(image: Image): I420Buffer {
    val w = image.width
    val h = image.height
    val y = image.planes[0]
    val u = image.planes[1] // Cb
    val v = image.planes[2] // Cr

    val dst = I420Buffer.allocate(w, h)

    copyPlane(
        src = y.buffer, srcRowStride = y.rowStride, srcPixelStride = y.pixelStride,
        dst = dst.planeY.buffer, dstRowStride = dst.planeY.rowStride.value,
        width = w, height = h, pad = 0
    )
    copyPlane(
        src = u.buffer, srcRowStride = u.rowStride, srcPixelStride = u.pixelStride,
        dst = dst.planeU.buffer, dstRowStride = dst.planeU.rowStride.value,
        width = w / 2, height = h / 2, pad = 128
    )
    copyPlane(
        src = v.buffer, srcRowStride = v.rowStride, srcPixelStride = v.pixelStride,
        dst = dst.planeV.buffer, dstRowStride = dst.planeV.rowStride.value,
        width = w / 2, height = h / 2, pad = 128
    )

    // вернуть курсоры
    dst.planeY.buffer.rewind()
    dst.planeU.buffer.rewind()
    dst.planeV.buffer.rewind()
    return dst
}

/** Безопасная копия с учётом rowStride/pixelStride; добивка хвоста pad (Y=0, UV=128). */
private fun copyPlane(
    src: ByteBuffer,
    srcRowStride: Int,
    srcPixelStride: Int,
    dst: ByteBuffer,
    dstRowStride: Int,
    width: Int,
    height: Int,
    pad: Int
) {
    val s = src.duplicate()
    val d = dst.duplicate()
    val sLimit = s.limit()
    val padByte = pad.toByte()
    val tmpRow = ByteArray(if (srcPixelStride == 1) width else 0)

    for (row in 0 until height) {
        val sBase = row * srcRowStride
        val dBase = row * dstRowStride

        val avail = if (sBase >= sLimit) 0 else min(srcRowStride, sLimit - sBase)
        if (avail <= 0) {
            // строка отсутствует — паддинг
            for (c in 0 until width) d.put(dBase + c, padByte)
            continue
        }

        if (srcPixelStride == 1) {
            // быстрый путь
            s.position(sBase)
            val toCopy = min(width, avail)
            s.get(tmpRow, 0, toCopy)

            val dRow = d.duplicate()
            dRow.position(dBase)
            dRow.put(tmpRow, 0, toCopy)

            val tail = if (toCopy > 0) tmpRow[toCopy - 1] else padByte
            for (c in toCopy until width) d.put(dBase + c, tail)
        } else {
            // общий путь
            val maxCols = (avail + srcPixelStride - 1) / srcPixelStride
            val cols = min(width, maxCols)
            var si = sBase
            for (c in 0 until cols) {
                d.put(dBase + c, s.get(si))
                si += srcPixelStride
            }
            val tail = if (cols > 0) d.get(dBase + cols - 1) else padByte
            for (c in cols until width) d.put(dBase + c, tail)
        }
    }
}

fun imageToNv21Rotated(src: ByteArray, width: Int, height: Int, rotationDegrees: Int): Nv21Result {
    val need = width * height * 3 / 2
    require(src.size >= need) { "NV21 buffer too small: have=${src.size}, need=$need" }

    val mode = when (((rotationDegrees % 360) + 360) % 360) {
        90  -> RotateMode.ROTATE_90
        180 -> RotateMode.ROTATE_180
        270 -> RotateMode.ROTATE_270
        else -> RotateMode.ROTATE_0
    }
    if (mode == RotateMode.ROTATE_0) {
        // копия на всякий случай, чтобы вызывающий не модифицировал исходный массив
        return Nv21Result(src.copyOf(need), width, height)
    }

    val nv21Src = Nv21Buffer.allocate(width, height).also { buf ->
        val bb = buf.asBuffer()
        bb.position(0)
        bb.put(src, 0, need)
        bb.position(0)
    }

    val i420 = I420Buffer.allocate(width, height)
    nv21Src.convertTo(i420)
    nv21Src.close()

    val (rw, rh) = if (mode == RotateMode.ROTATE_90 || mode == RotateMode.ROTATE_270) {
        height to width
    } else {
        width to height
    }
    val i420Rot = I420Buffer.allocate(rw, rh)
    i420.rotate(i420Rot, mode)
    i420.close()

    val nv21Dst = Nv21Buffer.allocate(rw, rh)
    i420Rot.convertTo(nv21Dst)
    i420Rot.close()

    val out = ByteArray(rw * rh * 3 / 2)
    nv21Dst.asBuffer().apply {
        position(0)
        get(out, 0, out.size)
    }
    nv21Dst.close()

    return Nv21Result(out, rw, rh)
}