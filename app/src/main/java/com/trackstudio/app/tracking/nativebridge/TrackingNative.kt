package com.trackstudio.app.tracking.nativebridge

import android.graphics.Bitmap
import com.trackstudio.app.tracking.model.ImagePoint

internal object TrackingNative {
    init {
        System.loadLibrary("trackstudio_native")
    }

    external fun nativeDetectFeatures(
        bitmap: Bitmap,
        maxFeatures: Int,
        quality: Float,
        minimumDistance: Float
    ): FloatArray

    external fun nativeTrackPoints(
        previous: Bitmap,
        current: Bitmap,
        points: FloatArray,
        windowSize: Int,
        pyramidLevels: Int
    ): FloatArray

    fun detectFeatures(
        bitmap: Bitmap,
        maxFeatures: Int,
        quality: Float,
        minimumDistance: Float
    ): List<ImagePoint> {
        val values = nativeDetectFeatures(bitmap, maxFeatures, quality, minimumDistance)
        return values.asList().chunked(2).mapNotNull { pair ->
            if (pair.size == 2) ImagePoint(pair[0], pair[1]) else null
        }
    }
}
