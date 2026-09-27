package com.trackstudio.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackController(
    private val scope: CoroutineScope,
    private val onFrameChanged: (Int) -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit
) {

    private var playbackJob: Job? = null

    private var frameCount = 0
    private var fps = 24.0
    private var currentFrame = 0

    fun configure(
        frameCount: Int,
        fps: Double,
        currentFrame: Int = 0
    ) {
        stop(notify = false)

        this.frameCount = frameCount.coerceAtLeast(0)
        this.fps = fps.coerceIn(1.0, 240.0)

        this.currentFrame =
            if (this.frameCount > 0) {
                currentFrame.coerceIn(0, this.frameCount - 1)
            } else {
                0
            }

        onPlayingChanged(false)
        onFrameChanged(this.currentFrame)
    }

    fun play() {
        if (frameCount <= 0) {
            return
        }

        if (playbackJob?.isActive == true) {
            return
        }

        /*
         * If playback is at the last frame, start from frame 0.
         */
        if (currentFrame >= frameCount - 1) {
            currentFrame = 0
            onFrameChanged(currentFrame)
        }

        onPlayingChanged(true)

        playbackJob = scope.launch {

            val frameIntervalNanos =
                (1_000_000_000.0 / fps)
                    .toLong()
                    .coerceAtLeast(1L)

            var nextFrameTime =
                System.nanoTime() + frameIntervalNanos

            while (isActive) {

                val remainingNanos =
                    nextFrameTime - System.nanoTime()

                if (remainingNanos > 0L) {

                    val remainingMillis =
                        remainingNanos / 1_000_000L

                    if (remainingMillis > 0L) {
                        delay(remainingMillis)
                    }
                }

                if (!isActive) {
                    break
                }

                if (currentFrame >= frameCount - 1) {
                    currentFrame = frameCount - 1
                    onFrameChanged(currentFrame)
                    stop()
                    break
                }

                currentFrame++

                onFrameChanged(currentFrame)

                /*
                 * Keep playback timing independent of image decoding.
                 */
                nextFrameTime += frameIntervalNanos

                /*
                 * If we fall too far behind, resynchronize the clock.
                 * This prevents a burst of frames after a slow decode.
                 */
                val now = System.nanoTime()

                if (
                    nextFrameTime <
                    now - frameIntervalNanos * 2L
                ) {
                    nextFrameTime =
                        now + frameIntervalNanos
                }
            }
        }
    }

    fun pause() {
        stop()
    }

    fun stop() {
        stop(notify = true)
    }

    private fun stop(notify: Boolean) {

        playbackJob?.cancel()
        playbackJob = null

        if (notify) {
            onPlayingChanged(false)
        }
    }

    fun nextFrame() {
        stop()

        if (frameCount <= 0) {
            return
        }

        currentFrame =
            (currentFrame + 1)
                .coerceAtMost(frameCount - 1)

        onFrameChanged(currentFrame)
    }

    fun previousFrame() {
        stop()

        if (frameCount <= 0) {
            return
        }

        currentFrame =
            (currentFrame - 1)
                .coerceAtLeast(0)

        onFrameChanged(currentFrame)
    }

    fun firstFrame() {
        stop()

        if (frameCount <= 0) {
            return
        }

        currentFrame = 0

        onFrameChanged(currentFrame)
    }

    fun lastFrame() {
        stop()

        if (frameCount <= 0) {
            return
        }

        currentFrame = frameCount - 1

        onFrameChanged(currentFrame)
    }

    fun seekTo(frame: Int) {
        stop()

        if (frameCount <= 0) {
            return
        }

        currentFrame =
            frame.coerceIn(
                0,
                frameCount - 1
            )

        onFrameChanged(currentFrame)
    }

    fun release() {
        stop()
    }
}