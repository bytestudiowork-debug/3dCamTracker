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
 * Uses the first and last observation of each track and the estimated
 * world-to-camera poses. Monocular translation scale is arbitrary at this
 * stage; landmarks are therefore reconstruction-relative, not metric.
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
            val first = track.observations.first()
            val last = track.observations.last()
            if (first.frameIndex == last.frameIndex) {
                rejectedShort++
                continue
            }
            val firstPose = poseByFrame[first.frameIndex]
            val lastPose = poseByFrame[last.frameIndex]
            if (firstPose == null || lastPose == null) {
                rejectedMissingPose++
                continue
            }

            val projection1 = projectionMatrix(cameraMatrix, firstPose)
            val projection2 = projectionMatrix(cameraMatrix, lastPose)
            val image1 = MatOfPoint2f(Point(first.x, first.y))
            val image2 = MatOfPoint2f(Point(last.x, last.y))
            val homogeneous = Mat()

            try {
                Calib3d.triangulatePoints(
                    projection1, projection2, image1, image2, homogeneous
                )
                if (homogeneous.rows() != 4 || homogeneous.cols() != 1) {
                    rejectedGeometry++
                    continue
                }
                val wData = homogeneous.get(3, 0) ?: run {
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
                val cameraPoint1 = transform(firstPose, point)
                val cameraPoint2 = transform(lastPose, point)
                if (cameraPoint1[2] <= 1e-6 || cameraPoint2[2] <= 1e-6) {
                    rejectedGeometry++
                    continue
                }

                val error1 = reprojectionError(cameraMatrix, cameraPoint1, first)
                val error2 = reprojectionError(cameraMatrix, cameraPoint2, last)
                val meanError = (error1 + error2) * 0.5
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

    private fun projectionMatrix(cameraMatrix: Mat, pose: CameraPose): Mat {
        val extrinsic = Mat(3, 4, CvType.CV_64F)
        val values = DoubleArray(12)
        for (row in 0..2) {
            for (column in 0..2) {
                values[row * 4 + column] = pose.rotation[row * 3 + column]
            }
            values[row * 4 + 3] = pose.translation[row]
        }
        extrinsic.put(0, 0, values)
        val projection = Mat()
        Core.gemm(cameraMatrix, extrinsic, 1.0, Mat(), 0.0, projection)
        extrinsic.release()
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
