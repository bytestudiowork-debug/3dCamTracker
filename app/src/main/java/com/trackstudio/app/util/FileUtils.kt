package com.trackstudio.app.util

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileUtils {

    /** Display name + size straight from the SAF content resolver, no I/O guessing. */
    fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else uri.lastPathSegment.orEmpty()
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                return name.orEmpty() to size
            }
        }
        return (uri.lastPathSegment ?: "unknown") to 0L
    }

    /** Per-project cache root under the app's own external files dir - never shared storage. */
    fun projectCacheDir(context: Context, projectId: String): File {
        val dir = File(context.getExternalFilesDir(null), "projects/$projectId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun proxyDir(context: Context, projectId: String, subdir: String): File {
        val dir = File(projectCacheDir(context, projectId), subdir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun humanReadableBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format("%.1f %s", value, units[unitIndex])
    }
}
