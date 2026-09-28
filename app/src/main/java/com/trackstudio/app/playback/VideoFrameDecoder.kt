package com.trackstudio.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.trackstudio.app.util.Logger
import kotlin.math.max

/**
 * Sequential decoder for video files (MP4/MOV/WebM and any container the
 * platform MediaCodec stack supports).
 *
 * The viewer is frame-based, so we expose the video as a list of frame
 * indices: seeking backwards re-extracts from the nearest sync sample,
 * seeking forward decodes only the missing frames. This keeps playback
 * smooth without loading the whole clip into memory.
 */
class VideoFrameDecoder(
    private val context: Context,
    private val uri: Uri
) {

    companion object {
        private const val DECODE_TIMEOUT_US = 10_000L
        private const val MAX_OUTPUT_DIMENSION = 1920
    }

    /**
     * Lightweight metadata probe - safe to call on the main thread.
     */
    data class VideoInfo(
        val frameCount: Int,
        val fps: Double,
        val width: Int,
        val height: Int
    )

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var trackIndex = -1
    private var frameCount = 0
    private var currentDecodedFrame = -1
    
    private var rotationDegrees = 0
    private var released = false

    /**
     * Probes frame count / fps using MediaMetadataRetriever. Falls back to
     * reasonable defaults when the container does not advertise them.
     */
    fun readInfo(): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )
                ?.toLongOrNull()
                ?: 0L

            val fps = retriever
                .extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                )
                ?.toDoubleOrNull()
                ?: run {
                    // Some containers omit frame rate; fall back to width/height hints.
                    Logger.w("Video frame rate metadata missing, assuming 24 fps")
                    24.0
                }

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            val effectiveFps = if (fps > 0.0 && fps < 1000.0) fps else 24.0

            val frames = if (durationMs > 0 && effectiveFps > 0.0) {
                max(1, Math.round(durationMs / 1000.0 * effectiveFps).toInt())
            } else {
                0
            }

            return VideoInfo(
                frameCount = frames,
                fps = effectiveFps,
                width = if (rotation % 180 == 0) width else height,
                height = if (rotation % 180 == 0) height else width
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Decodes and returns the bitmap for [frameIndex], or null on failure.
     */
    fun decodeFrame(frameIndex: Int): Bitmap? {
        check(!released) { "Decoder already released" }

        if (frameIndex < 0 || (frameCount > 0 && frameIndex >= frameCount)) {
            return null
        }

        openIfNeeded() ?: return null

        val codec = this.codec ?: return null

        return try {
            seekToFrame(frameIndex)
            drainUntilFrame(frameIndex, codec)
        } catch (t: Throwable) {
            Logger.e("Failed to decode video frame $frameIndex", t)
            resetCodec()
            null
        }
    }

    fun release() {
        released = true
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor?.release() }
        extractor = null

        currentDecodedFrame = -1
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun openIfNeeded(): MediaExtractor? {
        if (!released && extractor != null && codec != null) {
            return extractor
        }

        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)

            trackIndex = selectVideoTrack(ex)
            if (trackIndex < 0) {
                ex.release()
                Logger.e("No video track found in $uri")
                return null
            }

            ex.getTrackFormat(trackIndex).let { format ->
                rotationDegrees = format.getInteger(
                    MediaFormat.KEY_ROTATION
                ).takeIf { it in listOf(0, 90, 180, 270) } ?: 0
            }

            ex.selectTrack(trackIndex)

            val mime = ex.getTrackFormat(trackIndex).getString(
                MediaFormat.KEY_MIME
            ) ?: error("Video track has no MIME type")

            val c = MediaCodec.createDecoderByType(mime)
            c.configure(ex.getTrackFormat(trackIndex), null, null, 0)
            c.start()

            extractor = ex
            codec = c
            currentDecodedFrame = -1
            ex
        } catch (t: Throwable) {
            Logger.e("Failed to open video decoder", t)
            runCatching { ex.release() }
            null
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return i
            }
        }
        return -1
    }

    private fun seekToFrame(frameIndex: Int) {
        val extractor = extractor ?: return

        // Timestamp of the requested frame, derived from its index.
        val targetUs = frameIndex.toLong() * timePerFrameUs()

        // Seek to the nearest preceding sync frame; decode forward from there.
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        currentDecodedFrame = -1
        flushCodec()
    }

    private fun timePerFrameUs(): Long {
        val format = extractor?.getTrackFormat(trackIndex)
        val fps = format?.getFloat(MediaFormat.KEY_FRAME_RATE) ?: 0f
        val durationUs = format?.getLong(MediaFormat.KEY_DURATION) ?: 0L
        val tracks = if (frameCount > 0) frameCount else 0

        val effectiveFps = when {
            fps > 0f -> fps.toDouble()
            tracks > 0 && durationUs > 0 ->
                tracks * 1_000_000.0 / durationUs
            else -> 24.0
        }

        return (1_000_000.0 / effectiveFps).toLong().coerceAtLeast(1L)
    }

    private fun flushCodec() {
        runCatching { codec?.flush() }
    }

    private fun drainUntilFrame(
        targetFrame: Int,
        codec: MediaCodec
    ): Bitmap? {
        val extractor = extractor ?: return null
        val info = MediaCodec.BufferInfo()

        var inputDone = false
        var decodedCount = 0
        // Frames before the target that we must skip after a sync-point seek.
        val skipUntil = skippedFramesBefore(targetFrame)
        var skipped = 0

        val timeoutUs = DECODE_TIMEOUT_US

        while (true) {
            if (released) return null

            // Feed input.
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(
                            inIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            // Drain output.
            val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIndex >= 0 -> {
                    val isEos =
                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                    if (info.size > 0) {
                        if (skipped < skipUntil) {
                            skipped++
                            codec.releaseOutputBuffer(outIndex, false)
                        } else {
                            val bitmap = codec.getOutputImage(outIndex)?.let { image ->
                                bitmapFromYuv(image)
                            }
                            codec.releaseOutputBuffer(outIndex, false)

                            if (bitmap != null) {
                                currentDecodedFrame = targetFrame
                                return bitmap
                            }
                        }
                    } else {
                        codec.releaseOutputBuffer(outIndex, false)
                    }

                    if (isEos) {
                        Logger.e("Reached EOS before frame $targetFrame")
                        return null
                    }
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    decodedCount++
                    if (decodedCount > max(2000, (skipUntil + 64) * 4)) {
                        Logger.e("Timed out waiting for frame $targetFrame")
                        return null
                    }
                }
                else -> {
                    // INFO_OUTPUT_FORMAT_CHANGED / buffers-count: keep looping.
                }
            }
        }
    }

    /**
     * How many frames sit between the last sync sample and [targetFrame].
     * Without an index table we approximate: after SEEK_TO_PREVIOUS_SYNC the
     * extractor restarts at the keyframe, so we simply decode forward and
     * compare presentation order via counted output frames.
     */
    private fun skippedFramesBefore(targetFrame: Int): Int {
        if (currentDecodedFrame < 0) return 0
        val delta = targetFrame - currentDecodedFrame
        return if (delta > 0) delta else 0
    }

    private fun bitmapFromYuv(image: android.media.Image): Bitmap? {
        return try {
            val planeY = image.planes[0]
            val planeU = image.planes[1]
            val planeV = image.planes[2]

            val width = image.width
            val height = image.height

            val ySize = width * height
            val uvWidth = (width + 1) / 2
            val uvHeight = (height + 1) / 2

            val argb = IntArray(width * height)

            val yBuffer = planeY.buffer
            val uBuffer = planeU.buffer
            val vBuffer = planeV.buffer

            val yRowStride = planeY.rowStride
            val uRowStride = planeU.rowStride
            val uPixStride = planeU.pixelStride

            val yBytes = ByteArray(ySize)
            val uBytes = ByteArray(uvWidth * uvHeight + uRowStride)
            val vBytes = ByteArray(uvWidth * uvHeight + uRowStride)

            yBuffer.get(yBytes, 0, minOf(yBuffer.remaining(), yBytes.size))
            uBuffer.get(uBytes, 0, minOf(uBuffer.remaining(), uBytes.size))
            vBuffer.get(vBytes, 0, minOf(vBuffer.remaining(), vBytes.size))

            var outIdx = 0
            for (row in 0 until height) {
                val yRow = row * yRowStride
                val uRow = (row / 2) * uRowStride
                for (col in 0 until width) {
                    val y = yBytes[yRow + col].toInt() and 0xFF
                    val u = uBytes[uRow + (col / 2) * uPixStride].toInt() and 0xFF
                    val v = vBytes[uRow + (col / 2) * uPixStride].toInt() and 0xFF

                    var r = (y + (11 * (v - 128))) shr 3
                    var g = (y - (5 * (u - 128)) - (11 * (v - 128))) shr 3
                    var b = (y + (22 * (u - 128))) shr 3

                    r = r.coerceIn(0, 255)
                    g = g.coerceIn(0, 255)
                    b = b.coerceIn(0, 255)

                    argb[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            var bitmap = Bitmap.createBitmap(
                argb, width, height, Bitmap.Config.ARGB_8888
            )

            if (rotationDegrees != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, m, true
                )
            }

            downscaleIfNeeded(bitmap)
        } catch (t: Throwable) {
            Logger.e("YUV->RGB conversion failed", t)
            null
        }
    }

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_OUTPUT_DIMENSION) return bitmap

        val scale = MAX_OUTPUT_DIMENSION.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true
        )
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun resetCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
        currentDecodedFrame = -1
    }
}
