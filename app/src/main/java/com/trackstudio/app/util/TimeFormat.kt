package com.trackstudio.app.util

import java.util.Locale

/** "mm:ss" timecode for a zero-based frame index at the given frame rate. */
fun formatTimecode(frameIndex: Int, fps: Double): String {
    val totalSeconds =
        if (fps > 0.0) (frameIndex / fps).toInt() else 0

    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
