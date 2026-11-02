package com.trackstudio.app.project

import android.content.Context
import android.net.Uri
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.util.FileUtils
import java.io.File

/**
 * Chooses which images the viewer plays back.
 *
 * Proxy frames exist precisely so playback stays smooth on modest
 * hardware: they are small JPEGs in app storage, far cheaper to decode
 * than the original footage read through the document provider. If the
 * project has proxies enabled and the full set is on disk they are used;
 * otherwise (proxies off, or generation incomplete) playback falls back to
 * the original frames.
 */
object ViewerFrames {

    fun resolve(
        context: Context,
        project: ProjectSettings
    ): List<Uri> {

        val frames = project.footage.frames
        val originals = frames.map { it.uri }

        if (!project.proxy.enabled || frames.isEmpty()) {
            return originals
        }

        val dir =
            File(
                FileUtils.projectCacheDir(context, project.id),
                project.proxy.cacheSubdir
            )

        if (!dir.isDirectory) return originals

        fun proxyFile(index: Int) =
            File(dir, "proxy_%05d.jpg".format(index))

        // Resolve each frame independently: interrupted proxy generation must
        // not make playback fail because one middle frame is missing.
        return frames.map { frame ->
            val proxy = proxyFile(frame.index)
            if (proxy.isFile && proxy.length() > 0L) {
                Uri.fromFile(proxy)
            } else {
                frame.uri
            }
        }
    }
}
