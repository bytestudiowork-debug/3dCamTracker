package com.trackstudio.app.tracking.core

import android.graphics.Bitmap
import com.trackstudio.app.tracking.model.ImagePoint
import com.trackstudio.app.tracking.model.TrackObservation
import com.trackstudio.app.tracking.model.TrackPoint
import com.trackstudio.app.tracking.model.TrackStatus
import com.trackstudio.app.tracking.model.TrackingQuality
import com.trackstudio.app.tracking.nativebridge.TrackingNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

data class TrackedPointResult(
    val source: ImagePoint,
    val position: ImagePoint,
    val error: Float,
    val isValid: Boolean
) {
    val confidence: Float
        get() = if (!isValid || !error.isFinite()) 0f
        else (1f - error / 32f).coerceIn(0f, 1f)
}

data class TrackingFrameResult(
    val frameIndex: Int,
    val points: List<TrackedPointResult>
)

class TrackingEngine(
    private val quality: TrackingQuality = TrackingQuality()
) {
    suspend fun detectAutomaticPoints(frame: Bitmap): List<ImagePoint> =
        withContext(Dispatchers.Default) {
            coroutineContext.ensureActive()
            requireUsableFrame(frame)
            TrackingNative.detectFeatures(
                bitmap = frame,
                maxFeatures = quality.maxFeatures,
                quality = quality.featureQuality,
                minimumDistance = quality.minimumFeatureDistance
            )
        }

    suspend fun trackManualPoints(
        previousFrame: Bitmap,
        currentFrame: Bitmap,
        points: List<ImagePoint>
    ): TrackingFrameResult = trackPoints(previousFrame, currentFrame, points)

    suspend fun trackAutomaticPoints(
        previousFrame: Bitmap,
        currentFrame: Bitmap,
        points: List<ImagePoint>
    ): TrackingFrameResult = trackPoints(previousFrame, currentFrame, points)

    private suspend fun trackPoints(
        previousFrame: Bitmap,
        currentFrame: Bitmap,
        points: List<ImagePoint>
    ): TrackingFrameResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        requireUsableFrame(previousFrame)
        requireUsableFrame(currentFrame)
        require(previousFrame.width == currentFrame.width &&
            previousFrame.height == currentFrame.height) {
            "Tracking frames must have matching dimensions."
        }
        if (points.isEmpty()) return@withContext TrackingFrameResult(0, emptyList())

        val coordinates = FloatArray(points.size * 2)
        points.forEachIndexed { index, point ->
            coordinates[index * 2] = point.x
            coordinates[index * 2 + 1] = point.y
        }

        val native = TrackingNative.nativeTrackPoints(
            previous = previousFrame,
            current = currentFrame,
            points = coordinates,
            windowSize = quality.opticalFlowWindow,
            pyramidLevels = quality.pyramidLevels
        )

        val results = points.mapIndexed { index, source ->
            val offset = index * 4
            val x = native.getOrElse(offset) { source.x }
            val y = native.getOrElse(offset + 1) { source.y }
            val error = native.getOrElse(offset + 2) { Float.POSITIVE_INFINITY }
            val valid = native.getOrElse(offset + 3) { 0f } > 0.5f &&
                error.isFinite() && error <= quality.maximumTrackingError
            TrackedPointResult(
                source = source,
                position = ImagePoint(x, y),
                error = error,
                isValid = valid
            )
        }
        TrackingFrameResult(0, results)
    }

    fun appendTrackedObservations(
        tracks: List<TrackPoint>,
        frameIndex: Int,
        results: List<TrackedPointResult>
    ): List<TrackPoint> {
        require(tracks.size == results.size)
        return tracks.zip(results).map { (track, result) ->
            track.append(
                TrackObservation(
                    frameIndex = frameIndex,
                    point = result.position,
                    confidence = result.confidence,
                    status = if (result.isValid) TrackStatus.ACTIVE else TrackStatus.LOST
                )
            )
        }
    }

    private fun requireUsableFrame(bitmap: Bitmap) {
        require(!bitmap.isRecycled) { "Tracking frame has already been released." }
        require(bitmap.width >= 16 && bitmap.height >= 16) {
            "Tracking frame is too small."
        }
        require(bitmap.config == Bitmap.Config.ARGB_8888 ||
            bitmap.config == Bitmap.Config.RGB_565) {
            "Tracking requires an ARGB_8888 or RGB_565 bitmap."
        }
    }
}
