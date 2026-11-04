package com.trackstudio.app.tracking

import org.opencv.core.KeyPoint

/**
 * Builds persistent 2D feature tracks from geometrically verified matches
 * between consecutive frames. It stores only observations, not image data.
 *
 * A track is continued only when its feature survives the current pair's
 * geometric verification. A failed pair breaks continuity so unrelated
 * detections are never joined into one track.
 */
internal class FeatureTrackBuilder {
    private var nextTrackId = 0
    private var previousAssignments = mutableMapOf<Int, Int>()
    private val tracks = linkedMapOf<Int, MutableList<CameraFeatureObservation>>()

    /** Seeds persistent IDs from the selected, geometrically verified initialization pair. */
    fun seedPair(
        firstFrame: Int,
        secondFrame: Int,
        firstKeypoints: Array<KeyPoint>,
        secondKeypoints: Array<KeyPoint>,
        inlierMatches: List<Pair<Int, Int>>
    ) {
        require(firstFrame >= 0 && secondFrame > firstFrame)
        val assignments = mutableMapOf<Int, Int>()
        for ((firstIndex, secondIndex) in inlierMatches) {
            if (firstIndex !in firstKeypoints.indices ||
                secondIndex !in secondKeypoints.indices ||
                secondIndex in assignments
            ) continue
            val id = nextTrackId++
            tracks[id] = mutableListOf(
                CameraFeatureObservation(
                    firstFrame,
                    firstKeypoints[firstIndex].pt.x,
                    firstKeypoints[firstIndex].pt.y
                ),
                CameraFeatureObservation(
                    secondFrame,
                    secondKeypoints[secondIndex].pt.x,
                    secondKeypoints[secondIndex].pt.y
                )
            )
            assignments[secondIndex] = id
        }
        previousAssignments = assignments
    }

    fun addInlierMatches(
        frameIndex: Int,
        previousKeypoints: Array<KeyPoint>,
        currentKeypoints: Array<KeyPoint>,
        matches: List<Pair<Int, Int>>
    ) {
        val currentAssignments = mutableMapOf<Int, Int>()

        for ((previousIndex, currentIndex) in matches) {
            if (previousIndex !in previousKeypoints.indices ||
                currentIndex !in currentKeypoints.indices ||
                currentIndex in currentAssignments
            ) continue

            val trackId = previousAssignments[previousIndex] ?: run {
                val newId = nextTrackId++
                tracks[newId] = mutableListOf(
                    CameraFeatureObservation(
                        frameIndex = frameIndex - 1,
                        x = previousKeypoints[previousIndex].pt.x,
                        y = previousKeypoints[previousIndex].pt.y
                    )
                )
                newId
            }

            val observations = tracks[trackId] ?: continue
            val lastFrame = observations.lastOrNull()?.frameIndex
            if (lastFrame != frameIndex - 1) continue

            observations += CameraFeatureObservation(
                frameIndex = frameIndex,
                x = currentKeypoints[currentIndex].pt.x,
                y = currentKeypoints[currentIndex].pt.y
            )
            currentAssignments[currentIndex] = trackId
        }

        previousAssignments = currentAssignments
    }

    fun breakContinuity() {
        previousAssignments.clear()
    }

    fun build(minimumObservations: Int = 2): List<CameraFeatureTrack> =
        tracks.mapNotNull { (id, observations) ->
            if (observations.size < minimumObservations) null
            else CameraFeatureTrack(id, observations.toList())
        }
}

data class CameraFeatureObservation(
    val frameIndex: Int,
    val x: Double,
    val y: Double
)

data class CameraFeatureTrack(
    val id: Int,
    val observations: List<CameraFeatureObservation>
) {
    val length: Int
        get() = observations.size
}
