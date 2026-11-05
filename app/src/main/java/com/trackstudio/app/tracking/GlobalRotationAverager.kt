package com.trackstudio.app.tracking

import kotlin.math.*

/**
 * Android port of GLOMAP's global rotation averaging core.
 *
 * Follows the source pipeline: maximum-inlier spanning-tree initialization,
 * tangent-space relative-rotation residuals, L1-style reweighting, then
 * Geman-McClure IRLS. The normal equations are applied as a sparse graph
 * Laplacian and solved with conjugate gradients; no dense N x N matrix is built.
 *
 * Rotations map world coordinates into camera coordinates. An edge stores
 * R_camera2_from_camera1, so R2 = R21 * R1.
 */
internal object GlobalRotationAverager {
    data class Edge(
        val firstFrame: Int,
        val secondFrame: Int,
        val relativeRotation: DoubleArray,
        val inlierCount: Int
    )

    data class Result(
        val rotationsByFrame: Map<Int, DoubleArray>,
        val acceptedEdges: List<Edge>,
        val rejectedEdges: List<Edge>,
        val rootFrame: Int,
        val iterations: Int
    )

    fun solve(
        frameIndices: List<Int>,
        edges: List<Edge>,
        l1Iterations: Int = 5,
        irlsIterations: Int = 40,
        sigmaDegrees: Double = 5.0,
        rotationOutlierDegrees: Double = 15.0
    ): Result? {
        val frames = frameIndices.distinct().sorted()
        if (frames.size < 2) return null
        val index = frames.withIndex().associate { it.value to it.index }
        val validEdges = edges.filter {
            it.firstFrame in index && it.secondFrame in index &&
                it.firstFrame != it.secondFrame && it.inlierCount > 0 &&
                it.relativeRotation.size == 9 && it.relativeRotation.all(Double::isFinite)
        }
        if (validEdges.isEmpty()) return null

        // Reject edges that contradict an independent multi-edge path before
        // choosing the maximum-inlier spanning tree. This prevents one bad,
        // high-inlier pair from becoming the initialization backbone.
        val cycleFilteredEdges = rejectCycleInconsistentEdges(
            frames, validEdges, rotationOutlierDegrees
        )
        val component = largestComponent(frames, cycleFilteredEdges)
        if (component.size < 2) return null
        val activeFrames = frames.filter { it in component }
        val activeIndex = activeFrames.withIndex().associate { it.value to it.index }
        val activeEdges = cycleFilteredEdges.filter {
            it.firstFrame in activeIndex && it.secondFrame in activeIndex
        }
        val root = activeFrames.first()
        val rotations = initializeMst(activeFrames, activeEdges, root) ?: return null
        var iterations = 0

        fun refine(edgesToUse: List<Edge>, initialL1: Boolean): Boolean {
            if (edgesToUse.isEmpty()) return false
            var residuals = computeResiduals(activeFrames, activeIndex, edgesToUse, rotations)
            var baseWeights = edgesToUse.map { it.inlierCount.toDouble().coerceAtLeast(1.0) }
            val rounds = if (initialL1) l1Iterations else irlsIterations
            repeat(rounds.coerceAtLeast(0)) {
                val weights = DoubleArray(edgesToUse.size) { e ->
                    val norm = norm3(residuals[e])
                    if (initialL1) {
                        baseWeights[e] / max(norm, 1e-4)
                    } else {
                        val sigma = Math.toRadians(sigmaDegrees.coerceAtLeast(0.1))
                        val squared = norm * norm
                        baseWeights[e] * sigma * sigma / (squared + sigma * sigma).let { it * it }
                    }
                }
                val deltas = solveTangentUpdates(activeFrames.size, activeIndex, edgesToUse, residuals, weights)
                    ?: return false
                var averageStep = 0.0
                for (i in activeFrames.indices) {
                    if (activeFrames[i] == root) continue
                    val d = deltas[i]
                    rotations[activeFrames[i]] = multiply(
                        rotations.getValue(activeFrames[i]), expRotation(doubleArrayOf(-d[0], -d[1], -d[2]))
                    )
                    averageStep += norm3(d)
                }
                iterations++
                averageStep /= (activeFrames.size - 1).coerceAtLeast(1)
                residuals = computeResiduals(activeFrames, activeIndex, edgesToUse, rotations)
                if (averageStep < 1e-5) return true
            }
            return true
        }

        if (!refine(activeEdges, initialL1 = true)) return null
        if (!refine(activeEdges, initialL1 = false)) return null

        val accepted = activeEdges.filter { edge ->
            val r = relativeResidual(edge, rotations)
            Math.toDegrees(norm3(r)) <= rotationOutlierDegrees
        }
        // Include cycle-inconsistent and disconnected edges in the rejected
        // report. Keep the solved rotations untouched: pruning must never clear
        // entries from the active component or change its fixed gauge/root.
        val rejected = validEdges.filter { it !in accepted }
        return Result(
            rotationsByFrame = rotations.mapValues { it.value.copyOf() },
            acceptedEdges = accepted,
            rejectedEdges = rejected,
            rootFrame = root,
            iterations = iterations
        )
    }

    /**
     * Uses independent graph cycles as a robust pre-initialization check.
     * For each pair, find an alternate path that excludes all parallel edges
     * for that pair. Tree edges have no independent evidence and are retained.
     */
    /**
     * Uses independent graph cycles as a robust pre-initialization check.
     * The adjacency structure is built once and reused for every candidate,
     * avoiding per-edge graph allocations on mobile.
     */
    private fun rejectCycleInconsistentEdges(
        frames: List<Int>,
        edges: List<Edge>,
        thresholdDegrees: Double
    ): List<Edge> {
        data class Step(val edge: Edge, val from: Int, val to: Int)
        val adjacency = frames.associateWith { mutableListOf<Step>() }
        for (edge in edges) {
            adjacency[edge.firstFrame]?.add(Step(edge, edge.firstFrame, edge.secondFrame))
            adjacency[edge.secondFrame]?.add(Step(edge, edge.secondFrame, edge.firstFrame))
        }

        val threshold = Math.toRadians(thresholdDegrees.coerceAtLeast(0.1))
        val kept = ArrayList<Edge>(edges.size)
        for (candidate in edges) {
            val parent = HashMap<Int, Step>()
            val seen = hashSetOf(candidate.firstFrame)
            val queue = ArrayDeque<Int>()
            queue.add(candidate.firstFrame)
            while (queue.isNotEmpty() && candidate.secondFrame !in seen) {
                val current = queue.removeFirst()
                for (step in adjacency[current].orEmpty()) {
                    val samePair =
                        (step.edge.firstFrame == candidate.firstFrame && step.edge.secondFrame == candidate.secondFrame) ||
                        (step.edge.firstFrame == candidate.secondFrame && step.edge.secondFrame == candidate.firstFrame)
                    if (!samePair && seen.add(step.to)) {
                        parent[step.to] = step
                        queue.add(step.to)
                    }
                }
            }
            if (candidate.secondFrame !in seen) {
                kept += candidate
                continue
            }

            val path = ArrayList<Step>()
            var node = candidate.secondFrame
            while (node != candidate.firstFrame) {
                val step = parent[node] ?: break
                path += step
                node = step.from
            }
            path.reverse()
            var pathRotation = identity()
            for (step in path) {
                val directed = if (step.from == step.edge.firstFrame) {
                    step.edge.relativeRotation
                } else {
                    transpose(step.edge.relativeRotation)
                }
                pathRotation = multiply(directed, pathRotation)
            }
            val cycleError = norm3(
                logRotation(multiply(transpose(candidate.relativeRotation), pathRotation))
            )
            if (cycleError <= threshold) kept += candidate
        }
        return kept
    }

    private fun largestComponent(frames: List<Int>, edges: List<Edge>): Set<Int> {
        val adjacency = frames.associateWith { mutableListOf<Int>() }
        edges.forEach {
            adjacency[it.firstFrame]?.add(it.secondFrame)
            adjacency[it.secondFrame]?.add(it.firstFrame)
        }
        val unseen = frames.toMutableSet()
        var best = emptySet<Int>()
        while (unseen.isNotEmpty()) {
            val start = unseen.first()
            val component = linkedSetOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            unseen.remove(start)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                component.add(node)
                for (next in adjacency[node].orEmpty()) if (unseen.remove(next)) queue.add(next)
            }
            if (component.size > best.size) best = component
        }
        return best
    }

    private fun initializeMst(frames: List<Int>, edges: List<Edge>, root: Int): MutableMap<Int, DoubleArray>? {
        val adjacency = frames.associateWith { mutableListOf<Edge>() }
        edges.forEach {
            adjacency[it.firstFrame]?.add(it)
            adjacency[it.secondFrame]?.add(it)
        }
        val visited = mutableSetOf(root)
        val rotations = mutableMapOf(root to identity())
        while (visited.size < frames.size) {
            var best: Edge? = null
            var from = -1
            for (node in visited) for (edge in adjacency[node].orEmpty()) {
                val other = if (edge.firstFrame == node) edge.secondFrame else edge.firstFrame
                if (other !in visited && (best == null || edge.inlierCount > best!!.inlierCount)) {
                    best = edge
                    from = node
                }
            }
            val edge = best ?: return null
            val to = if (edge.firstFrame == from) edge.secondFrame else edge.firstFrame
            val relative = edge.relativeRotation
            rotations[to] = if (edge.firstFrame == from) {
                multiply(relative, rotations.getValue(from))
            } else {
                multiply(transpose(relative), rotations.getValue(from))
            }
            visited.add(to)
        }
        return rotations
    }

    private fun computeResiduals(
        frames: List<Int>, index: Map<Int, Int>, edges: List<Edge>, rotations: Map<Int, DoubleArray>
    ): List<DoubleArray> = edges.map { relativeResidual(it, rotations) }

    private fun relativeResidual(edge: Edge, rotations: Map<Int, DoubleArray>): DoubleArray {
        val r1 = rotations.getValue(edge.firstFrame)
        val r2 = rotations.getValue(edge.secondFrame)
        return scale(logRotation(multiply(transpose(r2), multiply(edge.relativeRotation, r1))), -1.0)
    }

    private fun solveTangentUpdates(
        count: Int, index: Map<Int, Int>, edges: List<Edge>, residuals: List<DoubleArray>, weights: DoubleArray
    ): Array<DoubleArray>? {
        val root = 0
        val freeCount = count - 1
        val result = Array(count) { DoubleArray(3) }
        if (freeCount <= 0) return result
        for (axis in 0..2) {
            val diagonal = DoubleArray(freeCount)
            val rhs = DoubleArray(freeCount)
            val links = ArrayList<Triple<Int, Int, Double>>(edges.size)
            edges.forEachIndexed { e, edge ->
                val a = index.getValue(edge.firstFrame)
                val b = index.getValue(edge.secondFrame)
                val w = weights[e].coerceAtLeast(1e-10)
                val target = residuals[e][axis]
                if (a != root) {
                    val ai = a - 1
                    diagonal[ai] += w
                    rhs[ai] -= w * target
                }
                if (b != root) {
                    val bi = b - 1
                    diagonal[bi] += w
                    rhs[bi] += w * target
                }
                if (a != root && b != root) links += Triple(a - 1, b - 1, w)
            }
            val x = conjugateGradient(diagonal, links, rhs) ?: return null
            for (i in 1 until count) result[i][axis] = x[i - 1]
        }
        return result
    }

    private fun conjugateGradient(diagonal: DoubleArray, links: List<Triple<Int, Int, Double>>, rhs: DoubleArray): DoubleArray? {
        fun multiplyVector(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size) { diagonal[it] * x[it] }
            for ((a, b, w) in links) {
                y[a] -= w * x[b]
                y[b] -= w * x[a]
            }
            return y
        }
        val x = DoubleArray(rhs.size)
        var r = rhs.copyOf()
        var p = r.copyOf()
        var rr = dot(r, r)
        val target = max(1e-20, rr * 1e-12)
        repeat(min(500, max(30, rhs.size * 3))) {
            if (rr <= target) return x
            val ap = multiplyVector(p)
            val denom = dot(p, ap)
            if (!denom.isFinite() || denom <= 1e-20) return if (rr < 1e-12) x else null
            val alpha = rr / denom
            for (i in x.indices) {
                x[i] += alpha * p[i]
                r[i] -= alpha * ap[i]
            }
            val next = dot(r, r)
            val beta = next / rr
            for (i in p.indices) p[i] = r[i] + beta * p[i]
            rr = next
        }
        return if (rr.isFinite()) x else null
    }

    private fun identity() = doubleArrayOf(1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0)
    private fun transpose(a: DoubleArray) = doubleArrayOf(a[0],a[3],a[6], a[1],a[4],a[7], a[2],a[5],a[8])
    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) for (k in 0..2) out[r*3+c] += a[r*3+k] * b[k*3+c]
        return out
    }
    private fun expRotation(v: DoubleArray): DoubleArray {
        val theta = norm3(v)
        if (theta < 1e-12) return identity()
        val x=v[0]/theta; val y=v[1]/theta; val z=v[2]/theta
        val c=cos(theta); val s=sin(theta); val t=1-c
        return doubleArrayOf(
            t*x*x+c, t*x*y-s*z, t*x*z+s*y,
            t*x*y+s*z, t*y*y+c, t*y*z-s*x,
            t*x*z-s*y, t*y*z+s*x, t*z*z+c
        )
    }
    private fun logRotation(r: DoubleArray): DoubleArray {
        val cosTheta = ((r[0]+r[4]+r[8]-1.0)*0.5).coerceIn(-1.0,1.0)
        val theta = acos(cosTheta)
        val factor = if (theta < 1e-8) 0.5 else theta/(2.0*sin(theta).let { if (abs(it)<1e-10) 1e-10 else it })
        return doubleArrayOf((r[7]-r[5])*factor,(r[2]-r[6])*factor,(r[3]-r[1])*factor)
    }
    private fun scale(a: DoubleArray, s: Double) = DoubleArray(3) { a[it]*s }
    private fun norm3(a: DoubleArray) = sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2])
    private fun dot(a: DoubleArray,b: DoubleArray)=a.indices.sumOf { a[it]*b[it] }
}
