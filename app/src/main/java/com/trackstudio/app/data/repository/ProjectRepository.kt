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
 * File-based project metadata storage for Phase 1.
 *
 * Each project gets its own directory containing project.json.
 * This keeps the implementation lightweight and avoids adding a database
 * dependency before the project-management requirements need one.
 */
class ProjectRepository(private val context: Context) {

    fun saveProjectMetadata(settings: ProjectSettings): File {
        val dir = FileUtils.projectCacheDir(context, settings.id)
        val file = File(dir, "project.json")
        file.writeText(toJson(settings).toString(2))
        return file
    }

    fun listRecentProjectDirs(): List<File> {
        val root = File(context.getExternalFilesDir(null), "projects")

        if (!root.exists()) {
            return emptyList()
        }

        return root
            .listFiles { file ->
                file.isDirectory && File(file, "project.json").exists()
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun loadProjectMetadata(projectDir: File): ProjectSettings? {
        val file = File(projectDir, "project.json")

        if (!file.exists()) {
            return null
        }

        return try {
            fromJson(JSONObject(file.readText()))
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Rename a project and update its metadata file.
     */
    fun renameProject(
        projectId: String,
        newName: String
    ): Boolean {
        val cleanName = newName.trim()

        if (cleanName.isEmpty()) {
            return false
        }

        val root = File(context.getExternalFilesDir(null), "projects")
        val projectDir = File(root, projectId)
        val metadataFile = File(projectDir, "project.json")

        if (!metadataFile.exists()) {
            return false
        }

        return try {
            val json = JSONObject(metadataFile.readText())
            json.put("projectName", cleanName)
            metadataFile.writeText(json.toString(2))

            // Update modification time so renamed projects remain visible
            // in the recent-project list.
            projectDir.setLastModified(System.currentTimeMillis())

            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Delete the complete project directory.
     *
     * The project directory is private app storage, so recursive deletion
     * also removes generated proxy/cache files belonging to the project.
     */
    fun deleteProject(projectId: String): Boolean {
        val root = File(context.getExternalFilesDir(null), "projects")
        val projectDir = File(root, projectId)

        if (!projectDir.exists()) {
            return false
        }

        return try {
            projectDir.deleteRecursively()
        } catch (t: Throwable) {
            false
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
        colorSpace = runCatching {
            ColorSpace.valueOf(
                json.optString("colorSpace", "SRGB")
            )
        }.getOrDefault(ColorSpace.SRGB),
        proxy = ProxySettings(
            enabled = json.optBoolean("proxyEnabled", true),
            resolution = runCatching {
                ProxyResolution.valueOf(
                    json.optString("proxyResolution", "HALF")
                )
            }.getOrDefault(ProxyResolution.HALF),
            jpegQuality = json.optInt("proxyJpegQuality", 85)
        )
    )
}
```
