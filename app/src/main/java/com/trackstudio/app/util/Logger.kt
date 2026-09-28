package com.trackstudio.app.util

import android.util.Log
import com.trackstudio.app.BuildConfig

/** One-line indirection so logging can be stripped/redirected later without touching call sites. */
object Logger {
    private const val TAG = "TrackStudio"

    fun d(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
    }

    fun e(msg: String, throwable: Throwable? = null) {
        Log.e(TAG, msg, throwable)
    }
}
