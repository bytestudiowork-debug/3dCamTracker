package com.trackstudio.app.tracking.core

import android.graphics.Bitmap
import com.trackstudio.app.tracking.model.ImagePoint
import com.trackstudio.app.tracking.model.TrackObservation
import com.trackstudio.app.tracking.model.TrackPoint
import com.trackstudio.app.tracking.model.TrackStatus
import com.trackstudio.app.tracking.model.TrackingQuality
import com.trackstudio.app.tracking.nativebridge.TrackingNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
        points: List<ImagePoint>,
        targetFrameIndex: Int
    ): TrackingFrameResult =
        trackPoints(previousFrame, currentFrame, points, targetFrameIndex)

    suspend fun trackAutomaticPoints(
        previousFrame: Bitmap,
        currentFrame: Bitmap,
        points: List<ImagePoint>,
        targetFrameIndex: Int
    ): TrackingFrameResult {
        val forward = trackPoints(previousFrame, currentFrame, points, targetFrameIndex)
        coroutineContext.ensureActive()
        val validIndices = forward.points.indices.filter { forward.points[it].isValid }
        if (validIndices.isEmpty()) return forward

        val reverseInput = validIndices.map { forward.points[it].position }
        val reverse = trackPoints(currentFrame, previousFrame, reverseInput, targetFrameIndex - 1)
        val tolerance = maxOf(1.5f, quality.opticalFlowWindow * 0.15f)
        val checked = forward.points.toMutableList()

        validIndices.forEachIndexed { reverseIndex, forwardIndex ->
            val back = reverse.points[reverseIndex]
            val original = forward.points[forwardIndex].source
            val dx = back.position.x - original.x
            val dy = back.position.y - original.y
            val consistent = back.isValid && dx * dx + dy * dy <= tolerance * tolerance
            if (!consistent) {
                checked[forwardIndex] = checked[forwardIndex].copy(isValid = false)
            }
        }
        return forward.copy(points = checked)
    }

    private suspend fun trackPoints(
        previousFrame: Bitmap,
        currentFrame: Bitmap,
        points: List<ImagePoint>,
        targetFrameIndex: Int
    ): TrackingFrameResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        require(targetFrameIndex >= 0) { "Target frame index cannot be negative." }
        requireUsableFrame(previousFrame)
        requireUsableFrame(currentFrame)
        require(previousFrame.width == currentFrame.width &&
            previousFrame.height == currentFrame.height) {
            "Tracking frames must have matching dimensions."
        }
        if (points.isEmpty()) return@withContext TrackingFrameResult(targetFrameIndex, emptyList())

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
        TrackingFrameResult(targetFrameIndex, results)
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
