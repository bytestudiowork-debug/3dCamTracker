package com.trackstudio.app.ui.newproject.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.common.LabeledValueRow
import com.trackstudio.app.ui.common.Panel
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.NewProjectEvent
import com.trackstudio.app.viewmodel.NewProjectUiState

@Composable
fun ProxyPanel(
    state: NewProjectUiState,
    onEvent: (NewProjectEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Panel(title = "Proxy Settings", modifier = modifier) {
        Text(
            "Proxies are lightweight preview copies generated once, on import - the original frames are never modified or duplicated at full size.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = state.proxySettings.enabled,
                onCheckedChange = { onEvent(NewProjectEvent.ProxyEnabledChanged(it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary)
            )
            Text(
                "Generate proxies for this project",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (state.proxySettings.enabled) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            ResolutionDropdown(state, onEvent)

            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            Text("JPEG Quality: ${state.proxySettings.jpegQuality}", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = state.proxySettings.jpegQuality.toFloat(),
                onValueChange = { onEvent(NewProjectEvent.ProxyQualityChanged(it.toInt())) },
                valueRange = 40f..100f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
            )

            if (state.footage.frameCount > 0) {
                val (w, h) = state.proxySettings.estimatedFrameDimensions(state.footage.width, state.footage.height)
                androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                Column {
                    LabeledValueRow("Proxy Resolution", "$w x $h")
                    LabeledValueRow("Proxy Frame Count", state.footage.frameCount.toString())
                }
            }
        }
    }
}

@Composable
private fun ResolutionDropdown(state: NewProjectUiState, onEvent: (NewProjectEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text("Proxy Resolution", style = MaterialTheme.typography.bodyLarge)
        androidx.compose.foundation.layout.Box {
            DesktopButton(
                text = state.proxySettings.resolution.label,
                onClick = { expanded = true },
                modifier = Modifier.width(140.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.availableProxyResolutions.forEach { resolution ->
                    DropdownMenuItem(
                        text = { Text(resolution.label) },
                        onClick = {
                            onEvent(NewProjectEvent.ProxyResolutionChanged(resolution))
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
