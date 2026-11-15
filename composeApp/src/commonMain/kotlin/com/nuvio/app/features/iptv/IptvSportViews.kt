package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage

/**
 * fork:live-tv-sport-art - the pieces any Live TV page draws for a fixture: one side's badge or photo ([IptvCrest]) and
 * the two sides together ([IptvMatchup]). They take an [IptvSportArt] from [rememberSportArt] and keep no state, so a
 * restyled page only places them.
 */

private fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }.let { words ->
        if (words.size == 1) words[0].take(3) else words.take(3).joinToString("") { it.take(1) }
    }.uppercase()

/**
 * One side: a badge sits whole on a soft disc; a fighter's photo fills a circle from the top. With no picture yet, the
 * side's initials on a tint of its own name, so the space never jumps when the picture arrives.
 */
@Composable
fun IptvCrest(side: IptvSportSide, size: Dp, modifier: Modifier = Modifier) {
    val hue = ((side.name.hashCode() % 360) + 360) % 360
    val shape = if (side.person) CircleShape else RoundedCornerShape(size * 0.22f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (side.picture != null && !side.person) Color.White.copy(alpha = 0.06f) else Color.hsv(hue.toFloat(), 0.45f, 0.32f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val picture = side.picture
        if (picture != null) {
            NuvioAsyncImage(
                model = picture,
                contentDescription = side.name,
                modifier = if (side.person) Modifier.size(size) else Modifier.size(size).padding(size * 0.12f),
                contentScale = if (side.person) ContentScale.Crop else ContentScale.Fit,
            )
        } else {
            Text(
                initials(side.name),
                style = MaterialTheme.typography.labelSmall,
                fontSize = (size.value * 0.30f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
            )
        }
    }
}

/**
 * The two sides, badge "v" badge. [names] puts each side's name under its badge (for big art); without, the badges
 * stand alone (a tile already has the title).
 */
@Composable
fun IptvMatchup(art: IptvSportArt, crest: Dp, modifier: Modifier = Modifier, names: Boolean = false) {
    val sides = art.sides.take(2)
    if (sides.size < 2) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(crest * 0.22f)) {
        sides.forEachIndexed { i, side ->
            if (i == 1) {
                Text(
                    "v",
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = (crest.value * 0.26f).coerceAtLeast(11f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            if (names) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IptvCrest(side, crest)
                    Text(
                        side.name,
                        modifier = Modifier.width(crest * 1.6f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                IptvCrest(side, crest)
            }
        }
    }
}
