package com.trackstudio.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.trackstudio.app.ui.home.HomeScreen
import com.trackstudio.app.ui.newproject.NewProjectScreen
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.TrackStudioTheme
import com.trackstudio.app.viewmodel.HomeViewModel
import com.trackstudio.app.viewmodel.NewProjectViewModel

class MainActivity : ComponentActivity() {

    // Belt-and-suspenders: the manifest already locks this Activity to
    // landscape, this just guarantees it even if some launcher/OEM skin
    // ignores the manifest attribute.
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

private enum class Screen { HOME, NEW_PROJECT }

@Composable
private fun TrackStudioApp() {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    val homeViewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = HomeViewModel.Factory(appContext)
    )

    var screen by remember { mutableStateOf(Screen.HOME) }
    // Regenerated every time the dialog is opened, so the ViewModel it keys
    // (below) is a fresh instance each time rather than one reused across
    // open/cancel cycles - a cancelled attempt must not leak into the next.
    var dialogInstanceKey by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(PanelBackground)) {
        HomeScreen(
            viewModel = homeViewModel,
            onNewProjectClick = {
                dialogInstanceKey = java.util.UUID.randomUUID().toString()
                screen = Screen.NEW_PROJECT
            },
            onOpenProject = { /* Opening an existing project is a later module. */ }
        )

        if (screen == Screen.NEW_PROJECT) {
            val newProjectViewModel: NewProjectViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                key = dialogInstanceKey,
                factory = NewProjectViewModel.Factory(appContext)
            )

            NewProjectScreen(
                viewModel = newProjectViewModel,
                onCancel = { screen = Screen.HOME },
                onProjectCreated = {
                    homeViewModel.refreshRecentProjects()
                    screen = Screen.HOME
                }
            )
        }
    }
}
