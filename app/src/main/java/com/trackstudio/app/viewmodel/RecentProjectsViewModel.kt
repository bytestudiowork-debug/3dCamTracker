package com.trackstudio.app.viewmodel
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecentProjectsUiState(
    val projects: List<ProjectSettings> = emptyList(),
    val isLoading: Boolean = false
)

class RecentProjectsViewModel(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecentProjectsUiState())
    val uiState: StateFlow<RecentProjectsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = RecentProjectsUiState(
                isLoading = true
            )

            val projects = repository
                .listRecentProjectDirs()
                .mapNotNull(repository::loadProjectMetadata)

            _uiState.value = RecentProjectsUiState(
                projects = projects,
                isLoading = false
            )
        }
    }

    fun renameProject(
        projectId: String,
        newName: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.renameProject(projectId, newName)) {
                refresh()
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.deleteProject(projectId)) {
                refresh()
            }
        }
    }

    class Factory(
        private val appContext: Context
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {
            return RecentProjectsViewModel(
                repository = ProjectRepository(appContext)
            ) as T
        }
    }
}

