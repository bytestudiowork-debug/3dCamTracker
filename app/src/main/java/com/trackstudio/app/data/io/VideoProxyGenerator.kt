package com.trackstudio.app.data.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream

/**
 * Builds the same numbered JPEG proxy sequence used by image projects, but
 * decodes each video presentation timestamp from the original source. This
 * output is only for editor playback; Global tracking still reads the source.
 */
class VideoProxyGenerator(private val context: Context) {
    fun generate(
        uri: Uri,
        frameCount: Int,
        rotationDegrees: Int,
        scale: Float,
        jpegQuality: Int,
        outputDir: File
    ): Flow<ProxyProgress> = flow {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            setExtractorDataSource(context, uri, extractor)
            var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = index
                    extractor.selectTrack(index)
                    break
                }
            }
            check(videoTrack >= 0) { "The selected file does not contain a video track." }

            val timestamps = ArrayList<Long>(frameCount)
            while (extractor.sampleTrackIndex == videoTrack) {
                val timestamp = extractor.sampleTime
                if (timestamp >= 0L) timestamps += timestamp
                if (!extractor.advance()) break
            }
            check(timestamps.size >= frameCount) {
                "The video provider exposed ${timestamps.size} samples, but the project expects $frameCount."
            }

            setRetrieverDataSource(context, uri, retriever)
            if (!outputDir.exists()) check(outputDir.mkdirs()) { "Could not create proxy directory." }
            // Clear stale output so incomplete retries cannot masquerade as a valid cache.
            outputDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("proxy_") && it.name.endsWith(".jpg") }
                ?.forEach { check(it.delete()) { "Could not clear an old proxy frame: ${it.name}" } }
            val proxyScale = scale.coerceIn(0.1f, 1f)
            val quality = jpegQuality.coerceIn(40, 100)
            // Resolve target dimensions once; metadata access per frame is expensive.
            val sourceWidth = runCatching { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() }
                .getOrNull()?.coerceAtLeast(1) ?: 1
            val sourceHeight = runCatching { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() }
                .getOrNull()?.coerceAtLeast(1) ?: 1
            val rotatedSource = rotationDegrees % 180 != 0
            val orientedWidth = if (rotatedSource) sourceHeight else sourceWidth
            val orientedHeight = if (rotatedSource) sourceWidth else sourceHeight
            val targetWidth = (orientedWidth * proxyScale).toInt().coerceAtLeast(1)
            val targetHeight = (orientedHeight * proxyScale).toInt().coerceAtLeast(1)
            // MediaMetadataRetriever decodes the coded (pre-rotation) frame.
            val decoderWidth = (sourceWidth * proxyScale).toInt().coerceAtLeast(1)
            val decoderHeight = (sourceHeight * proxyScale).toInt().coerceAtLeast(1)

            for (index in 0 until frameCount) {
                emit(ProxyProgress.Generating(index, frameCount))
                // Ask Android's decoder for the proxy dimensions directly. On API 27+
                // this avoids allocating a full-resolution Bitmap only to throw most
                // of its pixels away during a second resize. Older devices retain the
                // compatible full-decode + resize fallback.
                val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && proxyScale < 0.999f) {
                    runCatching {
                        retriever.getScaledFrameAtTime(
                            timestamps[index],
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            decoderWidth,
                            decoderHeight
                        )
                    }.getOrNull()
                } else {
                    retriever.getFrameAtTime(
                        timestamps[index],
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } ?: error("Unable to decode video frame ${index + 1} for proxy generation.")

                val rotated = rotateFrame(decoded, rotationDegrees)
                val scaled = if (rotated.width != targetWidth || rotated.height != targetHeight) {
                    Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true).also {
                        if (it !== rotated) rotated.recycle()
                    }
                } else rotated

                val destination = File(outputDir, "proxy_%05d.jpg".format(index))
                val written = FileOutputStream(destination).use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                }
                scaled.recycle()
                check(written) { "Could not write proxy frame ${index + 1}." }
                yield()
            }
            emit(ProxyProgress.Done(outputDir))
        } catch (cancelled: CancellationException) {
            outputDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("proxy_") && it.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
            throw cancelled
        } catch (error: Exception) {
            outputDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("proxy_") && it.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
            emit(ProxyProgress.Failed(error.message ?: "Video proxy generation failed."))
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun rotateFrame(bitmap: Bitmap, degrees: Int): Bitmap {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }
}
