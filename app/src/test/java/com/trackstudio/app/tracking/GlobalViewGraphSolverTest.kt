package com.trackstudio.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class GlobalViewGraphSolverTest {
    @Test
    fun runsThreePassGlobalRotationAveragingOnVerifiedViewGraph() {
        val truth = (0..4).associateWith { rotation(0.0, it * 0.08, it * 0.01) }
        val pairs = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 2, 1 to 3, 2 to 4, 0 to 4)
        val evaluations = pairs.map { (a, b) ->
            InitialPairEvaluation(
                candidate = candidate(a, b, relative(truth.getValue(b), truth.getValue(a))),
                accepted = true,
                score = 1.0,
                reason = "test edge"
            )
        }

        val result = GlobalViewGraphSolver.solve(evaluations)

        assertNotNull(result)
        assertEquals(pairs.size, result!!.inputEdgeCount)
        assertEquals(pairs.size, result.acceptedEdgeCount)
        assertEquals(5, result.rotationsByFrame.size)
        for ((frame, expected) in truth) {
            assertTrue(
                "frame=$frame",
                rotationError(result.rotationsByFrame.getValue(frame), expected) < 0.02
            )
        }
    }

    @Test
    fun excludesRejectedAndPoseLessEdgesFromViewGraph() {
        val good = candidate(0, 1, rotation(0.0, 0.1, 0.0))
        val missingPose = candidate(1, 2, null)
        val evaluations = listOf(
            InitialPairEvaluation(good, true, 1.0, "good"),
            InitialPairEvaluation(missingPose, true, 1.0, "no relative pose"),
            InitialPairEvaluation(candidate(2, 3, rotation(0.0, 0.1, 0.0)), false, 0.0, "rejected")
        )
        val result = GlobalViewGraphSolver.solve(evaluations)
        assertNotNull(result)
        assertEquals(1, result!!.inputEdgeCount)
        assertEquals(1, result.acceptedEdgeCount)
    }

    private fun candidate(a: Int, b: Int, r: DoubleArray?) = InitialPairCandidate(
        firstFrame = a,
        secondFrame = b,
        matchedCount = 200,
        inlierCount = 180,
        coveredGridCells = 12,
        medianParallaxDegrees = 5.0,
        upperQuartileParallaxDegrees = 10.0,
        cheiralityRatio = 0.9,
        homographyInlierRatio = 0.2,
        forwardMotionRatio = 0.2,
        relativeRotation = r
    )

    private fun rotation(x: Double, y: Double, z: Double): DoubleArray {
        val angle = kotlin.math.sqrt(x*x + y*y + z*z)
        if (angle < 1e-12) return doubleArrayOf(1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0)
        val nx=x/angle; val ny=y/angle; val nz=z/angle
        val c=cos(angle); val s=sin(angle); val t=1-c
        return doubleArrayOf(
            t*nx*nx+c, t*nx*ny-s*nz, t*nx*nz+s*ny,
            t*nx*ny+s*nz, t*ny*ny+c, t*ny*nz-s*nx,
            t*nx*nz-s*ny, t*ny*nz+s*nx, t*nz*nz+c
        )
    }
    private fun transpose(a: DoubleArray) = doubleArrayOf(a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8])
    private fun multiply(a: DoubleArray,b: DoubleArray): DoubleArray {
        val o=DoubleArray(9)
        for(r in 0..2) for(c in 0..2) for(k in 0..2) o[r*3+c]+=a[r*3+k]*b[k*3+c]
        return o
    }
    private fun relative(a: DoubleArray,b: DoubleArray)=multiply(a,transpose(b))
    private fun rotationError(a: DoubleArray,b: DoubleArray): Double {
        val d=multiply(a,transpose(b))
        return acos(((d[0]+d[4]+d[8]-1.0)*0.5).coerceIn(-1.0,1.0))
    }
}
