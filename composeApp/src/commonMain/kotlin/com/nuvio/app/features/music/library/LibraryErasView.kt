package com.nuvio.app.features.music.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio

/**
 * Library views, phase E1: the Eras view's ruler and header. One bar per era, its height the number of
 * releases and its colour the lead release's palette; the selected era is lit and named in the header.
 * Left/Up and Right/Down step through the eras, Escape goes back to the newest. The era's covers are
 * the next phase (E2); this file only draws what [buildLibraryEras] produced.
 *
 * Hosted by [MusicLibraryShelf] behind one `// fork:music` switch. [yearAgo] is the shelf's "a year ago this
 * week" strip, passed in as a slot so it stays above the ruler without being copied.
 */
@Composable
internal fun LibraryErasView(
    artists: List<LibraryArtist>,
    pending: List<String>,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    yearAgo: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val eras = remember(artists) { buildLibraryEras(artists, ErasAxis.Released) }
    // null means "not chosen": the newest era with something in it. Saved, so coming back lands where the person left.
    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
    val selected = chosen?.takeIf { it in eras.eras.indices } ?: eras.defaultEraIndex()
    val era = eras.eras.getOrNull(selected)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    LazyColumn(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionUp, Key.DirectionRight, Key.DirectionDown -> {
                        if (eras.eras.isEmpty()) return@onPreviewKeyEvent false
                        val back = event.key == Key.DirectionLeft || event.key == Key.DirectionUp
                        chosen = stepEra(eras.eras, selected, if (back) -1 else 1).takeIf { it >= 0 }
                        true
                    }
                    Key.Escape -> {
                        val had = chosen != null
                        chosen = null
                        had
                    }
                    else -> false
                }
            },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (yearAgo != null) item(key = "year-ago") { yearAgo() }
        pending.forEachIndexed { i, status ->
            item(key = "pending:$i") {
                Text("Adding… $status", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        if (eras.eras.isEmpty()) {
            item(key = "no-ruler") {
                Text(
                    "Nothing in your library has a release date yet, so there is no timeline to draw.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            item(key = "ruler") {
                Row(
                    Modifier.fillMaxWidth().height(RULER_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    eras.eras.forEach { e ->
                        EraBar(
                            era = e,
                            maxCount = eras.maxReleaseCount,
                            selected = e.index == selected,
                            reducedMotion = reducedMotion,
                            onClick = { chosen = e.index },
                            // An empty band is a gap in the library: kept, but drawn thin.
                            modifier = Modifier.weight(if (e.isEmpty) 0.35f else 1f),
                        )
                    }
                }
            }
            if (era != null) {
                item(key = "header") {
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            eraHeader(era),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        era.lead?.let { lead ->
                            Text(
                                "Led by ${lead.release.title} · ${lead.artist.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (eras.undated.isNotEmpty()) {
            item(key = "undated") {
                val n = eras.undated.size
                Text(
                    "$n undated release${if (n == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private val RULER_HEIGHT = 112.dp

/** One era on the ruler: its release count over a bar, the first year under it. */
@Composable
private fun EraBar(
    era: LibraryEra,
    maxCount: Int,
    selected: Boolean,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val tint = era.lead?.release?.palette?.let { Color(it.accent) } ?: colors.accent
    val glow by animateFloatAsState(if (selected) 1f else 0.45f, if (reducedMotion) snap() else tween(200), label = "eraBar")
    val fraction = if (maxCount == 0) 0f else era.releaseCount / maxCount.toFloat()
    val barHeight = if (era.isEmpty) 2.dp else (8f + 56f * fraction).dp
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
    ) {
        if (!era.isEmpty) {
            Text("${era.releaseCount}", fontSize = 10.sp, color = if (selected) colors.textPrimary else colors.textMuted, maxLines = 1)
        }
        Column(
            Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(4.dp)).background(tint.copy(alpha = glow)),
        ) {}
        Text(
            "${era.fromYear}",
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.textPrimary else colors.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}
