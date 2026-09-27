```kotlin
package com.trackstudio.app.ui.newproject.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.PanelSurfaceRaised
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.NewProjectEvent
import com.trackstudio.app.viewmodel.NewProjectUiState

@Composable
fun ProxyPanel(
    state: NewProjectUiState,
    onEvent: (NewProjectEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Panel(
        title = "Proxy Settings",
        modifier = modifier
    ) {
        Text(
            text = "Proxies are lightweight preview copies generated once, on import - " +
                "the original frames are never modified or duplicated at full size.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(Modifier.height(14.dp))

        ProxyEnableControl(
            enabled = state.proxySettings.enabled,
            onToggle = {
                onEvent(
                    NewProjectEvent.ProxyEnabledChanged(
                        !state.proxySettings.enabled
                    )
                )
            }
        )

        if (state.proxySettings.enabled) {
            Spacer(Modifier.height(14.dp))

            ResolutionDropdown(
                state = state,
                onEvent = onEvent
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "JPEG Quality: ${state.proxySettings.jpegQuality}",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )

            Slider(
                value = state.proxySettings.jpegQuality.toFloat(),
                onValueChange = {
                    onEvent(
                        NewProjectEvent.ProxyQualityChanged(
                            it.toInt()
                        )
                    )
                },
                valueRange = 40f..100f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = AccentPrimary,
                    activeTrackColor = AccentPrimary
                )
            )

            if (state.footage.frameCount > 0) {
                val (w, h) =
                    state.proxySettings.estimatedFrameDimensions(
                        state.footage.width,
                        state.footage.height
                    )

                Spacer(Modifier.height(6.dp))

                Column {
                    LabeledValueRow(
                        "Proxy Resolution",
                        "$w x $h"
                    )

                    LabeledValueRow(
                        "Proxy Frame Count",
                        state.footage.frameCount.toString()
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyEnableControl(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val background = if (enabled) {
        AccentPrimary.copy(alpha = 0.10f)
    } else {
        PanelSurfaceRaised
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = background,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = if (enabled) {
                    AccentPrimary
                } else {
                    PanelBorder
                },
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onToggle)
            .padding(
                horizontal = 14.dp,
                vertical = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = enabled,
            onCheckedChange = {
                onToggle()
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentPrimary,
                checkedTrackColor = AccentPrimary.copy(alpha = 0.35f)
            )
        )

        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        ) {
            Text(
                text = if (enabled) {
                    "Proxy Generation Enabled"
                } else {
                    "Proxy Generation Disabled"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = if (enabled) {
                    "Lightweight preview frames will be generated."
                } else {
                    "Original frames will be used directly."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ResolutionDropdown(
    state: NewProjectUiState,
    onEvent: (NewProjectEvent) -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    Column {
        Text(
            text = "Proxy Resolution",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary
        )

        Spacer(Modifier.height(4.dp))

        Box {
            DesktopButton(
                text = state.proxySettings.resolution.label,
                onClick = {
                    expanded = true
                },
                modifier = Modifier.width(140.dp)
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {
                state.availableProxyResolutions.forEach { resolution ->
                    DropdownMenuItem(
                        text = {
                            Text(resolution.label)
                        },
                        onClick = {
                            onEvent(
                                NewProjectEvent.ProxyResolutionChanged(
                                    resolution
                                )
                            )
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
```
