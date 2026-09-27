package com.trackstudio.app.data.native

/**
 * Thin JNI boundary. Deliberately has zero Android/Compose/business-logic
 * imports so it stays a pure bridge - all policy (which scale to use,
 * where to write, what to do on failure) lives in
 * data.io.ImageSequenceImporter, not here.
 */
class ProxyEngine {

    /**
     * Header-only probe: returns intArrayOf(width, height, channels), or an
     * empty array if [srcBytes] isn't a decodable image. Does not decode
     * pixel data, so it's safe to call once per frame even on a
     * several-thousand-frame sequence.
     */
    external fun nativeProbeImage(srcBytes: ByteArray): IntArray

    /**
     * Decodes [srcBytes], downscales by [scale] (0 < scale <= 1.0), and
     * writes a JPEG proxy to [dstPath]. Returns true on success.
     */
    external fun nativeGenerateProxy(
        srcBytes: ByteArray,
        dstPath: String,
        scale: Float,
        jpegQuality: Int
    ): Boolean

    companion object {
        init {
            System.loadLibrary("trackstudio_native")
        }
    }
}
