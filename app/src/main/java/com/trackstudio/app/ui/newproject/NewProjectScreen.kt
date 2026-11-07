package com.trackstudio.app.ui.newproject

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trackstudio.app.data.model.ProjectSettings
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.newproject.components.FootagePanel
import com.trackstudio.app.ui.newproject.components.ProjectSettingsPanel
import com.trackstudio.app.ui.newproject.components.ProxyPanel
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.PanelSurfaceRaised
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.CreationPhase
import com.trackstudio.app.viewmodel.NewProjectEvent
import com.trackstudio.app.viewmodel.NewProjectViewModel

private enum class SettingsTab(
    val label: String
) {
    FOOTAGE("Footage"),
    PROXY("Proxy"),
    PROJECT("Project")
}

@Composable
fun NewProjectScreen(
    viewModel: NewProjectViewModel,
    onCancel: () -> Unit,
    onProjectCreated: (ProjectSettings) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    var selectedTab by remember {
        mutableStateOf(SettingsTab.FOOTAGE)
    }

    /*
     * Creation is complete.
     *
     * Pass the completed ProjectSettings back to MainActivity.
     * MainActivity will then open the Viewer.
     */
    if (state.creationPhase == CreationPhase.DONE) {
        LaunchedEffect(Unit) {
            viewModel.getCompletedProjectSettings()?.let { settings ->
                onProjectCreated(settings)
            }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false
            )
    ) {

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .background(
                        PanelBackground,
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            PanelBorder
                        ),
                        RoundedCornerShape(8.dp)
                    )
        ) {

            TitleBar()

            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
            ) {

                TabRail(
                    selectedTab = selectedTab,
                    onSelect = {
                        selectedTab = it
                    }
                )

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(16.dp)
                ) {

                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(
                                    rememberScrollState()
                                )
                                .padding(
                                    bottom = 8.dp
                                )
                    ) {

                        when (selectedTab) {

                            SettingsTab.FOOTAGE ->
                                FootagePanel(
                                    state = state,
                                    onEvent =
                                        viewModel::onEvent,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                )

                            SettingsTab.PROXY ->
                                ProxyPanel(
                                    state = state,
                                    onEvent =
                                        viewModel::onEvent,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                )

                            SettingsTab.PROJECT ->
                                ProjectSettingsPanel(
                                    state = state,
                                    onEvent =
                                        viewModel::onEvent,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                )
                        }
                    }
                }
            }

            BottomBar(
                state = state,
                onCancel = onCancel,
                onEvent = viewModel::onEvent
            )
        }
    }
}

@Composable
private fun TitleBar() {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    PanelSurfaceRaised,
                    RoundedCornerShape(
                        topStart = 8.dp,
                        topEnd = 8.dp
                    )
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 14.dp
                ),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            text = "NEW PROJECT",
            style =
                MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
    }

    Divider(
        color = PanelBorder,
        thickness = 1.dp
    )
}

@Composable
private fun TabRail(
    selectedTab: SettingsTab,
    onSelect: (SettingsTab) -> Unit
) {

    Column(
        modifier =
            Modifier
                .width(150.dp)
                .fillMaxHeight()
                .background(
                    PanelSurfaceRaised
                )
                .padding(
                    vertical = 12.dp
                )
    ) {

        SettingsTab.values().forEach { tab ->

            val isSelected =
                tab == selectedTab

            val interactionSource =
                remember {
                    androidx.compose.foundation
                        .interaction
                        .MutableInteractionSource()
                }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) {
                                AccentPrimary.copy(
                                    alpha = 0.14f
                                )
                            } else {
                                Color.Transparent
                            }
                        )
                        .border(
                            BorderStroke(
                                width =
                                    if (isSelected) {
                                        2.dp
                                    } else {
                                        0.dp
                                    },
                                color =
                                    if (isSelected) {
                                        AccentPrimary
                                    } else {
                                        Color.Transparent
                                    }
                            )
                        )
                        .clickable(
                            interactionSource =
                                interactionSource,
                            indication = null,
                            onClick = {
                                onSelect(tab)
                            }
                        )
                        .padding(
                            horizontal = 16.dp,
                            vertical = 13.dp
                        )
            ) {

                Text(
                    text = tab.label,
                    color =
                        if (isSelected) {
                            TextPrimary
                        } else {
                            TextSecondary
                        },
                    style =
                        MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: com.trackstudio.app.viewmodel.NewProjectUiState,
    onCancel: () -> Unit,
    onEvent: (NewProjectEvent) -> Unit
) {

    Divider(
        color = PanelBorder,
        thickness = 1.dp
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
    ) {

        if (state.proxyWarning != null) {
            Text(
                text = state.proxyWarning!!,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (
            state.creationPhase ==
            CreationPhase.GENERATING_PROXIES
        ) {

            val (done, total) =
                state.creationProgress

            Text(
                text =
                    "Generating proxies: $done / $total",
                style =
                    MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            LinearProgressIndicator(
                progress = {
                    if (total == 0) {
                        0f
                    } else {
                        done.toFloat() /
                            total.toFloat()
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 4.dp,
                            bottom = 10.dp
                        )
            )
        }

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.End
        ) {

            DesktopButton(
                text = "Cancel",
                onClick = onCancel
            )

            Spacer(
                Modifier.width(12.dp)
            )

            DesktopButton(
                text =
                    if (
                        state.creationPhase ==
                        CreationPhase.GENERATING_PROXIES
                    ) {
                        "Creating..."
                    } else {
                        "Create Project"
                    },
                onClick = {
                    onEvent(
                        NewProjectEvent.CreateProject
                    )
                },
                enabled =
                    state.canCreateProject,
                emphasized = true
            )
        }
    }
}