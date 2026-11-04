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
    private val assignmentsByFrame = mutableMapOf<Int, MutableMap<Int, Int>>()
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
        val firstAssignments = mutableMapOf<Int, Int>()
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
            firstAssignments[firstIndex] = id
            assignments[secondIndex] = id
        }
        assignmentsByFrame[firstFrame] = firstAssignments
        assignmentsByFrame[secondFrame] = assignments
        previousAssignments = assignments
    }

    /** Restores feature-to-track IDs at a known frame before a reverse pass. */
    fun resetToFrame(frameIndex: Int) {
        previousAssignments = assignmentsByFrame[frameIndex]?.toMutableMap() ?: mutableMapOf()
    }

    /**
     * Extends tracks backwards. Match pairs are (later-frame index, earlier-frame index).
     * Observations are inserted in frame order, including inside tracks seeded by
     * the initialization pair.
     */
    fun addInlierMatchesBackward(
        frameIndex: Int,
        laterKeypoints: Array<KeyPoint>,
        earlierKeypoints: Array<KeyPoint>,
        matches: List<Pair<Int, Int>>
    ) {
        val earlierAssignments = mutableMapOf<Int, Int>()
        for ((laterIndex, earlierIndex) in matches) {
            if (laterIndex !in laterKeypoints.indices ||
                earlierIndex !in earlierKeypoints.indices ||
                earlierIndex in earlierAssignments
            ) continue

            val trackId = previousAssignments[laterIndex] ?: run {
                val newId = nextTrackId++
                tracks[newId] = mutableListOf(
                    CameraFeatureObservation(
                        frameIndex,
                        earlierKeypoints[earlierIndex].pt.x,
                        earlierKeypoints[earlierIndex].pt.y
                    ),
                    CameraFeatureObservation(
                        frameIndex + 1,
                        laterKeypoints[laterIndex].pt.x,
                        laterKeypoints[laterIndex].pt.y
                    )
                )
                newId
            }

            val observations = tracks[trackId] ?: continue
            val hasLaterObservation = observations.any { it.frameIndex == frameIndex + 1 }
            if (!hasLaterObservation) continue
            if (observations.none { it.frameIndex == frameIndex }) {
                val observation = CameraFeatureObservation(
                    frameIndex,
                    earlierKeypoints[earlierIndex].pt.x,
                    earlierKeypoints[earlierIndex].pt.y
                )
                val insertAt = observations.indexOfFirst { it.frameIndex > frameIndex }
                if (insertAt < 0) observations.add(observation)
                else observations.add(insertAt, observation)
            }
            earlierAssignments[earlierIndex] = trackId
        }
        assignmentsByFrame[frameIndex] = earlierAssignments
        previousAssignments = earlierAssignments
    }

    fun addInlierMatches(
        frameIndex: Int,
        previousKeypoints: Array<KeyPoint>,
        currentKeypoints: Array<KeyPoint>,
        matches: List<Pair<Int, Int>>
    ) {
        val previousFrameAssignments = previousAssignments.toMutableMap()
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
                previousFrameAssignments[previousIndex] = newId
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

        assignmentsByFrame[frameIndex - 1] = previousFrameAssignments
        assignmentsByFrame[frameIndex] = currentAssignments
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
