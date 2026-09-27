package com.trackstudio.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.common.Panel
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.HomeViewModel

/**
 * Phase 1 home screen: intentionally just the two entry points that were
 * asked for. Anything more (thumbnails, search, sorting) belongs to a
 * later refinement pass, not this module.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNewProjectClick: () -> Unit,
    onOpenProject: (ProjectSettings) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshRecentProjects() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "TRACKSTUDIO",
                style = MaterialTheme.typography.titleLarge,
                color = TextSecondary
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))

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
                    onClick = { /* expand handled below inline */ },
                    modifier = Modifier.width(240.dp),
                    enabled = uiState.recentProjects.isNotEmpty()
                )
            }

            if (uiState.recentProjects.isNotEmpty()) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
                Panel(
                    title = "Recent Projects",
                    modifier = Modifier.width(360.dp)
                ) {
                    LazyColumn(modifier = Modifier.padding(top = 4.dp)) {
                        items(uiState.recentProjects) { project ->
                            RecentProjectRow(project, onClick = { onOpenProject(project) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentProjectRow(project: ProjectSettings, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(project.projectName.ifBlank { "Untitled Project" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${project.footage.frameCount} frames · ${project.footage.width}x${project.footage.height}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}
