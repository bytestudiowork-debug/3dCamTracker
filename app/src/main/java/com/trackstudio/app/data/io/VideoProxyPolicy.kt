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
    fun recommendedViewerScale(width: Int, height: Int, trackingScale: Float): Float {
        val pixels = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
        val preferredViewerScale = when {
            pixels >= 8_000_000L -> 0.5f
            trackingScale <= 0.25f -> 0.5f
            else -> 0.75f
        }
        return maxOf(trackingScale.coerceIn(0.125f, 1f), preferredViewerScale)
    }

    fun recommendedResolution(
        width: Int,
        height: Int,
        durationUs: Long,
        frameCount: Int,
        sizeBytes: Long
    ): ProxyResolution {
        val safeWidth = width.coerceAtLeast(0)
        val safeHeight = height.coerceAtLeast(0)
        val pixels = safeWidth.toLong() * safeHeight.toLong()
        val durationSeconds = durationUs.coerceAtLeast(0L) / 1_000_000.0

        // Keep small footage at its native size so feature detection does not
        // lose corners and texture. 1080p/1440p footage is reduced by half.
        // Only high-resolution footage receives the quarter-size tracking copy.
        val highResolution = pixels >= 8_000_000L
        val highResolutionWorkload = durationSeconds >= 30.0 ||
            frameCount >= 900 || sizeBytes >= 500_000_000L

        return when {
            highResolution && highResolutionWorkload -> ProxyResolution.QUARTER
            safeWidth > 1280 || safeHeight > 720 -> ProxyResolution.HALF
            else -> ProxyResolution.FULL
        }
    }
}
