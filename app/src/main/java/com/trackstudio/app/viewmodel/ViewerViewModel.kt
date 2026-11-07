package com.trackstudio.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trackstudio.app.playback.FrameSource
import com.trackstudio.app.playback.PlaybackController
import com.trackstudio.app.playback.PlaybackState
import com.trackstudio.app.tracking.CameraTrackingEngine
import com.trackstudio.app.tracking.GlobalReconstructionEngine
import com.trackstudio.app.tracking.ReconstructionMode
import com.trackstudio.app.tracking.ReconstructionEngine
import com.trackstudio.app.tracking.CameraTrackingProgress
import com.trackstudio.app.tracking.TrackingVerificationProjector
import com.trackstudio.app.tracking.TrackingVerificationSegment
import com.trackstudio.app.tracking.OpenCvRuntime
import com.trackstudio.app.data.io.VideoPreviewDecoder
import com.trackstudio.app.data.io.SequentialVideoFrameDecoder
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class CameraTrackingSummary(
    val registeredCameras: Int,
    val totalFrames: Int,
    val landmarkCount: Int,
    val inlierRatio: Double,
    val initialPairFrames: Pair<Int, Int>?,
    val meanReprojectionErrorPx: Double = 0.0,
    val globalPositioningApplied: Boolean = false,
    val globalPositioningInitialCost: Double = Double.NaN,
    val globalPositioningFinalCost: Double = Double.NaN,
    val globalPositioningIterations: Int = 0,
    val globalPositioningStatus: String = "Not run"
)

data class ViewerUiState(
    val playback: PlaybackState = PlaybackState(),
    val currentBitmap: Bitmap? = null,
    val isLoadingFrame: Boolean = false,
    val errorMessage: String? = null,
    val thumbnails: Map<Int, Bitmap> = emptyMap(),
    val isAutoTracking: Boolean = false,
    val trackingProgress: CameraTrackingProgress? = null,
    val trackingError: String? = null,
    val trackingSummary: CameraTrackingSummary? = null,
    val reconstructionMode: ReconstructionMode = ReconstructionMode.CUSTOM,
    val trackingPointsByFrame: Map<Int, List<com.trackstudio.app.tracking.CameraFeatureObservation>> = emptyMap(),
    val trackingVerificationByFrame: Map<Int, List<TrackingVerificationSegment>> = emptyMap()
)

class ViewerViewModel(
    private val appContext: Context,
    private val customEngine: ReconstructionEngine = CameraTrackingEngine()
) : ViewModel() {

    companion object {
        private const val THUMBNAIL_MAX_DIMENSION = 320
        private const val MAX_TRACKING_OVERLAY_POINTS = 900
        private const val BACK_WINDOW = 2
        private const val DEFAULT_AHEAD = 3
        private const val MAX_AHEAD = 8
    }

    private var reconstructionEngine: ReconstructionEngine = customEngine

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var frameUris: List<Uri> = emptyList()
    private var trackingFrameUris: List<Uri> = emptyList()
    private var sourceVideoUri: Uri? = null
    private var sourceVideoFrameCount: Int = 0
    private var sourceVideoRotation: Int = 0
    private var sourceVideoWidth: Int = 0
    private var sourceVideoHeight: Int = 0
    private var videoPreviewDecoder: VideoPreviewDecoder? = null

    private val maxHeapMb =
        Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    private val maxDecodeDimension =
        if (maxHeapMb <= 192L) 1280 else 1920

    private val decodeSemaphore =
        Semaphore(if (Runtime.getRuntime().availableProcessors() >= 4) 2 else 1)

    private val bitmapCache =
        object : LruCache<Int, Bitmap>(cacheSizeKb()) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                max(1, value.byteCount / 1024)
        }

    private val inFlight = ConcurrentHashMap<Int, Deferred<Bitmap>>()

    @Volatile
    private var wantedCenter = 0

    @Volatile
    private var frameBytes = 0

    @Volatile
    private var sequenceGeneration = 0

    private var requestJob: Job? = null
    private var thumbnailJob: Job? = null
    private var trackingJob: Job? = null
    private var thumbnailCount = 0

    private val frameSource =
        object : FrameSource {
            override fun requestFrame(index: Int) = showFrame(index)
            override suspend fun presentFrame(index: Int) = presentFrameForPlayback(index)
            override fun prefetchForward(fromIndex: Int) = prefetchAhead(fromIndex)
        }

    private val playbackController =
        PlaybackController(
            scope = viewModelScope,
            source = frameSource,
            onPlayingChanged = { playing ->
                _uiState.update {
                    it.copy(playback = it.playback.copy(isPlaying = playing))
                }
            }
        )

    fun loadSequence(uris: List<Uri>, fps: Double, sourceUris: List<Uri> = uris, videoUri: Uri? = null, videoFrameCount: Int = uris.size, sourceWidth: Int = 0, sourceHeight: Int = 0, videoRotationDegrees: Int = 0) {
        trackingJob?.cancel()
        trackingJob = null
        requestJob?.cancel()
        thumbnailJob?.cancel()
        trackingJob?.cancel()
        playbackController.release()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()

        sequenceGeneration++
        videoPreviewDecoder?.close()
        videoPreviewDecoder = null
        sourceVideoUri = videoUri
        sourceVideoFrameCount = if (videoUri != null) videoFrameCount else 0
        sourceVideoRotation = videoRotationDegrees
        sourceVideoWidth = sourceWidth
        sourceVideoHeight = sourceHeight
        if (videoUri != null) videoPreviewDecoder = VideoPreviewDecoder(appContext, videoUri, videoRotationDegrees)
        frameUris = uris.toList()
        trackingFrameUris = sourceUris.toList()
        bitmapCache.evictAll()
        frameBytes = 0
        thumbnailCount = 0
        wantedCenter = 0

        val safeFps = fps.coerceIn(1.0, 240.0)

        playbackController.configure(
            frameCount = if (videoUri != null) videoFrameCount else frameUris.size,
            fps = safeFps,
            currentFrame = 0,
            speed = 1.0
        )

        val initialMode = if (videoUri != null) ReconstructionMode.GLOBAL else _uiState.value.reconstructionMode
        reconstructionEngine = if (initialMode == ReconstructionMode.GLOBAL) GlobalReconstructionEngine() else customEngine
        _uiState.value = ViewerUiState(
            playback = PlaybackState(
                currentFrame = 0,
                frameCount = if (videoUri != null) videoFrameCount else frameUris.size,
                fps = safeFps,
                speed = 1.0,
                isPlaying = false
            ),
            reconstructionMode = initialMode
        )

        if (frameUris.isNotEmpty() || videoUri != null) showFrame(0)
    }

    fun playPause() {
        if (_uiState.value.playback.isPlaying) playbackController.pause()
        else playbackController.play()
    }

    fun pause() = playbackController.pause()
    fun nextFrame() = playbackController.nextFrame()
    fun previousFrame() = playbackController.previousFrame()
    fun firstFrame() = playbackController.firstFrame()
    fun lastFrame() = playbackController.lastFrame()
    fun seekTo(frame: Int) = playbackController.seekTo(frame)

    fun setPlaybackSpeed(speed: Double) {
        val safeSpeed = speed.coerceIn(0.25, 2.0)
        playbackController.setSpeed(safeSpeed)
        _uiState.update {
            it.copy(playback = it.playback.copy(speed = safeSpeed))
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, trackingError = null) }
    }

    fun setReconstructionMode(mode: ReconstructionMode) {
        if (_uiState.value.isAutoTracking) return
        reconstructionEngine = when (mode) {
            ReconstructionMode.CUSTOM -> customEngine
            ReconstructionMode.GLOBAL -> GlobalReconstructionEngine()
        }
        _uiState.update {
            it.copy(
                reconstructionMode = mode,
                trackingSummary = null,
                trackingError = null,
                trackingPointsByFrame = emptyMap(),
                trackingVerificationByFrame = emptyMap()
            )
        }
    }

    fun startAutoTrack() {
        if (_uiState.value.isAutoTracking) return
        val useOriginals = _uiState.value.reconstructionMode == ReconstructionMode.GLOBAL
        val videoUri = sourceVideoUri
        if (videoUri != null && !useOriginals) {
            _uiState.update { it.copy(trackingError = "Direct video tracking currently uses the Global engine. Import an image sequence to use Custom Tracker.") }
            return
        }
        if (videoUri == null && useOriginals && trackingFrameUris.size != frameUris.size) {
            _uiState.update { it.copy(trackingError = "Original source frames are unavailable for Global reconstruction. Reopen or relink the project footage.") }
            return
        }
        val uris = (if (useOriginals) trackingFrameUris else frameUris).toList()
        val totalFrames = if (videoUri != null) sourceVideoFrameCount else uris.size
        val decodeLimit = if (useOriginals || videoUri != null) Int.MAX_VALUE else maxDecodeDimension
        if (totalFrames < 3) {
            _uiState.update { it.copy(trackingError = "Auto Track needs at least three frames.") }
            return
        }
        playbackController.pause()
        trackingJob?.cancel()
        _uiState.update { it.copy(isAutoTracking = true, trackingProgress = CameraTrackingProgress(0, totalFrames, "Preparing footage", 0f, 0, totalFrames), trackingError = null, trackingSummary = null, trackingPointsByFrame = emptyMap(), trackingVerificationByFrame = emptyMap()) }
        trackingJob = viewModelScope.launch {
            var intrinsics: Mat? = null
            var sequentialDecoder: SequentialVideoFrameDecoder? = null
            try {
                check(OpenCvRuntime.ensureLoaded()) { "OpenCV could not be initialized on this device." }
                val dimensions = if (videoUri != null) {
                    sourceVideoWidth to sourceVideoHeight
                } else {
                    withContext(Dispatchers.IO) {
                        val bitmap = decodeSampled(uris.first(), decodeLimit, Bitmap.Config.ARGB_8888)
                        try { bitmap.width to bitmap.height } finally { bitmap.recycle() }
                    }
                }
                if (videoUri != null) {
                    sequentialDecoder = withContext(Dispatchers.IO) {
                        SequentialVideoFrameDecoder(appContext, videoUri, sourceVideoRotation)
                    }
                }
                val width = dimensions.first
                val height = dimensions.second
                require(width > 0 && height > 0) { "The first frame has invalid dimensions." }
                // Approximate intrinsics until focal length calibration/EXIF support is added.
                val focal = max(width, height) * 0.9
                val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
                intrinsics = cameraMatrix
                cameraMatrix.put(0, 0, focal, 0.0, (width - 1) * 0.5, 0.0, focal, (height - 1) * 0.5, 0.0, 0.0, 1.0)
                val result = reconstructionEngine.solve(
                    frameCount = totalFrames,
                    cameraMatrix = cameraMatrix,
                    frameProvider = { index ->
                        withContext(Dispatchers.IO) {
                            val bitmap = if (sequentialDecoder != null) {
                                sequentialDecoder!!.nextFrame()
                                    ?: error("Video decoder ended before frame " + (index + 1) + ".")
                            } else {
                                decodeSampled(uris[index], decodeLimit, Bitmap.Config.ARGB_8888)
                            }
                            val mat = Mat()
                            try { Utils.bitmapToMat(bitmap, mat); mat }
                            catch (failure: Throwable) { mat.release(); throw failure }
                            finally { bitmap.recycle() }
                        }
                    },
                    onProgress = { progress -> _uiState.update { it.copy(trackingProgress = progress) } }
                )
                // Display every persistent, geometrically verified 2D feature track.
                // Requiring a successfully triangulated 3D landmark hid all points on
                // clips where the sparse map was valid but conservative.
                val pointsByFrame = result.featureTracks.asSequence()
                    .filter { it.observations.size >= 2 }
                    .flatMap { it.observations.asSequence() }
                    .filter { observation ->
                        observation.frameIndex in 0 until totalFrames &&
                            observation.x.isFinite() && observation.y.isFinite()
                    }
                    .groupBy { it.frameIndex }
                    .mapValues { (_, observations) ->
                        if (observations.size <= MAX_TRACKING_OVERLAY_POINTS) observations
                        else observations.filterIndexed { index, _ ->
                            index % ((observations.size + MAX_TRACKING_OVERLAY_POINTS - 1) / MAX_TRACKING_OVERLAY_POINTS) == 0
                        }.take(MAX_TRACKING_OVERLAY_POINTS)
                    }
                val verificationByFrame = TrackingVerificationProjector.project(
                    landmarks = result.landmarks,
                    poses = result.poses,
                    cameraMatrix = cameraMatrix,
                    imageWidth = width,
                    imageHeight = height
                )
                _uiState.update { state ->
                    state.copy(
                        isAutoTracking = false,
                        trackingProgress = CameraTrackingProgress(totalFrames, totalFrames, "Camera solve complete", 1f, totalFrames, totalFrames),
                        trackingSummary = CameraTrackingSummary(
                            result.registeredCameraCount,
                            result.processedFrames,
                            result.landmarks.size,
                            result.inlierRatio,
                            result.initialPairFrames,
                            result.landmarks.map { it.meanReprojectionErrorPx }
                                .filter { it.isFinite() }.average().takeIf { it.isFinite() } ?: 0.0,
                            result.globalPositioningApplied,
                            result.globalPositioningInitialCost,
                            result.globalPositioningFinalCost,
                            result.globalPositioningIterations,
                            result.globalPositioningStatus
                        ),
                        trackingPointsByFrame = pointsByFrame,
                        trackingVerificationByFrame = verificationByFrame,
                        trackingError = if (result.registeredCameraCount < 3) "Only ${result.registeredCameraCount} cameras registered. The solve may be incomplete; try footage with stronger parallax." else null
                    )
                }
                // The viewer initially opens on frame 0, which can be before the
                // selected initialization pair. Jump to the frame with the densest
                // verified observations so the user sees the solved feature overlay.
                val firstVisibleTrackFrame = pointsByFrame.maxByOrNull { it.value.size }?.key
                if (firstVisibleTrackFrame != null) seekTo(firstVisibleTrackFrame)
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(isAutoTracking = false) }
                throw cancelled
            } catch (failure: Throwable) {
                _uiState.update { it.copy(isAutoTracking = false, trackingError = failure.message ?: "Camera tracking failed.") }
            } finally {
                sequentialDecoder?.close()
                intrinsics?.release()
                trackingJob = null
            }
        }
    }

    fun cancelAutoTrack() { trackingJob?.cancel() }

    /**
     * Requests a modest set of evenly spaced preview thumbnails.
     * These are visual landmarks, not a frame-by-frame strip.
     */
    fun ensureThumbnails(count: Int) {
        if (frameUris.isEmpty() || count <= 0) return

        val targetCount = count.coerceIn(8, 48)
        if (targetCount == thumbnailCount) return

        thumbnailCount = targetCount
        thumbnailJob?.cancel()

        val generation = sequenceGeneration
        val uris = frameUris
        val indices = thumbnailIndices(uris.size, targetCount)

        thumbnailJob = viewModelScope.launch(Dispatchers.IO) {
            delay(250)

            for (index in indices) {
                if (generation != sequenceGeneration) return@launch
                if (_uiState.value.thumbnails.containsKey(index)) continue

                while (_uiState.value.playback.isPlaying) delay(250)

                val bitmap = runCatching {
                    decodeSemaphore.withPermit {
                        decodeSampled(
                            uri = uris[index],
                            maxDimension = THUMBNAIL_MAX_DIMENSION,
                            config = Bitmap.Config.RGB_565
                        )
                    }
                }.getOrNull() ?: continue

                if (generation != sequenceGeneration) return@launch

                _uiState.update {
                    it.copy(thumbnails = it.thumbnails + (index to bitmap))
                }
            }
        }
    }

    private fun showFrame(index: Int) {
        if (index !in 0 until _uiState.value.playback.frameCount) return

        wantedCenter = index
        requestJob?.cancel()

        val cached = bitmapCache.get(index)
        if (cached != null) {
            publish(index, cached)
            prefetchAround(index)
            return
        }

        _uiState.update {
            it.copy(
                playback = it.playback.copy(currentFrame = index),
                isLoadingFrame = true
            )
        }

        requestJob = viewModelScope.launch {
            try {
                val bitmap = awaitFrame(index)
                publish(index, bitmap)
                prefetchAround(index)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                reportError(index, t)
            }
        }
    }

    private suspend fun presentFrameForPlayback(index: Int) {
        if (index !in 0 until _uiState.value.playback.frameCount) return

        requestJob?.cancel()
        requestJob = null
        wantedCenter = index - 1

        try {
            val bitmap =
                bitmapCache.get(index) ?: run {
                    _uiState.update { it.copy(isLoadingFrame = true) }
                    awaitFrame(index)
                }

            publish(index, bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            reportError(index, t)
        }
    }

    private fun publish(index: Int, bitmap: Bitmap) {
        _uiState.update {
            it.copy(
                playback = it.playback.copy(currentFrame = index),
                currentBitmap = bitmap,
                isLoadingFrame = false,
                errorMessage = null
            )
        }
    }

    private fun reportError(index: Int, t: Throwable) {
        _uiState.update {
            it.copy(
                playback = it.playback.copy(currentFrame = index),
                isLoadingFrame = false,
                errorMessage = "Frame ${index + 1}: ${t.message ?: "unable to load frame."}"
            )
        }
    }

    private suspend fun awaitFrame(index: Int): Bitmap {
        repeat(3) {
            bitmapCache.get(index)?.let { return it }

            try {
                return launchDecode(index).await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }

        throw IllegalStateException("Unable to decode frame.")
    }

    private fun launchDecode(index: Int): Deferred<Bitmap> {
        inFlight[index]?.let { existing ->
            if (!existing.isCancelled) return existing
        }

        val uris = frameUris

        val deferred = viewModelScope.async(
            Dispatchers.IO,
            start = CoroutineStart.LAZY
        ) {
            val bitmap = decodeSemaphore.withPermit {
                if (!isWanted(index)) {
                    throw CancellationException("Frame $index no longer needed")
                }

                if (sourceVideoUri != null) {
                    requireNotNull(videoPreviewDecoder).frameAt(
                        index, _uiState.value.playback.fps, maxDecodeDimension
                    )
                } else {
                    decodeSampled(
                        uri = uris[index],
                        maxDimension = maxDecodeDimension,
                        config = Bitmap.Config.ARGB_8888
                    )
                }
            }

            if (frameBytes == 0) frameBytes = bitmap.byteCount
            bitmapCache.put(index, bitmap)
            bitmap
        }

        inFlight[index] = deferred
        deferred.invokeOnCompletion { inFlight.remove(index, deferred) }
        deferred.start()

        return deferred
    }

    private fun isWanted(index: Int): Boolean =
        index >= wantedCenter - BACK_WINDOW &&
            index <= wantedCenter + aheadCount() + 1

    private fun aheadCount(): Int {
        val bytes = frameBytes
        if (bytes <= 0) return DEFAULT_AHEAD

        val capacityFrames =
            (bitmapCache.maxSize().toLong() * 1024L / bytes).toInt()

        return (capacityFrames - 2).coerceIn(1, MAX_AHEAD)
    }

    private fun prefetchAhead(fromIndex: Int) {
        val ahead = aheadCount()
        for (offset in 1..ahead) {
            val index = fromIndex + offset
            if (index !in frameUris.indices) break
            warm(index)
        }
    }

    private fun prefetchAround(index: Int) {
        val radius = minOf(2, aheadCount())
        for (offset in 1..radius) {
            warm(index + offset)
            warm(index - offset)
        }
    }

    private fun warm(index: Int) {
        if (index !in 0 until _uiState.value.playback.frameCount) return
        if (bitmapCache.get(index) == null && inFlight[index] == null) launchDecode(index)
    }

    private fun decodeSampled(
        uri: Uri,
        maxDimension: Int,
        config: Bitmap.Config
    ): Bitmap {
        val resolver = appContext.contentResolver

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image." }
            BitmapFactory.decodeStream(input, null, bounds)
        }

        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Invalid image dimensions."
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maxDimension
            )
            inPreferredConfig = config
        }

        return resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image." }
            BitmapFactory.decodeStream(input, null, options)
                ?: error("Unable to decode image.")
        }
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int
    ): Int {
        var sampleSize = 1

        while (
            width / sampleSize > maxDimension ||
            height / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        return sampleSize
    }

    private fun thumbnailIndices(frameCount: Int, count: Int): List<Int> {
        if (frameCount <= 0) return emptyList()
        if (count <= 1 || frameCount == 1) return listOf(0)

        val actualCount = minOf(count, frameCount)

        return (0 until actualCount)
            .map { i ->
                (i.toLong() * (frameCount - 1) / (actualCount - 1)).toInt()
            }
            .distinct()
    }

    private fun cacheSizeKb(): Int {
        val budgetMb = (maxHeapMb / 6L).coerceIn(24L, 96L)
        return (budgetMb * 1024L).toInt()
    }

    override fun onCleared() {
        requestJob?.cancel()
        thumbnailJob?.cancel()
        playbackController.release()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        bitmapCache.evictAll()
        videoPreviewDecoder?.close()
        videoPreviewDecoder = null
        super.onCleared()
    }

    class Factory(
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ViewerViewModel(appContext) as T
    }
}
