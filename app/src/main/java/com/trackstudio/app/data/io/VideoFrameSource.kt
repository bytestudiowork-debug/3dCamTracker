package com.trackstudio.app.data.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.Image
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import kotlin.math.max


/** Provider-safe source setup. Some document providers reject the Uri overload
 * but expose the same content through an AssetFileDescriptor. */
internal fun setExtractorDataSource(context: Context, uri: Uri, extractor: MediaExtractor) {
    try {
        extractor.setDataSource(context, uri, null)
    } catch (first: Exception) {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw first
        descriptor.use {
            try {
                if (it.length >= 0L) extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                else extractor.setDataSource(it.fileDescriptor)
            } catch (fallback: Exception) {
                fallback.addSuppressed(first)
                throw fallback
            }
        }
    }
}

internal fun setRetrieverDataSource(context: Context, uri: Uri, retriever: MediaMetadataRetriever) {
    try {
        retriever.setDataSource(context, uri)
    } catch (first: Exception) {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw first
        descriptor.use {
            if (it.length >= 0L) retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            else retriever.setDataSource(it.fileDescriptor)
        }
    }
}

data class VideoSourceInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val frameRate: Double,
    val durationUs: Long,
    val rotationDegrees: Int,
    val sizeBytes: Long
)

/**
 * Reads stream metadata and counts compressed video samples. This does not
 * decode frames or write image files, so project import stays lightweight.
 */
object VideoSourceProbe {
    fun probe(context: Context, uri: Uri): VideoSourceInfo {
        val extractor = MediaExtractor()
        try {
            setExtractorDataSource(context, uri, extractor)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i
                    format = candidate
                    break
                }
            }
            check(track >= 0) { "The selected file does not contain a video stream." }
            val selected = requireNotNull(format)
            val rawWidth = selected.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = selected.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = runCatching { selected.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
            val durationUs = runCatching { selected.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            val nominalFps = runCatching { selected.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                .getOrDefault(24.0).takeIf { it > 0.0 } ?: 24.0

            extractor.selectTrack(track)
            var count = 0
            var firstPts = -1L
            var lastPts = -1L
            while (extractor.sampleTrackIndex == track) {
                val pts = extractor.sampleTime
                if (pts >= 0L) {
                    if (firstPts < 0L) firstPts = pts
                    lastPts = pts
                    count++
                }
                if (!extractor.advance()) break
            }
            check(count >= 3) { "The video contains fewer than three decodable frames." }
            val measuredFps = if (count > 1 && lastPts > firstPts) {
                (count - 1) * 1_000_000.0 / (lastPts - firstPts)
            } else nominalFps
            val size = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
                    ?: 0L
            }.getOrDefault(0L)
            val rotated = rotation % 180 != 0
            return VideoSourceInfo(
                width = if (rotated) rawHeight else rawWidth,
                height = if (rotated) rawWidth else rawHeight,
                frameCount = count,
                frameRate = measuredFps.coerceIn(1.0, 240.0),
                durationUs = durationUs,
                rotationDegrees = rotation,
                sizeBytes = size
            )
        } finally {
            extractor.release()
        }
    }
}

/**
 * Random-access, low-resolution editor preview. Tracking uses the separate
 * sequential MediaCodec decoder below and never relies on these preview frames.
 */
class VideoPreviewDecoder(
    context: Context,
    uri: Uri,
    private val rotationDegrees: Int
) : Closeable {
    private val retriever = MediaMetadataRetriever()
    private var closed = false

    init {
        setRetrieverDataSource(context, uri, retriever)
    }

    @Synchronized
    fun frameAt(index: Int, fps: Double, maxDimension: Int): Bitmap {
        check(!closed) { "Video preview decoder is closed." }
        val timeUs = (index.coerceAtLeast(0) * 1_000_000.0 / fps.coerceAtLeast(1.0)).toLong()
        val decoded = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: error("Unable to decode video preview frame ${index + 1}.")
        val rotated = rotateIfNeeded(decoded, rotationDegrees)
        val longest = max(rotated.width, rotated.height)
        if (longest <= maxDimension) return rotated
        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            rotated,
            (rotated.width * scale).toInt().coerceAtLeast(1),
            (rotated.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== rotated) rotated.recycle()
        return scaled
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            retriever.release()
        }
    }
}

/**
 * Stateful sequential decoder for reconstruction. Every compressed video
 * sample is decoded in presentation order; no timestamp seeking or frame
 * sampling is performed in this path.
 */
class SequentialVideoFrameDecoder(
    context: Context,
    uri: Uri,
    private val rotationDegrees: Int,
    private val outputScale: Float = 1f
) : Closeable {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var closed = false

    init {
        setExtractorDataSource(context, uri, extractor)
        var selectedFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                extractor.selectTrack(i)
                selectedFormat = candidate
                break
            }
        }
        val format = requireNotNull(selectedFormat) { "No video track was found." }
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    suspend fun nextFrame(): Bitmap? {
        check(!closed) { "Video tracking decoder is closed." }
        while (!outputEnded) {
            currentCoroutineContext().ensureActive()
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex)
                        ?: error("Video decoder returned an empty input buffer.")
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
            when {
                outputIndex >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val bitmap = if (info.size > 0) {
                        val image = decoder.getOutputImage(outputIndex)
                            ?: error("This device's decoder cannot expose YUV frames for tracking.")
                        image.toArgbBitmap(outputScale)
                    } else null
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (eos) outputEnded = true
                    if (bitmap != null) return rotateIfNeeded(bitmap, rotationDegrees)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            }
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }
}

private fun rotateIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
    val rotation = ((degrees % 360) + 360) % 360
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (result !== bitmap) bitmap.recycle()
    return result
}

private fun Image.toArgbBitmap(outputScale: Float): Bitmap {
    try {
        val chromaSubsample = when (format) {
            ImageFormat.YUV_420_888 -> 2 to 2
            ImageFormat.YUV_422_888 -> 2 to 1
            ImageFormat.YUV_444_888 -> 1 to 1
            else -> error("Unsupported decoded video pixel format: $format")
        }
        val crop = cropRect
        val sourceWidth = crop.width()
        val sourceHeight = crop.height()
        val scale = outputScale.coerceIn(0.125f, 1f)
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            val sy = crop.top + (y.toLong() * sourceHeight / height).toInt()
            for (x in 0 until width) {
                val sx = crop.left + (x.toLong() * sourceWidth / width).toInt()
                val yy = yBuffer.get(sy * yPlane.rowStride + sx * yPlane.pixelStride).toInt() and 255
                val uvx = sx / chromaSubsample.first
                val uvy = sy / chromaSubsample.second
                val u = (uBuffer.get(uvy * uPlane.rowStride + uvx * uPlane.pixelStride).toInt() and 255) - 128
                val v = (vBuffer.get(uvy * vPlane.rowStride + uvx * vPlane.pixelStride).toInt() and 255) - 128
                val c = max(0, yy - 16)
                val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
                row[x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    } finally {
        close()
    }
}
