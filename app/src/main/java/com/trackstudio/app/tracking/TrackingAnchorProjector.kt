package com.trackstudio.app.tracking

import org.opencv.core.Mat

data class AnchorWorldPosition(val x: Double, val y: Double, val z: Double)

data class TrackingAnchorSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val colorArgb: Long
)

internal data class TrackingAnchorContext(
    val poses: List<CameraPose>,
    val fx: Double,
    val skew: Double,
    val cx: Double,
    val fy: Double,
    val cy: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    val defaultPosition: AnchorWorldPosition,
    val recommendedAxisLength: Double
)

/**
 * Projects a fixed world-space XYZ anchor and its coordinate axes through the
 * solved camera poses. World units are relative until scene scale is calibrated.
 */
internal object TrackingAnchorProjector {
    fun createContext(
        landmarks: List<CameraLandmark>,
        poses: List<CameraPose>,
        cameraMatrix: Mat,
        imageWidth: Int,
        imageHeight: Int
    ): TrackingAnchorContext? {
        if (landmarks.size < 8 || cameraMatrix.empty() ||
            cameraMatrix.rows() != 3 || cameraMatrix.cols() != 3 ||
            imageWidth <= 0 || imageHeight <= 0
        ) return null

        val fx = cameraMatrix.get(0, 0)?.firstOrNull() ?: return null
        val skew = cameraMatrix.get(0, 1)?.firstOrNull() ?: return null
        val cx = cameraMatrix.get(0, 2)?.firstOrNull() ?: return null
        val fy = cameraMatrix.get(1, 1)?.firstOrNull() ?: return null
        val cy = cameraMatrix.get(1, 2)?.firstOrNull() ?: return null
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0.0 || fy <= 0.0) return null

        val stable = landmarks.filter {
            it.observationCount >= 3 &&
                it.meanReprojectionErrorPx.isFinite() &&
                it.meanReprojectionErrorPx <= 5.0 &&
                it.position.x.isFinite() && it.position.y.isFinite() && it.position.z.isFinite()
        }
        if (stable.size < 8) return null

        val reference = poses.firstOrNull {
            it.status == CameraPoseStatus.REFERENCE || it.status == CameraPoseStatus.PNP_REGISTERED
        } ?: poses.minByOrNull { it.frameIndex } ?: return null

        fun project(x: Double, y: Double, z: Double, pose: CameraPose): Pair<Double, Double>? {
            val r = pose.rotation
            val t = pose.translation
            if (r.size < 9 || t.size < 3) return null
            val cameraX = r[0] * x + r[1] * y + r[2] * z + t[0]
            val cameraY = r[3] * x + r[4] * y + r[5] * z + t[1]
            val cameraZ = r[6] * x + r[7] * y + r[8] * z + t[2]
            if (!cameraX.isFinite() || !cameraY.isFinite() || !cameraZ.isFinite() || cameraZ <= 1e-5) return null
            val u = (fx * cameraX + skew * cameraY) / cameraZ + cx
            val v = fy * cameraY / cameraZ + cy
            return if (u.isFinite() && v.isFinite()) u to v else null
        }

        val default = stable.asSequence().mapNotNull { landmark ->
            val p = project(landmark.position.x, landmark.position.y, landmark.position.z, reference)
                ?: return@mapNotNull null
            if (p.first !in 0.0..imageWidth.toDouble() || p.second !in 0.0..imageHeight.toDouble()) {
                return@mapNotNull null
            }
            val dx = (p.first - imageWidth * 0.5) / imageWidth
            val dy = (p.second - imageHeight * 0.5) / imageHeight
            landmark to (dx * dx + dy * dy +
                landmark.meanReprojectionErrorPx * 0.0005 -
                landmark.observationCount.coerceAtMost(20) * 0.00002)
        }.minByOrNull { it.second }?.first?.position ?: return null

        val xs = stable.map { it.position.x }.sorted()
        val ys = stable.map { it.position.y }.sorted()
        val zs = stable.map { it.position.z }.sorted()
        fun span(values: List<Double>) =
            values[(values.size * 9 / 10).coerceAtMost(values.lastIndex)] -
                values[(values.size / 10).coerceAtMost(values.lastIndex)]
        val extent = maxOf(span(xs), span(ys), span(zs))
        if (!extent.isFinite() || extent <= 1e-6) return null

        return TrackingAnchorContext(
            poses = poses.toList(),
            fx = fx, skew = skew, cx = cx, fy = fy, cy = cy,
            imageWidth = imageWidth, imageHeight = imageHeight,
            defaultPosition = AnchorWorldPosition(default.x, default.y, default.z),
            recommendedAxisLength = (extent * 0.08).coerceIn(0.01, 1.0)
        )
    }

    fun project(
        context: TrackingAnchorContext,
        position: AnchorWorldPosition,
        axisLength: Double = context.recommendedAxisLength
    ): Map<Int, List<TrackingAnchorSegment>> {
        data class AxisEndpoint(val x: Double, val y: Double, val z: Double, val color: Long)
        val length = axisLength.coerceIn(0.001, 1000.0)
        val endpoints = listOf(
            AxisEndpoint(position.x + length, position.y, position.z, 0xFFFF5252L),
            AxisEndpoint(position.x, position.y + length, position.z, 0xFF69F0AEL),
            AxisEndpoint(position.x, position.y, position.z + length, 0xFF448AFFL)
        )

        fun projectPoint(x: Double, y: Double, z: Double, pose: CameraPose): Pair<Float, Float>? {
            val r = pose.rotation
            val t = pose.translation
            if (r.size < 9 || t.size < 3) return null
            val cameraX = r[0] * x + r[1] * y + r[2] * z + t[0]
            val cameraY = r[3] * x + r[4] * y + r[5] * z + t[1]
            val cameraZ = r[6] * x + r[7] * y + r[8] * z + t[2]
            if (!cameraX.isFinite() || !cameraY.isFinite() || !cameraZ.isFinite() || cameraZ <= 1e-5) return null
            val u = (context.fx * cameraX + context.skew * cameraY) / cameraZ + context.cx
            val v = context.fy * cameraY / cameraZ + context.cy
            if (!u.isFinite() || !v.isFinite()) return null
            return u.toFloat() to v.toFloat()
        }

        val output = linkedMapOf<Int, List<TrackingAnchorSegment>>()
        context.poses.asSequence()
            .filter {
                it.status == CameraPoseStatus.REFERENCE ||
                    it.status == CameraPoseStatus.PNP_REGISTERED ||
                    it.status == CameraPoseStatus.RELATIVE_ESTIMATE
            }
            .sortedBy { it.frameIndex }
            .forEach { pose ->
                val origin = projectPoint(position.x, position.y, position.z, pose) ?: return@forEach
                val segments = endpoints.mapNotNull { axis ->
                    val end = projectPoint(axis.x, axis.y, axis.z, pose) ?: return@mapNotNull null
                    TrackingAnchorSegment(origin.first, origin.second, end.first, end.second, axis.color)
                }
                if (segments.isNotEmpty()) output[pose.frameIndex] = segments
            }
        return output
    }
}
