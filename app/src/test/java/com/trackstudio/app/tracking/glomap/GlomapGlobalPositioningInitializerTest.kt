package com.trackstudio.app.tracking.glomap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlomapGlobalPositioningInitializerTest {
    @Test
    fun mt19937MatchesPinnedStdUniformReferenceSequence() {
        val random = GlomapMt19937(1)
        val actual = DoubleArray(3) { random.uniformReal(-1.0, 1.0) }
        assertArrayEquals(
            doubleArrayOf(
                0.99436961646053112,
                0.86511472273633094,
                -0.74375110445538795
            ),
            actual,
            1e-15
        )
        assertEquals(6, random.samplesConsumed)
    }

    @Test
    fun initializesConstrainedFrameCentersAndTrackPointsInSourceOrder() {
        val frames = listOf(
            frame(10, center = doubleArrayOf(1.0, 2.0, 3.0)),
            frame(20, center = doubleArrayOf(4.0, 5.0, 6.0)),
            frame(30, center = doubleArrayOf(7.0, 8.0, 9.0))
        )
        val images = listOf(
            GlomapGlobalPositioningInitializer.ImageInput(100, 10, true),
            GlomapGlobalPositioningInitializer.ImageInput(200, 20, true),
            GlomapGlobalPositioningInitializer.ImageInput(300, 30, false)
        )
        val tracks = listOf(
            GlomapGlobalPositioningInitializer.TrackInput(1, listOf(100, 200, 300))
        )
        val result = GlomapGlobalPositioningInitializer.initialize(
            frames = frames,
            images = images,
            imagePairs = emptyList(),
            tracks = tracks
        )
        assertEquals(setOf(10, 20), result.constrainedFrameIds)
        assertArrayEquals(
            doubleArrayOf(99.436961646053112, 86.511472273633094, -74.375110445538795),
            result.frameTranslationsById.getValue(10),
            1e-12
        )
        assertArrayEquals(
            doubleArrayOf(1.0, 2.0, 3.0),
            result.cameraCentersByFrame.getValue(30),
            0.0
        )
        assertTrue(result.initializedPointTrackIds.contains(1))
        assertEquals(18, result.randomEngineSamplesConsumed)
    }

    @Test
    fun validImagePairsConstrainFramesEvenWithoutLongTracks() {
        val result = GlomapGlobalPositioningInitializer.initialize(
            frames = listOf(frame(1), frame(2)),
            images = listOf(
                GlomapGlobalPositioningInitializer.ImageInput(11, 1, false),
                GlomapGlobalPositioningInitializer.ImageInput(22, 2, false)
            ),
            imagePairs = listOf(
                GlomapGlobalPositioningInitializer.ImagePairInput(11, 22, true),
                GlomapGlobalPositioningInitializer.ImagePairInput(11, 22, false)
            ),
            tracks = emptyList()
        )
        assertEquals(setOf(1, 2), result.constrainedFrameIds)
        assertEquals(12, result.randomEngineSamplesConsumed)
    }

    @Test
    fun disabledRandomPositioningUsesExistingPoseCentersOnlyForConstrainedFrames() {
        val result = GlomapGlobalPositioningInitializer.initialize(
            frames = listOf(
                frame(1, center = doubleArrayOf(3.0, 4.0, 5.0), translation = doubleArrayOf(8.0, 8.0, 8.0)),
                frame(2, center = doubleArrayOf(6.0, 7.0, 8.0), translation = doubleArrayOf(9.0, 9.0, 9.0))
            ),
            images = listOf(GlomapGlobalPositioningInitializer.ImageInput(11, 1, true)),
            imagePairs = emptyList(),
            tracks = emptyList(),
            options = GlomapGlobalPositioningInitializer.Options(generateRandomPositions = false)
        )
        assertArrayEquals(doubleArrayOf(3.0, 4.0, 5.0), result.cameraCentersByFrame.getValue(1), 0.0)
        assertArrayEquals(doubleArrayOf(9.0, 9.0, 9.0), result.cameraCentersByFrame.getValue(2), 0.0)
        assertEquals(0, result.randomEngineSamplesConsumed)
    }

    @Test
    fun shortTracksDoNotConstrainOrReceiveRandomPoints() {
        val result = GlomapGlobalPositioningInitializer.initialize(
            frames = listOf(frame(1)),
            images = listOf(GlomapGlobalPositioningInitializer.ImageInput(11, 1, true)),
            imagePairs = emptyList(),
            tracks = listOf(
                GlomapGlobalPositioningInitializer.TrackInput(7, listOf(11, 11))
            )
        )
        assertFalse(result.constrainedFrameIds.contains(1))
        assertFalse(result.initializedPointTrackIds.contains(7))
        assertEquals(0, result.randomEngineSamplesConsumed)
    }

    private fun frame(
        id: Int,
        center: DoubleArray = doubleArrayOf(1.0, 2.0, 3.0),
        translation: DoubleArray = doubleArrayOf(4.0, 5.0, 6.0)
    ) = GlomapGlobalPositioningInitializer.FrameInput(id, center, translation)
}
