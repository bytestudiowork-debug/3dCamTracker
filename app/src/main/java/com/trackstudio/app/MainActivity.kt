package com.trackstudio.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.data.repository.ProjectRepository
import com.trackstudio.app.project.ProjectLoadResult
import com.trackstudio.app.project.ProjectLoader
import com.trackstudio.app.project.ViewerFrames
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.home.HomeScreen
import com.trackstudio.app.ui.newproject.NewProjectScreen
import com.trackstudio.app.ui.recentprojects.RecentProjectsScreen
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.ui.theme.TrackStudioTheme
import com.trackstudio.app.ui.viewer.ViewerScreen
import com.trackstudio.app.viewmodel.HomeViewModel
import com.trackstudio.app.viewmodel.NewProjectViewModel
import com.trackstudio.app.viewmodel.RecentProjectsViewModel
import com.trackstudio.app.viewmodel.ViewerViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            val requiredFlags =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

            if (visibility and requiredFlags != requiredFlags) {
                hideSystemBars()
            }
        }

        hideSystemBars()

        setContent {
            TrackStudioTheme {
                TrackStudioApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private enum class Screen {
    HOME,
    NEW_PROJECT,
    RECENT_PROJECTS,
    VIEWER,
    LOADING_PROJECT
}

@Composable
private fun TrackStudioApp() {

    val appContext =
        androidx.compose.ui.platform
            .LocalContext.current
            .applicationContext

    val homeViewModel:
        HomeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory =
                HomeViewModel.Factory(
                    appContext
                )
        )

    val recentProjectsViewModel:
        RecentProjectsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory =
                RecentProjectsViewModel.Factory(
                    appContext
                )
        )

    val viewerViewModel:
        ViewerViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory =
                ViewerViewModel.Factory(
                    appContext
                )
        )

    val projectLoader =
        remember {
            ProjectLoader(
                appContext =
                    appContext,

                repository =
                    ProjectRepository(
                        appContext
                    )
            )
        }

    var screen by remember {
        mutableStateOf(
            Screen.HOME
        )
    }

    var dialogInstanceKey by remember {
        mutableStateOf(
            java.util.UUID
                .randomUUID()
                .toString()
        )
    }

    var viewerProject by remember {
        mutableStateOf<ProjectSettings?>(
            null
        )
    }

    var projectToLoadId by remember {
        mutableStateOf<String?>(null)
    }

    var loadingProjectName by remember {
        mutableStateOf("")
    }

    var loadingProgress by remember {
        mutableStateOf(0 to 0)
    }

    var loadingFrameName by remember {
        mutableStateOf("")
    }

    var loadingError by remember {
        mutableStateOf<String?>(null)
    }

    /*
     * Existing project loading.
     *
     * When Edit is clicked, projectToLoadId changes.
     * This effect rescans the project's source folder.
     */
    LaunchedEffect(projectToLoadId) {

        val projectId =
            projectToLoadId
                ?: return@LaunchedEffect

        projectLoader
            .loadProject(projectId)
            .collect { result ->

                when (result) {

                    is ProjectLoadResult.Probing -> {

                        loadingProgress =
                            result.done to
                                result.total

                        loadingFrameName =
                            result.currentName
                    }

                    is ProjectLoadResult.Success -> {

                        viewerProject =
                            result.settings

                        projectToLoadId =
                            null

                        loadingError =
                            null

                        screen =
                            Screen.VIEWER
                    }

                    is ProjectLoadResult.Failed -> {

                        loadingError =
                            result.reason

                        projectToLoadId =
                            null
                    }
                }
            }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    PanelBackground
                )
    ) {

        when (screen) {

            Screen.HOME -> {

                HomeScreen(
                    viewModel =
                        homeViewModel,

                    onNewProjectClick = {

                        dialogInstanceKey =
                            java.util.UUID
                                .randomUUID()
                                .toString()

                        screen =
                            Screen.NEW_PROJECT
                    },

                    onRecentProjectsClick = {

                        recentProjectsViewModel
                            .refresh()

                        screen =
                            Screen.RECENT_PROJECTS
                    }
                )
            }

            Screen.NEW_PROJECT -> {

                val newProjectViewModel:
                    NewProjectViewModel =
                    androidx.lifecycle
                        .viewmodel
                        .compose
                        .viewModel(
                            key =
                                dialogInstanceKey,

                            factory =
                                NewProjectViewModel
                                    .Factory(
                                        appContext
                                    )
                        )

                NewProjectScreen(

                    viewModel =
                        newProjectViewModel,

                    onCancel = {
                        screen =
                            Screen.HOME
                    },

                    onProjectCreated = {
                        project ->

                        /*
                         * Update Recent Projects.
                         */
                        homeViewModel
                            .refreshRecentProjects()

                        recentProjectsViewModel
                            .refresh()

                        /*
                         * The new project already has
                         * its validated frame URIs.
                         */
                        viewerProject =
                            project

                        screen =
                            Screen.VIEWER
                    }
                )
            }

            Screen.RECENT_PROJECTS -> {

                RecentProjectsScreen(

                    viewModel =
                        recentProjectsViewModel,

                    onBack = {
                        screen =
                            Screen.HOME
                    },

                    onEditProject = {
                        project ->

                        loadingProjectName =
                            project.projectName

                        loadingProgress =
                            0 to
                                project.footage.frameCount

                        loadingFrameName =
                            ""

                        loadingError =
                            null

                        projectToLoadId =
                            project.id

                        screen =
                            Screen.LOADING_PROJECT
                    }
                )
            }

            Screen.LOADING_PROJECT -> {

                ProjectLoadingScreen(

                    projectName =
                        loadingProjectName,

                    done =
                        loadingProgress.first,

                    total =
                        loadingProgress.second,

                    currentName =
                        loadingFrameName,

                    error =
                        loadingError,

                    onBack = {

                        projectToLoadId =
                            null

                        loadingError =
                            null

                        screen =
                            Screen.RECENT_PROJECTS
                    }
                )
            }

            Screen.VIEWER -> {

                val project =
                    viewerProject

                if (project != null) {

                    /*
                     * Remembered so the list keeps the same identity
                     * across recompositions (the viewer reloads its
                     * sequence whenever this list changes).
                     */
                    val viewerFrameUris =
                        remember(
                            project.id,
                            project.footage.frameCount,
                            project.proxy.enabled
                        ) {
                            ViewerFrames.resolve(
                                appContext,
                                project
                            )
                        }

                    ViewerScreen(

                        viewModel =
                            viewerViewModel,

                        frameUris =
                            viewerFrameUris,

                        fps =
                            project.frameRate,

                        projectName =
                            project.projectName,

                        onBack = {

                            viewerViewModel
                                .firstFrame()

                            viewerProject =
                                null

                            screen =
                                Screen.HOME
                        }
                    )

                } else {

                    Text(
                        text =
                            "No project loaded.",
                        color =
                            TextSecondary,
                        modifier =
                            Modifier.align(
                                Alignment.Center
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectLoadingScreen(
    projectName: String,
    done: Int,
    total: Int,
    currentName: String,
    error: String?,
    onBack: () -> Unit
) {

    Box(
        modifier =
            Modifier.fillMaxSize(),
        contentAlignment =
            Alignment.Center
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally,

            modifier =
                Modifier.padding(32.dp)
        ) {

            if (error == null) {

                CircularProgressIndicator()

                Spacer(
                    Modifier.height(20.dp)
                )

                Text(
                    text =
                        "Opening $projectName",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium
                )

                Spacer(
                    Modifier.height(8.dp)
                )

                Text(
                    text =
                        if (total > 0) {
                            "Scanning frames: $done / $total"
                        } else {
                            "Scanning source folder..."
                        },

                    color =
                        TextSecondary
                )

                if (
                    currentName.isNotBlank()
                ) {

                    Spacer(
                        Modifier.height(6.dp)
                    )

                    Text(
                        text =
                            currentName,

                        color =
                            TextSecondary
                    )
                }

            } else {

                Text(
                    text =
                        "Unable to open project",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium
                )

                Spacer(
                    Modifier.height(10.dp)
                )

                Text(
                    text = error,

                    color =
                        MaterialTheme
                            .colorScheme
                            .error
                )

                Spacer(
                    Modifier.height(20.dp)
                )

                DesktopButton(
                    text = "Back",
                    onClick = onBack
                )
            }
        }
    }
}
