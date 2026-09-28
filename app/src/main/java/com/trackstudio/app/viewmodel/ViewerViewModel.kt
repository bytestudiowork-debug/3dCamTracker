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

data class ViewerUiState(
    val playback: PlaybackState = PlaybackState(),
    val currentBitmap: Bitmap? = null,
    val isLoadingFrame: Boolean = false,
    val errorMessage: String? = null,
    /** Small preview images keyed by frame index (used by the filmstrip). */
    val thumbnails: Map<Int, Bitmap> = emptyMap(),
    val playbackSpeed: Float = 1f
)

class ViewerViewModel(
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val THUMBNAIL_MAX_DIMENSION = 320

        /** Frames kept behind the playhead (for stepping backwards). */
        private const val BACK_WINDOW = 2

        private const val DEFAULT_AHEAD = 3
        private const val MAX_AHEAD = 8
    }

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var frameUris: List<Uri> = emptyList()

    // ---- Resource budgets, scaled to the device -----------------------

    private val maxHeapMb =
        Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    /** Longest edge a frame is decoded at. Smaller on low-memory devices. */
    private val maxDecodeDimension =
        if (maxHeapMb <= 192L) 1280 else 1920

    /** Decodes running at once. One on low-core devices. */
    private val decodeSemaphore =
        Semaphore(
            if (Runtime.getRuntime().availableProcessors() >= 4) 2 else 1
        )

    private val bitmapCache =
        object : LruCache<Int, Bitmap>(cacheSizeKb()) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                max(1, value.byteCount / 1024)
        }

    // ---- Decode bookkeeping -------------------------------------------

    /** Decodes that are queued or running, by frame index. */
    private val inFlight = ConcurrentHashMap<Int, Deferred<Bitmap>>()

    /** Frame the user is looking at / playback is at. */
    @Volatile
    private var wantedCenter = 0

    /** Byte size of one decoded frame, learned from the first decode. */
    @Volatile
    private var frameBytes = 0

    @Volatile
    private var sequenceGeneration = 0

    private var requestJob: Job? = null
    private var thumbnailJob: Job? = null
    private var thumbnailCount = 0

    private val frameSource =
        object : FrameSource {

            override fun requestFrame(index: Int) {
                showFrame(index)
            }

            override suspend fun presentFrame(index: Int) {
                presentFrameForPlayback(index)
            }

            override fun prefetchForward(fromIndex: Int) {
                prefetchAhead(fromIndex)
            }
        }

    private val playbackController =
        PlaybackController(
            scope = viewModelScope,
            source = frameSource,
            onPlayingChanged = { playing ->
                _uiState.update {
                    it.copy(
                        playback = it.playback.copy(isPlaying = playing)
                    )
                }
            }
        )

    // ---- Public API ---------------------------------------------------

    fun loadSequence(
        uris: List<Uri>,
        fps: Double
    ) {
        requestJob?.cancel()
        thumbnailJob?.cancel()
        playbackController.release()

        inFlight.values.forEach { it.cancel() }
        inFlight.clear()

        sequenceGeneration++

        frameUris = uris.toList()
        bitmapCache.evictAll()
        frameBytes = 0
        thumbnailCount = 0
        wantedCenter = 0

        val safeFps = fps.coerceIn(1.0, 240.0)
        val speed = _uiState.value.playbackSpeed

        playbackController.configure(
            frameCount = frameUris.size,
            fps = safeFps,
            currentFrame = 0
        )
        playbackController.speed = speed.toDouble()

        _uiState.value =
            ViewerUiState(
                playback = PlaybackState(
                    currentFrame = 0,
                    frameCount = frameUris.size,
                    fps = safeFps,
                    isPlaying = false
                ),
                playbackSpeed = speed
            )

        if (frameUris.isNotEmpty()) {
            showFrame(0)
        }
    }

    fun playPause() {
        if (_uiState.value.playback.isPlaying) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun pause() = playbackController.pause()

    fun nextFrame() = playbackController.nextFrame()

    fun previousFrame() = playbackController.previousFrame()

    fun firstFrame() = playbackController.firstFrame()

    fun lastFrame() = playbackController.lastFrame()

    fun seekTo(frame: Int) = playbackController.seekTo(frame)

    fun setPlaybackSpeed(speed: Float) {
        playbackController.speed = speed.toDouble()
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Makes sure [count] evenly spaced preview thumbnails exist for the
     * filmstrip. Cheap and low priority: decoded heavily downsampled, one
     * at a time, and paused while the sequence is playing.
     */
    fun ensureThumbnails(count: Int) {
        if (frameUris.isEmpty() || count <= 0) {
            return
        }

        if (count == thumbnailCount) {
            return
        }

        thumbnailCount = count
        thumbnailJob?.cancel()

        val generation = sequenceGeneration
        val uris = frameUris
        val indices = thumbnailIndices(uris.size, count)

        thumbnailJob =
            viewModelScope.launch(Dispatchers.IO) {

                // Let the first full frame win the CPU.
                delay(400)

                for (index in indices) {

                    if (generation != sequenceGeneration) {
                        return@launch
                    }

                    if (_uiState.value.thumbnails.containsKey(index)) {
                        continue
                    }

                    while (_uiState.value.playback.isPlaying) {
                        delay(250)
                    }

                    val bitmap =
                        runCatching {
                            decodeSemaphore.withPermit {
                                decodeSampled(
                                    uri = uris[index],
                                    maxDimension = THUMBNAIL_MAX_DIMENSION,
                                    config = Bitmap.Config.RGB_565
                                )
                            }
                        }.getOrNull() ?: continue

                    if (generation != sequenceGeneration) {
                        return@launch
                    }

                    _uiState.update {
                        it.copy(thumbnails = it.thumbnails + (index to bitmap))
                    }
                }
            }
    }

    // ---- Showing frames -----------------------------------------------

    /** Seek/step path: latest request wins, never blocks. */
    private fun showFrame(index: Int) {
        if (index !in frameUris.indices) {
            return
        }

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

        requestJob =
            viewModelScope.launch {
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

    /** Playback path: suspends until the frame is decoded and on screen. */
    private suspend fun presentFrameForPlayback(index: Int) {
        if (index !in frameUris.indices) {
            return
        }

        // A pending seek must not land after (and overwrite) this frame.
        requestJob?.cancel()
        requestJob = null

        // Keep the decode window centred just behind the frame we want.
        wantedCenter = index - 1

        try {
            val bitmap =
                bitmapCache.get(index)
                    ?: run {
                        _uiState.update { it.copy(isLoadingFrame = true) }
                        awaitFrame(index)
                    }

            publish(index, bitmap)

        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Skip an undecodable frame instead of stopping playback.
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
                errorMessage =
                    "Frame ${index + 1}: " +
                        (t.message ?: "unable to load frame.")
            )
        }
    }

    // ---- Decoding -----------------------------------------------------

    private suspend fun awaitFrame(index: Int): Bitmap {
        repeat(3) {
            bitmapCache.get(index)?.let { return it }

            try {
                return launchDecode(index).await()
            } catch (e: CancellationException) {
                // Rethrows if *we* were cancelled; otherwise the decode was
                // dropped as no-longer-needed, so ask for it again.
                currentCoroutineContext().ensureActive()
            }
        }

        throw IllegalStateException("Unable to decode frame.")
    }

    private fun launchDecode(index: Int): Deferred<Bitmap> {
        inFlight[index]?.let { existing ->
            if (!existing.isCancelled) {
                return existing
            }
        }

        val uris = frameUris

        val deferred =
            viewModelScope.async(
                Dispatchers.IO,
                start = CoroutineStart.LAZY
            ) {
                val bitmap =
                    decodeSemaphore.withPermit {

                        // The playhead may have moved on while this waited
                        // in the queue - skip work nobody needs any more.
                        if (!isWanted(index)) {
                            throw CancellationException(
                                "Frame $index no longer needed"
                            )
                        }

                        decodeSampled(
                            uri = uris[index],
                            maxDimension = maxDecodeDimension,
                            config = Bitmap.Config.ARGB_8888
                        )
                    }

                if (frameBytes == 0) {
                    frameBytes = bitmap.byteCount
                }

                bitmapCache.put(index, bitmap)
                bitmap
            }

        inFlight[index] = deferred

        deferred.invokeOnCompletion {
            inFlight.remove(index, deferred)
        }

        deferred.start()

        return deferred
    }

    private fun isWanted(index: Int): Boolean =
        index >= wantedCenter - BACK_WINDOW &&
            index <= wantedCenter + aheadCount() + 1

    /** How many frames ahead of the playhead are worth keeping decoded. */
    private fun aheadCount(): Int {
        val bytes = frameBytes

        if (bytes <= 0) {
            return DEFAULT_AHEAD
        }

        val capacityFrames =
            (bitmapCache.maxSize().toLong() * 1024L / bytes).toInt()

        // Leave room for the frame on screen plus one spare.
        return (capacityFrames - 2).coerceIn(1, MAX_AHEAD)
    }

    private fun prefetchAhead(fromIndex: Int) {
        val ahead = aheadCount()

        for (offset in 1..ahead) {
            val index = fromIndex + offset

            if (index !in frameUris.indices) {
                break
            }

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
        if (index !in frameUris.indices) {
            return
        }

        if (
            bitmapCache.get(index) == null &&
            inFlight[index] == null
        ) {
            launchDecode(index)
        }
    }

    private fun decodeSampled(
        uri: Uri,
        maxDimension: Int,
        config: Bitmap.Config
    ): Bitmap {

        val resolver = appContext.contentResolver

        // Pass 1: image size only.
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image." }
            BitmapFactory.decodeStream(input, null, bounds)
        }

        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Invalid image dimensions."
        }

        // Pass 2: decode only as large as needed.
        val options =
            BitmapFactory.Options().apply {
                inSampleSize =
                    calculateSampleSize(
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

    private fun thumbnailIndices(
        frameCount: Int,
        count: Int
    ): List<Int> {

        if (frameCount <= 0) {
            return emptyList()
        }

        if (count <= 1 || frameCount == 1) {
            return listOf(0)
        }

        return (0 until count)
            .map { i ->
                (i.toLong() * (frameCount - 1) / (count - 1)).toInt()
            }
            .distinct()
    }

    private fun cacheSizeKb(): Int {
        // About a sixth of the app heap, kept within sane bounds.
        val budgetMb =
            (maxHeapMb / 6L).coerceIn(24L, 96L)

        return (budgetMb * 1024L).toInt()
    }

    override fun onCleared() {
        requestJob?.cancel()
        thumbnailJob?.cancel()
        playbackController.release()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        bitmapCache.evictAll()
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
