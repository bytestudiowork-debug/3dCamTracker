package com.trackstudio.app.data.repository

import android.content.Context
import com.trackstudio.app.data.model.ColorSpace
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.model.ProxyResolution
import com.trackstudio.app.data.model.ProxySettings
import com.trackstudio.app.util.FileUtils
import org.json.JSONObject
import java.io.File

/**
 * Deliberately file-based (org.json, already in the Android SDK) rather
 * than Room/SQLite for Phase 1 - a project's metadata is a handful of
 * scalar fields plus a frame-URI list, which a single JSON file handles
 * without pulling in a database dependency this module doesn't need yet.
 * Swappable for Room later behind this same interface if project count or
 * query needs grow.
 */
class ProjectRepository(private val context: Context) {

    /** Note: only project-level metadata is persisted here; the actual frame
     * Uri list is intentionally NOT written to disk yet - SAF permissions
     * are per-launch unless taken as persistable, which the "recent
     * projects" flow will need to do explicitly in a later module. */
    fun saveProjectMetadata(settings: ProjectSettings): File {
        val dir = FileUtils.projectCacheDir(context, settings.id)
        val file = File(dir, "project.json")
        file.writeText(toJson(settings).toString(indent = 2))
        return file
    }

    fun listRecentProjectDirs(): List<File> {
        val root = File(context.getExternalFilesDir(null), "projects")
        if (!root.exists()) return emptyList()
        return root.listFiles { f -> f.isDirectory && File(f, "project.json").exists() }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun loadProjectMetadata(projectDir: File): ProjectSettings? {
        val file = File(projectDir, "project.json")
        if (!file.exists()) return null
        return try {
            fromJson(JSONObject(file.readText()))
        } catch (t: Throwable) {
            null
        }
    }

    private fun toJson(s: ProjectSettings): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("projectName", s.projectName)
        put("frameRate", s.frameRate)
        put("sensorWidthMm", s.sensorWidthMm)
        put("colorSpace", s.colorSpace.name)
        put("createdAtEpochMs", s.createdAtEpochMs)
        put("footageWidth", s.footage.width)
        put("footageHeight", s.footage.height)
        put("footageFrameCount", s.footage.frameCount)
        put("footagePattern", s.footage.detectedPattern)
        put("proxyEnabled", s.proxy.enabled)
        put("proxyResolution", s.proxy.resolution.name)
        put("proxyJpegQuality", s.proxy.jpegQuality)
    }

    private fun fromJson(json: JSONObject): ProjectSettings = ProjectSettings(
        id = json.getString("id"),
        projectName = json.optString("projectName", ""),
        frameRate = json.optDouble("frameRate", 24.0),
        sensorWidthMm = json.optDouble("sensorWidthMm", 36.0),
        colorSpace = runCatching { ColorSpace.valueOf(json.optString("colorSpace", "SRGB")) }
            .getOrDefault(ColorSpace.SRGB),
        proxy = ProxySettings(
            enabled = json.optBoolean("proxyEnabled", true),
            resolution = runCatching {
                ProxyResolution.valueOf(json.optString("proxyResolution", "HALF"))
            }.getOrDefault(ProxyResolution.HALF),
            jpegQuality = json.optInt("proxyJpegQuality", 85)
        )
        // Note: footage frame list is not reconstructed from disk yet (see
        // saveProjectMetadata comment) - only summary fields round-trip.
    )
}
