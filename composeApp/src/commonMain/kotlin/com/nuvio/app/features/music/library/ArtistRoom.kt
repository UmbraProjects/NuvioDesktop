package com.nuvio.app.features.music.library

// The old artist page (the Room) was removed on Nov 12 2026; the new artist page (ArtistStage.kt) is the only one. What is
// left here is shared with it: the release sections, the filter rule and the filter panel.

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.features.details.components.DetailIconAction
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.details.components.DetailActionButtons
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.material.icons.rounded.LocalFireDepartment
import com.nuvio.app.core.ui.NuvioGlass
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.LocalMusicBarInset
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.identity.HeroSource
import com.nuvio.app.features.music.identity.ReleasePaletteResolver
import com.nuvio.app.features.music.identity.identity

/** The release grid's sections, in order, and the kinds each holds. */
internal val RELEASE_SECTIONS = listOf(
    "Albums" to setOf(ReleaseKind.Album),
    "Singles & EPs" to setOf(ReleaseKind.Single, ReleaseKind.EP),
    "Live & Compilations" to setOf(ReleaseKind.Live, ReleaseKind.Compilation),
    "Other" to setOf(ReleaseKind.Other),
)

internal fun ReleaseGroup.passes(playableOnly: Boolean, filter: ReleaseFilter, addonKeys: Set<String>): Boolean =
    (!playableOnly || isPlayableWith(addonKeys)) && matches(filter)

/** The one filter system: playable-only, plus the kind chips. Always shown, above the release grid. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterPanel(
    discography: ArtistDiscography,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    /** True once the music addon is known to have some releases: the note then says dimmed ones are on neither. */
    addonInPlay: Boolean = false,
    /** The new artist page's form: the pills centred on the page's own ground, no box behind them and no note. */
    bare: Boolean = false,
    /** The kind pills offered; null offers every one. */
    kinds: Set<ReleaseFilter>? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        if (bare) Modifier.fillMaxWidth() else Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = if (bare) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, if (bare) Alignment.CenterHorizontally else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentedPair(
                left = "Playable ${discography.playableCount}",
                right = "All ${discography.totalCount}",
                rightSelected = !playableOnly,
                onSelect = { all -> onPlayableOnly(!all) },
            )
            ReleaseFilter.entries.forEach { f ->
                if (f == ReleaseFilter.All) return@forEach
                if (kinds != null && f !in kinds) return@forEach
                val n = discography.filterCounts[f] ?: return@forEach
                if (n == discography.totalCount && f != filter) return@forEach
                ActionPill("${f.label} $n", selected = f == filter) { onFilter(if (f == filter) ReleaseFilter.All else f) }
            }
        }
        if (!bare) {
            Text(
                if (addonInPlay) "Dimmed releases aren't on YouTube Music or your music addon." else "Dimmed releases aren't on YouTube Music.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

