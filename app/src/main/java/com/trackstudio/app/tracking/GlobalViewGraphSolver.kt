package com.trackstudio.app.tracking

/**
 * Builds and solves the coarse temporal view graph using verified two-view poses.
 *
 * The controller follows GLOMAP's global-mapper ordering: estimate rotations,
 * remove edges with excessive rotation residual, retain the largest connected
 * component, estimate again, filter again, then perform the final estimation.
 *
 * This stage intentionally returns global rotations separately from incremental
 * PnP translations. Combining rotations from a global gauge with translations
 * from the incremental map without global positioning would corrupt camera poses.
 */
internal object GlobalViewGraphSolver {
    data class Result(
        val rotationsByFrame: Map<Int, DoubleArray>,
        val inputEdgeCount: Int,
        val acceptedEdgeCount: Int,
        val iterations: Int
    )

    fun solve(
        evaluations: List<InitialPairEvaluation>,
        policy: InitialPairPolicy = InitialPairPolicy(),
        rotationOutlierDegrees: Double = 10.0
    ): Result? {
        val edges = evaluations.asSequence()
            .filter { it.accepted }
            .map { it.candidate }
            .filter {
                it.inlierCount >= policy.minimumInliers &&
                    it.inlierRatio >= policy.minimumInlierRatio &&
                    it.relativeRotation?.size == 9 &&
                    it.relativeRotation.all(Double::isFinite)
            }
            .map {
                GlobalRotationAverager.Edge(
                    firstFrame = it.firstFrame,
                    secondFrame = it.secondFrame,
                    relativeRotation = it.relativeRotation!!.copyOf(),
                    inlierCount = it.inlierCount
                )
            }
            .toList()
        if (edges.isEmpty()) return null

        // GLOMAP performs three rotation-averaging passes, with rotation-error
        // filtering and largest-component extraction after the first two.
        val first = GlobalRotationAverager.solve(
            frameIndices = edges.flatMap { listOf(it.firstFrame, it.secondFrame) }.distinct(),
            edges = edges,
            rotationOutlierDegrees = rotationOutlierDegrees
        ) ?: return null
        if (first.acceptedEdges.isEmpty()) return null

        val second = GlobalRotationAverager.solve(
            frameIndices = first.rotationsByFrame.keys.toList(),
            edges = first.acceptedEdges,
            rotationOutlierDegrees = rotationOutlierDegrees
        ) ?: return null
        if (second.acceptedEdges.isEmpty()) return null

        val final = GlobalRotationAverager.solve(
            frameIndices = second.rotationsByFrame.keys.toList(),
            edges = second.acceptedEdges,
            rotationOutlierDegrees = rotationOutlierDegrees
        ) ?: return null

        return Result(
            rotationsByFrame = final.rotationsByFrame,
            inputEdgeCount = edges.size,
            acceptedEdgeCount = final.acceptedEdges.size,
            iterations = first.iterations + second.iterations + final.iterations
        )
    }
}
