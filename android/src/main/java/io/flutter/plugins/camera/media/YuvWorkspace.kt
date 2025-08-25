package io.flutter.plugins.camera.media

import io.github.crow_misia.libyuv.I420Buffer
import io.github.crow_misia.libyuv.Nv21Buffer
import io.github.crow_misia.libyuv.RotateMode

/**
 * Reusable workspace with preallocated libyuv buffers for NV21 <-> I420 and rotation.
 * Keeps two sizes: source (w,h) and rotated (rw,rh).
 */
class YuvWorkspace {
    private var nv21Src: Nv21Buffer? = null
    private var i420: I420Buffer? = null
    private var i420Rot: I420Buffer? = null
    private var nv21Rot: Nv21Buffer? = null

    var w: Int = -1
        private set
    var h: Int = -1
        private set
    var rw: Int = -1
        private set
    var rh: Int = -1
        private set

    /** Ensure buffers are allocated for given source size and rotation output size. */
    fun ensure(width: Int, height: Int, rotationDegrees: Int) {
        val mode = toMode(rotationDegrees)
        val swap = (mode == RotateMode.ROTATE_90 || mode == RotateMode.ROTATE_270)
        val outW = if (swap) height else width
        val outH = if (swap) width else height

        if (nv21Src == null || w != width || h != height) {
            nv21Src?.close(); i420?.close()
            nv21Src = Nv21Buffer.allocate(width, height)
            i420 = I420Buffer.allocate(width, height)
            w = width; h = height
        }
        if (nv21Rot == null || rw != outW || rh != outH) {
            nv21Rot?.close(); i420Rot?.close()
            nv21Rot = Nv21Buffer.allocate(outW, outH)
            i420Rot = I420Buffer.allocate(outW, outH)
            rw = outW; rh = outH
        }
    }

    /**
     * Rotate NV21 by converting to I420, rotating, then converting back to NV21.
     * The result is written into dstOut (no extra Java arrays allocated).
     */
    fun rotateNv21(src: ByteArray, width: Int, height: Int, rotationDegrees: Int, dstOut: ByteArray) {
        ensure(width, height, rotationDegrees)
        val needSrc = nv21Size(width, height)
        val needDst = nv21Size(rw, rh)

        nv21Src!!.asBuffer().apply {
            position(0)
            put(src, 0, needSrc)
            position(0)
        }

        nv21Src!!.convertTo(i420!!)
        i420!!.rotate(i420Rot!!, toMode(rotationDegrees))
        i420Rot!!.convertTo(nv21Rot!!)

        nv21Rot!!.asBuffer().apply {
            position(0)
            get(dstOut, 0, needDst)
        }
    }

    /** Java-friendly getters used from LastFrameStore/Camera. */
    fun getRotatedWidth(): Int = rw
    fun getRotatedHeight(): Int = rh

    fun close() {
        nv21Src?.close(); nv21Rot?.close()
        i420?.close(); i420Rot?.close()
        nv21Src = null; nv21Rot = null; i420 = null; i420Rot = null
        w = -1; h = -1; rw = -1; rh = -1
    }

    private fun nv21Size(w: Int, h: Int) = w * h + (w * h) / 2

    private fun toMode(deg: Int): RotateMode {
        val d = ((deg % 360) + 360) % 360
        return when (d) {
            90 -> RotateMode.ROTATE_90
            180 -> RotateMode.ROTATE_180
            270 -> RotateMode.ROTATE_270
            else -> RotateMode.ROTATE_0
        }
    }
}
