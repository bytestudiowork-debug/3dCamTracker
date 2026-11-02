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
    val thumbnails: Map<Int, Bitmap> = emptyMap()
)

class ViewerViewModel(
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val THUMBNAIL_MAX_DIMENSION = 320
        private const val BACK_WINDOW = 2
        private const val DEFAULT_AHEAD = 3
        private const val MAX_AHEAD = 8
    }

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var frameUris: List<Uri> = emptyList()

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

    fun loadSequence(uris: List<Uri>, fps: Double) {
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

        playbackController.configure(
            frameCount = frameUris.size,
            fps = safeFps,
            currentFrame = 0,
            speed = 1.0
        )

        _uiState.value = ViewerUiState(
            playback = PlaybackState(
                currentFrame = 0,
                frameCount = frameUris.size,
                fps = safeFps,
                speed = 1.0,
                isPlaying = false
            )
        )

        if (frameUris.isNotEmpty()) showFrame(0)
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
        _uiState.update { it.copy(errorMessage = null) }
    }

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
        if (index !in frameUris.indices) return

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
        if (index !in frameUris.indices) return

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
            throw IllegalStateException(
                "Playback stopped because frame ${index + 1} could not be decoded.",
                t
            )
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

                decodeSampled(
                    uri = uris[index],
                    maxDimension = maxDecodeDimension,
                    config = Bitmap.Config.ARGB_8888
                )
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
        if (index !in frameUris.indices) return
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
