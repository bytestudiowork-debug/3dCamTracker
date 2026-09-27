package com.trackstudio.app.ui.newproject.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.data.model.ColorSpace
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.common.DesktopTextField
import com.trackstudio.app.ui.common.Panel
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.NewProjectEvent
import com.trackstudio.app.viewmodel.NewProjectUiState

private val COMMON_FRAME_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)

@Composable
fun ProjectSettingsPanel(
    state: NewProjectUiState,
    onEvent: (NewProjectEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Panel(title = "Project Settings", modifier = modifier) {
        DesktopTextField(
            value = state.projectName,
            onValueChange = { onEvent(NewProjectEvent.ProjectNameChanged(it)) },
            label = "Project Name",
            modifier = Modifier.fillMaxWidth()
        )

        androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
        FrameRateDropdown(state, onEvent)

        androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
        Text(
            "Sensor Width (mm)",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Used later for focal-length-in-pixels conversion during tracking. 36mm = full-frame default.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        DesktopTextField(
            value = if (state.sensorWidthMm == 0.0) "" else state.sensorWidthMm.toString(),
            onValueChange = { text ->
                text.toDoubleOrNull()?.let { onEvent(NewProjectEvent.SensorWidthChanged(it)) }
            },
            label = "mm",
            modifier = Modifier.width(140.dp)
        )

        androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
        ColorSpaceDropdown(state, onEvent)
    }
}

@Composable
private fun FrameRateDropdown(state: NewProjectUiState, onEvent: (NewProjectEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Frame Rate", style = MaterialTheme.typography.bodyLarge)
        Box {
            DesktopButton(
                text = "${state.frameRate} fps",
                onClick = { expanded = true },
                modifier = Modifier.width(140.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                COMMON_FRAME_RATES.forEach { fps ->
                    DropdownMenuItem(
                        text = { Text("$fps fps") },
                        onClick = {
                            onEvent(NewProjectEvent.FrameRateChanged(fps))
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSpaceDropdown(state: NewProjectUiState, onEvent: (NewProjectEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Color Space", style = MaterialTheme.typography.bodyLarge)
        Box {
            DesktopButton(
                text = state.colorSpace.label,
                onClick = { expanded = true },
                modifier = Modifier.width(160.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ColorSpace.values().forEach { cs ->
                    DropdownMenuItem(
                        text = { Text(cs.label) },
                        onClick = {
                            onEvent(NewProjectEvent.ColorSpaceChanged(cs))
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
