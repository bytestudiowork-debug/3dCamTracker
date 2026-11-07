package com.trackstudio.app.data.repository

import android.content.Context
import com.trackstudio.app.data.model.ColorSpace
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.model.ProxyResolution
import com.trackstudio.app.data.model.ProxySettings
import com.trackstudio.app.util.FileUtils
import org.json.JSONObject
import java.io.File

class ProjectRepository(
    private val context: Context
) {

    fun saveProjectMetadata(
        settings: ProjectSettings
    ): File {

        val dir =
            FileUtils.projectCacheDir(
                context,
                settings.id
            )

        val file =
            File(
                dir,
                "project.json"
            )

        file.writeText(
            toJson(settings).toString(2)
        )

        return file
    }

    fun listRecentProjectDirs(): List<File> {

        val root =
            File(
                context.getExternalFilesDir(null),
                "projects"
            )

        if (!root.exists()) {
            return emptyList()
        }

        return root
            .listFiles { file ->
                file.isDirectory &&
                    File(
                        file,
                        "project.json"
                    ).exists()
            }
            ?.sortedByDescending {
                it.lastModified()
            }
            ?: emptyList()
    }

    fun loadProjectMetadata(
        projectDir: File
    ): ProjectSettings? {

        val file =
            File(
                projectDir,
                "project.json"
            )

        if (!file.exists()) {
            return null
        }

        return try {

            fromJson(
                JSONObject(
                    file.readText()
                )
            )

        } catch (_: Throwable) {

            null
        }
    }

    fun loadProject(
        projectId: String
    ): ProjectSettings? {

        val root =
            File(
                context.getExternalFilesDir(null),
                "projects"
            )

        val projectDir =
            File(
                root,
                projectId
            )

        return loadProjectMetadata(
            projectDir
        )
    }

    fun renameProject(
        projectId: String,
        newName: String
    ): Boolean {

        val cleanName =
            newName.trim()

        if (cleanName.isEmpty()) {
            return false
        }

        val root =
            File(
                context.getExternalFilesDir(null),
                "projects"
            )

        val projectDir =
            File(
                root,
                projectId
            )

        val metadataFile =
            File(
                projectDir,
                "project.json"
            )

        if (!metadataFile.exists()) {
            return false
        }

        return try {

            val json =
                JSONObject(
                    metadataFile.readText()
                )

            json.put(
                "projectName",
                cleanName
            )

            metadataFile.writeText(
                json.toString(2)
            )

            projectDir.setLastModified(
                System.currentTimeMillis()
            )

            true

        } catch (_: Throwable) {

            false
        }
    }

    fun deleteProject(
        projectId: String
    ): Boolean {

        val root =
            File(
                context.getExternalFilesDir(null),
                "projects"
            )

        val projectDir =
            File(
                root,
                projectId
            )

        if (!projectDir.exists()) {
            return false
        }

        return try {
            projectDir.deleteRecursively()
        } catch (_: Throwable) {
            false
        }
    }

    private fun toJson(
        settings: ProjectSettings
    ): JSONObject {

        return JSONObject().apply {

            put(
                "id",
                settings.id
            )

            put(
                "projectName",
                settings.projectName
            )

            put(
                "sourceFolderUri",
                settings.sourceFolderUri
            )
            put("sourceVideoUri", settings.sourceVideoUri)
            put("videoRotationDegrees", settings.videoRotationDegrees)

            put(
                "frameRate",
                settings.frameRate
            )

            put(
                "sensorWidthMm",
                settings.sensorWidthMm
            )

            put(
                "colorSpace",
                settings.colorSpace.name
            )

            put(
                "createdAtEpochMs",
                settings.createdAtEpochMs
            )

            put(
                "footageWidth",
                settings.footage.width
            )

            put(
                "footageHeight",
                settings.footage.height
            )

            put(
                "footageFrameCount",
                settings.footage.frameCount
            )

            put(
                "footagePattern",
                settings.footage.detectedPattern
            )

            put(
                "footageTotalSizeBytes",
                settings.footage.totalSizeBytes
            )

            put(
                "footageResolutionConsistent",
                settings.footage.isResolutionConsistent
            )

            put(
                "proxyEnabled",
                settings.proxy.enabled
            )

            put(
                "proxyResolution",
                settings.proxy.resolution.name
            )

            put(
                "proxyJpegQuality",
                settings.proxy.jpegQuality
            )
        }
    }

    private fun fromJson(
        json: JSONObject
    ): ProjectSettings {

        return ProjectSettings(

            id =
                json.getString(
                    "id"
                ),

            projectName =
                json.optString(
                    "projectName",
                    ""
                ),

            sourceFolderUri =
                json.optString(
                    "sourceFolderUri",
                    ""
                ),

            sourceVideoUri = json.optString("sourceVideoUri", ""),
            videoRotationDegrees = json.optInt("videoRotationDegrees", 0),
            frameRate =
                json.optDouble(
                    "frameRate",
                    24.0
                ),

            sensorWidthMm =
                json.optDouble(
                    "sensorWidthMm",
                    36.0
                ),

            colorSpace =
                runCatching {

                    ColorSpace.valueOf(
                        json.optString(
                            "colorSpace",
                            "SRGB"
                        )
                    )

                }.getOrDefault(
                    ColorSpace.SRGB
                ),

            proxy =
                ProxySettings(

                    enabled =
                        json.optBoolean(
                            "proxyEnabled",
                            true
                        ),

                    resolution =
                        runCatching {

                            ProxyResolution.valueOf(
                                json.optString(
                                    "proxyResolution",
                                    "HALF"
                                )
                            )

                        }.getOrDefault(
                            ProxyResolution.HALF
                        ),

                    jpegQuality =
                        json.optInt(
                            "proxyJpegQuality",
                            85
                        )
                ),

            footage =
                createStoredFootageInfo(
                    json
                )
        )
    }

    /**
     * The complete frame list is intentionally not stored in JSON.
     *
     * It can contain thousands of SAF Uris.
     *
     * The source folder is rescanned when the project is reopened.
     */
    private fun createStoredFootageInfo(
        json: JSONObject
    ) =
        com.trackstudio.app.data.model.FootageInfo(

            frames = emptyList(),

            detectedPattern =
                json.optString(
                    "footagePattern",
                    ""
                ),

            width =
                json.optInt(
                    "footageWidth",
                    0
                ),

            height =
                json.optInt(
                    "footageHeight",
                    0
                ),

            frameCount =
                json.optInt(
                    "footageFrameCount",
                    0
                ),

            totalSizeBytes =
                json.optLong(
                    "footageTotalSizeBytes",
                    0L
                ),

            isResolutionConsistent =
                json.optBoolean(
                    "footageResolutionConsistent",
                    true
                )
        )
}