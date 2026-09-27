package com.trackstudio.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackstudio.app.data.io.ImageSequenceImporter
import com.trackstudio.app.data.io.ImportProgress
import com.trackstudio.app.data.io.ProxyProgress
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.repository.ProjectRepository
import com.trackstudio.app.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class NewProjectViewModel(
    private val appContext: Context,
    private val importer: ImageSequenceImporter,
    private val repository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        NewProjectUiState()
    )

    val uiState: StateFlow<NewProjectUiState> =
        _uiState.asStateFlow()

    private val pendingProjectId: String =
        UUID.randomUUID().toString()

    fun onEvent(event: NewProjectEvent) {

        when (event) {

            is NewProjectEvent.ProjectNameChanged ->
                _uiState.update {
                    it.copy(projectName = event.name)
                }

            is NewProjectEvent.FramesFolderSelected ->
                importFramesFolder(event.folderUri)

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
                    it.copy(frameRate = event.fps)
                }

            is NewProjectEvent.SensorWidthChanged ->
                _uiState.update {
                    it.copy(sensorWidthMm = event.mm)
                }

            is NewProjectEvent.ColorSpaceChanged ->
                _uiState.update {
                    it.copy(colorSpace = event.colorSpace)
                }

            NewProjectEvent.CreateProject ->
                createProject()

            NewProjectEvent.DismissError ->
                _uiState.update {
                    it.copy(importError = null)
                }
        }
    }

    private fun importFramesFolder(folderUri: Uri) {

        _uiState.update {
            it.copy(
                importPhase = ImportPhase.PROBING,
                importError = null,
                probingProgress = 0 to 0,
                probingCurrentName = ""
            )
        }

        viewModelScope.launch {

            importer.probeFolder(folderUri)
                .collect { progress ->

                    when (progress) {

                        is ImportProgress.Probing ->
                            _uiState.update {
                                it.copy(
                                    probingProgress =
                                        progress.done to progress.total,
                                    probingCurrentName =
                                        progress.currentName
                                )
                            }

                        is ImportProgress.Done ->
                            _uiState.update {

                                val suggestProxy =
                                    progress.footage
                                        .requiresProxyByDefault

                                it.copy(
                                    importPhase =
                                        ImportPhase.PROBED,

                                    footage =
                                        progress.footage,

                                    proxySettings =
                                        it.proxySettings.copy(
                                            enabled =
                                                suggestProxy ||
                                                        it.proxySettings.enabled
                                        )
                                )
                            }

                        is ImportProgress.Failed ->
                            _uiState.update {
                                it.copy(
                                    importPhase =
                                        ImportPhase.FAILED,
                                    importError =
                                        progress.reason
                                )
                            }
                    }
                }
        }
    }

    private fun createProject() {

        val state = _uiState.value

        if (!state.canCreateProject) {
            return
        }

        val settings = ProjectSettings(
            id = pendingProjectId,
            projectName = state.projectName,
            footage = state.footage,
            proxy = state.proxySettings,
            frameRate = state.frameRate,
            sensorWidthMm = state.sensorWidthMm,
            colorSpace = state.colorSpace
        )

        if (!state.proxySettings.enabled) {

            repository.saveProjectMetadata(settings)

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
                    0 to state.footage.frameCount
            )
        }

        viewModelScope.launch {

            val outputDir =
                FileUtils.proxyDir(
                    context = appContext,
                    projectId = settings.id,
                    subdir = settings.proxy.cacheSubdir
                )

            importer.generateProxies(
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
                                    progress.done to progress.total
                            )
                        }

                    is ProxyProgress.Done -> {

                        repository.saveProjectMetadata(
                            settings
                        )

                        _uiState.update {
                            it.copy(
                                creationPhase =
                                    CreationPhase.DONE
                            )
                        }
                    }

                    is ProxyProgress.Failed ->
                        _uiState.update {
                            it.copy(
                                creationPhase =
                                    CreationPhase.FAILED,
                                importError =
                                    progress.reason
                            )
                        }
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
                importer =
                    ImageSequenceImporter(resolver),
                repository =
                    ProjectRepository(appContext)
            ) as T
        }
    }
}