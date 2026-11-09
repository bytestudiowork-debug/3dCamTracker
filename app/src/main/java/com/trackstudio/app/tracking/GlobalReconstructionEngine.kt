package com.trackstudio.app.tracking

import com.trackstudio.app.tracking.glomap.GlomapGlobalPositioningInitializer
import com.trackstudio.app.tracking.glomap.GlomapGlobalPositioningProblemBuilder
import com.trackstudio.app.tracking.glomap.GlomapGlobalBundleAdjuster
import com.trackstudio.app.tracking.glomap.GlomapNativeSolver
import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
 import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point3
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.core.KeyPoint
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Independent global reconstruction path.
 *
 * Unlike IncrementalSfMSolver, this engine does not consume an incremental
 * camera map, seed poses, or landmarks. It extracts features for the complete
 * sequence, verifies a multi-view image-pair graph, estimates all camera
 * rotations jointly, establishes multi-view tracks, and solves camera centres
 * and points together with the global direction-constraint positioner.
 *
 * The final sparse points are retriangulated and robustly refined with the
 * mobile landmark block optimizer. Camera intrinsics are currently assumed
 * known/approximated by the caller; metric scale remains a reconstruction gauge.
 */
class GlobalReconstructionEngine(
    private val maxFeatures: Int = 1400,
    private val ratioThreshold: Float = 0.78f,
    private val maximumPairCount: Int = 420
) : ReconstructionEngine {

    internal data class FrameFeatures(
        val keypoints: Array<KeyPoint>,
        val descriptors: Mat,
        val width: Int,
        val height: Int
    )

    private data class VerifiedPair(
        val first: Int,
        val second: Int,
        val rotation: DoubleArray,
        val translationDirection: DoubleArray,
        val inlierMatches: List<Pair<Int, Int>>,
        val matchCount: Int
    )

    override suspend fun solve(
        frameCount: Int,
        cameraMatrix: Mat,
        frameProvider: suspend (Int) -> Mat?,
        onProgress: suspend (CameraTrackingProgress) -> Unit
    ): CameraTrackingResult = withContext(Dispatchers.Default) {
        require(frameCount >= 3) { "Global reconstruction needs at least three frames." }
        require(!cameraMatrix.empty() && cameraMatrix.rows() == 3 && cameraMatrix.cols() == 3) {
            "A valid 3x3 camera intrinsic matrix is required."
        }
        check(OpenCvRuntime.ensureLoaded()) { "OpenCV could not be initialized on this device." }

        val orb = ORB.create(maxFeatures.coerceIn(500, 4000))
        val frames = arrayOfNulls<FrameFeatures>(frameCount)
        var decodedFrames = 0
        fun publishProgress(phase: String, fraction: Float) {
            // Native optimizer iteration callbacks are synchronous. Bridge them
            // to the suspend progress handler so Pause really stops at the next
            // optimizer iteration instead of merely updating the UI.
            runBlocking {
                currentCoroutineContext().ensureActive()
                onProgress(CameraTrackingProgress(frameCount, frameCount, phase, fraction, decodedFrames, frameCount))
            }
        }
        try {
            // Global reconstruction starts from the entire sequence, not an
            // initialization pair selected by the Custom engine.
            for (frameIndex in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val source = frameProvider(frameIndex)
                    ?: throw IllegalStateException("Unable to decode frame ${frameIndex + 1}.")
                try {
                    val gray = toGray(source)
                    val keypoints = org.opencv.core.MatOfKeyPoint()
                    val descriptors = Mat()
                    try {
                        val mask = Mat()
                        try {
                            orb.detectAndCompute(gray, mask, keypoints, descriptors)
                        } finally {
                            mask.release()
                        }
                        frames[frameIndex] = FrameFeatures(
                            keypoints.toArray(), descriptors, source.cols(), source.rows()
                        )
                    } catch (failure: Throwable) {
                        descriptors.release()
                        throw failure
                    } finally {
                        gray.release()
                        keypoints.release()
                    }
                } finally {
                    source.release()
                }
                decodedFrames++
                onProgress(
                    CameraTrackingProgress(
                        frameIndex = frameIndex + 1,
                        frameCount = frameCount,
                        phase = "Global feature extraction",
                        overallFraction = 0.25f * decodedFrames / frameCount,
                        completedFrames = decodedFrames,
                        totalFrames = frameCount
                    )
                )
            }

            val candidatePairs = buildPairSchedule(frameCount, maximumPairCount)
            val completedPairs = AtomicInteger(0)
            val progressMutex = Mutex()
            val workerCount = minOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), 2, candidatePairs.size.coerceAtLeast(1))
            val chunks = candidatePairs.chunked((candidatePairs.size + workerCount - 1) / workerCount)
            val verifiedPairs = coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.Default) {
                        val workerMatcher = BFMatcher.create(Core.NORM_HAMMING, false)
                        try {
                            chunk.mapNotNull { pair ->
                                currentCoroutineContext().ensureActive()
                                val first = frames[pair.first] ?: return@mapNotNull null
                                val second = frames[pair.second] ?: return@mapNotNull null
                                val verified = verifyPair(pair.first, pair.second, first, second, cameraMatrix, workerMatcher)
                                val completed = completedPairs.incrementAndGet()
                                if (completed % 2 == 0 || completed == candidatePairs.size) {
                                    progressMutex.withLock {
                                        val current = completedPairs.get()
                                        onProgress(CameraTrackingProgress(
                                            frameCount, frameCount,
                                            "Verifying image pairs " + current + "/" + candidatePairs.size,
                                            0.25f + 0.30f * current / candidatePairs.size.coerceAtLeast(1),
                                            decodedFrames, frameCount
                                        ))
                                    }
                                }
                                verified
                            }
                        } finally {
                            workerMatcher.clear()
                        }
                    }
                }.awaitAll().flatten()
            }
            val totalMatches = verifiedPairs.sumOf { it.matchCount }
            val totalInliers = verifiedPairs.sumOf { it.inlierMatches.size }
            if (verifiedPairs.isEmpty()) {
                throw IllegalStateException("No image pairs passed geometric verification. Use textured footage with visible camera translation.")
            }

            val rotationEdges = verifiedPairs.map {
                GlobalRotationAverager.Edge(
                    firstFrame = it.first,
                    secondFrame = it.second,
                    relativeRotation = it.rotation,
                    inlierCount = it.inlierMatches.size
                )
            }
            val firstRotationPass = GlobalRotationAverager.solve(
                frameIndices = verifiedPairs.flatMap { listOf(it.first, it.second) }.distinct(),
                edges = rotationEdges
            ) ?: throw IllegalStateException("The verified view graph is disconnected or has insufficient rotation constraints.")
            val rotationPass = GlobalRotationAverager.solve(
                frameIndices = firstRotationPass.rotationsByFrame.keys.toList(),
                edges = firstRotationPass.acceptedEdges
            ) ?: throw IllegalStateException("Global rotation refinement failed.")
            val rotations = rotationPass.rotationsByFrame
            val activeFrames = rotations.keys.sorted()
            val activePairs = verifiedPairs.filter {
                it.first in rotations && it.second in rotations &&
                    rotationPass.acceptedEdges.any { edge ->
                        edge.firstFrame == it.first && edge.secondFrame == it.second
                    }
            }
            if (activeFrames.size < 3 || activePairs.isEmpty()) {
                throw IllegalStateException("The global view graph contains fewer than three connected cameras.")
            }

            onProgress(
                CameraTrackingProgress(
                    frameIndex = frameCount,
                    frameCount = frameCount,
                    phase = "Establishing global feature tracks",
                    overallFraction = 0.62f,
                    completedFrames = decodedFrames,
                    totalFrames = frameCount
                )
            )
            val trackBuilder = GlobalTrackGraphBuilder()
            activePairs.forEach { pair ->
                trackBuilder.addVerifiedMatches(pair.first, pair.second, pair.inlierMatches)
            }
            val tracks = trackBuilder.build(frames, rotations.keys)
                .filter { it.observations.size >= GlomapGlobalPositioningProblemBuilder.DEFAULT_MINIMUM_TRACK_VIEWS }
            if (tracks.size < 20) {
                throw IllegalStateException("Global reconstruction found only ${tracks.size} multi-view tracks; at least 20 are needed for a stable solve.")
            }

            val images = activeFrames.map { frame ->
                GlomapGlobalPositioningInitializer.ImageInput(
                    imageId = frame,
                    frameId = frame,
                    isRegistered = true
                )
            }
            val initializer = GlomapGlobalPositioningInitializer.initialize(
                frames = activeFrames.map { frame ->
                    GlomapGlobalPositioningInitializer.FrameInput(
                        frameId = frame,
                        cameraCenterFromPose = doubleArrayOf(0.0, 0.0, 0.0),
                        existingTranslation = doubleArrayOf(0.0, 0.0, 0.0)
                    )
                },
                images = images,
                imagePairs = activePairs.map {
                    GlomapGlobalPositioningInitializer.ImagePairInput(
                        firstImageId = it.first,
                        secondImageId = it.second,
                        isValid = true
                    )
                },
                tracks = tracks.map { track ->
                    GlomapGlobalPositioningInitializer.TrackInput(
                        trackId = track.id,
                        observationImageIds = track.observations.map { it.frameIndex }.distinct()
                    )
                }
            )

            val fx = cameraMatrix.get(0, 0)[0]
            val skew = cameraMatrix.get(0, 1)[0]
            val cx = cameraMatrix.get(0, 2)[0]
            val fy = cameraMatrix.get(1, 1)[0]
            val cy = cameraMatrix.get(1, 2)[0]
            require(fx.isFinite() && fy.isFinite() && fx > 0.0 && fy > 0.0) {
                "Camera intrinsics contain invalid focal lengths."
            }

            val positionTracks = tracks.map { track ->
                val observations = track.observations.mapNotNull { observation ->
                    val rotation = rotations[observation.frameIndex] ?: return@mapNotNull null
                    val center = initializer.frameTranslationsById[observation.frameIndex]
                        ?: return@mapNotNull null
                    val normalizedY = (observation.y - cy) / fy
                    val normalizedX = (observation.x - cx - skew * normalizedY) / fx
                    GlomapGlobalPositioningProblemBuilder.CameraObservation(
                        frameIndex = observation.frameIndex,
                        rotation = rotation,
                        cameraCenter = center,
                        undistortedFeatureDirection = doubleArrayOf(normalizedX, normalizedY, 1.0),
                        isCalibrated = true
                    )
                }
                GlomapGlobalPositioningProblemBuilder.Track(
                    trackId = track.id,
                    observations = observations,
                    initializedPoint = initializer.pointsByTrack[track.id]
                )
            }
            val positioningProblem = GlomapGlobalPositioningProblemBuilder.build(positionTracks)
            if (positioningProblem.constraints.size < 60) {
                throw IllegalStateException("Global positioning has too few valid track observations (${positioningProblem.constraints.size}).")
            }

            onProgress(
                CameraTrackingProgress(
                    frameIndex = frameCount,
                    frameCount = frameCount,
                    phase = "Solving global camera positions and 3D points",
                    overallFraction = 0.78f,
                    completedFrames = decodedFrames,
                    totalFrames = frameCount
                )
            )
            val positionResult = GlomapNativeSolver().solve(
                problem = positioningProblem,
                frameCentersById = initializer.frameTranslationsById,
                pointsByTrackId = initializer.pointsByTrack.mapNotNull { (id, point) ->
                    point?.let { id to it }
                }.toMap(),
                maxIterations = 100,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                onIteration = { iteration, total -> publishProgress("Global position solve " + iteration + "/" + total, 0.78f + 0.10f * iteration.toFloat() / total.coerceAtLeast(1)) }
            ) ?: throw IllegalStateException("The global camera-position and point solve did not converge to a usable solution.")

            onProgress(
                CameraTrackingProgress(
                    frameIndex = frameCount,
                    frameCount = frameCount,
                    phase = "Joint global bundle adjustment",
                    overallFraction = 0.88f,
                    completedFrames = decodedFrames,
                    totalFrames = frameCount
                )
            )
            val bundleResult = GlomapGlobalBundleAdjuster().solve(
                frameCentersById = positionResult.frameCentersById,
                rotationsByFrame = rotations,
                pointsByTrackId = positionResult.pointsByTrackId,
                tracks = tracks,
                cameraMatrix = cameraMatrix,
                maxIterations = 45,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                onIteration = { iteration, total -> publishProgress("Global bundle adjustment " + iteration + "/" + total, 0.88f + 0.05f * iteration.toFloat() / total.coerceAtLeast(1)) }
            ) ?: throw IllegalStateException("Global bundle adjustment could not produce a usable joint camera-and-point solution.")

            val poses = activeFrames.mapNotNull { frame ->
                val rotation = rotations[frame] ?: return@mapNotNull null
                val center = bundleResult.frameCentersById[frame] ?: return@mapNotNull null
                val translation = doubleArrayOf(
                    -(rotation[0] * center[0] + rotation[1] * center[1] + rotation[2] * center[2]),
                    -(rotation[3] * center[0] + rotation[4] * center[1] + rotation[5] * center[2]),
                    -(rotation[6] * center[0] + rotation[7] * center[1] + rotation[8] * center[2])
                )
                CameraPose(frame, rotation.copyOf(), translation, CameraPoseStatus.RELATIVE_ESTIMATE)
            }

            onProgress(
                CameraTrackingProgress(
                    frameIndex = frameCount,
                    frameCount = frameCount,
                    phase = "Retriangulating and refining global landmarks",
                    overallFraction = 0.94f,
                    completedFrames = decodedFrames,
                    totalFrames = frameCount
                )
            )
            val triangulated = SfMTriangulator.triangulate(
                tracks, poses, cameraMatrix,
                onProgress = { completed, total ->
                    publishProgress("Triangulating landmarks " + completed + "/" + total, 0.94f + 0.02f * completed.toFloat() / total.coerceAtLeast(1))
                }
            )
            val poseByFrame = poses.associateBy { it.frameIndex }
            val bundleLandmarks = tracks.mapNotNull trackLoop@ { track ->
                val point = bundleResult.pointsByTrackId[track.id] ?: return@trackLoop null
                val errors = track.observations.mapNotNull observationLoop@ { observation ->
                    val pose = poseByFrame[observation.frameIndex] ?: return@observationLoop null
                    reprojectionError(cameraMatrix, pose, point, observation)
                }
                if (errors.size < 2) return@trackLoop null
                CameraLandmark(
                    trackId = track.id,
                    position = Point3(point[0], point[1], point[2]),
                    observationCount = errors.size,
                    meanReprojectionErrorPx = errors.average()
                )
            }
            val mergedLandmarks = linkedMapOf<Int, CameraLandmark>()
            bundleLandmarks.forEach { mergedLandmarks[it.trackId] = it }
            triangulated.landmarks.forEach { candidate ->
                val previous = mergedLandmarks[candidate.trackId]
                if (previous == null ||
                    candidate.meanReprojectionErrorPx < previous.meanReprojectionErrorPx
                ) mergedLandmarks[candidate.trackId] = candidate
            }
            val refinedLandmarks = MobileBundleAdjuster.optimizeLandmarks(
                featureTracks = tracks,
                landmarks = mergedLandmarks.values.toList(),
                poses = poses,
                cameraMatrix = cameraMatrix,
                onProgress = { completed, total ->
                    publishProgress("Refining landmarks " + completed + "/" + total, 0.96f + 0.03f * completed.toFloat() / total.coerceAtLeast(1))
                }
            )
            publishProgress("Finalizing camera diagnostics", 0.995f)
            // Accumulate counts in one pass. The previous per-frame nested scan
            // was O(frames × tracks × observations), which became a long silent
            // tail on dense 99-frame reconstructions.
            val frameTrackCounts = IntArray(frameCount)
            tracks.forEachIndexed { trackIndex, track ->
                if (trackIndex % 512 == 0) {
                    publishProgress(
                        "Finalizing camera diagnostics " + trackIndex + "/" + tracks.size,
                        0.995f + 0.004f * trackIndex.toFloat() / tracks.size.coerceAtLeast(1)
                    )
                }
                track.observations.asSequence().map { it.frameIndex }.distinct().forEach { frame ->
                    if (frame in frameTrackCounts.indices) frameTrackCounts[frame]++
                }
            }
            val frameMatchedCounts = IntArray(frameCount)
            verifiedPairs.forEach { pair ->
                val inliers = pair.inlierMatches.size
                frameMatchedCounts[pair.first] += inliers
                frameMatchedCounts[pair.second] += inliers
            }
            val diagnostics = activeFrames.map { frame ->
                val featureCount = frames[frame]?.keypoints?.size ?: 0
                FrameTrackingDiagnostic(
                    frameIndex = frame,
                    detectedFeatures = featureCount,
                    matchedFeatures = frameMatchedCounts[frame],
                    inlierFeatures = frameTrackCounts[frame],
                    status = "Global reconstruction registered"
                )
            }
            val averageInlierRatio = verifiedPairs.map {
                it.inlierMatches.size.toDouble() / it.matchCount.coerceAtLeast(1)
            }.average().takeIf { it.isFinite() } ?: 0.0
            onProgress(
                CameraTrackingProgress(
                    frameIndex = frameCount,
                    frameCount = frameCount,
                    phase = "Global reconstruction complete",
                    overallFraction = 1f,
                    completedFrames = decodedFrames,
                    totalFrames = frameCount
                )
            )

            CameraTrackingResult(
                poses = poses,
                diagnostics = diagnostics,
                processedFrames = decodedFrames,
                solvedFramePairs = verifiedPairs.size,
                totalMatches = totalMatches,
                totalInliers = totalInliers,
                inlierRatio = averageInlierRatio,
                isCameraMotionEstimated = poses.size >= 3,
                featureTracks = tracks,
                landmarks = refinedLandmarks,
                triangulationStats = triangulated.stats,
                initialPairFrames = activePairs.maxByOrNull { it.inlierMatches.size }
                    ?.let { it.first to it.second },
                registeredCameraCount = poses.size,
                globalRotationsByFrame = rotations,
                viewGraphEdgeCount = verifiedPairs.size,
                viewGraphAcceptedEdgeCount = rotationPass.acceptedEdges.size,
                globalRotationIterations = firstRotationPass.iterations + rotationPass.iterations,
                globalPositioningApplied = true,
                globalPositioningInitialCost = bundleResult.initialCost,
                globalPositioningFinalCost = bundleResult.finalCost,
                globalPositioningIterations = positionResult.iterations + bundleResult.iterations,
                globalPositioningStatus = "Global positioning and bundle adjustment completed"
            )
        } finally {
            frames.filterNotNull().forEach { it.descriptors.release() }
            orb.clear()
        }
    }

    private fun buildPairSchedule(frameCount: Int, maximumPairs: Int): List<Pair<Int, Int>> {
        val pairs = linkedSetOf<Pair<Int, Int>>()
        // Preserve a connected temporal backbone first; without this, a hard
        // pair cap could leave the tail of a long clip outside the view graph.
        for (first in 0 until frameCount - 1) pairs += first to first + 1
        // Keep the adjacent-frame graph connected, but sample wider baselines.
        val pairBudget = maxOf(frameCount - 1, minOf(maximumPairs, frameCount * 2))
        // Add progressively wider baselines across the whole sequence before
        // spending the remaining budget on long-range loop-closure candidates.
        for (offset in listOf(2, 4, 8)) {
            for (first in 0 until frameCount - offset step offset) {
                pairs += first to first + offset
                if (pairs.size >= pairBudget) return pairs.toList()
            }
        }
        val anchorStride = (frameCount / 8).coerceAtLeast(1)
        for (first in 0 until frameCount step anchorStride) {
            for (second in listOf(frameCount - 1, frameCount / 2)) {
                if (second - first > 1) pairs += first to second
                if (pairs.size >= pairBudget) return pairs.toList()
            }
        }
        return pairs.toList()
    }

    private fun verifyPair(
        firstIndex: Int,
        secondIndex: Int,
        first: FrameFeatures,
        second: FrameFeatures,
        cameraMatrix: Mat,
        matcher: BFMatcher
    ): VerifiedPair? {
        if (first.descriptors.empty() || second.descriptors.empty() ||
            first.keypoints.isEmpty() || second.keypoints.isEmpty()
        ) return null

        val knn = mutableListOf<MatOfDMatch>()
        val matched = mutableListOf<Pair<Int, Int>>()
        val usedTrain = HashSet<Int>()
        try {
            matcher.knnMatch(first.descriptors, second.descriptors, knn, 2)
            for (row in knn) {
                val candidates = row.toArray()
                if (candidates.size < 2) continue
                val best = candidates[0]
                val next = candidates[1]
                if (best.distance >= ratioThreshold * next.distance) continue
                if (best.queryIdx !in first.keypoints.indices ||
                    best.trainIdx !in second.keypoints.indices ||
                    !usedTrain.add(best.trainIdx)
                ) continue
                matched += best.queryIdx to best.trainIdx
            }
        } finally {
            knn.forEach { it.release() }
        }
        if (matched.size < 30) return null

        val points1 = MatOfPoint2f()
        val points2 = MatOfPoint2f()
        val essentialMask = Mat()
        val bestMask = Mat()
        val bestRotation = Mat()
        val bestTranslation = Mat()
        return try {
            points1.fromList(matched.map { first.keypoints[it.first].pt })
            points2.fromList(matched.map { second.keypoints[it.second].pt })
            val essential = Calib3d.findEssentialMat(
                points1, points2, cameraMatrix, Calib3d.RANSAC,
                0.999, 1.0, 900, essentialMask
            )
            try {
                if (essential.empty() || essential.cols() != 3 ||
                    essential.rows() < 3 || essential.rows() % 3 != 0
                ) return null

                var bestInliers = 0
                for (candidateIndex in 0 until essential.rows() / 3) {
                    val candidate = essential.rowRange(candidateIndex * 3, candidateIndex * 3 + 3).clone()
                    val mask = essentialMask.clone()
                    val rotation = Mat()
                    val translation = Mat()
                    try {
                        val inliers = Calib3d.recoverPose(
                            candidate, points1, points2, cameraMatrix,
                            rotation, translation, mask
                        )
                        if (inliers > bestInliers) {
                            bestInliers = inliers
                            mask.copyTo(bestMask)
                            rotation.copyTo(bestRotation)
                            translation.copyTo(bestTranslation)
                        }
                    } finally {
                        candidate.release()
                        mask.release()
                        rotation.release()
                        translation.release()
                    }
                }
                if (bestInliers < 30 || bestRotation.empty() || bestTranslation.empty()) return null
                val rotationValues = DoubleArray(9)
                val translationValues = DoubleArray(3)
                bestRotation.get(0, 0, rotationValues)
                bestTranslation.get(0, 0, translationValues)
                val translationNorm = sqrt(translationValues.sumOf { it * it })
                if (!translationNorm.isFinite() || translationNorm < 1e-9) return null
                for (i in translationValues.indices) translationValues[i] /= translationNorm

                val inlierMatches = (0 until bestMask.rows()).mapNotNull { index ->
                    val accepted = bestMask.get(index, 0)?.firstOrNull() ?: 0.0
                    if (accepted != 0.0) matched[index] else null
                }
                if (inlierMatches.size < 30 || inlierMatches.size.toDouble() / matched.size < 0.20) return null
                VerifiedPair(
                    firstIndex, secondIndex, rotationValues,
                    translationValues, inlierMatches, matched.size
                )
            } finally {
                essential.release()
            }
        } finally {
            points1.release()
            points2.release()
            essentialMask.release()
            bestMask.release()
            bestRotation.release()
            bestTranslation.release()
        }
    }

    private fun reprojectionError(
        cameraMatrix: Mat,
        pose: CameraPose,
        point: DoubleArray,
        observation: CameraFeatureObservation
    ): Double? {
        val r = pose.rotation
        val t = pose.translation
        val x = r[0] * point[0] + r[1] * point[1] + r[2] * point[2] + t[0]
        val y = r[3] * point[0] + r[4] * point[1] + r[5] * point[2] + t[1]
        val z = r[6] * point[0] + r[7] * point[1] + r[8] * point[2] + t[2]
        if (!z.isFinite() || z <= 1e-6) return null
        val fx = cameraMatrix.get(0, 0)[0]
        val skew = cameraMatrix.get(0, 1)[0]
        val cx = cameraMatrix.get(0, 2)[0]
        val fy = cameraMatrix.get(1, 1)[0]
        val cy = cameraMatrix.get(1, 2)[0]
        val dx = (fx * x + skew * y) / z + cx - observation.x
        val dy = fy * y / z + cy - observation.y
        return sqrt(dx * dx + dy * dy).takeIf { it.isFinite() }
    }

    private fun toGray(source: Mat): Mat {
        if (source.channels() == 1) return source.clone()
        val gray = Mat()
        when (source.channels()) {
            4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            else -> {
                gray.release()
                throw IllegalArgumentException("Unsupported frame channel count: ${source.channels()}")
            }
        }
        return gray
    }
}

/**
 * Multi-view track graph. Each node is a detected feature in one frame.
 * A union is rejected if it would put two different features from the same
 * frame into one track, which prevents inconsistent loop-closure edges from
 * silently corrupting a landmark's observation list.
 */
private class GlobalTrackGraphBuilder {
    private val parent = HashMap<Long, Long>()
    private val rank = HashMap<Long, Int>()
    private val frameSets = HashMap<Long, MutableSet<Int>>()

    fun addVerifiedMatches(
        firstFrame: Int,
        secondFrame: Int,
        matches: List<Pair<Int, Int>>
    ) {
        for ((firstFeature, secondFeature) in matches) {
            val a = node(firstFrame, firstFeature)
            val b = node(secondFrame, secondFeature)
            ensure(a, firstFrame)
            ensure(b, secondFrame)
            union(a, b)
        }
    }

    fun build(
        frames: Array<GlobalReconstructionEngine.FrameFeatures?>,
        activeFrames: Set<Int>
    ): List<CameraFeatureTrack> {
        val observations = HashMap<Long, MutableMap<Int, CameraFeatureObservation>>()
        for (node in parent.keys) {
            val root = find(node)
            val frame = (node ushr 32).toInt()
            if (frame !in activeFrames) continue
            val featureIndex = node.toInt()
            val keypoint = frames[frame]?.keypoints?.getOrNull(featureIndex) ?: continue
            observations.getOrPut(root) { linkedMapOf() }[frame] =
                CameraFeatureObservation(frame, keypoint.pt.x, keypoint.pt.y)
        }
        return observations.values.mapNotNull { entries ->
            if (entries.size < 2) null
            else CameraFeatureTrack(-1, entries.values.sortedBy { it.frameIndex })
        }.mapIndexed { index, track -> track.copy(id = index) }
    }

    private fun node(frame: Int, feature: Int): Long =
        (frame.toLong() shl 32) or (feature.toLong() and 0xffffffffL)

    private fun ensure(value: Long, frame: Int) {
        if (parent.containsKey(value)) return
        parent[value] = value
        rank[value] = 0
        frameSets[value] = mutableSetOf(frame)
    }

    private fun find(value: Long): Long {
        val p = parent[value] ?: return value
        if (p == value) return value
        val root = find(p)
        parent[value] = root
        return root
    }

    private fun union(first: Long, second: Long) {
        var a = find(first)
        var b = find(second)
        if (a == b) return
        val framesA = frameSets[a] ?: mutableSetOf()
        val framesB = frameSets[b] ?: mutableSetOf()
        if (framesA.any { it in framesB }) return
        if ((rank[a] ?: 0) < (rank[b] ?: 0)) {
            val swap = a
            a = b
            b = swap
        }
        parent[b] = a
        framesA.addAll(framesB)
        frameSets.remove(b)
        if (rank[a] == rank[b]) rank[a] = (rank[a] ?: 0) + 1
    }
}
