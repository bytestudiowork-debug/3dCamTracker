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

        // Follow the GLOMAP controller order: largest connected component
        // first, then rotation estimation and post-estimation outlier filtering.
        // An extra cycle pre-filter is intentionally not applied here.
        val component = largestComponent(frames, validEdges)
        if (component.size < 2) return null
        val activeFrames = frames.filter { it in component }
        val activeIndex = activeFrames.withIndex().associate { it.value to it.index }
        val activeEdges = validEdges.filter {
            it.firstFrame in activeIndex && it.secondFrame in activeIndex
        }
        val root = activeFrames.first()
        val rotations = initializeMst(activeFrames, activeEdges, root) ?: return null
        var iterations = 0

        fun refine(edgesToUse: List<Edge>, initialL1: Boolean): Boolean {
            if (edgesToUse.isEmpty()) return false
            var residuals = computeResiduals(activeFrames, activeIndex, edgesToUse, rotations)
            val rounds = if (initialL1) l1Iterations else irlsIterations
            var admmInnerIterations = 10
            repeat(rounds.coerceAtLeast(0)) {
                val deltas = if (initialL1) {
                    solveL1Admm(activeFrames.size, activeIndex, edgesToUse, residuals, admmInnerIterations)
                        .also { admmInnerIterations = min(admmInnerIterations * 2, 100) }
                } else {
                    val sigma = Math.toRadians(sigmaDegrees.coerceAtLeast(0.1))
                    val weights = DoubleArray(edgesToUse.size) { e ->
                        val squared = norm3(residuals[e]).let { it * it }
                        sigma * sigma / ((squared + sigma * sigma) * (squared + sigma * sigma))
                    }
                    solveTangentUpdates(activeFrames.size, activeIndex, edgesToUse, residuals, weights)
                } ?: return false
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

    /**
     * ADMM solution of the COLMAP/GLOMAP least-absolute-deviation subproblem
     * min_x ||A x - b||_1 for the anchored view-graph incidence matrix.
     * The x-update solves (A^T A)x=A^T(b+z-u) with the sparse graph Laplacian.
     */
    private fun solveL1Admm(
        count: Int, index: Map<Int, Int>, edges: List<Edge>,
        residuals: List<DoubleArray>, maxIterations: Int
    ): Array<DoubleArray>? {
        val root = 0
        val freeCount = count - 1
        val result = Array(count) { DoubleArray(3) }
        if (freeCount <= 0) return result
        val diagonal = DoubleArray(freeCount)
        val links = ArrayList<Triple<Int, Int, Double>>(edges.size)
        for (edge in edges) {
            val a = index[edge.firstFrame] ?: return null
            val b = index[edge.secondFrame] ?: return null
            if (a != root) diagonal[a - 1] += 1.0
            if (b != root) diagonal[b - 1] += 1.0
            if (a != root && b != root) links += Triple(a - 1, b - 1, 1.0)
        }
        if (diagonal.any { it <= 0.0 }) return null

        fun aTimes(x: DoubleArray): DoubleArray = DoubleArray(edges.size) { e ->
            val a = index.getValue(edges[e].firstFrame)
            val b = index.getValue(edges[e].secondFrame)
            (if (b == root) 0.0 else x[b - 1]) -
                (if (a == root) 0.0 else x[a - 1])
        }
        fun atTimes(y: DoubleArray): DoubleArray {
            val out = DoubleArray(freeCount)
            edges.forEachIndexed { e, edge ->
                val a = index.getValue(edge.firstFrame)
                val b = index.getValue(edge.secondFrame)
                if (a != root) out[a - 1] -= y[e]
                if (b != root) out[b - 1] += y[e]
            }
            return out
        }

        val rho = 1.0
        val alpha = 1.0
        val sqrtRows = sqrt(edges.size.toDouble())
        val sqrtCols = sqrt(freeCount.toDouble())
        for (axis in 0..2) {
            val b = DoubleArray(edges.size) { residuals[it][axis] }
            val bNorm = sqrt(dot(b, b))
            var z = DoubleArray(edges.size)
            var u = DoubleArray(edges.size)
            var x = DoubleArray(freeCount)
            for (iteration in 0 until maxIterations.coerceAtLeast(1)) {
                val rhs = atTimes(DoubleArray(edges.size) { e -> b[e] + z[e] - u[e] })
                x = conjugateGradient(diagonal, links, rhs) ?: return null
                val ax = aTimes(x)
                val axHat = DoubleArray(edges.size) { e ->
                    alpha * ax[e] + (1.0 - alpha) * (z[e] + b[e])
                }
                val zOld = z
                z = DoubleArray(edges.size) { e ->
                    val value = axHat[e] - b[e] + u[e]
                    when {
                        value > 1.0 / rho -> value - 1.0 / rho
                        value < -1.0 / rho -> value + 1.0 / rho
                        else -> 0.0
                    }
                }
                for (e in u.indices) u[e] += axHat[e] - z[e] - b[e]
                val primal = DoubleArray(edges.size) { e -> ax[e] - z[e] - b[e] }
                val deltaZ = DoubleArray(edges.size) { e -> z[e] - zOld[e] }
                val dualVector = atTimes(deltaZ)
                val rNorm = sqrt(dot(primal, primal))
                val sNorm = rho * sqrt(dot(dualVector, dualVector))
                val axNorm = sqrt(dot(ax, ax))
                val zNorm = sqrt(dot(z, z))
                val atUNorm = sqrt(dot(atTimes(u), atTimes(u)))
                val epsPri = sqrtRows * 1e-4 + 1e-2 * max(bNorm, max(axNorm, zNorm))
                val epsDual = sqrtCols * 1e-4 + 1e-2 * rho * atUNorm
                if (rNorm < epsPri && sNorm < epsDual) break
            }
            for (i in 1 until count) result[i][axis] = x[i - 1]
        }
        return result
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
