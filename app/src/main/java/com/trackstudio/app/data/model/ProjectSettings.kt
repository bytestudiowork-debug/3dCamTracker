package com.trackstudio.app.data.model

import java.util.UUID

/**
 * The full result of Module 1 (New Project / Footage Import). This is the
 * hand-off point to whatever module comes next (proxy pipeline is already
 * satisfied by the time this exists; a future tracking module just reads
 * this object).
 */
data class ProjectSettings(
    val id: String = UUID.randomUUID().toString(),
    val projectName: String = "",
    val footage: FootageInfo = FootageInfo.EMPTY,
    val proxy: ProxySettings = ProxySettings(),
    val frameRate: Double = 24.0,
    // Sensor width matters as soon as camera-tracking math (focal length in
    // mm <-> pixels) enters the picture - captured here so the user never
    // has to re-enter it once tracking starts.
    val sensorWidthMm: Double = 36.0,
    val colorSpace: ColorSpace = ColorSpace.SRGB,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    val isReadyToCreate: Boolean
        get() = projectName.isNotBlank() && footage.frameCount > 0
}
