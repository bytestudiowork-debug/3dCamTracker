package com.trackstudio.app.tracking.model

import kotlin.math.max
import kotlin.math.min

data class ImagePoint(
    val x: Float,
    val y: Float
)

enum class TrackStatus {
    ACTIVE,
    LOST,
    OCCLUDED,
    DISABLED
}

data class TrackObservation(
    val frameIndex: Int,
    val point: ImagePoint,
    val confidence: Float,
    val status: TrackStatus = TrackStatus.ACTIVE
) {
    init {
        require(frameIndex >= 0)
        require(point.x.isFinite() && point.y.isFinite())
        require(confidence in 0f..1f)
    }
}

data class TrackPoint(
    val id: Long,
    val label: String = "",
    val observations: List<TrackObservation> = emptyList(),
    val isManuallyCreated: Boolean = true
) {
    init {
        require(id >= 0L)
        require(observations.zipWithNext().all { (a, b) -> a.frameIndex < b.frameIndex })
    }

    fun observationAt(frameIndex: Int): TrackObservation? =
        observations.firstOrNull { it.frameIndex == frameIndex }

    fun append(observation: TrackObservation): TrackPoint {
        val last = observations.lastOrNull()
        require(last == null || observation.frameIndex > last.frameIndex)
        return copy(observations = observations + observation)
    }
}

data class CameraIntrinsics(
    val imageWidth: Int,
    val imageHeight: Int,
    val focalLengthPixelsX: Float,
    val focalLengthPixelsY: Float = focalLengthPixelsX,
    val principalPointX: Float = imageWidth / 2f,
    val principalPointY: Float = imageHeight / 2f,
    val pixelAspectRatio: Float = 1f
) {
    init {
        require(imageWidth > 0 && imageHeight > 0)
        require(focalLengthPixelsX > 0f && focalLengthPixelsY > 0f)
        require(pixelAspectRatio > 0f)
    }
}

data class LensDistortion(
    val radialK1: Float = 0f,
    val radialK2: Float = 0f,
    val radialK3: Float = 0f,
    val tangentialP1: Float = 0f,
    val tangentialP2: Float = 0f
)

data class TrackingQuality(
    val maxFeatures: Int = 500,
    val featureQuality: Float = 0.01f,
    val minimumFeatureDistance: Float = 12f,
    val opticalFlowWindow: Int = 21,
    val pyramidLevels: Int = 3,
    val maximumTrackingError: Float = 24f
) {
    init {
        require(maxFeatures in 20..5000)
        require(featureQuality in 0.0001f..0.5f)
        require(minimumFeatureDistance >= 2f)
        require(opticalFlowWindow in 7..51 && opticalFlowWindow % 2 == 1)
        require(pyramidLevels in 1..6)
        require(maximumTrackingError > 0f)
    }
}

data class TrackingFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    val timestampNanos: Long? = null
) {
    init {
        require(index >= 0 && width > 0 && height > 0)
    }
}

data class TrackingProgress(
    val completedFrames: Int,
    val totalFrames: Int,
    val trackedPoints: Int,
    val lostPoints: Int
) {
    val fraction: Float
        get() = if (totalFrames <= 0) 0f else
            (completedFrames.toFloat() / totalFrames).coerceIn(0f, 1f)
}
