package com.trackstudio.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeUiState(
    val recentProjects: List<ProjectSettings> = emptyList(),
    val isLoading: Boolean = false
)

class HomeViewModel(private val repository: ProjectRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Cheap enough (a few JSON files) to run on the caller's thread on-resume. */
    fun refreshRecentProjects() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val projects = repository.listRecentProjectDirs().mapNotNull { repository.loadProjectMetadata(it) }
        _uiState.value = HomeUiState(recentProjects = projects, isLoading = false)
    }

    class Factory(private val appContext: Context) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(ProjectRepository(appContext)) as T
        }
    }
}
