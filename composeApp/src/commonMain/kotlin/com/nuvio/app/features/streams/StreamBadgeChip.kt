package com.nuvio.app.features.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlin.math.round
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_size
import nuvio.composeapp.generated.resources.unit_bytes_gb
import nuvio.composeapp.generated.resources.unit_bytes_mb
import org.jetbrains.compose.resources.stringResource

private const val STREAM_SIZE_PLACEHOLDER = ""

/**
 * Formats a file size into the badge text ("SIZE 4.2 GB"). Stream lists resolve it once and provide
 * it here, so a list of two hundred rows does not look the same three resources up once per row —
 * on desktop each lookup is a blocking read of the packaged resource file.
 */
internal val LocalStreamSizeLabelFormat = staticCompositionLocalOf<((Long) -> String)?> { null }

@Composable
internal fun rememberStreamSizeLabelFormat(): (Long) -> String {
    val template = stringResource(Res.string.streams_size, STREAM_SIZE_PLACEHOLDER)
    val gbUnit = stringResource(Res.string.unit_bytes_gb)
    val mbUnit = stringResource(Res.string.unit_bytes_mb)
    return remember(template, gbUnit, mbUnit) {
        { bytes ->
            val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            val sizeLabel = if (gib >= 1.0) {
                "${round(gib * 10.0) / 10.0} $gbUnit"
            } else {
                "${round(bytes.toDouble() / (1024.0 * 1024.0)).toInt()} $mbUnit"
            }
            template.replace(STREAM_SIZE_PLACEHOLDER, sizeLabel)
        }
    }
}

internal object StreamBadgeChipDefaults {
    val shape = RoundedCornerShape(NuvioTokens.Radius.sm)
    val fileSizeHorizontalPadding = NuvioTokens.Space.s6
    val fileSizeFontSize: TextUnit = NuvioTokens.Type.labelSm
    val fileSizeLineHeight: TextUnit = NuvioTokens.LineHeight.bodySm
    val fileSizeLetterSpacing: TextUnit = NuvioTokens.LetterSpacing.none
}

internal enum class StreamBadgeChipSize(
    val containerHeight: Dp,
    val imageHeight: Dp,
    val minImageWidth: Dp,
    val maxImageWidth: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
) {
    STREAM(
        containerHeight = 20.dp,
        imageHeight = 16.dp,
        minImageWidth = 34.dp,
        maxImageWidth = 92.dp,
        horizontalPadding = 3.dp,
        verticalPadding = 2.dp,
    ),
    PREVIEW(
        containerHeight = 24.dp,
        imageHeight = 18.dp,
        minImageWidth = 38.dp,
        maxImageWidth = 112.dp,
        horizontalPadding = 4.dp,
        verticalPadding = 3.dp,
    ),
}

@Composable
internal fun StreamBadgeChip(
    imageURL: String,
    name: String,
    tagColor: String,
    tagStyle: String,
    borderColor: String,
    size: StreamBadgeChipSize,
    modifier: Modifier = Modifier,
) {
    val backgroundColorArgb = if (tagStyle.equals("filled", ignoreCase = true)) {
        tagColor.toBadgeColorArgbOrNull()
    } else {
        null
    }
    val outlineColorArgb = borderColor.toBadgeColorArgbOrNull()
    val shape = StreamBadgeChipDefaults.shape
    var chipModifier = modifier.height(size.containerHeight)
    if (backgroundColorArgb != null) {
        chipModifier = chipModifier.background(Color(backgroundColorArgb), shape)
    }
    if (outlineColorArgb != null) {
        chipModifier = chipModifier.border(NuvioTokens.Border.thin, Color(outlineColorArgb), shape)
    }

    Box(
        modifier = chipModifier
            .padding(horizontal = size.horizontalPadding, vertical = size.verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageURL,
            contentDescription = name,
            modifier = Modifier
                .height(size.imageHeight)
                .widthIn(min = size.minImageWidth, max = size.maxImageWidth)
                .clip(shape),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
internal fun StreamBadgeImage(
    badge: StreamBadge,
    size: StreamBadgeChipSize = StreamBadgeChipSize.STREAM,
) {
    StreamBadgeChip(
        imageURL = badge.imageURL,
        name = badge.name,
        tagColor = badge.tagColor,
        tagStyle = badge.tagStyle,
        borderColor = badge.borderColor,
        size = size,
    )
}

@Composable
internal fun StreamFileSizeBadge(stream: StreamItem) {
    val tokens = MaterialTheme.nuvio
    val bytes = stream.behaviorHints.videoSize ?: return
    val formatSize = LocalStreamSizeLabelFormat.current ?: rememberStreamSizeLabelFormat()

    val badgeShape = StreamBadgeChipDefaults.shape
    Box(
        modifier = Modifier
            .height(StreamBadgeChipSize.STREAM.containerHeight)
            .clip(badgeShape)
            .background(tokens.colors.surfacePopover)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, badgeShape)
            .padding(horizontal = StreamBadgeChipDefaults.fileSizeHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatSize(bytes),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = StreamBadgeChipDefaults.fileSizeFontSize,
                lineHeight = StreamBadgeChipDefaults.fileSizeLineHeight,
                fontWeight = FontWeight.Bold,
                letterSpacing = StreamBadgeChipDefaults.fileSizeLetterSpacing,
            ),
            color = tokens.colors.textPrimary,
        )
    }
}

/**
 * A pack colour as CSS for the player HUD. Packs write 8-digit colours as `#AARRGGBB` (Android's
 * order), which CSS would read as `#RRGGBBAA`: `#FF0D47A1`, a blue, came out red.
 */
internal fun String.toBadgeCssColorOrNull(): String? {
    val argb = toBadgeColorArgbOrNull() ?: return null
    val alpha = ((argb shr 24) and 0xFF) / 255.0
    return "rgba(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}, ${(alpha * 1000).toInt() / 1000.0})"
}

/** The chip fill a pack asked for, or null: only a "filled" tag has one. */
internal fun badgeFillCssColorOrNull(tagColor: String, tagStyle: String): String? =
    if (tagStyle.equals("filled", ignoreCase = true)) tagColor.toBadgeCssColorOrNull() else null

private fun String.toBadgeColorArgbOrNull(): Long? {
    val hex = trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)
}
