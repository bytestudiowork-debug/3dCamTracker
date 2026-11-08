package com.trackstudio.app.data.io

import com.trackstudio.app.data.model.ProxyResolution

/**
 * Conservative automatic working-resolution policy for video projects.
 *
 * Video originals are never modified. Half resolution is the default;
 * quarter resolution is selected for footage whose pixel count, duration,
 * frame count or compressed size makes full-resolution tracking expensive.
 */
object VideoProxyPolicy {
    fun recommendedResolution(
        width: Int,
        height: Int,
        durationUs: Long,
        frameCount: Int,
        sizeBytes: Long
    ): ProxyResolution {
        val pixels = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
        val durationSeconds = durationUs.coerceAtLeast(0L) / 1_000_000.0
        val largeHighResolutionClip = pixels >= 8_000_000L && durationSeconds >= 45.0
        val veryLongClip = durationSeconds >= 300.0
        val veryLargeFile = sizeBytes >= 1_000_000_000L
        val veryDenseClip = frameCount >= 6_000

        return if (largeHighResolutionClip || veryLongClip || veryLargeFile || veryDenseClip) {
            ProxyResolution.QUARTER
        } else {
            ProxyResolution.HALF
        }
    }
}
