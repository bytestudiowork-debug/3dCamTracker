package com.trackstudio.app.playback

data class PlaybackState(
    val currentFrame: Int = 0,
    val frameCount: Int = 0,
    val fps: Double = 24.0,
    val speed: Double = 1.0,
    val isPlaying: Boolean = false
) {
    val hasFrames: Boolean
        get() = frameCount > 0

    val isFirstFrame: Boolean
        get() = currentFrame <= 0

    val isLastFrame: Boolean
        get() = frameCount <= 0 || currentFrame >= frameCount - 1

    val frameNumber: Int
        get() = if (frameCount > 0) currentFrame + 1 else 0

    val durationSeconds: Double
        get() = if (fps > 0.0) frameCount.toDouble() / fps else 0.0
}
