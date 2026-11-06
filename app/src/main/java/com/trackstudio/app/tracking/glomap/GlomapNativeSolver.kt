package com.trackstudio.app.tracking.glomap

internal fun interface GlomapSolverProgressListener {
    fun onIteration(iteration: Int, totalIterations: Int)
}

/**
 * JNI bridge for the GLOMAP ONLY_POINTS global-positioning solve.
 * Input order is stable and caller-controlled, matching the Kotlin problem builder.
 */
internal class GlomapNativeSolver {
    data class Result(
        val frameCentersById: Map<Int, DoubleArray>,
        val pointsByTrackId: Map<Int, DoubleArray>,
        val scales: DoubleArray,
        val initialCost: Double,
        val finalCost: Double,
        val iterations: Int
    )

    fun solve(
        problem: GlomapGlobalPositioningProblemBuilder.Problem,
        frameCentersById: Map<Int, DoubleArray>,
        pointsByTrackId: Map<Int, DoubleArray>,
        maxIterations: Int = 100,
        numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        onIteration: (Int, Int) -> Unit = { _, _ -> }
    ): Result? {
        require(maxIterations > 0 && numThreads > 0)
        if (problem.constraints.isEmpty()) return null

        // Preserve first-observation ordering while avoiding intermediate
        // distinct/filter lists during native-problem packing.
        val frameIds = LinkedHashSet<Int>()
        val trackIds = LinkedHashSet<Int>()
        for (constraint in problem.constraints) {
            if (frameCentersById.containsKey(constraint.frameIndex)) {
                frameIds += constraint.frameIndex
            }
            if (pointsByTrackId.containsKey(constraint.trackId)) {
                trackIds += constraint.trackId
            }
        }
        if (frameIds.isEmpty() || trackIds.isEmpty()) return null

        val orderedFrameIds = frameIds.toList()
        val orderedTrackIds = trackIds.toList()
        val frameIndexById = HashMap<Int, Int>(orderedFrameIds.size)
        orderedFrameIds.forEachIndexed { index, id -> frameIndexById[id] = index }
        val trackIndexById = HashMap<Int, Int>(orderedTrackIds.size)
        orderedTrackIds.forEachIndexed { index, id -> trackIndexById[id] = index }

        val constraints = ArrayList<GlomapGlobalPositioningProblemBuilder.Constraint>(
            problem.constraints.size
        )
        for (constraint in problem.constraints) {
            if (frameIndexById.containsKey(constraint.frameIndex) &&
                trackIndexById.containsKey(constraint.trackId)
            ) constraints += constraint
        }
        if (constraints.isEmpty()) return null

        // Fill primitive buffers directly. The previous flatMap/toList path
        // allocated a temporary List<Double> for every 3D vector and direction.
        val initialFrames = DoubleArray(orderedFrameIds.size * 3)
        orderedFrameIds.forEachIndexed { index, id ->
            val value = frameCentersById.getValue(id)
            requireVector3(value, "frame center")
            System.arraycopy(value, 0, initialFrames, index * 3, 3)
        }
        val initialPoints = DoubleArray(orderedTrackIds.size * 3)
        orderedTrackIds.forEachIndexed { index, id ->
            val value = pointsByTrackId.getValue(id)
            requireVector3(value, "track point")
            System.arraycopy(value, 0, initialPoints, index * 3, 3)
        }

        val frameIndices = IntArray(constraints.size)
        val trackIndices = IntArray(constraints.size)
        val directions = DoubleArray(constraints.size * 3)
        val scales = DoubleArray(constraints.size)
        val lossScales = DoubleArray(constraints.size)
        constraints.forEachIndexed { index, constraint ->
            frameIndices[index] = frameIndexById.getValue(constraint.frameIndex)
            trackIndices[index] = trackIndexById.getValue(constraint.trackId)
            val direction = constraint.translationObservation
            requireVector3(direction, "translation observation")
            System.arraycopy(direction, 0, directions, index * 3, 3)
            scales[index] = constraint.initialScale
            lossScales[index] = constraint.lossScale
        }

        val packed = nativeSolve(
            initialFrames, initialPoints, frameIndices, trackIndices,
            directions, scales, lossScales, maxIterations, numThreads,
            GlomapSolverProgressListener(onIteration)
        )
        val expectedSize = 4 + initialFrames.size + initialPoints.size + scales.size
        if (packed.size != expectedSize || packed[0] != 1.0) return null

        var offset = 4
        val solvedFrames = LinkedHashMap<Int, DoubleArray>(orderedFrameIds.size)
        for (id in orderedFrameIds) {
            solvedFrames[id] = packed.copyOfRange(offset, offset + 3)
            offset += 3
        }
        val solvedPoints = LinkedHashMap<Int, DoubleArray>(orderedTrackIds.size)
        for (id in orderedTrackIds) {
            solvedPoints[id] = packed.copyOfRange(offset, offset + 3)
            offset += 3
        }
        val solvedScales = packed.copyOfRange(offset, packed.size)
        if (solvedScales.any { !it.isFinite() || it < 1e-5 }) return null

        return Result(
            frameCentersById = solvedFrames,
            pointsByTrackId = solvedPoints,
            scales = solvedScales,
            initialCost = packed[1],
            finalCost = packed[2],
            iterations = packed[3].toInt()
        )
    }

    private external fun nativeSolve(
        frameCenters: DoubleArray,
        points: DoubleArray,
        frameIndices: IntArray,
        trackIndices: IntArray,
        directions: DoubleArray,
        initialScales: DoubleArray,
        lossScales: DoubleArray,
        maxIterations: Int,
        numThreads: Int,
        progressListener: GlomapSolverProgressListener
    ): DoubleArray

    private fun requireVector3(value: DoubleArray, label: String) {
        require(value.size == 3 && value.all { it.isFinite() }) {
            "$label must contain three finite values"
        }
    }

    companion object {
        init {
            System.loadLibrary("trackstudio_glomap")
        }
    }
}
