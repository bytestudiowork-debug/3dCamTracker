package com.trackstudio.app.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.KeyPoint
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * UI-independent first stage of TrackStudio's 3D camera tracker.
 *
 * It detects ORB features, matches them between adjacent frames, estimates
 * the essential matrix with RANSAC, and recovers relative camera motion.
 * It deliberately does not claim to be a complete SfM solve yet: landmark
 * triangulation, loop closure and bundle adjustment are later engine stages.
 *
 * The frame provider is called sequentially so callers can decode one frame
 * at a time instead of retaining an entire image sequence in memory.
 */
class CameraTrackingEngine(
    private val maxFeatures: Int = 1800,
    private val ratioThreshold: Float = 0.78f,
    private val minimumMatches: Int = 12
) {

    suspend fun solve(
        frameCount: Int,
        cameraMatrix: Mat,
        frameProvider: suspend (Int) -> Mat?,
        onProgress: suspend (CameraTrackingProgress) -> Unit = {}
    ): CameraTrackingResult = withContext(Dispatchers.Default) {
        require(frameCount >= 2) { "Camera tracking needs at least two frames." }
        require(!cameraMatrix.empty() && cameraMatrix.rows() == 3 && cameraMatrix.cols() == 3) {
            "A valid 3x3 camera intrinsic matrix is required."
        }

        check(OpenCvRuntime.ensureLoaded()) {
            "OpenCV could not be initialized on this device."
        }

        val orb = ORB.create(maxFeatures.coerceIn(300, 6000))
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val poses = mutableListOf<CameraPose>()
        val diagnostics = mutableListOf<FrameTrackingDiagnostic>()

        var previousGray: Mat? = null
        var previousDescriptors: Mat? = null
        var previousKeypointArray: Array<KeyPoint>? = null
        var accumulatedRotation = Mat.eye(3, 3, CvType.CV_64F)
        var accumulatedTranslation = Mat.zeros(3, 1, CvType.CV_64F)
        var totalInliers = 0
        var totalMatches = 0
        var solvedPairs = 0

        try {
            for (frameIndex in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                onProgress(
                    CameraTrackingProgress(
                        frameIndex = frameIndex,
                        frameCount = frameCount,
                        phase = "Detecting features"
                    )
                )

                val source = frameProvider(frameIndex)
                    ?: throw IllegalStateException("Unable to decode frame ${frameIndex + 1}.")
                val gray = Mat()
                val keypoints = org.opencv.core.MatOfKeyPoint()
                val descriptors = Mat()

                try {
                    when (source.channels()) {
                        1 -> source.copyTo(gray)
                        3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
                        4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                        else -> throw IllegalArgumentException(
                            "Unsupported image channel count: ${source.channels()}"
                        )
                    }

                    orb.detectAndCompute(gray, Mat(), keypoints, descriptors)

                    if (frameIndex == 0) {
                        poses += CameraPose(
                            frameIndex = 0,
                            rotation = identity3(),
                            translation = doubleArrayOf(0.0, 0.0, 0.0)
                        )
                        diagnostics += FrameTrackingDiagnostic(
                            frameIndex = 0,
                            detectedFeatures = keypoints.rows(),
                            matchedFeatures = 0,
                            inlierFeatures = 0,
                            status = "Reference frame"
                        )
                    } else {
                        val prevDescriptors = previousDescriptors
                        val prevGray = previousGray
                        if (prevDescriptors == null || prevGray == null ||
                            prevDescriptors.empty() || descriptors.empty()
                        ) {
                            diagnostics += FrameTrackingDiagnostic(
                                frameIndex, keypoints.rows(), 0, 0,
                                "Insufficient visual features"
                            )
                        } else {
                            onProgress(
                                CameraTrackingProgress(
                                    frameIndex, frameCount, "Matching features"
                                )
                            )
                            val candidates = mutableListOf<MatOfDMatch>()
                            try {
                                matcher.knnMatch(
                                    prevDescriptors,
                                    descriptors,
                                    candidates,
                                    2
                                )

                                val previousPoints = mutableListOf<Point>()
                                val currentPoints = mutableListOf<Point>()
                                for (candidate in candidates) {
                                    val pair = candidate.toArray()
                                    if (pair.size >= 2 &&
                                        pair[0].distance < ratioThreshold * pair[1].distance
                                    ) {
                                        val a = pair[0].queryIdx
                                        val b = pair[0].trainIdx
                                        val prevArray = previousKeypointArray ?: continue
                                        val currArray = keypoints.toArray()
                                        if (a < prevArray.size && b < currArray.size) {
                                            previousPoints += prevArray[a].pt
                                            currentPoints += currArray[b].pt
                                        }
                                    }
                                }

                                val matchCount = previousPoints.size
                                totalMatches += matchCount
                                var inlierCount = 0
                                var pairStatus = "Not enough reliable matches"

                                if (matchCount >= minimumMatches) {
                                    val points1 = MatOfPoint2f()
                                    val points2 = MatOfPoint2f()
                                    val inlierMask = Mat()
                                    val essential = Mat()
                                    val rotation = Mat()
                                    val translation = Mat()
                                    try {
                                        points1.fromList(previousPoints)
                                        points2.fromList(currentPoints)
                                        essential.create(3, 3, CvType.CV_64F)
                                        val found = Calib3d.findEssentialMat(
                                            points1,
                                            points2,
                                            cameraMatrix,
                                            Calib3d.RANSAC,
                                            0.999,
                                            1.0,
                                            inlierMask
                                        )
                                        if (!found.empty()) {
                                            found.copyTo(essential)
                                            inlierCount = Calib3d.recoverPose(
                                                essential,
                                                points1,
                                                points2,
                                                cameraMatrix,
                                                rotation,
                                                translation,
                                                inlierMask
                                            )
                                        }

                                        if (inlierCount >= minimumMatches) {
                                            val nextTranslation = Mat()
                                            val nextRotation = Mat()
                                            Core.gemm(
                                                rotation,
                                                accumulatedTranslation,
                                                1.0,
                                                translation,
                                                1.0,
                                                nextTranslation
                                            )
                                            Core.gemm(
                                                rotation,
                                                accumulatedRotation,
                                                1.0,
                                                Mat(),
                                                0.0,
                                                nextRotation
                                            )
                                            accumulatedTranslation.release()
                                            accumulatedRotation.release()
                                            accumulatedTranslation = nextTranslation
                                            accumulatedRotation = nextRotation
                                            solvedPairs++
                                            pairStatus = "Camera motion estimated"
                                        } else {
                                            pairStatus = "Weak camera motion estimate"
                                        }
                                    } finally {
                                        points1.release()
                                        points2.release()
                                        inlierMask.release()
                                        essential.release()
                                        rotation.release()
                                        translation.release()
                                    }
                                }

                                totalInliers += inlierCount
                                poses += CameraPose(
                                    frameIndex = frameIndex,
                                    rotation = matrix3(accumulatedRotation),
                                    translation = vector3(accumulatedTranslation)
                                )
                                diagnostics += FrameTrackingDiagnostic(
                                    frameIndex,
                                    keypoints.rows(),
                                    matchCount,
                                    inlierCount,
                                    pairStatus
                                )
                            } finally {
                                candidates.forEach { it.release() }
                            }
                        }
                    }
                } finally {
                    source.release()
                    gray.release()
                    keypoints.release()
                }

                previousGray?.release()
                previousDescriptors?.release()
                previousGray = gray.clone()
                previousDescriptors = descriptors
                previousKeypointArray = keypoints.toArray()

                onProgress(
                    CameraTrackingProgress(
                        frameIndex = frameIndex + 1,
                        frameCount = frameCount,
                        phase = if (frameIndex == frameCount - 1) "Analysis complete" else "Tracking camera motion"
                    )
                )
            }

            val inlierRatio =
                if (totalMatches == 0) 0.0 else totalInliers.toDouble() / totalMatches
            CameraTrackingResult(
                poses = poses,
                diagnostics = diagnostics,
                processedFrames = frameCount,
                solvedFramePairs = solvedPairs,
                totalMatches = totalMatches,
                totalInliers = totalInliers,
                inlierRatio = inlierRatio,
                isCameraMotionEstimated = solvedPairs > 0
            )
        } finally {
            previousGray?.release()
            previousDescriptors?.release()
            accumulatedRotation.release()
            accumulatedTranslation.release()
            orb.clear()
        }
    }


    private fun identity3() = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
    )

    private fun matrix3(matrix: Mat): DoubleArray {
        val values = DoubleArray(9)
        matrix.get(0, 0, values)
        return values
    }

    private fun vector3(matrix: Mat): DoubleArray {
        val values = DoubleArray(3)
        matrix.get(0, 0, values)
        return values
    }
}

data class CameraTrackingProgress(
    val frameIndex: Int,
    val frameCount: Int,
    val phase: String
) {
    val fraction: Float
        get() = if (frameCount <= 0) 0f
        else (frameIndex.toFloat() / frameCount).coerceIn(0f, 1f)
}

data class CameraPose(
    val frameIndex: Int,
    val rotation: DoubleArray,
    val translation: DoubleArray
)

data class FrameTrackingDiagnostic(
    val frameIndex: Int,
    val detectedFeatures: Int,
    val matchedFeatures: Int,
    val inlierFeatures: Int,
    val status: String
)

data class CameraTrackingResult(
    val poses: List<CameraPose>,
    val diagnostics: List<FrameTrackingDiagnostic>,
    val processedFrames: Int,
    val solvedFramePairs: Int,
    val totalMatches: Int,
    val totalInliers: Int,
    val inlierRatio: Double,
    val isCameraMotionEstimated: Boolean
)
