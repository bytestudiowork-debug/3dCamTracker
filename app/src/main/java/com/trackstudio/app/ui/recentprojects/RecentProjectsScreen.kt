package com.trackstudio.app.ui.recentprojects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.common.Panel
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.RecentProjectsViewModel

@Composable
fun RecentProjectsScreen(
    viewModel: RecentProjectsViewModel,
    onBack: () -> Unit,
    onEditProject: (ProjectSettings) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    var renameProject by remember {
        mutableStateOf<ProjectSettings?>(null)
    }

    var deleteProject by remember {
        mutableStateOf<ProjectSettings?>(null)
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(28.dp)
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            DesktopButton(
                text = "← Back",
                onClick = onBack
            )

            Spacer(
                Modifier.width(20.dp)
            )

            Text(
                text = "Recent Projects",
                style =
                    MaterialTheme
                        .typography
                        .headlineSmall
            )
        }

        Spacer(
            Modifier.padding(
                top = 20.dp
            )
        )

        Panel(
            title = "Projects",
            modifier =
                Modifier.fillMaxSize()
        ) {

            if (state.isLoading) {

                Box(
                    modifier =
                        Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        text = "Loading projects...",
                        color = TextSecondary
                    )
                }

            } else if (state.projects.isEmpty()) {

                Box(
                    modifier =
                        Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        text = "No recent projects.",
                        color = TextSecondary
                    )
                }

            } else {

                LazyColumn(
                    modifier =
                        Modifier.fillMaxSize(),

                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    items(
                        items = state.projects,
                        key = { project ->
                            project.id
                        }
                    ) { project ->

                        ProjectRow(
                            project = project,

                            onEdit = {
                                /*
                                 * Pass the complete project
                                 * configuration to MainActivity.
                                 *
                                 * MainActivity then asks
                                 * ProjectLoader to rescan the
                                 * saved source folder.
                                 */
                                onEditProject(project)
                            },

                            onRename = {
                                renameProject =
                                    project
                            },

                            onDelete = {
                                deleteProject =
                                    project
                            }
                        )
                    }
                }
            }
        }
    }

    /*
     * Rename dialog
     */
    renameProject?.let { project ->

        RenameProjectDialog(

            project = project,

            onDismiss = {
                renameProject = null
            },

            onRename = { newName ->

                viewModel.renameProject(
                    projectId = project.id,
                    newName = newName
                )

                renameProject = null
            }
        )
    }

    /*
     * Delete confirmation
     */
    deleteProject?.let { project ->

        DeleteProjectDialog(

            project = project,

            onDismiss = {
                deleteProject = null
            },

            onConfirm = {

                viewModel.deleteProject(
                    project.id
                )

                deleteProject = null
            }
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSettings,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {

    var menuExpanded by remember {
        mutableStateOf(false)
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 8.dp,
                    vertical = 6.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text =
                    project.projectName.ifBlank {
                        "Untitled Project"
                    },

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge
            )

            Text(
                text =
                    buildProjectDescription(
                        project
                    ),

                color =
                    TextSecondary,

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium
            )
        }

        Box {

            IconButton(
                onClick = {
                    menuExpanded = true
                }
            ) {

                Icon(
                    imageVector =
                        Icons.Default.MoreVert,

                    contentDescription =
                        "Project actions"
                )
            }

            DropdownMenu(
                expanded =
                    menuExpanded,

                onDismissRequest = {
                    menuExpanded = false
                }
            ) {

                DropdownMenuItem(

                    text = {
                        Text("Edit")
                    },

                    onClick = {

                        menuExpanded = false

                        onEdit()
                    }
                )

                DropdownMenuItem(

                    text = {
                        Text("Rename")
                    },

                    onClick = {

                        menuExpanded = false

                        onRename()
                    }
                )

                DropdownMenuItem(

                    text = {
                        Text("Delete")
                    },

                    onClick = {

                        menuExpanded = false

                        onDelete()
                    }
                )
            }
        }
    }
}

private fun buildProjectDescription(
    project: ProjectSettings
): String {

    val frameText =
        "${project.footage.frameCount} frames"

    val resolutionText =
        if (
            project.footage.width > 0 &&
            project.footage.height > 0
        ) {
            "${project.footage.width} × " +
                "${project.footage.height}"
        } else {
            "Unknown resolution"
        }

    val fpsText =
        if (
            project.frameRate ==
            project.frameRate.toLong().toDouble()
        ) {
            "${project.frameRate.toLong()} FPS"
        } else {
            "%.2f FPS".format(
                project.frameRate
            )
        }

    return "$frameText · $resolutionText · $fpsText"
}

@Composable
private fun RenameProjectDialog(
    project: ProjectSettings,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {

    var name by remember {
        mutableStateOf(
            project.projectName
        )
    }

    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {
            Text("Rename Project")
        },

        text = {

            OutlinedTextField(

                value = name,

                onValueChange = {
                    name = it
                },

                label = {
                    Text("Project name")
                },

                singleLine = true,

                modifier =
                    Modifier.fillMaxWidth()
            )
        },

        confirmButton = {

            TextButton(

                enabled =
                    name.trim().isNotEmpty(),

                onClick = {
                    onRename(
                        name.trim()
                    )
                }
            ) {

                Text("Rename")
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {

                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DeleteProjectDialog(
    project: ProjectSettings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {

    AlertDialog(

        onDismissRequest =
            onDismiss,

        title = {
            Text("Delete Project?")
        },

        text = {

            Text(
                "Delete \"${project.projectName.ifBlank {
                    "Untitled Project"
                }}\"? " +
                    "This will remove the project's " +
                    "stored files and cannot be undone."
            )
        },

        confirmButton = {

            TextButton(
                onClick = onConfirm
            ) {

                Text("Delete")
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {

                Text("Cancel")
            }
        }
    )
}