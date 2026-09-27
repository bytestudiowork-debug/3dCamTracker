```kotlin
package com.trackstudio.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.trackstudio.app.ui.home.HomeScreen
import com.trackstudio.app.ui.newproject.NewProjectScreen
import com.trackstudio.app.ui.recentprojects.RecentProjectsScreen
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.TrackStudioTheme
import com.trackstudio.app.viewmodel.HomeViewModel
import com.trackstudio.app.viewmodel.NewProjectViewModel
import com.trackstudio.app.viewmodel.RecentProjectsViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        setContent {
            TrackStudioTheme {
                TrackStudioApp()
            }
        }
    }
}

private enum class Screen {
    HOME,
    NEW_PROJECT,
    RECENT_PROJECTS
}

@Composable
private fun TrackStudioApp() {
    val appContext =
        androidx.compose.ui.platform.LocalContext.current.applicationContext

    val homeViewModel: HomeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory = HomeViewModel.Factory(appContext)
        )

    val recentProjectsViewModel: RecentProjectsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory = RecentProjectsViewModel.Factory(appContext)
        )

    var screen by remember {
        mutableStateOf(Screen.HOME)
    }

    var dialogInstanceKey by remember {
        mutableStateOf(
            java.util.UUID.randomUUID().toString()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBackground)
    ) {

        when (screen) {

            Screen.HOME -> {
                HomeScreen(
                    viewModel = homeViewModel,

                    onNewProjectClick = {
                        dialogInstanceKey =
                            java.util.UUID.randomUUID().toString()

                        screen = Screen.NEW_PROJECT
                    },

                    onRecentProjectsClick = {
                        recentProjectsViewModel.refresh()
                        screen = Screen.RECENT_PROJECTS
                    }
                )
            }

            Screen.RECENT_PROJECTS -> {
                RecentProjectsScreen(
                    viewModel = recentProjectsViewModel,

                    onBack = {
                        screen = Screen.HOME
                    },

                    onEditProject = {
                        // Editor navigation will be connected when the
                        // editor module is implemented.
                    }
                )
            }

            Screen.NEW_PROJECT -> {

                val newProjectViewModel: NewProjectViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        key = dialogInstanceKey,
                        factory = NewProjectViewModel.Factory(appContext)
                    )

                NewProjectScreen(
                    viewModel = newProjectViewModel,

                    onCancel = {
                        screen = Screen.HOME
                    },

                    onProjectCreated = {
                        homeViewModel.refreshRecentProjects()
                        recentProjectsViewModel.refresh()
                        screen = Screen.HOME
                    }
                )
            }
        }
    }
}
```
