package com.trackstudio.app.viewmodel

import com.trackstudio.app.data.model.ColorSpace
import com.trackstudio.app.data.model.FootageInfo
import com.trackstudio.app.data.model.ProxyResolution
import com.trackstudio.app.data.model.ProxySettings

enum class ImportPhase {
    IDLE,
    PROBING,
    PROBED,
    FAILED
}

enum class CreationPhase {
    IDLE,
    GENERATING_PROXIES,
    DONE,
    FAILED
}

/**
 * Single immutable snapshot the New Project screen renders from.
 */
data class NewProjectUiState(
    val projectName: String = "",
    val importPhase: ImportPhase = ImportPhase.IDLE,

    val probingProgress: Pair<Int, Int> = 0 to 0,
    val probingCurrentName: String = "",

    val footage: FootageInfo = FootageInfo.EMPTY,
    val importError: String? = null,

    val proxySettings: ProxySettings = ProxySettings(),
    val frameRate: Double = 24.0,
    val sensorWidthMm: Double = 36.0,
    val colorSpace: ColorSpace = ColorSpace.SRGB,

    val creationPhase: CreationPhase = CreationPhase.IDLE,
    val creationProgress: Pair<Int, Int> = 0 to 0,
    val proxyWarning: String? = null
) {
    val canCreateProject: Boolean
        get() = projectName.isNotBlank() &&
                importPhase == ImportPhase.PROBED &&
                footage.frameCount > 0 &&
                creationPhase != CreationPhase.GENERATING_PROXIES

    val availableProxyResolutions: List<ProxyResolution>
        get() = ProxyResolution.values().toList()
}

sealed class NewProjectEvent {

    data class ProjectNameChanged(
        val name: String
    ) : NewProjectEvent()

    /**
     * A complete frames folder selected through Android's folder picker.
     */
    data class FramesFolderSelected(
        val folderUri: android.net.Uri
    ) : NewProjectEvent()

    data class ProxyEnabledChanged(
        val enabled: Boolean
    ) : NewProjectEvent()

    data class ProxyResolutionChanged(
        val resolution: ProxyResolution
    ) : NewProjectEvent()

    data class ProxyQualityChanged(
        val quality: Int
    ) : NewProjectEvent()

    data class FrameRateChanged(
        val fps: Double
    ) : NewProjectEvent()

    data class SensorWidthChanged(
        val mm: Double
    ) : NewProjectEvent()

    data class ColorSpaceChanged(
        val colorSpace: ColorSpace
    ) : NewProjectEvent()

    object CreateProject : NewProjectEvent()

    object DismissError : NewProjectEvent()
}