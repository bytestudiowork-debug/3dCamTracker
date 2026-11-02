package com.trackstudio.app.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

interface FrameSource {
    fun requestFrame(index: Int)
    suspend fun presentFrame(index: Int)
    fun prefetchForward(fromIndex: Int)
}

/**
 * Real-time playback clock for an image sequence.
 *
 * A frame is presented before the playhead advances. Deadlines use a
 * monotonic clock, and missed deadlines are resynchronized instead of
 * producing catch-up bursts.
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
        this.fps = fps.takeIf { it.isFinite() }?.coerceIn(1.0, 240.0) ?: 24.0
        this.speed = speed.takeIf { it.isFinite() }?.coerceIn(0.25, 2.0) ?: 1.0
        this.currentFrame =
            if (this.frameCount > 0) currentFrame.coerceIn(0, this.frameCount - 1) else 0
    }

    fun setSpeed(value: Double) {
        if (value.isFinite()) speed = value.coerceIn(0.25, 2.0)
    }

    fun play() {
        if (frameCount <= 0 || isPlaying) return

        val startFrame = if (currentFrame >= frameCount - 1) 0 else currentFrame
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (startFrame != currentFrame) currentFrame = startFrame
                source.presentFrame(startFrame)

                var nextDeadline = System.nanoTime() + frameIntervalNanos()

                while (coroutineContext.isActive && currentFrame < frameCount - 1) {
                    source.prefetchForward(currentFrame)
                    waitUntil(nextDeadline)
                    if (!coroutineContext.isActive) break

                    val target = currentFrame + 1
                    source.presentFrame(target)
                    currentFrame = target

                    val interval = frameIntervalNanos()
                    nextDeadline += interval

                    val now = System.nanoTime()
                    if (nextDeadline <= now) {
                        nextDeadline = now + interval
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // FrameSource reports decoding errors to the viewer state.
            } finally {
                if (playbackJob === coroutineContext[Job]) {
                    playbackJob = null
                    onPlayingChanged(false)
                }
            }
        }

        playbackJob = job
        onPlayingChanged(true)
        job.start()
    }

    fun pause() = stop()

    private fun stop() {
        val job = playbackJob
        playbackJob = null
        job?.cancel()
        if (job?.isActive == true) onPlayingChanged(false)
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
        while (coroutineContext.isActive) {
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
