package com.trackstudio.app.data.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
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
            val proxyScale = scale.coerceIn(0.1f, 1f)
            val quality = jpegQuality.coerceIn(40, 100)

            for (index in 0 until frameCount) {
                emit(ProxyProgress.Generating(index, frameCount))
                val decoded = retriever.getFrameAtTime(
                    timestamps[index],
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: error("Unable to decode video frame ${index + 1} for proxy generation.")

                val rotated = rotateFrame(decoded, rotationDegrees)
                val longest = maxOf(rotated.width, rotated.height)
                val targetWidth = (rotated.width * proxyScale).toInt().coerceAtLeast(1)
                val targetHeight = (rotated.height * proxyScale).toInt().coerceAtLeast(1)
                val scaled = if (proxyScale < 0.999f) {
                    Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true)
                } else rotated
                if (scaled !== rotated) rotated.recycle()

                val destination = File(outputDir, "proxy_%05d.jpg".format(index))
                val written = FileOutputStream(destination).use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                }
                scaled.recycle()
                check(written) { "Could not write proxy frame ${index + 1}." }
                yield()
            }
            emit(ProxyProgress.Done(outputDir))
        } catch (error: Exception) {
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
