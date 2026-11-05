package com.trackstudio.app.tracking.glomap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BataDirectionResidualTest {
    @Test
    fun evaluatesTheOriginalGlomapBataResidual() {
        val residual = BataDirectionResidual(doubleArrayOf(2.0, 4.0, 6.0))
        val actual = residual.evaluate(
            position1 = doubleArrayOf(1.0, 2.0, 3.0),
            position2 = doubleArrayOf(2.0, 4.0, 6.0),
            scale = 2.0
        )
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), actual, 1e-12)
    }

    @Test
    fun returnsExactAnalyticJacobians() {
        val jacobians = BataDirectionResidual(doubleArrayOf(1.0, 2.0, 3.0))
            .jacobians(
                position1 = doubleArrayOf(1.0, 2.0, 3.0),
                position2 = doubleArrayOf(4.0, 6.0, 8.0),
                scale = 0.5
            )
        assertArrayEquals(
            doubleArrayOf(0.5, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.5),
            jacobians.position1,
            0.0
        )
        assertArrayEquals(
            doubleArrayOf(-0.5, 0.0, 0.0, 0.0, -0.5, 0.0, 0.0, 0.0, -0.5),
            jacobians.position2,
            0.0
        )
        assertArrayEquals(doubleArrayOf(-3.0, -4.0, -5.0), jacobians.scale, 0.0)
    }

    @Test
    fun initializedScaleUsesGlomapProjectionAndLowerBound() {
        assertEquals(
            2.0,
            BataDirectionResidual.initializeScale(
                translation = doubleArrayOf(2.0, 0.0, 0.0),
                point = doubleArrayOf(4.0, 0.0, 0.0),
                cameraCenter = doubleArrayOf(0.0, 0.0, 0.0)
            ),
            1e-12
        )
        assertEquals(
            1e-5,
            BataDirectionResidual.initializeScale(
                translation = doubleArrayOf(-2.0, 0.0, 0.0),
                point = doubleArrayOf(4.0, 0.0, 0.0),
                cameraCenter = doubleArrayOf(0.0, 0.0, 0.0)
            ),
            0.0
        )
    }
}
