package com.trackstudio.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.KeyPoint

class FeatureTrackBuilderTest {

    @Test
    fun reversePassFillsGapWithoutDuplicatingInitializationObservations() {
        val builder = FeatureTrackBuilder()
        val frame0 = keypoint(10f, 10f)
        val frame1 = keypoint(12f, 10f)
        val frame2 = keypoint(14f, 10f)
        val frame3 = keypoint(16f, 10f)

        builder.seedPair(
            firstFrame = 0,
            secondFrame = 2,
            firstKeypoints = arrayOf(frame0),
            secondKeypoints = arrayOf(frame2),
            inlierMatches = listOf(0 to 0)
        )
        builder.addInlierMatches(
            frameIndex = 3,
            previousKeypoints = arrayOf(frame2),
            currentKeypoints = arrayOf(frame3),
            matches = listOf(0 to 0)
        )

        builder.resetToFrame(2)
        builder.addInlierMatchesBackward(
            frameIndex = 1,
            laterKeypoints = arrayOf(frame2),
            earlierKeypoints = arrayOf(frame1),
            matches = listOf(0 to 0)
        )
        builder.addInlierMatchesBackward(
            frameIndex = 0,
            laterKeypoints = arrayOf(frame1),
            earlierKeypoints = arrayOf(frame0),
            matches = listOf(0 to 0)
        )

        val track = builder.build().single()
        assertEquals(listOf(0, 1, 2, 3), track.observations.map { it.frameIndex })
        assertEquals(4, track.observations.map { it.frameIndex }.distinct().size)
        assertEquals(4, track.length)
    }

    @Test
    fun reversePassCreatesTrackWhenFeatureWasNotInInitializationPair() {
        val builder = FeatureTrackBuilder()
        val frame2 = keypoint(20f, 20f)
        val frame1 = keypoint(18f, 20f)

        builder.seedPair(
            firstFrame = 0,
            secondFrame = 2,
            firstKeypoints = arrayOf(keypoint(10f, 10f)),
            secondKeypoints = arrayOf(keypoint(11f, 10f)),
            inlierMatches = listOf(0 to 0)
        )
        builder.resetToFrame(2)
        builder.addInlierMatchesBackward(
            frameIndex = 1,
            laterKeypoints = arrayOf(keypoint(11f, 10f), frame2),
            earlierKeypoints = arrayOf(keypoint(10f, 10f), frame1),
            matches = listOf(1 to 1)
        )

        val newTrack = builder.build().first { it.observations.first().x == 18.0 }
        assertEquals(listOf(1, 2), newTrack.observations.map { it.frameIndex })
    }


    @Test
    fun keyframeRelocalizationReconnectsExistingTrackAfterContinuityBreak() {
        val builder = FeatureTrackBuilder()
        val first = keypoint(10f, 10f)
        val anchor = keypoint(12f, 10f)
        val recovered = keypoint(18f, 11f)

        builder.seedPair(
            firstFrame = 0,
            secondFrame = 2,
            firstKeypoints = arrayOf(first),
            secondKeypoints = arrayOf(anchor),
            inlierMatches = listOf(0 to 0)
        )
        builder.breakContinuity()
        builder.addInlierMatchesFromFrame(
            sourceFrame = 2,
            frameIndex = 5,
            sourceKeypoints = arrayOf(anchor),
            currentKeypoints = arrayOf(recovered),
            matches = listOf(0 to 0)
        )

        val track = builder.build().single()
        assertEquals(listOf(0, 2, 5), track.observations.map { it.frameIndex })
        assertEquals(18.0, track.observations.last().x, 0.0)
    }

    @Test
    fun progressLabelAndFractionAreBounded() {
        val progress = CameraTrackingProgress(
            frameIndex = 240,
            frameCount = 200,
            phase = "Registering cameras",
            overallFraction = 1.2f,
            completedFrames = 240,
            totalFrames = 200
        )

        assertEquals("200/200 frames", progress.frameLabel)
        assertEquals(1f, progress.fraction)
        assertTrue(progress.fraction in 0f..1f)
    }

    private fun keypoint(x: Float, y: Float) = KeyPoint(x, y, 1f)
}
