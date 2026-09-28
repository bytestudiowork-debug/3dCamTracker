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
        private const val MAX_DECODE_DIMENSION = 1920
        private const val MAX_CONCURRENT_DECODES = 2
        private const val PREFETCH_DISTANCE = 2
    }

    private val _uiState =
        MutableStateFlow(ViewerUiState())

    val uiState: StateFlow<ViewerUiState> =
        _uiState.asStateFlow()

    private var frameUris: List<Uri> = emptyList()

    private val decodeSemaphore =
        Semaphore(MAX_CONCURRENT_DECODES)

    private val bitmapCache =
        object : LruCache<Int, Bitmap>(cacheSizeKb()) {
            override fun sizeOf(
                key: Int,
                value: Bitmap
            ): Int {
                return max(1, value.byteCount / 1024)
            }
        }

    /*
     * Frame decoding must not be cancelled every time playback
     * advances. On a real image sequence, decoding a frame can take
     * longer than one playback interval, especially when the source
     * is accessed through a SAF ContentResolver.
     *
     * We therefore keep a small set of decode jobs alive and only
     * display a decoded frame if it is still the requested frame.
     */
    private val decodeJobs =
        mutableMapOf<Int, Job>()

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
        cancelAllDecodeJobs()

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
        if (_uiState.value.playback.isPlaying) {
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
        if (frameIndex !in frameUris.indices) {
            return
        }

        /*
         * Always update the timeline immediately. The bitmap may
         * arrive a little later if this frame is not cached yet.
         */
        _uiState.value =
            _uiState.value.copy(
                playback =
                    _uiState.value.playback.copy(
                        currentFrame = frameIndex
                    ),
                isLoadingFrame =
                    bitmapCache.get(frameIndex) == null,
                errorMessage = null
            )

        bitmapCache.get(frameIndex)?.let { cached ->
            _uiState.value =
                _uiState.value.copy(
                    currentBitmap = cached,
                    isLoadingFrame = false
                )

            prefetchNearbyFrames(frameIndex)
            return
        }

        /*
         * Do not start duplicate decodes for the same frame.
         */
        if (decodeJobs[frameIndex]?.isActive == true) {
            prefetchNearbyFrames(frameIndex)
            return
        }

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                val result =
                    runCatching {
                        decodeFrame(frameIndex)
                    }

                withContext(Dispatchers.Main) {
                    decodeJobs.remove(frameIndex)

                    result
                        .onSuccess { bitmap ->
                            /*
                             * Cache the completed frame even if playback
                             * has already moved on. This is what allows
                             * subsequent playback frames to become smooth.
                             */
                            val existing =
                                bitmapCache.get(frameIndex)

                            if (existing == null) {
                                bitmapCache.put(
                                    frameIndex,
                                    bitmap
                                )
                            } else {
                                recycleIfNeeded(bitmap)
                            }

                            /*
                             * Never let an older asynchronous decode
                             * replace the frame currently requested by
                             * the user/playback controller.
                             */
                            if (
                                _uiState.value.playback.currentFrame ==
                                frameIndex
                            ) {
                                _uiState.value =
                                    _uiState.value.copy(
                                        currentBitmap =
                                            bitmapCache.get(frameIndex),
                                        isLoadingFrame = false,
                                        errorMessage = null
                                    )

                                prefetchNearbyFrames(frameIndex)
                            }
                        }
                        .onFailure { error ->
                            if (
                                _uiState.value.playback.currentFrame ==
                                frameIndex
                            ) {
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

        decodeJobs[frameIndex] = job

        prefetchNearbyFrames(frameIndex)
    }

    private suspend fun decodeFrame(
        frameIndex: Int
    ): Bitmap {
        decodeSemaphore.acquire()

        try {
            val uri = frameUris[frameIndex]
            val resolver = appContext.contentResolver

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
                    maxDimension = MAX_DECODE_DIMENSION
                )

            return resolver
                .openInputStream(uri)
                .use { input ->
                    requireNotNull(input) {
                        "Unable to open image."
                    }

                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig =
                                Bitmap.Config.ARGB_8888
                            inMutable = false
                        }

                    BitmapFactory.decodeStream(
                        input,
                        null,
                        options
                    ) ?: error("Unable to decode image.")
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
            width / sampleSize > maxDimension ||
            height / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        return sampleSize.coerceAtLeast(1)
    }

    private fun prefetchNearbyFrames(
        frameIndex: Int
    ) {
        for (offset in 1..PREFETCH_DISTANCE) {
            val forwardIndex = frameIndex + offset
            if (forwardIndex in frameUris.indices) {
                requestPrefetch(forwardIndex)
            }

            val backwardIndex = frameIndex - offset
            if (backwardIndex in frameUris.indices) {
                requestPrefetch(backwardIndex)
            }
        }
    }

    private fun requestPrefetch(
        frameIndex: Int
    ) {
        if (
            bitmapCache.get(frameIndex) != null ||
            decodeJobs[frameIndex]?.isActive == true
        ) {
            return
        }

        val job =
            viewModelScope.launch(Dispatchers.IO) {
                val result =
                    runCatching {
                        decodeFrame(frameIndex)
                    }

                withContext(Dispatchers.Main) {
                    decodeJobs.remove(frameIndex)

                    result.onSuccess { bitmap ->
                        val existing =
                            bitmapCache.get(frameIndex)

                        if (existing == null) {
                            bitmapCache.put(
                                frameIndex,
                                bitmap
                            )
                        } else {
                            recycleIfNeeded(bitmap)
                        }

                        if (
                            _uiState.value.playback.currentFrame ==
                            frameIndex
                        ) {
                            _uiState.value =
                                _uiState.value.copy(
                                    currentBitmap =
                                        bitmapCache.get(frameIndex),
                                    isLoadingFrame = false,
                                    errorMessage = null
                                )
                        }
                    }
                }
            }

        decodeJobs[frameIndex] = job
    }

    private fun cancelAllDecodeJobs() {
        decodeJobs.values.forEach { it.cancel() }
        decodeJobs.clear()
    }

    private fun recycleIfNeeded(
        bitmap: Bitmap
    ) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun cacheSizeKb(): Int {
        val memoryClassMb =
            Runtime.getRuntime().maxMemory() /
                (1024L * 1024L)

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
        cancelAllDecodeJobs()
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
            return ViewerViewModel(appContext) as T
        }
    }
}