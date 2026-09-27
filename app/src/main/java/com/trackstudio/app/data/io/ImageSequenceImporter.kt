package com.trackstudio.app.data.io

import android.content.ContentResolver
import android.net.Uri
import com.trackstudio.app.data.model.FootageFrame
import com.trackstudio.app.data.model.FootageInfo
import com.trackstudio.app.data.native.ProxyEngine
import com.trackstudio.app.util.FileUtils
import com.trackstudio.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import java.io.File

sealed class ImportProgress {
    data class Probing(val done: Int, val total: Int, val currentName: String) : ImportProgress()
    data class Done(val footage: FootageInfo) : ImportProgress()
    data class Failed(val reason: String) : ImportProgress()
}

sealed class ProxyProgress {
    data class Generating(val done: Int, val total: Int) : ProxyProgress()
    data class Done(val outputDir: File) : ProxyProgress()
    data class Failed(val reason: String) : ProxyProgress()
}

/**
 * All logic for turning a user's raw file selection into a validated
 * [FootageInfo], and later into proxy frames. Contains no Compose/UI code -
 * the ViewModel is the only thing that talks to this class, so the UI can
 * be re-skinned freely without touching import behavior.
 */
class ImageSequenceImporter(
    private val contentResolver: ContentResolver,
    private val proxyEngine: ProxyEngine = ProxyEngine()
) {

    private val supportedExtensions = setOf(
        "jpg", "jpeg", "png", "tif", "tiff", "webp", "bmp"
    )

    /**
     * Probes every selected frame: reads its bytes (needed regardless, since
     * SAF gives us no seekable random file access) and asks the native layer
     * for width/height via a header-only decode. Emits progress so the UI
     * can show something meaningful on a large sequence instead of a frozen
     * spinner - this is the one part of import that scales with frame
     * count, everything else is O(1) metadata.
     */
    fun probeSequence(uris: List<Uri>): Flow<ImportProgress> = flow {
        if (uris.isEmpty()) {
            emit(ImportProgress.Failed("No files selected."))
            return@flow
        }

        data class Named(val uri: Uri, val name: String, val size: Long)

        val named = uris.map { uri ->
            val (name, size) = FileUtils.queryNameAndSize(contentResolver, uri)
            Named(uri, name, size)
        }

        val rejectedExt = named.filter { it.name.substringAfterLast('.', "").lowercase() !in supportedExtensions }
        if (rejectedExt.size == named.size) {
            emit(ImportProgress.Failed("None of the selected files are a supported image type."))
            return@flow
        }

        val candidates = named - rejectedExt.toSet()
        val orderedNames = ImageSequenceValidator.sortNaturally(candidates.map { it.name })
        val byName = candidates.associateBy { it.name }
        val orderedCandidates = orderedNames.mapNotNull { byName[it] }

        val frames = ArrayList<FootageFrame>(orderedCandidates.size)
        var widthSeen = -1
        var heightSeen = -1
        var consistent = true

        orderedCandidates.forEachIndexed { index, item ->
            emit(ImportProgress.Probing(index, orderedCandidates.size, item.name))

            val bytes = try {
                contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
            } catch (t: Throwable) {
                Logger.e("Failed reading ${item.name}", t)
                null
            }

            if (bytes == null) {
                Logger.e("Skipping unreadable frame: ${item.name}")
                return@forEachIndexed
            }

            val info = proxyEngine.nativeProbeImage(bytes)
            if (info.size < 2) {
                Logger.e("Skipping non-decodable frame: ${item.name}")
                return@forEachIndexed
            }

            val (w, h) = info[0] to info[1]
            if (widthSeen == -1) {
                widthSeen = w; heightSeen = h
            } else if (w != widthSeen || h != heightSeen) {
                consistent = false
            }

            frames += FootageFrame(
                uri = item.uri,
                displayName = item.name,
                index = index,
                width = w,
                height = h,
                sizeBytes = item.size
            )

            // Yields the probing loop back to the dispatcher between frames
            // so a huge sequence can't starve other coroutines sharing the
            // IO dispatcher's thread pool.
            yield()
        }

        if (frames.isEmpty()) {
            emit(ImportProgress.Failed("No frame in the selection could be decoded."))
            return@flow
        }

        val pattern = ImageSequenceValidator.describeSequence(frames.map { it.displayName })
            ?: "${frames.size} stills (no common numbering pattern detected)"

        emit(
            ImportProgress.Done(
                FootageInfo(
                    frames = frames,
                    detectedPattern = pattern,
                    width = widthSeen,
                    height = heightSeen,
                    frameCount = frames.size,
                    totalSizeBytes = frames.sumOf { it.sizeBytes },
                    isResolutionConsistent = consistent
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Generates one proxy JPEG per frame into [outputDir]. Re-reads each
     * source frame's bytes rather than caching them from the probe pass -
     * trading one extra I/O pass for not holding potentially gigabytes of
     * decoded/raw frame data in memory, which is the constraint that
     * actually matters on a low-end device.
     */
    fun generateProxies(
        footage: FootageInfo,
        scale: Float,
        jpegQuality: Int,
        outputDir: File
    ): Flow<ProxyProgress> = flow {
        if (!outputDir.exists()) outputDir.mkdirs()

        footage.frames.forEachIndexed { index, frame ->
            emit(ProxyProgress.Generating(index, footage.frames.size))

            val bytes = try {
                contentResolver.openInputStream(frame.uri)?.use { it.readBytes() }
            } catch (t: Throwable) {
                Logger.e("Proxy source read failed for ${frame.displayName}", t)
                null
            }

            if (bytes == null) return@forEachIndexed

            val dstFile = File(outputDir, "proxy_%05d.jpg".format(frame.index))
            val ok = proxyEngine.nativeGenerateProxy(bytes, dstFile.absolutePath, scale, jpegQuality)
            if (!ok) {
                Logger.e("Proxy generation failed for ${frame.displayName}")
            }

            yield()
        }

        emit(ProxyProgress.Done(outputDir))
    }.flowOn(Dispatchers.IO)
}
