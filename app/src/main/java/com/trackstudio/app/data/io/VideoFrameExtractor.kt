package com.trackstudio.app.data.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.Image
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

sealed class VideoExtractionProgress {
    data class Started(val estimatedFrames: Int, val width: Int, val height: Int, val frameRate: Double) : VideoExtractionProgress()
    data class FrameWritten(val done: Int, val total: Int, val name: String) : VideoExtractionProgress()
    data class Done(val outputDir: File, val frameCount: Int, val width: Int, val height: Int, val frameRate: Double, val totalSizeBytes: Long) : VideoExtractionProgress()
    data class Failed(val reason: String) : VideoExtractionProgress()
}

/** Sequential full-stream decode. Frames are persisted losslessly as PNGs. */
class VideoFrameExtractor(private val context: Context) {
    fun extract(videoUri: Uri, outputDir: File): Flow<VideoExtractionProgress> = flow {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            outputDir.deleteRecursively()
            check(outputDir.mkdirs() || outputDir.isDirectory) { "Could not create the source-frame directory." }
            extractor = MediaExtractor()
            extractor.setDataSource(context, videoUri, null)

            var videoTrack = -1
            var selectedFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val trackMime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (trackMime.startsWith("video/")) {
                    videoTrack = index
                    selectedFormat = candidate
                    break
                }
            }
            check(videoTrack >= 0) { "The selected file does not contain a supported video stream." }
            val format = requireNotNull(selectedFormat)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                .getOrDefault(24.0).takeIf { it > 0.0 } ?: 24.0
            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            val estimate = if (durationUs > 0L) max(1, (durationUs / 1_000_000.0 * fps).toInt()) else 0
            emit(VideoExtractionProgress.Started(estimate, width, height, fps))

            extractor.selectTrack(videoTrack)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var frameIndex = 0
            var totalBytes = 0L

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Video decoder returned an empty input buffer.")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val image = decoder.getOutputImage(outputIndex)
                                ?: error("This device's decoder cannot expose decoded video frames. Try a video encoded with a different codec.")
                            val bitmap = image.useAndConvertToBitmap()
                            val frameFile = File(outputDir, "frame_%06d.png".format(frameIndex))
                            FileOutputStream(frameFile).use { stream ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                    "Could not save decoded frame $frameIndex."
                                }
                            }
                            bitmap.recycle()
                            totalBytes += frameFile.length()
                            frameIndex++
                            emit(VideoExtractionProgress.FrameWritten(frameIndex, estimate, frameFile.name))
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (eos) outputEnded = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }
            check(frameIndex > 0) { "The video decoder did not produce any frames." }
            emit(VideoExtractionProgress.Done(outputDir, frameIndex, width, height, fps, totalBytes))
        } catch (cancelled: CancellationException) {
            outputDir.deleteRecursively()
            throw cancelled
        } catch (error: Exception) {
            outputDir.deleteRecursively()
            emit(VideoExtractionProgress.Failed(error.message ?: "Video frame extraction failed."))
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun Image.useAndConvertToBitmap(): Bitmap {
        try {
            check(format == ImageFormat.YUV_420_888) {
                "The video decoder returned an unsupported pixel format ($format)."
            }
            val crop = cropRect
            val width = crop.width()
            val height = crop.height()
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val sourceY = crop.top + y
                for (x in 0 until width) {
                    val sourceX = crop.left + x
                    val yy = yBuffer.get(sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride).toInt() and 0xff
                    val uvX = sourceX / 2
                    val uvY = sourceY / 2
                    val uu = (uBuffer.get(uvY * uPlane.rowStride + uvX * uPlane.pixelStride).toInt() and 0xff) - 128
                    val vv = (vBuffer.get(uvY * vPlane.rowStride + uvX * vPlane.pixelStride).toInt() and 0xff) - 128
                    val c = max(0, yy - 16)
                    val r = ((298 * c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                    val g = ((298 * c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                    val b = ((298 * c + 516 * uu + 128) shr 8).coerceIn(0, 255)
                    pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } finally {
            close()
        }
    }
}
