package com.trackstudio.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.PanelSurface
import com.trackstudio.app.ui.theme.TextSecondary

/**
 * A flat, hairline-bordered panel - the base unit of the desktop-style
 * layout. Sharp-ish corners (4dp) on purpose; this UI is explicitly not
 * going for Material's usual rounded-card look.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(PanelSurface, RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, PanelBorder), RoundedCornerShape(4.dp))
    ) {
        if (title != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF23252B), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            }
        }
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

/** Flat outlined button matching a desktop tool's toolbar buttons - no elevation, no fill by default. */
@Composable
fun DesktopButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(3.dp),
        colors = if (emphasized) {
            ButtonDefaults.outlinedButtonColors(containerColor = AccentPrimary.copy(alpha = 0.15f))
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
        border = BorderStroke(1.dp, if (emphasized) AccentPrimary else PanelBorder),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DesktopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        enabled = enabled,
        singleLine = singleLine,
        modifier = modifier,
        shape = RoundedCornerShape(3.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentPrimary,
            unfocusedBorderColor = PanelBorder
        ),
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

/** A compact label/value row used throughout the settings panels (e.g. "Resolution   3840x2160"). */
@Composable
fun LabeledValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
