package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The house dialog panel: rounded, softly lit from above, with a hairline edge and a real shadow.
 *
 * This is the drop-in replacement for the flat `Surface(shape = RoundedCornerShape(20.dp), color =
 * surface)` that every `BasicAlertDialog` in the app used to wrap its content in. It keeps Material
 * `Surface` underneath — rather than a bare `Box` — so `LocalContentColor` still resolves the way
 * callers expect and unstyled `Text` inside a dialog does not silently change colour.
 */
@Composable
fun NuvioDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(DIALOG_CORNER),
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Surface(
        modifier = modifier
            .shadow(elevation = 32.dp, shape = shape, clip = false)
            .clip(shape)
            // Flat fill, deliberately. This used to be a top-down sheen, but a ~5% lighten spread
            // over the full height of a dialog only spans a handful of 8-bit levels, so each step
            // landed as a band tens of pixels tall — clearly visible on a dark panel. Depth comes
            // from the shadow and hairline border instead. Don't reintroduce a gradient here
            // without dithering it; at this contrast it will band again.
            .background(colors.surfaceDialog)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.08f), shape = shape),
        shape = shape,
        // The gradient above is the fill; Surface only carries shape + content colour from here.
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

/**
 * The house modal shell: [NuvioDialogSurface] plus a titled header, a scroll-safe body and a
 * right-aligned action strip.
 *
 * Replaces bare `AlertDialog` usage, whose flat Material default reads dated against the rest of
 * the app. Intended to be shared by every popup panel of this shape, so keep it presentational —
 * no feature state, no per-screen conditionals.
 */
@Composable
fun NuvioModalDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxWidth: androidx.compose.ui.unit.Dp = 620.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(DIALOG_CORNER)

    Dialog(
        onDismissRequest = onDismissRequest,
        // The Material default clamps modals to a narrow platform width, which squeezes content
        // like the episode grid into an unreadable column.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NuvioDialogSurface(modifier = modifier.widthIn(max = maxWidth), shape = shape) {
          Column {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            DialogHairline()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )

            DialogHairline()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                actions()
            }
          }
        }
    }
}

/**
 * Signature-compatible stand-in for Material's `AlertDialog`, rendered on [NuvioDialogSurface].
 *
 * Exists so the app's confirm/prompt dialogs can move onto the house panel by swapping the call
 * name — their `title`/`text`/`confirmButton` slots keep working unchanged.
 */
@Composable
fun NuvioAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    maxWidth: androidx.compose.ui.unit.Dp = 560.dp,
) {
    val shape = RoundedCornerShape(DIALOG_CORNER)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NuvioDialogSurface(modifier = modifier.widthIn(max = maxWidth), shape = shape) {
            Column {
                title?.let {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
                    ) {
                        ProvideDialogTitleStyle(it)
                    }
                    DialogHairline()
                }
                text?.let {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                    ) {
                        it()
                    }
                    DialogHairline()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/**
 * Material's AlertDialog styles its title slot for the caller. Callers migrating here pass a plain
 * `Text`, so apply the same treatment rather than making every site restate it.
 */
@Composable
private fun ProvideDialogTitleStyle(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.nuvio.colors.textPrimary,
        ),
        content = content,
    )
}

@Composable
private fun DialogHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.06f)),
    )
}

private val DIALOG_CORNER = 20.dp
