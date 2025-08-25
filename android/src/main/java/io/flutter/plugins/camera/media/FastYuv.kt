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

/**
 * Image(YUV_420_888) -> I420 (manual copy) -> NV21 (libyuv convertTo).
 * Returns NV21 data and dimensions.
 */
@JvmOverloads
fun imageToNv21(image: Image, reuse: ByteArray? = null): Nv21Result {
    require(image.format == ImageFormat.YUV_420_888) { "Image must be YUV_420_888" }

    val i420 = imageToI420(image) // manual copy with stride/pixelStride handling
    val w = i420.width
    val h = i420.height
    val need = nv21Size(w, h)

    // I420 -> NV21 via libyuv
    val nv21Buf = Nv21Buffer.allocate(w, h)
    i420.convertTo(nv21Buf)
    i420.close()

    // Read back to byte[]
    val out = if (reuse != null && reuse.size == need) reuse else ByteArray(need)
    val bb: ByteBuffer = nv21Buf.asBuffer().duplicate()
    bb.position(0)
    bb.get(out, 0, need)
    nv21Buf.close()

    return Nv21Result(out, w, h)
}

/**
 * Manual conversion YUV_420_888 -> I420Buffer without extension helpers.
 * Handles arbitrary rowStride / pixelStride and pads tail bytes when needed.
 */
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

    // Rewind buffers for downstream consumers
    dst.planeY.buffer.rewind()
    dst.planeU.buffer.rewind()
    dst.planeV.buffer.rewind()
    return dst
}

/**
 * Safe plane copy honoring rowStride/pixelStride; fills missing tail with 'pad'
 * (Y=0, UV=128) to keep colors neutral when source row is shorter than requested width.
 */
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
            // Source row is missing -> pad whole row
            for (c in 0 until width) d.put(dBase + c, padByte)
            continue
        }

        if (srcPixelStride == 1) {
            // Fast path: contiguous pixels
            s.position(sBase)
            val toCopy = min(width, avail)
            s.get(tmpRow, 0, toCopy)

            val dRow = d.duplicate()
            dRow.position(dBase)
            dRow.put(tmpRow, 0, toCopy)

            val tail = if (toCopy > 0) tmpRow[toCopy - 1] else padByte
            for (c in toCopy until width) d.put(dBase + c, tail)
        } else {
            // Generic path: strided pixels
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

/**
 * Rotate an NV21 frame and return a new buffer.
 * Internally uses I420Rotate for robustness, then converts back to NV21.
 */
fun imageToNv21Rotated(src: ByteArray, width: Int, height: Int, rotationDegrees: Int): Nv21Result {
    val need = nv21Size(width, height)
    require(src.size >= need) { "NV21 buffer too small: have=${src.size}, need=$need" }

    val mode = rotateMode(rotationDegrees)
    if (mode == RotateMode.ROTATE_0) {
        // Return a copy so the caller can safely mutate the result
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

    val out = ByteArray(nv21Size(rw, rh))
    nv21Dst.asBuffer().apply {
        position(0)
        get(out, 0, out.size)
    }
    nv21Dst.close()

    return Nv21Result(out, rw, rh)
}

/**
 * Rotate NV21 into a provided destination buffer (no extra Java array allocation).
 * Destination must be sized to fit the rotated frame. Internally uses I420Rotate.
 *
 * @param src NV21 source buffer (size = width*height*3/2)
 * @param width source width (must be even for 4:2:0)
 * @param height source height (must be even for 4:2:0)
 * @param rotationDegrees 0, 90, 180, 270
 * @param dst output NV21 buffer; its size must be outW*outH*3/2 after rotation
 */
fun rotateNv21Into(src: ByteArray, width: Int, height: Int, rotationDegrees: Int, dst: ByteArray) {
    val mode = rotateMode(rotationDegrees)

    // If no rotation, just copy (and validate size).
    if (mode == RotateMode.ROTATE_0) {
        val need = nv21Size(width, height)
        require(dst.size >= need) { "dst too small: have=${dst.size}, need=$need" }
        System.arraycopy(src, 0, dst, 0, need)
        return
    }

    // 4:2:0 requires even dimensions for rotation.
    require((width and 1) == 0 && (height and 1) == 0) { "NV21 rotate requires even width/height" }

    val (outW, outH) = if (mode == RotateMode.ROTATE_90 || mode == RotateMode.ROTATE_270) {
        height to width
    } else {
        width to height
    }
    val needSrc = nv21Size(width, height)
    val needDst = nv21Size(outW, outH)
    require(src.size >= needSrc) { "src too small: have=${src.size}, need=$needSrc" }
    require(dst.size >= needDst) { "dst too small: have=${dst.size}, need=$needDst" }

    var nv21Src: Nv21Buffer? = null
    var i420: I420Buffer? = null
    var i420Rot: I420Buffer? = null
    var nv21Dst: Nv21Buffer? = null

    try {
        nv21Src = Nv21Buffer.allocate(width, height).also { buf ->
            val bb = buf.asBuffer()
            bb.position(0)
            bb.put(src, 0, needSrc)
            bb.position(0)
        }

        i420 = I420Buffer.allocate(width, height)
        i420Rot = I420Buffer.allocate(outW, outH)
        nv21Dst = Nv21Buffer.allocate(outW, outH)

        // NV21 -> I420 -> rotate -> NV21
        nv21Src.convertTo(i420!!)
        i420!!.rotate(i420Rot!!, mode)
        i420Rot!!.convertTo(nv21Dst!!)

        // Read back directly into dst
        nv21Dst.asBuffer().apply {
            position(0)
            get(dst, 0, needDst)
        }
    } finally {
        try { nv21Src?.close() } catch (_: Throwable) {}
        try { i420?.close() } catch (_: Throwable) {}
        try { i420Rot?.close() } catch (_: Throwable) {}
        try { nv21Dst?.close() } catch (_: Throwable) {}
    }
}

private fun rotateMode(deg: Int): RotateMode {
    val d = ((deg % 360) + 360) % 360
    return when (d) {
        90  -> RotateMode.ROTATE_90
        180 -> RotateMode.ROTATE_180
        270 -> RotateMode.ROTATE_270
        else -> RotateMode.ROTATE_0
    }
}

private fun nv21Size(w: Int, h: Int): Int {
    val y = w * h
    return y + (y / 2)
}
