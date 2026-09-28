package com.trackstudio.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * What the playback clock needs from whoever owns the decoded frames.
 */
interface FrameSource {

    /**
     * Show [index] as soon as it is ready. Non-blocking; a newer request
     * replaces an older one. Used for seeking and single-stepping.
     */
    fun requestFrame(index: Int)

    /**
     * Suspends until [index] is decoded AND on screen. Used by the
     * playback clock so it never advances past a frame that has not been
     * shown yet.
     */
    suspend fun presentFrame(index: Int)

    /** Hint: playback is running forward from [fromIndex]; warm the cache. */
    fun prefetchForward(fromIndex: Int)
}

/**
 * Real-time playback clock over an image sequence.
 *
 * The clock is based on a monotonic nanosecond deadline rather than a
 * repeating millisecond delay. This prevents integer-rounding error from
 * accumulating and making playback faster than the selected FPS.
 *
 * If decoding takes longer than a frame interval, playback does not burst
 * through multiple frames to catch up. It resynchronizes to the next frame
 * boundary, keeping motion timing stable.
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val source: FrameSource,
    private val onPlayingChanged: (Boolean) -> Unit
) {

    private var playbackJob: Job? = null

    private var frameCount = 0
    private var fps = 24.0
    private var currentFrame = 0

    val isPlaying: Boolean
        get() = playbackJob?.isActive == true

    fun configure(
        frameCount: Int,
        fps: Double,
        currentFrame: Int = 0
    ) {
        stop()

        this.frameCount = frameCount.coerceAtLeast(0)
        this.fps = fps.coerceIn(1.0, 240.0)

        this.currentFrame =
            if (this.frameCount > 0) {
                currentFrame.coerceIn(0, this.frameCount - 1)
            } else {
                0
            }
    }

    fun play() {
        if (frameCount <= 0 || isPlaying) {
            return
        }

        // Restart from the beginning when play is pressed on the last frame.
        if (currentFrame >= frameCount - 1) {
            currentFrame = 0
            source.requestFrame(0)
        }

        onPlayingChanged(true)

        playbackJob = scope.launch {
            val intervalNanos = frameIntervalNanos()

            // The current frame is already visible. Schedule the next frame
            // exactly one frame interval from the moment playback starts.
            var nextFrameDeadline = System.nanoTime() + intervalNanos

            while (isActive && currentFrame < frameCount - 1) {
                source.prefetchForward(currentFrame)

                waitUntil(nextFrameDeadline)

                if (!isActive || currentFrame >= frameCount - 1) {
                    break
                }

                val target = currentFrame + 1

                // Presentation includes waiting for decode when necessary.
                // Never advance the playhead before the target is presented.
                source.presentFrame(target)
                currentFrame = target

                nextFrameDeadline += intervalNanos

                // A slow decode can make us miss the old deadline. Do not
                // execute several frame transitions back-to-back. Give the
                // next frame a complete interval from the current instant.
                val now = System.nanoTime()
                if (nextFrameDeadline <= now) {
                    nextFrameDeadline = now + intervalNanos
                }
            }

            if (isActive) {
                playbackJob = null
                onPlayingChanged(false)
            }
        }
    }

    fun pause() {
        stop()
    }

    private fun stop() {
        val wasPlaying = isPlaying

        playbackJob?.cancel()
        playbackJob = null

        if (wasPlaying) {
            onPlayingChanged(false)
        }
    }

    fun nextFrame() = jumpTo(currentFrame + 1)

    fun previousFrame() = jumpTo(currentFrame - 1)

    fun firstFrame() = jumpTo(0)

    fun lastFrame() = jumpTo(frameCount - 1)

    fun seekTo(frame: Int) = jumpTo(frame)

    private fun jumpTo(frame: Int) {
        stop()

        if (frameCount <= 0) {
            return
        }

        currentFrame = frame.coerceIn(0, frameCount - 1)
        source.requestFrame(currentFrame)
    }

    fun release() {
        stop()
    }

    /**
     * Waits efficiently without a busy loop. Millisecond delays are rounded
     * UP so scheduler precision can never make playback faster by truncating
     * the remaining fractional millisecond.
     */
    private suspend fun waitUntil(deadlineNanos: Long) {
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()

            if (remainingNanos <= 0L) {
                return
            }

            val millis = ceil(remainingNanos / 1_000_000.0)
                .toLong()
                .coerceAtLeast(1L)

            delay(millis)
        }
    }

    private fun frameIntervalNanos(): Long =
        (1_000_000_000.0 / fps)
            .toLong()
            .coerceAtLeast(1L)
}
