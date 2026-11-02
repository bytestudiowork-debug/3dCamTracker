package com.trackstudio.app.tracking.core

import android.graphics.Bitmap
import com.trackstudio.app.tracking.model.TrackObservation
import com.trackstudio.app.tracking.model.TrackPoint
import com.trackstudio.app.tracking.model.TrackStatus
import com.trackstudio.app.tracking.model.TrackingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface TrackingFrameProvider {
    suspend fun loadFrame(index: Int, maximumDimension: Int): Bitmap
    fun releaseFrame(bitmap: Bitmap)
}

class TrackingSequenceProcessor(
    private val engine: TrackingEngine,
    private val frames: TrackingFrameProvider
) {
    suspend fun trackManual(
        startFrame: Int,
        endFrame: Int,
        tracks: List<TrackPoint>,
        onProgress: (TrackingProgress) -> Unit = {}
    ): List<TrackPoint> {
        require(startFrame >= 0 && endFrame >= 0 && startFrame != endFrame)
        require(tracks.all { it.observationAt(startFrame) != null }) {
            "Every manual track must have a point on the starting frame."
        }
        return process(startFrame, endFrame, tracks, automatic = false, onProgress)
    }

    suspend fun trackAutomatic(
        startFrame: Int,
        endFrame: Int,
        firstTrackId: Long,
        onProgress: (TrackingProgress) -> Unit = {}
    ): List<TrackPoint> {
        require(startFrame >= 0 && endFrame >= 0 && startFrame != endFrame)
        require(firstTrackId >= 0L)
        return process(
            startFrame,
            endFrame,
            emptyList(),
            automatic = true,
            firstTrackId = firstTrackId,
            onProgress = onProgress
        )
    }

    private suspend fun process(
        startFrame: Int,
        endFrame: Int,
        initialTracks: List<TrackPoint>,
        automatic: Boolean,
        firstTrackId: Long = 0L,
        onProgress: (TrackingProgress) -> Unit
    ): List<TrackPoint> = withContext(Dispatchers.Default) {
        val direction = if (endFrame > startFrame) 1 else -1
        val totalFrames = kotlin.math.abs(endFrame - startFrame)
        var tracks = initialTracks
        var previous: Bitmap? = null
        var current: Bitmap? = null

        try {
            coroutineContext.ensureActive()
            val firstFrame = frames.loadFrame(startFrame, engine.maximumAnalysisDimension)
            previous = firstFrame

            if (automatic) {
                val seeds = engine.detectAutomaticPoints(firstFrame)
                tracks = seeds.mapIndexed { index, point ->
                    TrackPoint(
                        id = firstTrackId + index.toLong(),
                        label = "Auto ${index + 1}",
                        isManuallyCreated = false,
                        observations = listOf(
                            TrackObservation(startFrame, point, 1f, TrackStatus.ACTIVE)
                        )
                    )
                }
            }

            onProgress(
                TrackingProgress(
                    completedFrames = 0,
                    totalFrames = totalFrames,
                    trackedPoints = tracks.size,
                    lostPoints = 0
                )
            )

            var frameIndex = startFrame
            while (frameIndex != endFrame) {
                coroutineContext.ensureActive()
                val nextIndex = frameIndex + direction
                val previousFrame = previous ?: error("Previous tracking frame is unavailable.")
                val nextFrame = frames.loadFrame(nextIndex, engine.maximumAnalysisDimension)
                current = nextFrame

                val activeTracks = tracks.filter {
                    it.observationAt(frameIndex)?.status == TrackStatus.ACTIVE
                }
                val points = activeTracks.map { it.observationAt(frameIndex)!!.point }

                val result = if (automatic) {
                    engine.trackAutomaticPoints(previousFrame, nextFrame, points, nextIndex)
                } else {
                    engine.trackManualPoints(previousFrame, nextFrame, points, nextIndex)
                }

                val updates = activeTracks.zip(result.points)
                val updatedById = updates.associate { (track, tracked) ->
                    val observation = TrackObservation(
                        frameIndex = nextIndex,
                        point = if (tracked.isValid) tracked.position
                            else track.observationAt(frameIndex)!!.point,
                        confidence = tracked.confidence,
                        status = if (tracked.isValid) TrackStatus.ACTIVE else TrackStatus.LOST
                    )
                    track.id to track.putObservation(observation)
                }
                tracks = tracks.map { track ->
                    updatedById[track.id] ?: run {
                        val previousObservation = track.observationAt(frameIndex)
                        if (previousObservation?.status == TrackStatus.LOST) {
                            track.putObservation(
                                TrackObservation(
                                    frameIndex = nextIndex,
                                    point = previousObservation.point,
                                    confidence = 0f,
                                    status = TrackStatus.LOST
                                )
                            )
                        } else {
                            track
                        }
                    }
                }

                frames.releaseFrame(previousFrame)
                previous = nextFrame
                current = null
                frameIndex = nextIndex

                onProgress(
                    TrackingProgress(
                        completedFrames = kotlin.math.abs(frameIndex - startFrame),
                        totalFrames = totalFrames,
                        trackedPoints = tracks.count {
                            it.observationAt(frameIndex)?.status == TrackStatus.ACTIVE
                        },
                        lostPoints = tracks.count {
                            it.observationAt(frameIndex)?.status == TrackStatus.LOST
                        }
                    )
                )
            }
            tracks
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            current?.let(frames::releaseFrame)
            previous?.let(frames::releaseFrame)
        }
    }
}
