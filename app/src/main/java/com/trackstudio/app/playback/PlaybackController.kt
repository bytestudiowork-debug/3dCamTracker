package com.trackstudio.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

interface FrameSource {
    fun requestFrame(index: Int)
    suspend fun presentFrame(index: Int)
    fun prefetchForward(fromIndex: Int)
}

/**
 * Real-time playback clock over an image sequence.
 *
 * Timing uses a monotonic clock and the selected playback speed.
 * Frames are presented before the playhead advances, so slow decoding
 * cannot create a burst of frames or a permanently fast clock.
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val source: FrameSource,
    private val onPlayingChanged: (Boolean) -> Unit
) {
    private var playbackJob: Job? = null
    private var frameCount = 0
    private var fps = 24.0
    private var speed = 1.0
    private var currentFrame = 0

    val isPlaying: Boolean
        get() = playbackJob?.isActive == true

    val playbackSpeed: Double
        get() = speed

    fun configure(
        frameCount: Int,
        fps: Double,
        currentFrame: Int = 0,
        speed: Double = 1.0
    ) {
        stop()
        this.frameCount = frameCount.coerceAtLeast(0)
        this.fps = fps.coerceIn(1.0, 240.0)
        this.speed = speed.coerceIn(0.25, 2.0)
        this.currentFrame =
            if (this.frameCount > 0) currentFrame.coerceIn(0, this.frameCount - 1) else 0
    }

    fun setSpeed(value: Double) {
        speed = value.coerceIn(0.25, 2.0)
    }

    fun play() {
        if (frameCount <= 0 || isPlaying) return

        if (currentFrame >= frameCount - 1) {
            currentFrame = 0
            source.requestFrame(0)
        }

        onPlayingChanged(true)

        playbackJob = scope.launch {
            var nextFrameDeadline = System.nanoTime() + frameIntervalNanos()

            while (isActive && currentFrame < frameCount - 1) {
                source.prefetchForward(currentFrame)
                waitUntil(nextFrameDeadline)

                if (!isActive || currentFrame >= frameCount - 1) break

                val target = currentFrame + 1
                source.presentFrame(target)
                currentFrame = target

                val interval = frameIntervalNanos()
                nextFrameDeadline += interval

                val now = System.nanoTime()
                if (nextFrameDeadline <= now) {
                    nextFrameDeadline = now + interval
                }
            }

            if (isActive) {
                playbackJob = null
                onPlayingChanged(false)
            }
        }
    }

    fun pause() = stop()

    private fun stop() {
        val wasPlaying = isPlaying
        playbackJob?.cancel()
        playbackJob = null
        if (wasPlaying) onPlayingChanged(false)
    }

    fun nextFrame() = jumpTo(currentFrame + 1)
    fun previousFrame() = jumpTo(currentFrame - 1)
    fun firstFrame() = jumpTo(0)
    fun lastFrame() = jumpTo(frameCount - 1)
    fun seekTo(frame: Int) = jumpTo(frame)

    private fun jumpTo(frame: Int) {
        stop()
        if (frameCount <= 0) return
        currentFrame = frame.coerceIn(0, frameCount - 1)
        source.requestFrame(currentFrame)
    }

    fun release() = stop()

    private suspend fun waitUntil(deadlineNanos: Long) {
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return

            val millis = ceil(remainingNanos / 1_000_000.0)
                .toLong()
                .coerceAtLeast(1L)
            delay(millis)
        }
    }

    private fun frameIntervalNanos(): Long =
        (1_000_000_000.0 / (fps * speed)).toLong().coerceAtLeast(1L)
}
