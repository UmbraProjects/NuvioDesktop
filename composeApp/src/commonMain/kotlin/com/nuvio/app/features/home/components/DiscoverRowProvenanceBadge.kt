package com.nuvio.app.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discover.DiscoverRowProvenance
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.discover_row_badge_ai
import nuvio.composeapp.generated.resources.discover_row_badge_custom
import nuvio.composeapp.generated.resources.discover_row_badge_imported
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background

/**
 * A Discover row's title with its provenance badge beside it — plan §7, phase 7.
 *
 * Rendered through `NuvioShelfSection`'s existing `titleContent` slot rather than through a new
 * parameter on the shared shelf, because that slot already replaces the header title and is already
 * width-constrained in both the ordinary and the TV-mode header layouts. A second slot would have
 * had to be threaded through three call sites and would collide with the one TV Mode's row-jump
 * dots occupy.
 *
 * The title keeps the header's own type and truncation; the badge is a quiet outlined chip that
 * takes its space from the end of the title rather than from the row.
 */
@Composable
fun DiscoverRowTitleWithProvenance(
    title: String,
    provenance: DiscoverRowProvenance,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val label = when (provenance) {
        DiscoverRowProvenance.Ai -> stringResource(Res.string.discover_row_badge_ai)
        DiscoverRowProvenance.Imported -> stringResource(Res.string.discover_row_badge_imported)
        DiscoverRowProvenance.Custom -> stringResource(Res.string.discover_row_badge_custom)
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(
            modifier = Modifier.background(tokens.colors.accentFill(0.14f), tokens.shapes.chip),
            shape = tokens.shapes.chip,
            color = Color.Transparent,
            contentColor = tokens.colors.accent,
            border = BorderStroke(1.dp, tokens.colors.accentFill(0.34f)),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
