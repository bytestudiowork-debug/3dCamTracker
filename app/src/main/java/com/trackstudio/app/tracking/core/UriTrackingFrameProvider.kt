package com.trackstudio.app.tracking.core

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Decodes one sequence frame for analysis without retaining a sequence in memory.
 *
 * Each call returns a distinct software bitmap owned by the caller. The sequence
 * processor must release it after the adjacent frame pair has been processed.
 */
class UriTrackingFrameProvider(
    private val contentResolver: ContentResolver,
    frameUris: List<Uri>
) : TrackingFrameProvider {

    private val uris = frameUris.toList()

    override suspend fun loadFrame(
        index: Int,
        maximumDimension: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        require(index in uris.indices) { "Frame index is outside the sequence." }
        require(maximumDimension >= 16) { "Analysis dimension is too small." }
        coroutineContext.ensureActive()

        val uri = uris[index]
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open frame ${index + 1}." }
            BitmapFactory.decodeStream(input, null, bounds)
        }

        require(bounds.outWidth >= 16 && bounds.outHeight >= 16) {
            "Frame ${index + 1} has invalid dimensions."
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maximumDimension ||
            bounds.outHeight / sampleSize > maximumDimension
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        val decoded = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to reopen frame ${index + 1}." }
            BitmapFactory.decodeStream(input, null, options)
                ?: error("Unable to decode frame ${index + 1}.")
        }

        try {
            coroutineContext.ensureActive()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            decoded.recycle()
            throw cancelled
        }
        val softwareBitmap = if (
            decoded.config == Bitmap.Config.ARGB_8888 &&
            decoded.width <= maximumDimension &&
            decoded.height <= maximumDimension
        ) {
            decoded
        } else {
            val converted = decoded.copy(Bitmap.Config.ARGB_8888, false)
                ?: run {
                    decoded.recycle()
                    error("Unable to prepare frame ${index + 1} for tracking.")
                }
            decoded.recycle()
            converted
        }

        require(softwareBitmap.width <= maximumDimension &&
            softwareBitmap.height <= maximumDimension) {
            softwareBitmap.recycle()
            "Decoded frame exceeds the tracking resolution limit."
        }
        softwareBitmap
    }

    override fun releaseFrame(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
