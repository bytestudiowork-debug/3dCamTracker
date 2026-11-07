package com.trackstudio.app.data.io

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.trackstudio.app.data.model.FootageFrame
import com.trackstudio.app.data.model.FootageInfo
import com.trackstudio.app.data.native.ProxyEngine
import com.trackstudio.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import java.io.File

sealed class ImportProgress {
    data class Probing(
        val done: Int,
        val total: Int,
        val currentName: String
    ) : ImportProgress()

    data class Done(
        val footage: FootageInfo
    ) : ImportProgress()

    data class Failed(
        val reason: String
    ) : ImportProgress()
}

class ImageSequenceImporter(
    private val contentResolver: ContentResolver,
    private val proxyEngine: ProxyEngine = ProxyEngine()
) {

    private val supportedExtensions = setOf(
        "jpg",
        "jpeg",
        "png",
        "tif",
        "tiff",
        "webp",
        "bmp"
    )

    private data class FolderImage(
        val uri: Uri,
        val name: String,
        val size: Long
    )

    /**
     * Reads every supported image directly inside the selected folder.
     *
     * The folder itself is represented by a tree Uri. We enumerate its
     * children through DocumentsContract instead of asking the user to
     * manually select every frame.
     */
    fun probeFolder(folderUri: Uri): Flow<ImportProgress> = flow {

        val images = try {
            scanFolder(folderUri)
        } catch (t: Throwable) {
            Logger.e("Failed to scan frames folder", t)
            emit(
                ImportProgress.Failed(
                    "Could not read the selected folder."
                )
            )
            return@flow
        }

        if (images.isEmpty()) {
            emit(
                ImportProgress.Failed(
                    "No supported image frames were found in the selected folder."
                )
            )
            return@flow
        }

        val orderedImages = ImageSequenceValidator.sortNaturally(
            images.map { it.name }
        ).mapNotNull { name ->
            images.firstOrNull { it.name == name }
        }

        val frames = ArrayList<FootageFrame>(orderedImages.size)

        var widthSeen = -1
        var heightSeen = -1
        var consistent = true

        orderedImages.forEachIndexed { index, item ->

            emit(
                ImportProgress.Probing(
                    done = index,
                    total = orderedImages.size,
                    currentName = item.name
                )
            )

            val bytes = try {
                contentResolver.openInputStream(item.uri)?.use {
                    it.readBytes()
                }
            } catch (t: Throwable) {
                Logger.e(
                    "Failed reading ${item.name}",
                    t
                )
                null
            }

            if (bytes == null) {
                Logger.e(
                    "Skipping unreadable frame: ${item.name}"
                )
                return@forEachIndexed
            }

            val info = try {
                proxyEngine.nativeProbeImage(bytes)
            } catch (t: Throwable) {
                Logger.e(
                    "Image probe failed for ${item.name}",
                    t
                )
                intArrayOf()
            }

            if (info.size < 2) {
                Logger.e(
                    "Skipping non-decodable frame: ${item.name}"
                )
                return@forEachIndexed
            }

            val width = info[0]
            val height = info[1]

            if (widthSeen == -1) {
                widthSeen = width
                heightSeen = height
            } else if (
                width != widthSeen ||
                height != heightSeen
            ) {
                consistent = false
            }

            frames += FootageFrame(
                uri = item.uri,
                displayName = item.name,
                index = index,
                width = width,
                height = height,
                sizeBytes = item.size
            )

            yield()
        }

        if (frames.isEmpty()) {
            emit(
                ImportProgress.Failed(
                    "No frame in the selected folder could be decoded."
                )
            )
            return@flow
        }

        val pattern =
            ImageSequenceValidator.describeSequence(
                frames.map { it.displayName }
            ) ?: "${frames.size} stills (no common numbering pattern detected)"

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
     * Enumerates direct children of a SAF folder.
     *
     * Only metadata is read here. Actual image bytes are opened later,
     * one frame at a time, during probing/proxy generation.
     */
    private fun scanFolder(folderUri: Uri): List<FolderImage> {
        if (folderUri.scheme == "file") {
            val directory = File(folderUri.path ?: return emptyList())
            return directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.lowercase() in supportedExtensions }
                ?.map { FolderImage(Uri.fromFile(it), it.name, it.length()) }
                ?.toList()
                ?: emptyList()
        }

        val documentId =
            DocumentsContract.getTreeDocumentId(folderUri)

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                documentId
            )

        val results = ArrayList<FolderImage>()

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->

            val idIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )

            val nameIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            val sizeIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_SIZE
            )

            val mimeIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            while (cursor.moveToNext()) {

                val documentIdValue =
                    cursor.getString(idIndex)

                val name =
                    cursor.getString(nameIndex) ?: continue

                val mimeType =
                    if (mimeIndex >= 0) {
                        cursor.getString(mimeIndex)
                    } else {
                        null
                    }

                val size =
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        0L
                    }

                // Ignore subfolders. The user selected a frames folder;
                // we intentionally do not recursively scan unrelated
                // directories inside it.
                if (
                    mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    continue
                }

                val extension =
                    name.substringAfterLast('.', "")
                        .lowercase()

                if (extension !in supportedExtensions) {
                    continue
                }

                val fileUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        folderUri,
                        documentIdValue
                    )

                results += FolderImage(
                    uri = fileUri,
                    name = name,
                    size = size
                )
            }
        }

        return results
    }

}
