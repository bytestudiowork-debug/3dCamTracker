package com.trackstudio.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trackstudio.app.playback.PlaybackController
import com.trackstudio.app.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

data class ViewerUiState(
    val playback: PlaybackState = PlaybackState(),
    val currentBitmap: Bitmap? = null,
    val isLoadingFrame: Boolean = false,
    val errorMessage: String? = null
)

class ViewerViewModel(
    private val appContext: Context
) : ViewModel() {

    companion object {

        /*
         * Viewer images are decoded only as large as necessary
         * for normal viewing.
         *
         * The original footage remains untouched.
         */
        private const val MAX_DECODE_DIMENSION = 1920

        /*
         * Keep native image decoding concurrency low.
         * This avoids creating many large temporary bitmaps.
         */
        private const val MAX_CONCURRENT_DECODES = 2

        /*
         * Frames immediately around the current frame are
         * prepared in advance.
         */
        private const val PREFETCH_DISTANCE = 2
    }

    private val _uiState =
        MutableStateFlow(ViewerUiState())

    val uiState: StateFlow<ViewerUiState> =
        _uiState.asStateFlow()

    private var frameUris: List<Uri> = emptyList()

    private val decodeSemaphore =
        Semaphore(MAX_CONCURRENT_DECODES)

    /*
     * Memory-bounded bitmap cache.
     */
    private val bitmapCache =
        object : LruCache<Int, Bitmap>(cacheSizeKb()) {

            override fun sizeOf(
                key: Int,
                value: Bitmap
            ): Int {
                return max(
                    1,
                    value.byteCount / 1024
                )
            }
        }

    /*
     * Every new frame request increments this number.
     *
     * An old asynchronous decode result cannot replace
     * a newer requested frame.
     */
    private val loadGeneration =
        AtomicLong(0L)

    private var currentLoadJob: Job? = null

    private val playbackController =
        PlaybackController(
            scope = viewModelScope,

            onFrameChanged = { frame ->
                loadFrame(frame)
            },

            onPlayingChanged = { playing ->
                _uiState.value =
                    _uiState.value.copy(
                        playback =
                            _uiState.value.playback.copy(
                                isPlaying = playing
                            )
                    )
            }
        )

    fun loadSequence(
        uris: List<Uri>,
        fps: Double
    ) {
        currentLoadJob?.cancel()

        loadGeneration.incrementAndGet()

        frameUris = uris.toList()

        bitmapCache.evictAll()

        playbackController.configure(
            frameCount = frameUris.size,
            fps = fps,
            currentFrame = 0
        )

        if (frameUris.isEmpty()) {

            _uiState.value =
                ViewerUiState(
                    playback =
                        PlaybackState(
                            fps = fps
                        )
                )

            return
        }

        loadFrame(0)
    }

    fun playPause() {

        if (
            _uiState.value.playback.isPlaying
        ) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    fun nextFrame() {
        playbackController.nextFrame()
    }

    fun previousFrame() {
        playbackController.previousFrame()
    }

    fun firstFrame() {
        playbackController.firstFrame()
    }

    fun lastFrame() {
        playbackController.lastFrame()
    }

    fun seekTo(frame: Int) {
        playbackController.seekTo(frame)
    }

    fun clearError() {

        _uiState.value =
            _uiState.value.copy(
                errorMessage = null
            )
    }

    private fun loadFrame(
        frameIndex: Int
    ) {
        if (
            frameIndex !in frameUris.indices
        ) {
            return
        }

        val generation =
            loadGeneration.incrementAndGet()

        val cached =
            bitmapCache.get(frameIndex)

        if (cached != null) {

            _uiState.value =
                _uiState.value.copy(
                    playback =
                        _uiState.value.playback.copy(
                            currentFrame = frameIndex
                        ),
                    currentBitmap = cached,
                    isLoadingFrame = false,
                    errorMessage = null
                )

            prefetchNearbyFrames(
                frameIndex = frameIndex,
                generation = generation
            )

            return
        }

        _uiState.value =
            _uiState.value.copy(
                playback =
                    _uiState.value.playback.copy(
                        currentFrame = frameIndex
                    ),
                isLoadingFrame = true,
                errorMessage = null
            )

        currentLoadJob?.cancel()

        currentLoadJob =
            viewModelScope.launch(Dispatchers.IO) {

                val result =
                    runCatching {
                        decodeFrame(frameIndex)
                    }

                withContext(Dispatchers.Main) {

                    /*
                     * This decode belongs to an older request.
                     */
                    if (
                        generation !=
                        loadGeneration.get()
                    ) {
                        result.getOrNull()?.let {
                            recycleIfNeeded(it)
                        }

                        return@withContext
                    }

                    result
                        .onSuccess { bitmap ->

                            bitmapCache.put(
                                frameIndex,
                                bitmap
                            )

                            _uiState.value =
                                _uiState.value.copy(
                                    currentBitmap = bitmap,
                                    isLoadingFrame = false,
                                    errorMessage = null
                                )

                            prefetchNearbyFrames(
                                frameIndex = frameIndex,
                                generation = generation
                            )
                        }
                        .onFailure { error ->

                            _uiState.value =
                                _uiState.value.copy(
                                    isLoadingFrame = false,
                                    errorMessage =
                                        error.message
                                            ?: "Unable to load frame."
                                )
                        }
                }
            }
    }

    private suspend fun decodeFrame(
        frameIndex: Int
    ): Bitmap {

        decodeSemaphore.acquire()

        try {

            val uri =
                frameUris[frameIndex]

            val resolver =
                appContext.contentResolver

            /*
             * Pass 1:
             * read only image dimensions.
             */
            val bounds =
                resolver
                    .openInputStream(uri)
                    .use { input ->

                        requireNotNull(input) {
                            "Unable to open image."
                        }

                        val options =
                            BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }

                        BitmapFactory.decodeStream(
                            input,
                            null,
                            options
                        )

                        options
                    }

            require(
                bounds.outWidth > 0 &&
                    bounds.outHeight > 0
            ) {
                "Invalid image dimensions."
            }

            val sampleSize =
                calculateSampleSize(
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    maxDimension =
                        MAX_DECODE_DIMENSION
                )

            /*
             * Pass 2:
             * decode only the required resolution.
             */
            return resolver
                .openInputStream(uri)
                .use { input ->

                    requireNotNull(input) {
                        "Unable to open image."
                    }

                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize =
                                sampleSize

                            inPreferredConfig =
                                Bitmap.Config.ARGB_8888

                            inMutable = false
                        }

                    BitmapFactory.decodeStream(
                        input,
                        null,
                        options
                    ) ?: error(
                        "Unable to decode image."
                    )
                }

        } finally {
            decodeSemaphore.release()
        }
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int
    ): Int {

        var sampleSize = 1

        while (
            width / sampleSize >
            maxDimension ||
            height / sampleSize >
            maxDimension
        ) {
            sampleSize *= 2
        }

        return sampleSize.coerceAtLeast(1)
    }

    private fun prefetchNearbyFrames(
        frameIndex: Int,
        generation: Long
    ) {
        if (
            generation !=
            loadGeneration.get()
        ) {
            return
        }

        /*
         * Forward frames.
         */
        for (
            offset in 1..PREFETCH_DISTANCE
        ) {

            val index =
                frameIndex + offset

            if (
                index !in frameUris.indices
            ) {
                break
            }

            if (
                bitmapCache.get(index) == null
            ) {
                launchPrefetch(index)
            }
        }

        /*
         * Backward frames.
         */
        for (
            offset in 1..PREFETCH_DISTANCE
        ) {

            val index =
                frameIndex - offset

            if (
                index !in frameUris.indices
            ) {
                break
            }

            if (
                bitmapCache.get(index) == null
            ) {
                launchPrefetch(index)
            }
        }
    }

    private fun launchPrefetch(
        frameIndex: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {

            if (
                bitmapCache.get(frameIndex) != null
            ) {
                return@launch
            }

            runCatching {
                decodeFrame(frameIndex)
            }.onSuccess { bitmap ->

                if (
                    bitmapCache.get(frameIndex) == null
                ) {
                    bitmapCache.put(
                        frameIndex,
                        bitmap
                    )
                } else {
                    recycleIfNeeded(bitmap)
                }
            }
        }
    }

    private fun recycleIfNeeded(
        bitmap: Bitmap
    ) {
        /*
         * LruCache owns cached bitmaps.
         *
         * A bitmap that wasn't inserted can be safely
         * recycled when it is no longer needed.
         */
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun cacheSizeKb(): Int {

        val memoryClassMb =
            Runtime.getRuntime()
                .maxMemory() /
                (1024L * 1024L)

        /*
         * Conservative cache sizes for Android devices.
         */
        val targetMb =
            when {
                memoryClassMb <= 128 -> 16
                memoryClassMb <= 256 -> 24
                memoryClassMb <= 512 -> 32
                else -> 48
            }

        return (
            targetMb * 1024L
        )
            .coerceAtMost(
                Int.MAX_VALUE.toLong()
            )
            .toInt()
    }

    override fun onCleared() {

        currentLoadJob?.cancel()

        playbackController.release()

        bitmapCache.evictAll()

        super.onCleared()
    }

    class Factory(
        private val appContext: Context
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {
            return ViewerViewModel(
                appContext
            ) as T
        }
    }
}