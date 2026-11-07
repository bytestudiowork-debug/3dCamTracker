package com.trackstudio.app.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.trackstudio.app.data.io.ImageSequenceImporter
import com.trackstudio.app.data.io.ImportProgress
import com.trackstudio.app.data.model.FootageInfo
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Loads an existing TrackStudio project.
 *
 * Project metadata stores the source folder URI rather than thousands
 * of individual frame URIs. When opening a project, the source folder
 * is scanned again and the current validated frame list is reconstructed.
 */
class ProjectLoader(
    private val appContext: Context,
    private val repository: ProjectRepository
) {

    private val importer =
        ImageSequenceImporter(
            appContext.contentResolver
        )

    /**
     * Load a project from its saved project ID.
     *
     * Returns progress while the source sequence is being rescanned.
     */
    fun loadProject(
        projectId: String
    ): Flow<ProjectLoadResult> = flow {

        val settings =
            repository.loadProject(
                projectId
            )

        if (settings == null) {
            emit(
                ProjectLoadResult.Failed(
                    "Project metadata could not be loaded."
                )
            )
            return@flow
        }

        if (settings.sourceFolderUri.isBlank()) {
            emit(
                ProjectLoadResult.Failed(
                    "This project does not have a saved source folder."
                )
            )
            return@flow
        }

        val folderUri =
            Uri.parse(
                settings.sourceFolderUri
            )

        if (folderUri.scheme != "file" && !hasPersistedReadPermission(folderUri)) {
            emit(
                ProjectLoadResult.Failed(
                    "Source folder permission is unavailable. " +
                        "The footage needs to be relinked."
                )
            )
            return@flow
        }

        importer
            .probeFolder(folderUri)
            .collect { progress ->

                when (progress) {

                    is ImportProgress.Probing -> {

                        emit(
                            ProjectLoadResult.Probing(
                                done = progress.done,
                                total = progress.total,
                                currentName =
                                    progress.currentName
                            )
                        )
                    }

                    is ImportProgress.Done -> {

                        val footage =
                            progress.footage

                        if (footage.frameCount <= 0) {

                            emit(
                                ProjectLoadResult.Failed(
                                    "The source folder contains no valid frames."
                                )
                            )

                        } else {

                            emit(
                                ProjectLoadResult.Success(
                                    settings =
                                        settings.copy(
                                            footage = footage
                                        )
                                )
                            )
                        }
                    }

                    is ImportProgress.Failed -> {

                        emit(
                            ProjectLoadResult.Failed(
                                progress.reason
                            )
                        )
                    }
                }
            }
    }

    private fun hasPersistedReadPermission(
        uri: Uri
    ): Boolean {

        val permissions =
            appContext
                .contentResolver
                .persistedUriPermissions

        return permissions.any { permission ->
            permission.uri == uri &&
                permission.isReadPermission
        }
    }
}

sealed class ProjectLoadResult {

    data class Probing(
        val done: Int,
        val total: Int,
        val currentName: String
    ) : ProjectLoadResult()

    data class Success(
        val settings: ProjectSettings
    ) : ProjectLoadResult()

    data class Failed(
        val reason: String
    ) : ProjectLoadResult()
}