package com.trackstudio.app.data.model

import java.util.UUID

/**
 * Complete project configuration.
 *
 * The source folder URI is persisted so the project can be reopened
 * without copying the original image sequence into app storage.
 */
data class ProjectSettings(
    val id: String = UUID.randomUUID().toString(),
    val projectName: String = "",
    val sourceFolderUri: String = "",
    val sourceVideoUri: String = "",
    val videoRotationDegrees: Int = 0,
    val footage: FootageInfo = FootageInfo.EMPTY,
    val proxy: ProxySettings = ProxySettings(),
    val frameRate: Double = 24.0,
    val sensorWidthMm: Double = 36.0,
    val colorSpace: ColorSpace = ColorSpace.SRGB,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val isReadyToCreate: Boolean
        get() =
            projectName.isNotBlank() &&
                (sourceFolderUri.isNotBlank() || sourceVideoUri.isNotBlank()) &&
                footage.frameCount > 0
}