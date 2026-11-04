package com.trackstudio.app.tracking

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Core
import org.opencv.core.MatOfPoint2f
import kotlin.math.sqrt

/**
 * Initial sparse two-view triangulation stage.
 *
 * Selects the observation pair with the widest camera-center baseline,
 * triangulates from that pair, then validates the point against every
 * available observation. Monocular translation scale remains arbitrary.
 */
internal object SfMTriangulator {

    fun triangulate(
        featureTracks: List<CameraFeatureTrack>,
        poses: List<CameraPose>,
        cameraMatrix: Mat,
        maximumReprojectionErrorPx: Double = 4.0
    ): TriangulationResult {
        val poseByFrame = poses.associateBy { it.frameIndex }
        val landmarks = mutableListOf<CameraLandmark>()
        var rejectedShort = 0
        var rejectedMissingPose = 0
        var rejectedGeometry = 0

        for (track in featureTracks) {
            if (track.observations.size < 2) {
                rejectedShort++
                continue
            }
            val validObservations = track.observations.mapNotNull { observation ->
                poseByFrame[observation.frameIndex]?.let { observation to it }
            }
            if (validObservations.size < 2) {
                rejectedMissingPose++
                continue
            }

            // A wider camera-center baseline generally provides stronger depth
            // constraints than blindly selecting the first and last timestamps.
            var selectedPair: Pair<Pair<CameraFeatureObservation, CameraPose>, Pair<CameraFeatureObservation, CameraPose>>? = null
            var widestBaselineSquared = -1.0
            for (firstIndex in 0 until validObservations.lastIndex) {
                for (lastIndex in firstIndex + 1 until validObservations.size) {
                    val firstCandidate = validObservations[firstIndex]
                    val lastCandidate = validObservations[lastIndex]
                    val baselineSquared = cameraCenterDistanceSquared(
                        firstCandidate.second, lastCandidate.second
                    )
                    if (baselineSquared > widestBaselineSquared) {
                        widestBaselineSquared = baselineSquared
                        selectedPair = firstCandidate to lastCandidate
                    }
                }
            }
            val pair = selectedPair
            if (pair == null || widestBaselineSquared < 1e-10) {
                rejectedGeometry++
                continue
            }
            val (firstObservation, firstPose) = pair.first
            val (lastObservation, lastPose) = pair.second

            val projection1 = projectionMatrix(cameraMatrix, firstPose)
            val projection2 = projectionMatrix(cameraMatrix, lastPose)
            val image1 = MatOfPoint2f(Point(firstObservation.x, firstObservation.y))
            val image2 = MatOfPoint2f(Point(lastObservation.x, lastObservation.y))
            val homogeneous = Mat()

            try {
                Calib3d.triangulatePoints(
                    projection1, projection2, image1, image2, homogeneous
                )
                if (homogeneous.rows() != 4 || homogeneous.cols() != 1) {
                    rejectedGeometry++
                    continue
                }
                val wData = homogeneous.get(3, 0)
                if (wData == null || wData.isEmpty()) {
                    rejectedGeometry++
                    continue
                }
                val w = wData[0]
                if (!w.isFinite() || kotlin.math.abs(w) < 1e-9) {
                    rejectedGeometry++
                    continue
                }
                val x = (homogeneous.get(0, 0)?.get(0) ?: Double.NaN) / w
                val y = (homogeneous.get(1, 0)?.get(0) ?: Double.NaN) / w
                val z = (homogeneous.get(2, 0)?.get(0) ?: Double.NaN) / w
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
                    rejectedGeometry++
                    continue
                }

                val point = doubleArrayOf(x, y, z)
                var reprojectionErrorSum = 0.0
                var validProjectionCount = 0
                var hasNegativeDepth = false
                for ((observation, pose) in validObservations) {
                    val cameraPoint = transform(pose, point)
                    if (cameraPoint[2] <= 1e-6) {
                        hasNegativeDepth = true
                        break
                    }
                    val error = reprojectionError(cameraMatrix, cameraPoint, observation)
                    if (!error.isFinite()) {
                        hasNegativeDepth = true
                        break
                    }
                    reprojectionErrorSum += error
                    validProjectionCount++
                }
                if (hasNegativeDepth || validProjectionCount < 2) {
                    rejectedGeometry++
                    continue
                }
                val meanError = reprojectionErrorSum / validProjectionCount
                if (!meanError.isFinite() || meanError > maximumReprojectionErrorPx) {
                    rejectedGeometry++
                    continue
                }

                landmarks += CameraLandmark(
                    trackId = track.id,
                    position = Point3(x, y, z),
                    observationCount = track.observations.size,
                    meanReprojectionErrorPx = meanError
                )
            } finally {
                projection1.release()
                projection2.release()
                image1.release()
                image2.release()
                homogeneous.release()
            }
        }

        return TriangulationResult(
            landmarks = landmarks,
            stats = TriangulationStats(
                inputTracks = featureTracks.size,
                triangulatedLandmarks = landmarks.size,
                rejectedShortTracks = rejectedShort,
                rejectedMissingPoses = rejectedMissingPose,
                rejectedInvalidGeometry = rejectedGeometry,
                meanReprojectionErrorPx = if (landmarks.isEmpty()) 0.0
                    else landmarks.map { it.meanReprojectionErrorPx }.average()
            )
        )
    }

    private fun cameraCenterDistanceSquared(first: CameraPose, second: CameraPose): Double {
        fun center(pose: CameraPose): DoubleArray {
            val r = pose.rotation
            val t = pose.translation
            // Camera center in world coordinates: C = -R^T * t.
            return doubleArrayOf(
                -(r[0] * t[0] + r[3] * t[1] + r[6] * t[2]),
                -(r[1] * t[0] + r[4] * t[1] + r[7] * t[2]),
                -(r[2] * t[0] + r[5] * t[1] + r[8] * t[2])
            )
        }
        val a = center(first)
        val b = center(second)
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz
    }

    private fun projectionMatrix(cameraMatrix: Mat, pose: CameraPose): Mat {
        val extrinsic = Mat(3, 4, CvType.CV_64F)
        val values = DoubleArray(12)
        for (row in 0..2) {
            for (column in 0..2) {
                values[row * 4 + column] = pose.rotation[row * 3 + column]
            }
            values[row * 4 + 3] = pose.translation[row]
        }
        extrinsic.put(0, 0, *values)
        val projection = Mat()
        val zero = Mat.zeros(3, 4, CvType.CV_64F)
        try {
            Core.gemm(cameraMatrix, extrinsic, 1.0, zero, 0.0, projection)
        } finally {
            zero.release()
            extrinsic.release()
        }
        return projection
    }

    private fun transform(pose: CameraPose, point: DoubleArray): DoubleArray {
        val r = pose.rotation
        val t = pose.translation
        return doubleArrayOf(
            r[0] * point[0] + r[1] * point[1] + r[2] * point[2] + t[0],
            r[3] * point[0] + r[4] * point[1] + r[5] * point[2] + t[1],
            r[6] * point[0] + r[7] * point[1] + r[8] * point[2] + t[2]
        )
    }

    private fun reprojectionError(
        cameraMatrix: Mat,
        cameraPoint: DoubleArray,
        observation: CameraFeatureObservation
    ): Double {
        val fx = cameraMatrix.get(0, 0)[0]
        val skew = cameraMatrix.get(0, 1)[0]
        val cx = cameraMatrix.get(0, 2)[0]
        val fy = cameraMatrix.get(1, 1)[0]
        val cy = cameraMatrix.get(1, 2)[0]
        val u = (fx * cameraPoint[0] + skew * cameraPoint[1]) / cameraPoint[2] + cx
        val v = fy * cameraPoint[1] / cameraPoint[2] + cy
        val dx = u - observation.x
        val dy = v - observation.y
        return sqrt(dx * dx + dy * dy)
    }
}

internal data class TriangulationResult(
    val landmarks: List<CameraLandmark>,
    val stats: TriangulationStats
)

data class CameraLandmark(
    val trackId: Int,
    val position: Point3,
    val observationCount: Int,
    val meanReprojectionErrorPx: Double
)

data class TriangulationStats(
    val inputTracks: Int = 0,
    val triangulatedLandmarks: Int = 0,
    val rejectedShortTracks: Int = 0,
    val rejectedMissingPoses: Int = 0,
    val rejectedInvalidGeometry: Int = 0,
    val meanReprojectionErrorPx: Double = 0.0
)
