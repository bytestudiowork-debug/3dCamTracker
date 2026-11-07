package com.trackstudio.app.data.io

import android.content.ContentResolver
import com.trackstudio.app.data.model.FootageInfo
import com.trackstudio.app.data.native.ProxyEngine
import com.trackstudio.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import java.io.File

sealed class ProxyProgress {
    data class Generating(val done: Int, val total: Int) : ProxyProgress()
    data class Done(val outputDir: File) : ProxyProgress()
    data class Failed(val reason: String) : ProxyProgress()
}

/** Optional playback optimization, isolated from source import and validation. */
class ProxyGenerator(
    private val contentResolver: ContentResolver,
    private val proxyEngine: ProxyEngine = ProxyEngine()
) {
    fun generate(footage: FootageInfo, scale: Float, jpegQuality: Int, outputDir: File): Flow<ProxyProgress> = flow {
        if (!outputDir.exists()) check(outputDir.mkdirs()) { "Could not create proxy directory." }
        var failures = 0
        footage.frames.forEachIndexed { index, frame ->
            emit(ProxyProgress.Generating(index, footage.frames.size))
            val bytes = try {
                contentResolver.openInputStream(frame.uri)?.use { it.readBytes() }
            } catch (error: Exception) {
                Logger.e("Proxy source read failed for ${frame.displayName}", error)
                null
            }
            if (bytes == null) {
                failures++
                return@forEachIndexed
            }
            val destination = File(outputDir, "proxy_%05d.jpg".format(frame.index))
            val generated = runCatching {
                proxyEngine.nativeGenerateProxy(bytes, destination.absolutePath, scale, jpegQuality)
            }.getOrDefault(false)
            if (!generated) {
                failures++
                Logger.e("Proxy generation failed for ${frame.displayName}")
            }
            yield()
        }
        if (failures > 0) {
            emit(ProxyProgress.Failed("$failures of ${footage.frames.size} proxies could not be generated. Original frames remain available."))
        } else {
            emit(ProxyProgress.Done(outputDir))
        }
    }.flowOn(Dispatchers.IO)
}
