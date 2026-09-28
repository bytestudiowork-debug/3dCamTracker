package com.trackstudio.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * Clock-driven playback over an image sequence.
 *
 * Design notes (this replaces the previous implementation, whose playback
 * appeared frozen):
 *
 *  - The old clock fired a "load frame N" request every 1/fps seconds and
 *    each request cancelled the previous decode. Whenever decoding a frame
 *    took longer than one frame interval, *every* decode was superseded
 *    before it finished, so no new image was ever displayed.
 *  - Now the clock asks the [FrameSource] to present the next frame and
 *    waits for it. If decoding is slower than real time, playback simply
 *    runs slower (every frame is shown, none is dropped) instead of
 *    freezing, and the clock re-syncs rather than bursting to catch up.
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

    /** 1.0 = normal speed. */
    var speed: Double = 1.0
        set(value) {
            field = value.coerceIn(0.05, 8.0)
        }

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

            var nextFrameTime =
                System.nanoTime() + frameIntervalNanos()

            while (isActive) {

                source.prefetchForward(currentFrame)

                val waitMillis =
                    (nextFrameTime - System.nanoTime()) / 1_000_000L

                if (waitMillis > 0L) {
                    delay(waitMillis)
                }

                if (currentFrame >= frameCount - 1) {
                    break
                }

                val target = currentFrame + 1

                // May suspend for a while if this frame is still decoding.
                source.presentFrame(target)

                currentFrame = target

                nextFrameTime += frameIntervalNanos()

                // Running late (slow decode): resync instead of bursting.
                val now = System.nanoTime()
                if (nextFrameTime < now) {
                    nextFrameTime = now
                }
            }

            // Reached the end (or was cancelled: the canceller already
            // reported the state change).
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

    private fun frameIntervalNanos(): Long =
        (1_000_000_000.0 / (fps * speed))
            .toLong()
            .coerceAtLeast(1L)
}
