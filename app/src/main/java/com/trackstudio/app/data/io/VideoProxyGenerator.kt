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
 * Produces editor and tracking proxies from one sequential timestamp list.
 * The viewer proxy is clearer; the tracking proxy is smaller but retains every
 * source presentation timestamp. Each source frame is decoded only once.
 */
class VideoProxyGenerator(private val context: Context) {
    fun generate(
        uri: Uri, frameCount: Int, rotationDegrees: Int, scale: Float,
        jpegQuality: Int, outputDir: File
    ): Flow<ProxyProgress> = generateInternal(
        uri, frameCount, rotationDegrees, scale, scale, jpegQuality, outputDir, null
    )

    fun generateDual(
        uri: Uri, frameCount: Int, rotationDegrees: Int,
        viewerScale: Float, trackingScale: Float, jpegQuality: Int,
        viewerOutputDir: File, trackingOutputDir: File
    ): Flow<ProxyProgress> = generateInternal(
        uri, frameCount, rotationDegrees, viewerScale, trackingScale,
        jpegQuality, viewerOutputDir, trackingOutputDir
    )

    private fun generateInternal(
        uri: Uri, frameCount: Int, rotationDegrees: Int,
        requestedViewerScale: Float, requestedTrackingScale: Float,
        jpegQuality: Int, viewerOutputDir: File, trackingOutputDir: File?
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

            // Preserve every compressed video sample in presentation order.
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
            prepareDirectory(viewerOutputDir)
            trackingOutputDir?.let(::prepareDirectory)

            val viewerScale = requestedViewerScale.coerceIn(0.25f, 1f)
            val trackingScale = requestedTrackingScale.coerceIn(0.125f, viewerScale)
            val viewerQuality = jpegQuality.coerceIn(40, 100)
            val trackingQuality = maxOf(viewerQuality, 90)

            val sourceWidth = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            }.getOrNull()?.coerceAtLeast(1) ?: 1
            val sourceHeight = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
            }.getOrNull()?.coerceAtLeast(1) ?: 1
            val rotatedSource = rotationDegrees % 180 != 0
            val orientedWidth = if (rotatedSource) sourceHeight else sourceWidth
            val orientedHeight = if (rotatedSource) sourceWidth else sourceHeight
            val viewerWidth = (orientedWidth * viewerScale).toInt().coerceAtLeast(1)
            val viewerHeight = (orientedHeight * viewerScale).toInt().coerceAtLeast(1)
            val trackingWidth = (orientedWidth * trackingScale).toInt().coerceAtLeast(1)
            val trackingHeight = (orientedHeight * trackingScale).toInt().coerceAtLeast(1)
            val decoderWidth = (sourceWidth * viewerScale).toInt().coerceAtLeast(1)
            val decoderHeight = (sourceHeight * viewerScale).toInt().coerceAtLeast(1)

            for (index in 0 until frameCount) {
                emit(ProxyProgress.Generating(index, frameCount))
                val scaledDecode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && viewerScale < 0.999f) {
                    runCatching {
                        retriever.getScaledFrameAtTime(
                            timestamps[index], MediaMetadataRetriever.OPTION_CLOSEST,
                            decoderWidth, decoderHeight
                        )
                    }.getOrNull()
                } else null
                val sourceFrame = scaledDecode ?: retriever.getFrameAtTime(
                    timestamps[index], MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: error("Unable to decode video frame ${index + 1} for proxy generation.")

                val oriented = rotateFrame(sourceFrame, rotationDegrees)
                val viewerFrame = if (oriented.width != viewerWidth || oriented.height != viewerHeight) {
                    Bitmap.createScaledBitmap(oriented, viewerWidth, viewerHeight, true).also {
                        if (it !== oriented) oriented.recycle()
                    }
                } else oriented

                try {
                    writeJpeg(viewerFrame, File(viewerOutputDir, frameName(index)), viewerQuality)
                    if (trackingOutputDir != null) {
                        val trackingFrame = if (viewerFrame.width != trackingWidth || viewerFrame.height != trackingHeight) {
                            Bitmap.createScaledBitmap(viewerFrame, trackingWidth, trackingHeight, true)
                        } else viewerFrame
                        try {
                            writeJpeg(trackingFrame, File(trackingOutputDir, frameName(index)), trackingQuality)
                        } finally {
                            if (trackingFrame !== viewerFrame) trackingFrame.recycle()
                        }
                    }
                } finally {
                    viewerFrame.recycle()
                }
                yield()
            }
            emit(ProxyProgress.Done(viewerOutputDir))
        } catch (cancelled: CancellationException) {
            clearProxyFrames(viewerOutputDir)
            trackingOutputDir?.let(::clearProxyFrames)
            throw cancelled
        } catch (error: Exception) {
            clearProxyFrames(viewerOutputDir)
            trackingOutputDir?.let(::clearProxyFrames)
            emit(ProxyProgress.Failed(error.message ?: "Video proxy generation failed."))
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun prepareDirectory(directory: File) {
        if (!directory.exists()) check(directory.mkdirs()) { "Could not create proxy directory." }
        clearProxyFrames(directory)
    }

    private fun clearProxyFrames(directory: File) {
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("proxy_") && it.name.endsWith(".jpg") }
            ?.forEach { it.delete() }
    }

    private fun writeJpeg(bitmap: Bitmap, destination: File, quality: Int) {
        val written = FileOutputStream(destination).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        check(written) { "Could not write proxy frame: ${destination.name}." }
    }

    private fun frameName(index: Int) = "proxy_%05d.jpg".format(index)

    private fun rotateFrame(bitmap: Bitmap, degrees: Int): Bitmap {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }
}
