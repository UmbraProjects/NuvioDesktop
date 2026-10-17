package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

// Upstream's shared dialog building blocks, ported for the watch party screens. The fork's own
// dialogs use NuvioDialogSurface / NuvioModalDialog; these stay for party code to match upstream.

/** Upstream's Gradients.SurfaceEdge, which this fork does not have. */
private val DialogSurfaceEdge = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f)),
)

enum class DialogButtonStyle {
    Primary,
    Secondary,
    Destructive,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogSurface(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BasicAlertDialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = tokens.components.dialogMaxWidth),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
            border = BorderStroke(tokens.borders.thin, DialogSurfaceEdge),
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                    )
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                    )
                }
                content()
            }
        }
    }
}

@Composable
fun DialogButtons(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = NuvioTokens.Space.s8),
    ) { measurables, constraints ->
        val gap = NuvioTokens.Space.s10.roundToPx()
        val width = constraints.maxWidth
        val totalGap = gap * (measurables.size - 1).coerceAtLeast(0)
        val itemWidth = (width - totalGap) / measurables.size.coerceAtLeast(1)
        if (measurables.all { it.maxIntrinsicWidth(constraints.maxHeight) <= itemWidth }) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(itemWidth)) }
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(index * (itemWidth + gap), 0)
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }.asReversed()
            layout(width, placeables.sumOf { it.height } + totalGap) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DialogButtonStyle = DialogButtonStyle.Secondary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val containerColor = when (style) {
        DialogButtonStyle.Primary -> tokens.colors.accent
        DialogButtonStyle.Secondary -> tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover)
        DialogButtonStyle.Destructive -> tokens.colors.danger
    }
    val contentColor = if (style == DialogButtonStyle.Secondary) tokens.colors.textPrimary else tokens.colors.onAccent
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = NuvioTokens.Space.s48),
        enabled = enabled && !loading,
        elevation = ButtonDefaults.buttonElevation(
            hoveredElevation = 0.dp,
        ),
        shape = tokens.shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = containerColor.alpha * tokens.opacity.disabled),
            disabledContentColor = contentColor.copy(alpha = tokens.opacity.disabled),
        ),
        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(tokens.icons.sm),
                color = contentColor,
                strokeWidth = NuvioTokens.Border.medium,
            )
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s8))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
