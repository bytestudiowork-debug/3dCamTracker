package com.trackstudio.app.data.model

import android.net.Uri

/**
 * One frame of an imported image sequence, after it has passed validation.
 * `uri` is the original SAF content Uri - we never copy the source frame
 * itself into app storage, only the (much smaller) proxy, so import of a
 * multi-GB sequence costs no extra disk space up front.
 */
data class FootageFrame(
    val uri: Uri,
    val displayName: String,
    val index: Int,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

/**
 * Aggregate description of an imported sequence, derived once all frames
 * have been probed. This is what the rest of the app (proxy generation,
 * eventually the tracker) reasons about - not the raw Uri list.
 */
data class FootageInfo(
    val frames: List<FootageFrame>,
    val detectedPattern: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val totalSizeBytes: Long,
    val isResolutionConsistent: Boolean
) {
    val requiresProxyByDefault: Boolean
        // Anything above ~2K wide is a reasonable trigger to nudge users
        // toward proxies on a "low end device" - keeps interactive preview
        // and (later) tracking passes off full 4K/6K frames by default.
        get() = width > 2048

    companion object {
        val EMPTY = FootageInfo(
            frames = emptyList(),
            detectedPattern = "",
            width = 0,
            height = 0,
            frameCount = 0,
            totalSizeBytes = 0L,
            isResolutionConsistent = true
        )
    }
}
