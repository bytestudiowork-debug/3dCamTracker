package com.trackstudio.app.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.core.KeyPoint
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Incremental, map-based camera solve.
 *
 * Pass one evaluates a bounded set of temporal pairs and selects a geometrically
 * well-conditioned initialization. Pass two seeds persistent tracks from that
 * pair, registers later frames with PnP against the same 3D map, and periodically
 * adds triangulated landmarks. No per-frame recoverPose translation is accumulated.
 *
 * The sparse map uses the arbitrary unit baseline of the initial pair. It is
 * internally consistent, but has no metric scale until a user supplies scale.
 */
internal class IncrementalSfMSolver(
    private val maxFeatures: Int = 1800,
    private val ratioThreshold: Float = 0.78f
) {
    private data class CachedFrame(
        val keypoints: Array<KeyPoint>,
        val descriptors: Mat,
        val width: Int,
        val height: Int
    )

    private data class PairGeometry(
        val matches: List<Pair<Int, Int>>,
        val rotation: DoubleArray,
        val translation: DoubleArray,
        val inlierCount: Int
    )

    suspend fun solve(
        frameCount: Int,
        cameraMatrix: Mat,
        frameProvider: suspend (Int) -> Mat?,
        onProgress: suspend (CameraTrackingProgress) -> Unit
    ): CameraTrackingResult = withContext(Dispatchers.Default) {
        require(frameCount >= 3) { "Camera tracking needs at least three frames for initialization." }
        require(!cameraMatrix.empty() && cameraMatrix.rows() == 3 && cameraMatrix.cols() == 3) {
            "A valid 3x3 camera intrinsic matrix is required."
        }
        check(OpenCvRuntime.ensureLoaded()) { "OpenCV could not be initialized on this device." }

        val pairCandidates = InitialPairSelector.temporalCandidates(
            frameCount = frameCount,
            anchorCount = 6,
            offsets = listOf(2, 4, 6, 9, 13, 18, 24, 32),
            maximumPairs = 36
        )
        require(pairCandidates.isNotEmpty()) { "No initialization frame pairs are available." }
        val candidateFrames = pairCandidates.flatMap { listOf(it.first, it.second) }.toSet()
        val processedFrameFlags = BooleanArray(frameCount)
        var processedFrameCount = 0
        fun markFrameProcessed(index: Int) {
            if (index in processedFrameFlags.indices && !processedFrameFlags[index]) {
                processedFrameFlags[index] = true
                processedFrameCount++
            }
        }
        val cache = linkedMapOf<Int, CachedFrame>()
        val orb = ORB.create(maxFeatures.coerceIn(400, 5000))
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

        try {
            // Bounded first pass: retain descriptors only for frames participating
            // in the coarse pair schedule, not for every frame in a long sequence.
            val orderedCandidateFrames = candidateFrames.sorted()
            for ((sampleIndex, index) in orderedCandidateFrames.withIndex()) {
                currentCoroutineContext().ensureActive()
                val source = frameProvider(index)
                    ?: throw IllegalStateException("Unable to decode frame ${index + 1}.")
                try {
                    if (index in candidateFrames) {
                        val gray = toGray(source)
                        val keypoints = org.opencv.core.MatOfKeyPoint()
                        val descriptors = Mat()
                        try {
                            val detectionMask = Mat()
                            try {
                                orb.detectAndCompute(gray, detectionMask, keypoints, descriptors)
                            } finally {
                                detectionMask.release()
                            }
                            cache[index] = CachedFrame(
                                keypoints.toArray(), descriptors,
                                source.cols(), source.rows()
                            )
                        } catch (t: Throwable) {
                            descriptors.release()
                            throw t
                        } finally {
                            gray.release()
                            keypoints.release()
                        }
                    }
                } finally {
                    source.release()
                }
                markFrameProcessed(index)
                onProgress(
                    CameraTrackingProgress(
                        frameIndex = index + 1,
                        frameCount = frameCount,
                        phase = "Analyzing candidate frames",
                        overallFraction = 0.25f * (sampleIndex + 1) / orderedCandidateFrames.size,
                        completedFrames = processedFrameCount,
                        totalFrames = frameCount
                    )
                )
            }

            val evaluations = mutableListOf<InitialPairEvaluation>()
            pairCandidates.forEachIndexed { candidateIndex, pair ->
                currentCoroutineContext().ensureActive()
                val first = cache[pair.first]
                val second = cache[pair.second]
                if (first != null && second != null) {
                    evaluations += OpenCvInitialPairEvaluator.evaluate(
                        firstFrame = pair.first,
                        secondFrame = pair.second,
                        firstKeypoints = first.keypoints,
                        firstDescriptors = first.descriptors,
                        secondKeypoints = second.keypoints,
                        secondDescriptors = second.descriptors,
                        cameraMatrix = cameraMatrix,
                        frameWidth = minOf(first.width, second.width),
                        frameHeight = minOf(first.height, second.height)
                    )
                }
                onProgress(
                    CameraTrackingProgress(
                        frameIndex = frameCount,
                        frameCount = frameCount,
                        phase = "Evaluating camera-pair geometry (${candidateIndex + 1}/${pairCandidates.size})",
                        overallFraction = 0.25f + 0.10f * (candidateIndex + 1) / pairCandidates.size,
                        completedFrames = processedFrameCount,
                        totalFrames = frameCount
                    )
                )
            }

            val selection = InitialPairSelector.select(evaluations.map { it.candidate })
            val selected = selection.selected
                ?: throw IllegalStateException(
                    "No reliable 3D initialization pair: ${selection.reason}. Try footage with visible texture and sideways camera movement."
                )
            val firstFrame = cache[selected.firstFrame]
                ?: error("Selected first frame was not retained.")
            val secondFrame = cache[selected.secondFrame]
                ?: error("Selected second frame was not retained.")

            onProgress(
                CameraTrackingProgress(
                    selected.secondFrame + 1,
                    frameCount,
                    "Initializing 3D camera map",
                    overallFraction = 0.36f,
                    completedFrames = processedFrameCount,
                    totalFrames = frameCount
                )
            )
            val geometry = estimatePairGeometry(
                firstFrame, secondFrame, cameraMatrix, matcher
            ) ?: throw IllegalStateException(
                "The selected pair passed evaluation but could not be reconstructed."
            )
            if (geometry.inlierCount < 30) {
                throw IllegalStateException("Initial pair has too few cheirality-verified points.")
            }

            val trackBuilder = FeatureTrackBuilder()
            trackBuilder.seedPair(
                selected.firstFrame,
                selected.secondFrame,
                firstFrame.keypoints,
                secondFrame.keypoints,
                geometry.matches
            )

            val poseByFrame = arrayOfNulls<CameraPose>(frameCount)
            val referencePose = CameraPose(
                selected.firstFrame, identity3(), doubleArrayOf(0.0, 0.0, 0.0),
                CameraPoseStatus.REFERENCE
            )
            val secondPose = CameraPose(
                selected.secondFrame, geometry.rotation, geometry.translation,
                CameraPoseStatus.RELATIVE_ESTIMATE
            )
            poseByFrame[selected.firstFrame] = referencePose
            poseByFrame[selected.secondFrame] = secondPose

            var tracks = trackBuilder.build()
            val initialReconstruction = SfMTriangulator.triangulate(
                tracks, listOf(referencePose, secondPose), cameraMatrix
            )
            require(initialReconstruction.landmarks.size >= 30) {
                "Initial 3D map is too small (${initialReconstruction.landmarks.size} landmarks). " +
                    "Use footage with stronger camera translation and more static scene texture."
            }
            val landmarksByTrack = linkedMapOf<Int, CameraLandmark>()
            initialReconstruction.landmarks.forEach { landmarksByTrack[it.trackId] = it }

            var previousDescriptors = secondFrame.descriptors.clone()
            var previousKeypoints = secondFrame.keypoints
            var totalMatches = geometry.matches.size
            var totalCorrespondences = geometry.matches.size
            var totalInliers = geometry.inlierCount
            var registeredFrames = 2
            var acceptedRegistrationsSinceMapUpdate = 0
            val diagnostics = MutableList(frameCount) { index ->
                FrameTrackingDiagnostic(index, 0, 0, 0, "Not registered")
            }
            diagnostics[selected.firstFrame] = FrameTrackingDiagnostic(
                selected.firstFrame, firstFrame.keypoints.size, 0, 0, "Initialization reference"
            )
            diagnostics[selected.secondFrame] = FrameTrackingDiagnostic(
                selected.secondFrame, secondFrame.keypoints.size,
                geometry.matches.size, geometry.inlierCount, "Initial pair reconstructed"
            )

            try {
                for (frameIndex in selected.secondFrame + 1 until frameCount) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        CameraTrackingProgress(
                            frameIndex,
                            frameCount,
                            "Registering camera frame",
                            overallFraction = 0.36f + 0.32f *
                                (frameIndex - selected.secondFrame).toFloat() /
                                (frameCount - selected.secondFrame).coerceAtLeast(1),
                            completedFrames = processedFrameCount,
                            totalFrames = frameCount
                        )
                    )
                    val source = frameProvider(frameIndex)
                        ?: throw IllegalStateException("Unable to decode frame ${frameIndex + 1}.")
                    val gray = toGray(source)
                    val keypointsMat = org.opencv.core.MatOfKeyPoint()
                    val descriptors = Mat()
                    try {
                        val detectionMask = Mat()
                        try {
                            orb.detectAndCompute(gray, detectionMask, keypointsMat, descriptors)
                        } finally {
                            detectionMask.release()
                        }
                        val currentKeypoints = keypointsMat.toArray()
                        val matches = if (!previousDescriptors.empty() && !descriptors.empty()) {
                            verifiedMatches(
                                previousDescriptors, previousKeypoints,
                                descriptors, currentKeypoints, cameraMatrix, matcher
                            )
                        } else emptyList()

                        totalMatches += matches.size
                        if (matches.isEmpty()) {
                            trackBuilder.breakContinuity()
                        } else {
                            trackBuilder.addInlierMatches(
                                frameIndex, previousKeypoints, currentKeypoints, matches
                            )
                        }
                        tracks = trackBuilder.build()

                        val registration = IncrementalCameraRegistrar.register(
                            frameIndex = frameIndex,
                            frameWidth = source.cols(),
                            frameHeight = source.rows(),
                            featureTracks = tracks,
                            landmarks = landmarksByTrack.values.toList(),
                            cameraMatrix = cameraMatrix
                        )
                        totalCorrespondences += registration.correspondenceCount
                        if (registration.accepted && registration.pose != null) {
                            poseByFrame[frameIndex] = registration.pose
                            totalInliers += registration.inlierCount
                            registeredFrames++
                            acceptedRegistrationsSinceMapUpdate++
                            diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                frameIndex, currentKeypoints.size, matches.size,
                                registration.inlierCount,
                                "PnP registered (${registration.inlierCount} inliers, " +
                                    "median ${"%.2f".format(registration.medianReprojectionErrorPx)} px)"
                            )

                            // Triangulate periodically, not after every frame. This bounds
                            // CPU cost while allowing the map to grow during the solve.
                            if (acceptedRegistrationsSinceMapUpdate >= 3) {
                                val validPoses = poseByFrame.filterNotNull()
                                val update = SfMTriangulator.triangulate(
                                    tracks, validPoses, cameraMatrix
                                )
                                update.landmarks.forEach { landmark ->
                                    val old = landmarksByTrack[landmark.trackId]
                                    if (old == null ||
                                        landmark.meanReprojectionErrorPx < old.meanReprojectionErrorPx
                                    ) landmarksByTrack[landmark.trackId] = landmark
                                }
                                acceptedRegistrationsSinceMapUpdate = 0
                            }
                        } else {
                            diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                frameIndex, currentKeypoints.size, matches.size,
                                registration.inlierCount, registration.reason
                            )
                        }

                        previousDescriptors.release()
                        previousDescriptors = descriptors.clone()
                        previousKeypoints = currentKeypoints
                    } finally {
                        source.release()
                        gray.release()
                        keypointsMat.release()
                        descriptors.release()
                    }
                    markFrameProcessed(frameIndex)
                    onProgress(
                        CameraTrackingProgress(
                            frameIndex + 1,
                            frameCount,
                            "Registered ${registeredFrames} cameras",
                            overallFraction = 0.36f + 0.32f *
                                (frameIndex + 1 - selected.secondFrame).toFloat() /
                                (frameCount - selected.secondFrame).coerceAtLeast(1),
                            completedFrames = processedFrameCount,
                            totalFrames = frameCount
                        )
                    )
                }
            } finally {
                previousDescriptors.release()
            }

            // Run the same map-based registration backwards from the second
            // initialization camera. This fills frames before the selected pair
            // and the interval between the pair without inventing translations.
            trackBuilder.resetToFrame(selected.secondFrame)
            var reverseDescriptors = secondFrame.descriptors.clone()
            var reverseKeypoints = secondFrame.keypoints
            try {
                for (frameIndex in selected.secondFrame - 1 downTo 0) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        CameraTrackingProgress(
                            frameIndex + 1,
                            frameCount,
                            "Registering earlier frames",
                            overallFraction = 0.68f + 0.22f *
                                (selected.secondFrame - frameIndex).toFloat() /
                                selected.secondFrame.coerceAtLeast(1),
                            completedFrames = processedFrameCount,
                            totalFrames = frameCount
                        )
                    )
                    val source = frameProvider(frameIndex)
                        ?: throw IllegalStateException("Unable to decode frame ${frameIndex + 1}.")
                    val gray = toGray(source)
                    val keypointsMat = org.opencv.core.MatOfKeyPoint()
                    val descriptors = Mat()
                    try {
                        val detectionMask = Mat()
                        try {
                            orb.detectAndCompute(gray, detectionMask, keypointsMat, descriptors)
                        } finally {
                            detectionMask.release()
                        }
                        val currentKeypoints = keypointsMat.toArray()
                        val matches = if (!reverseDescriptors.empty() && !descriptors.empty()) {
                            verifiedMatches(
                                reverseDescriptors, reverseKeypoints,
                                descriptors, currentKeypoints, cameraMatrix, matcher
                            )
                        } else emptyList()
                        totalMatches += matches.size
                        if (matches.isEmpty()) {
                            trackBuilder.breakContinuity()
                        } else {
                            trackBuilder.addInlierMatchesBackward(
                                frameIndex, reverseKeypoints, currentKeypoints, matches
                            )
                        }
                        tracks = trackBuilder.build()

                        if (frameIndex == selected.firstFrame) {
                            diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                frameIndex, currentKeypoints.size, matches.size,
                                matches.size, "Initialization reference"
                            )
                        } else {
                            val registration = IncrementalCameraRegistrar.register(
                                frameIndex = frameIndex,
                                frameWidth = source.cols(),
                                frameHeight = source.rows(),
                                featureTracks = tracks,
                                landmarks = landmarksByTrack.values.toList(),
                                cameraMatrix = cameraMatrix
                            )
                            totalCorrespondences += registration.correspondenceCount
                            if (registration.accepted && registration.pose != null) {
                                poseByFrame[frameIndex] = registration.pose
                                totalInliers += registration.inlierCount
                                registeredFrames++
                                acceptedRegistrationsSinceMapUpdate++
                                diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                    frameIndex, currentKeypoints.size, matches.size,
                                    registration.inlierCount,
                                    "PnP registered backwards (${registration.inlierCount} inliers)"
                                )
                                if (acceptedRegistrationsSinceMapUpdate >= 3) {
                                    val update = SfMTriangulator.triangulate(
                                        tracks, poseByFrame.filterNotNull(), cameraMatrix
                                    )
                                    update.landmarks.forEach { landmark ->
                                        val old = landmarksByTrack[landmark.trackId]
                                        if (old == null ||
                                            landmark.meanReprojectionErrorPx < old.meanReprojectionErrorPx
                                        ) landmarksByTrack[landmark.trackId] = landmark
                                    }
                                    acceptedRegistrationsSinceMapUpdate = 0
                                }
                            } else {
                                diagnostics[frameIndex] = FrameTrackingDiagnostic(
                                    frameIndex, currentKeypoints.size, matches.size,
                                    registration.inlierCount, registration.reason
                                )
                            }
                        }

                        reverseDescriptors.release()
                        reverseDescriptors = descriptors.clone()
                        reverseKeypoints = currentKeypoints
                        markFrameProcessed(frameIndex)
                        onProgress(
                            CameraTrackingProgress(
                                frameIndex + 1,
                                frameCount,
                                "Registered earlier frames",
                                overallFraction = 0.68f + 0.22f *
                                    (selected.secondFrame - frameIndex).toFloat() /
                                    selected.secondFrame.coerceAtLeast(1),
                                completedFrames = processedFrameCount,
                                totalFrames = frameCount
                            )
                        )
                    } finally {
                        source.release()
                        gray.release()
                        keypointsMat.release()
                        descriptors.release()
                    }
                }
            } finally {
                reverseDescriptors.release()
            }

            // Flush newly observed tracks once more after the final registered pose.
            SfMTriangulator.triangulate(
                tracks, poseByFrame.filterNotNull(), cameraMatrix
            ).landmarks.forEach { landmark ->
                val old = landmarksByTrack[landmark.trackId]
                if (old == null || landmark.meanReprojectionErrorPx < old.meanReprojectionErrorPx) {
                    landmarksByTrack[landmark.trackId] = landmark
                }
            }

            // Two bounded global refinement rounds: re-estimate each registered
            // camera against the latest shared map, then retriangulate landmarks
            // using updated camera poses. This reduces incremental drift without
            // treating independent per-frame pose fits as joint bundle adjustment.
            val refinementFrames = poseByFrame.indices.filter { index ->
                index != selected.firstFrame && poseByFrame[index] != null
            }
            repeat(2) { refinementRound ->
                currentCoroutineContext().ensureActive()
                for ((position, frameIndex) in refinementFrames.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val registration = IncrementalCameraRegistrar.register(
                        frameIndex = frameIndex,
                        frameWidth = firstFrame.width,
                        frameHeight = firstFrame.height,
                        featureTracks = tracks,
                        landmarks = landmarksByTrack.values.toList(),
                        cameraMatrix = cameraMatrix
                    )
                    if (registration.accepted && registration.pose != null) {
                        poseByFrame[frameIndex] = registration.pose
                        diagnostics[frameIndex] = FrameTrackingDiagnostic(
                            frameIndex,
                            diagnostics[frameIndex].detectedFeatures,
                            diagnostics[frameIndex].matchedFeatures,
                            registration.inlierCount,
                            "Refined PnP (${registration.inlierCount} inliers, " +
                                "median ${"%.2f".format(registration.medianReprojectionErrorPx)} px)"
                        )
                    }
                    if (position % 8 == 0 || position == refinementFrames.lastIndex) {
                        onProgress(
                            CameraTrackingProgress(
                                frameIndex + 1,
                                frameCount,
                                "Refining camera map (${refinementRound + 1}/2)",
                                overallFraction = 0.90f + 0.09f *
                                    (refinementRound + (position + 1).toFloat() /
                                        refinementFrames.size.coerceAtLeast(1)) / 2f,
                                completedFrames = frameCount,
                                totalFrames = frameCount
                            )
                        )
                    }
                }

                val refinedMap = SfMTriangulator.triangulate(
                    tracks, poseByFrame.filterNotNull(), cameraMatrix
                )
                refinedMap.landmarks.forEach { landmark ->
                    val old = landmarksByTrack[landmark.trackId]
                    if (old == null ||
                        landmark.meanReprojectionErrorPx < old.meanReprojectionErrorPx
                    ) {
                        landmarksByTrack[landmark.trackId] = landmark
                    }
                }
            }

            val denseTimeline = ArrayList<CameraPose>(frameCount)
            var lastValid: CameraPose? = null
            for (index in 0 until frameCount) {
                val actual = poseByFrame[index]
                if (actual != null) {
                    denseTimeline += actual
                    lastValid = actual
                } else {
                    val beforeInitialization = index < selected.firstFrame
                    val held = lastValid ?: referencePose
                    denseTimeline += CameraPose(
                        index,
                        if (beforeInitialization) identity3() else held.rotation.copyOf(),
                        if (beforeInitialization) doubleArrayOf(0.0, 0.0, 0.0)
                        else held.translation.copyOf(),
                        if (beforeInitialization) CameraPoseStatus.UNREGISTERED
                        else CameraPoseStatus.HELD_LAST_VALID
                    )
                }
            }

            val allCorrespondences = max(1, totalCorrespondences)
            val finalStats = SfMTriangulator.triangulate(
                tracks, poseByFrame.filterNotNull(), cameraMatrix
            ).stats
            onProgress(CameraTrackingProgress(
                frameCount, frameCount, "Camera solve complete",
                overallFraction = 1f,
                completedFrames = frameCount,
                totalFrames = frameCount
            ))
            CameraTrackingResult(
                poses = denseTimeline,
                diagnostics = diagnostics,
                processedFrames = frameCount,
                solvedFramePairs = registeredFrames - 1,
                totalMatches = totalMatches,
                totalInliers = totalInliers,
                inlierRatio = (totalInliers.toDouble() / allCorrespondences).coerceIn(0.0, 1.0),
                isCameraMotionEstimated = registeredFrames >= 2,
                featureTracks = tracks,
                landmarks = landmarksByTrack.values.toList(),
                triangulationStats = finalStats,
                initialPairFrames = selected.firstFrame to selected.secondFrame,
                registeredCameraCount = registeredFrames
            )
        } finally {
            cache.values.forEach { it.descriptors.release() }
            matcher.clear()
            orb.clear()
        }
    }

    private fun estimatePairGeometry(
        first: CachedFrame,
        second: CachedFrame,
        cameraMatrix: Mat,
        matcher: BFMatcher
    ): PairGeometry? {
        val raw = ratioMatches(
            first.descriptors, second.descriptors,
            first.keypoints, second.keypoints, matcher
        )
        if (raw.size < 8) return null
        return verifyGeometry(raw, first.keypoints, second.keypoints, cameraMatrix, true)
    }

    private fun verifiedMatches(
        firstDescriptors: Mat,
        firstKeypoints: Array<KeyPoint>,
        secondDescriptors: Mat,
        secondKeypoints: Array<KeyPoint>,
        cameraMatrix: Mat,
        matcher: BFMatcher
    ): List<Pair<Int, Int>> {
        val raw = ratioMatches(
            firstDescriptors, secondDescriptors, firstKeypoints, secondKeypoints, matcher
        )
        if (raw.size < 8) return emptyList()
        return verifyGeometry(raw, firstKeypoints, secondKeypoints, cameraMatrix, false)
            ?.matches ?: emptyList()
    }

    private fun ratioMatches(
        firstDescriptors: Mat,
        secondDescriptors: Mat,
        firstKeypoints: Array<KeyPoint>,
        secondKeypoints: Array<KeyPoint>,
        matcher: BFMatcher
    ): List<Pair<Int, Int>> {
        val rows = mutableListOf<MatOfDMatch>()
        val result = mutableListOf<Pair<Int, Int>>()
        val usedTrain = HashSet<Int>()
        try {
            matcher.knnMatch(firstDescriptors, secondDescriptors, rows, 2)
            for (row in rows) {
                val pair = row.toArray()
                if (pair.size < 2) continue
                val best = pair[0]
                if (best.distance >= ratioThreshold * pair[1].distance) continue
                if (best.queryIdx !in firstKeypoints.indices ||
                    best.trainIdx !in secondKeypoints.indices ||
                    !usedTrain.add(best.trainIdx)
                ) continue
                result += best.queryIdx to best.trainIdx
            }
        } finally {
            rows.forEach { it.release() }
        }
        return result
    }

    private fun verifyGeometry(
        matches: List<Pair<Int, Int>>,
        firstKeypoints: Array<KeyPoint>,
        secondKeypoints: Array<KeyPoint>,
        cameraMatrix: Mat,
        includePose: Boolean
    ): PairGeometry? {
        val points1 = MatOfPoint2f()
        val points2 = MatOfPoint2f()
        val mask = Mat()
        val rotation = Mat()
        val translation = Mat()
        try {
            points1.fromArray(*matches.map { firstKeypoints[it.first].pt }.toTypedArray())
            points2.fromArray(*matches.map { secondKeypoints[it.second].pt }.toTypedArray())
            val essential = Calib3d.findEssentialMat(
                points1, points2, cameraMatrix, Calib3d.RANSAC,
                0.999, 1.25, 1200, mask
            )
            try {
                if (essential.empty() || essential.cols() != 3 ||
                    essential.rows() < 3 || essential.rows() % 3 != 0
                ) return null

                var bestCount = 0
                var bestMask: Mat? = null
                var bestR: Mat? = null
                var bestT: Mat? = null
                try {
                    for (index in 0 until essential.rows() / 3) {
                        val candidate = essential.rowRange(index * 3, index * 3 + 3).clone()
                        val candidateMask = mask.clone()
                        val r = Mat()
                        val t = Mat()
                        try {
                            val count = Calib3d.recoverPose(
                                candidate, points1, points2, cameraMatrix, r, t, candidateMask
                            )
                            if (count > bestCount) {
                                bestCount = count
                                bestMask?.release()
                                bestR?.release()
                                bestT?.release()
                                bestMask = candidateMask.clone()
                                bestR = r.clone()
                                bestT = t.clone()
                            }
                        } finally {
                            candidate.release()
                            candidateMask.release()
                            r.release()
                            t.release()
                        }
                    }
                    val chosenMask = bestMask ?: return null
                    val inlierPairs = matches.filterIndexed { index, _ ->
                        chosenMask.get(index, 0)?.firstOrNull()?.let { it != 0.0 } == true
                    }
                    if (inlierPairs.size < 8) return null
                    if (!includePose) {
                        return PairGeometry(inlierPairs, identity3(), doubleArrayOf(0.0, 0.0, 0.0), bestCount)
                    }
                    val chosenR = bestR ?: return null
                    val chosenT = bestT ?: return null
                    val rValues = DoubleArray(9)
                    val tValues = DoubleArray(3)
                    chosenR.get(0, 0, rValues)
                    chosenT.get(0, 0, tValues)
                    return PairGeometry(inlierPairs, rValues, tValues, bestCount)
                } finally {
                    bestMask?.release()
                    bestR?.release()
                    bestT?.release()
                }
            } finally {
                essential.release()
            }
        } finally {
            points1.release()
            points2.release()
            mask.release()
            rotation.release()
            translation.release()
        }
    }

    private fun toGray(source: Mat): Mat {
        val gray = Mat()
        when (source.channels()) {
            1 -> source.copyTo(gray)
            3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            else -> {
                gray.release()
                throw IllegalArgumentException("Unsupported image channel count: ${source.channels()}")
            }
        }
        return gray
    }

    private fun identity3() = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
    )
}
