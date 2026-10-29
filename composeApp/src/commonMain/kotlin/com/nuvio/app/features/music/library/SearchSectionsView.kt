package com.nuvio.app.features.music.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio

/** HTPC search page, grouped results (drawing part; the order and indices are in SearchSectionsModel.kt). */

/** The jump chips above the scroll: one per section, the one on screen lit. */
@Composable
internal fun SectionChips(
    slots: List<SectionSlot>,
    current: SearchSection?,
    onJump: (SectionSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        items(slots, key = { it.section }) { slot ->
            FocusPill(chipLabel(slot), selected = slot.section == current, onClick = { onJump(slot) })
        }
    }
}

/** A section's label above its rows. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 28.sp,
        color = MaterialTheme.nuvio.colors.textPrimary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** A ladder offer as a full-width card: the old purple pill, large enough to aim at from the sofa. */
@Composable
internal fun LadderOfferCard(offer: LadderOffer, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = offer.label,
        fontSize = 26.sp,
        color = colors.onAccent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.accent)
            .then(if (focused) Modifier.border(3.dp, colors.textPrimary, shape) else Modifier)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** "See all 20 artists": switches to the tab that lists every result of the kind. */
@Composable
internal fun SeeAllRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = text,
        fontSize = 24.sp,
        color = colors.accent,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .then(if (focused) Modifier.border(3.dp, colors.accent, shape) else Modifier)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun FocusPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(22.dp)
    Text(
        text = text,
        fontSize = 22.sp,
        color = if (selected) colors.onAccent else colors.textPrimary,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.accent else colors.surfaceCard)
            .then(if (focused) Modifier.border(3.dp, colors.accent, shape) else Modifier)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
