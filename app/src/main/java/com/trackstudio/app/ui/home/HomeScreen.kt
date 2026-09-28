package com.trackstudio.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.common.Panel
import com.trackstudio.app.ui.theme.AccentBlue
import com.trackstudio.app.ui.theme.BgDeep
import com.trackstudio.app.ui.theme.BgPanel
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNewProjectClick: () -> Unit,
    onRecentProjectsClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshRecentProjects()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgPanel)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TRACKSTUDIO", style = MaterialTheme.typography.titleMedium, color = AccentBlue)
            Spacer(Modifier.weight(1f))
            Text("3D CAMERA TRACKING", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Panel(modifier = Modifier.width(360.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("TRACKSTUDIO", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text("3D Camera Tracking", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Spacer(Modifier.height(24.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DesktopButton(
                            text = "New Project",
                            onClick = onNewProjectClick,
                            emphasized = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        DesktopButton(
                            text = "Recent Projects (${uiState.recentProjects.size})",
                            onClick = onRecentProjectsClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.recentProjects.isNotEmpty()
                        )
                    }
                }
            }
        }
    }
}
