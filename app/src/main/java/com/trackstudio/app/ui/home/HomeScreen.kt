package com.trackstudio.app.ui.home
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TRACKSTUDIO",
                style = MaterialTheme.typography.titleLarge,
                color = TextSecondary
            )

            Spacer(Modifier.height(24.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DesktopButton(
                    text = "New Project",
                    onClick = onNewProjectClick,
                    emphasized = true,
                    modifier = Modifier.width(240.dp)
                )

                DesktopButton(
                    text = "Recent Projects (${uiState.recentProjects.size})",
                    onClick = onRecentProjectsClick,
                    modifier = Modifier.width(240.dp),
                    enabled = uiState.recentProjects.isNotEmpty()
                )
            }
        }
    }
}

