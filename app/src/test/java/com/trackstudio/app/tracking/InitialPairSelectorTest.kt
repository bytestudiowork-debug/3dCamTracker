package com.trackstudio.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialPairSelectorTest {

    @Test
    fun selectsWellConditionedPairInsteadOfLargestRawMatchCount() {
        val highCountButWeak = candidate(
            secondFrame = 20,
            matched = 1500,
            inliers = 420,
            coverage = 3,
            medianParallax = 0.4,
            upperParallax = 1.2,
            cheirality = 0.9
        )
        val wellConditioned = candidate(
            secondFrame = 12,
            matched = 900,
            inliers = 360,
            coverage = 13,
            medianParallax = 7.0,
            upperParallax = 14.0,
            cheirality = 0.82
        )

        val result = InitialPairSelector.select(listOf(highCountButWeak, wellConditioned))

        assertEquals(wellConditioned, result.selected)
        assertTrue(result.evaluations.first().reason.contains("concentrated"))
    }

    @Test
    fun rejectsHomographyDominatedPairWithoutParallax() {
        val planar = candidate(
            matched = 800,
            inliers = 500,
            medianParallax = 1.5,
            upperParallax = 3.0,
            homographyRatio = 0.98
        )

        val result = InitialPairSelector.select(listOf(planar))

        assertNull(result.selected)
        assertTrue(result.evaluations.single().reason.contains("Homography-dominated"))
    }

    @Test
    fun rejectsPairWithPoorCheirality() {
        val candidate = candidate(cheirality = 0.2)
        val result = InitialPairSelector.select(listOf(candidate))

        assertNull(result.selected)
        assertTrue(result.evaluations.single().reason.contains("cheirality"))
    }

    @Test
    fun temporalSearchIsBoundedUniqueAndOrdered() {
        val pairs = InitialPairSelector.temporalCandidates(
            frameCount = 100,
            anchorCount = 5,
            offsets = listOf(2, 4, 4, 8),
            maximumPairs = 10
        )

        assertEquals(10, pairs.size)
        assertEquals(10, pairs.distinct().size)
        assertTrue(pairs.all { it.first < it.second && it.second < 100 })
    }

    @Test
    fun returnsNoSelectionWhenEveryPairFailsQualityGates() {
        val result = InitialPairSelector.select(
            listOf(candidate(inliers = 20), candidate(secondFrame = 9, inliers = 25))
        )

        assertNotNull(result)
        assertNull(result.selected)
        assertFalse(result.evaluations.any { it.accepted })
    }

    private fun candidate(
        secondFrame: Int = 10,
        matched: Int = 700,
        inliers: Int = 420,
        coverage: Int = 12,
        medianParallax: Double = 6.0,
        upperParallax: Double = 12.0,
        cheirality: Double = 0.8,
        homographyRatio: Double = 0.55
    ) = InitialPairCandidate(
        firstFrame = 0,
        secondFrame = secondFrame,
        matchedCount = matched,
        inlierCount = inliers,
        coveredGridCells = coverage,
        medianParallaxDegrees = medianParallax,
        upperQuartileParallaxDegrees = upperParallax,
        cheiralityRatio = cheirality,
        homographyInlierRatio = homographyRatio,
        forwardMotionRatio = 0.35
    )
}
