package com.trackstudio.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.trackstudio.app.ui.theme.AccentBlue
import com.trackstudio.app.ui.theme.BgCard
import com.trackstudio.app.ui.theme.BgPanel
import com.trackstudio.app.ui.theme.DividerCol
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(BgPanel, RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, DividerCol), RoundedCornerShape(6.dp))
    ) {
        if (title != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgCard, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
            }
        }
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

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
        shape = RoundedCornerShape(4.dp),
        colors = if (emphasized) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = AccentBlue.copy(alpha = 0.16f),
                contentColor = TextPrimary
            )
        } else {
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = TextPrimary
            )
        },
        border = BorderStroke(1.dp, if (emphasized) AccentBlue else DividerCol),
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
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = DividerCol,
            focusedContainerColor = BgCard,
            unfocusedContainerColor = BgCard
        ),
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

@Composable
fun LabeledValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
    }
}
