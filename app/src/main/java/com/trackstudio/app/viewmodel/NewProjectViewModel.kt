package com.trackstudio.app.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackstudio.app.data.io.ImageSequenceImporter
import com.trackstudio.app.data.io.ImportProgress
import com.trackstudio.app.data.io.ProxyGenerator
import com.trackstudio.app.data.io.ProxyProgress
import com.trackstudio.app.data.io.VideoFrameExtractor
import com.trackstudio.app.data.io.VideoExtractionProgress
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.repository.ProjectRepository
import com.trackstudio.app.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class NewProjectViewModel(
    private val appContext: Context,
    private val importer: ImageSequenceImporter,
    private val videoExtractor: VideoFrameExtractor,
    private val proxyGenerator: ProxyGenerator,
    private val repository: ProjectRepository
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(NewProjectUiState())

    val uiState: StateFlow<NewProjectUiState> =
        _uiState.asStateFlow()

    private val pendingProjectId =
        UUID.randomUUID().toString()

    /**
     * The folder selected through SAF.
     *
     * Stored separately from FootageInfo because FootageInfo describes
     * the imported sequence, while this URI identifies its source.
     */
    private var selectedFolderUri: Uri? = null
    private var importJob: Job? = null

    /**
     * Completed project configuration.
     *
     * MainActivity can use this to immediately open the Viewer.
     */
    private var completedProjectSettings: ProjectSettings? = null

    fun getCompletedProjectSettings(): ProjectSettings? {
        return completedProjectSettings
    }

    fun onEvent(event: NewProjectEvent) {

        when (event) {

            is NewProjectEvent.ProjectNameChanged ->
                _uiState.update {
                    it.copy(
                        projectName = event.name
                    )
                }

            is NewProjectEvent.FramesFolderSelected ->
                importFramesFolder(event.folderUri)

            is NewProjectEvent.VideoSelected ->
                importVideo(event.videoUri)

            NewProjectEvent.CancelImport ->
                cancelImport()

            is NewProjectEvent.ProxyEnabledChanged ->
                _uiState.update {
                    it.copy(
                        proxySettings =
                            it.proxySettings.copy(
                                enabled = event.enabled
                            )
                    )
                }

            is NewProjectEvent.ProxyResolutionChanged ->
                _uiState.update {
                    it.copy(
                        proxySettings =
                            it.proxySettings.copy(
                                resolution = event.resolution
                            )
                    )
                }

            is NewProjectEvent.ProxyQualityChanged ->
                _uiState.update {
                    it.copy(
                        proxySettings =
                            it.proxySettings.copy(
                                jpegQuality = event.quality
                            )
                    )
                }

            is NewProjectEvent.FrameRateChanged ->
                _uiState.update {
                    it.copy(
                        frameRate = event.fps
                    )
                }

            is NewProjectEvent.SensorWidthChanged ->
                _uiState.update {
                    it.copy(
                        sensorWidthMm = event.mm
                    )
                }

            is NewProjectEvent.ColorSpaceChanged ->
                _uiState.update {
                    it.copy(
                        colorSpace = event.colorSpace
                    )
                }

            NewProjectEvent.CreateProject ->
                createProject()

            NewProjectEvent.DismissError ->
                _uiState.update {
                    it.copy(
                        importError = null
                    )
                }
        }
    }

    private fun importFramesFolder(folderUri: Uri) {
        importJob?.cancel()
        takePersistableReadPermission(folderUri)
        _uiState.update {
            it.copy(importPhase = ImportPhase.PROBING, importError = null,
                probingProgress = 0 to 0, probingCurrentName = "")
        }
        importJob = viewModelScope.launch { probeFolder(folderUri) }
    }

    private fun importVideo(videoUri: Uri) {
        importJob?.cancel()
        takePersistableReadPermission(videoUri)
        _uiState.update {
            it.copy(importPhase = ImportPhase.EXTRACTING_VIDEO, importError = null,
                extractionProgress = 0 to 0, extractionCurrentName = "")
        }
        importJob = viewModelScope.launch {
            val sourceFramesDir = FileUtils.proxyDir(
                appContext, pendingProjectId,
                "source_frames_${UUID.randomUUID().toString().take(8)}"
            )
            videoExtractor.extract(videoUri, sourceFramesDir).collect { progress ->
                when (progress) {
                    is VideoExtractionProgress.Started ->
                        _uiState.update {
                            it.copy(extractionProgress = 0 to progress.estimatedFrames,
                                frameRate = progress.frameRate)
                        }
                    is VideoExtractionProgress.FrameWritten ->
                        _uiState.update {
                            it.copy(extractionProgress = progress.done to progress.total,
                                extractionCurrentName = progress.name)
                        }
                    is VideoExtractionProgress.Done -> {
                        val framesUri = Uri.fromFile(progress.outputDir)
                        _uiState.update {
                            it.copy(importPhase = ImportPhase.PROBING,
                                probingProgress = 0 to progress.frameCount,
                                probingCurrentName = "Validating decoded frames...")
                        }
                        probeFolder(framesUri)
                    }
                    is VideoExtractionProgress.Failed ->
                        _uiState.update {
                            it.copy(importPhase = ImportPhase.FAILED, importError = progress.reason)
                        }
                }
            }
        }
    }

    private suspend fun probeFolder(folderUri: Uri) {
        importer.probeFolder(folderUri).collect { progress ->
            when (progress) {
                is ImportProgress.Probing ->
                    _uiState.update {
                        it.copy(probingProgress = progress.done to progress.total,
                            probingCurrentName = progress.currentName)
                    }
                is ImportProgress.Done -> {
                    selectedFolderUri = folderUri
                    val suggestProxy = progress.footage.requiresProxyByDefault
                    _uiState.update {
                        it.copy(importPhase = ImportPhase.PROBED, footage = progress.footage,
                            proxySettings = it.proxySettings.copy(
                                enabled = suggestProxy || it.proxySettings.enabled))
                    }
                }
                is ImportProgress.Failed ->
                    _uiState.update {
                        it.copy(importPhase = ImportPhase.FAILED, importError = progress.reason)
                    }
            }
        }
    }

    private fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _uiState.update {
            it.copy(importPhase = if (it.footage.frameCount > 0) ImportPhase.PROBED else ImportPhase.IDLE,
                importError = null, extractionCurrentName = "", probingCurrentName = "")
        }
    }

    private fun takePersistableReadPermission(
        folderUri: Uri
    ) {
        try {

            appContext.contentResolver.takePersistableUriPermission(
                folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

        } catch (_: SecurityException) {

            /**
             * Some providers do not support persistent permissions.
             *
             * The import can still work during the current session.
             * Reopening that project later may require relinking the folder.
             */
        }
    }

    private fun createProject() {

        val state = _uiState.value
        val sourceUri = selectedFolderUri

        if (
            !state.canCreateProject ||
            sourceUri == null
        ) {
            return
        }

        val settings =
            ProjectSettings(
                id = pendingProjectId,

                projectName =
                    state.projectName.trim(),

                sourceFolderUri =
                    sourceUri.toString(),

                footage =
                    state.footage,

                proxy =
                    state.proxySettings,

                frameRate =
                    state.frameRate,

                sensorWidthMm =
                    state.sensorWidthMm,

                colorSpace =
                    state.colorSpace
            )

        if (!state.proxySettings.enabled) {

            repository.saveProjectMetadata(settings)

            completedProjectSettings = settings

            _uiState.update {
                it.copy(
                    creationPhase =
                        CreationPhase.DONE
                )
            }

            return
        }

        _uiState.update {
            it.copy(
                creationPhase =
                    CreationPhase.GENERATING_PROXIES,

                creationProgress =
                    0 to state.footage.frameCount,
                proxyWarning = null
            )
        }

        // Persist the project before starting the optional proxy stage.
        // A proxy failure must never discard the imported original footage.
        repository.saveProjectMetadata(settings)

        viewModelScope.launch {

            val outputDir =
                FileUtils.proxyDir(
                    context = appContext,
                    projectId = settings.id,
                    subdir = settings.proxy.cacheSubdir
                )

            try {
                proxyGenerator.generate(
                    footage = settings.footage,
                    scale = settings.proxy.resolution.scale,
                    jpegQuality = settings.proxy.jpegQuality,
                    outputDir = outputDir
                ).collect { progress ->

                when (progress) {

                    is ProxyProgress.Generating ->
                        _uiState.update {
                            it.copy(
                                creationProgress =
                                    progress.done to
                                        progress.total
                            )
                        }

                    is ProxyProgress.Done -> {

                        repository.saveProjectMetadata(
                            settings
                        )

                        completedProjectSettings =
                            settings

                        _uiState.update {
                            it.copy(
                                creationPhase =
                                    CreationPhase.DONE
                            )
                        }
                    }

                    is ProxyProgress.Failed -> {
                        // Proxy generation is an optional playback optimization.
                        // Keep the original footage usable and let ViewerFrames fall back to it.
                        repository.saveProjectMetadata(settings)
                        completedProjectSettings = settings
                        _uiState.update {
                            it.copy(
                                creationPhase = CreationPhase.DONE,
                                proxyWarning = "Project created. Proxy generation failed; original footage will be used. ${progress.reason}"
                            )
                        }
                    }
                }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                repository.saveProjectMetadata(settings)
                completedProjectSettings = settings
                _uiState.update {
                    it.copy(
                        creationPhase = CreationPhase.DONE,
                        proxyWarning = "Project created. Proxy generation stopped; original footage will be used. ${error.message ?: "Unknown proxy error"}"
                    )
                }
            }
        }
    }

    class Factory(
        private val appContext: Context
    ) : androidx.lifecycle.ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {

            val resolver =
                appContext.contentResolver

            return NewProjectViewModel(
                appContext = appContext,

                importer = ImageSequenceImporter(resolver),
                videoExtractor = VideoFrameExtractor(appContext),
                proxyGenerator = ProxyGenerator(resolver),
                repository =
                    ProjectRepository(
                        appContext
                    )
            ) as T
        }
    }
}