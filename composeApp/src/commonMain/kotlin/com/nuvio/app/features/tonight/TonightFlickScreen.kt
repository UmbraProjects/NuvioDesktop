package com.nuvio.app.features.tonight

import com.nuvio.app.core.ui.keyLayer
import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem

/*
 * fork:tonight - Channel Flick (phase 3): trailers back to back in one large frame. OK plays the
 * title, Right flicks to the next channel, Left never shows it again (Discover's Not interested),
 * Up saves it to the library for another night. A channel with no trailer is skipped by itself.
 */

@Composable
internal fun TonightFlickView(
    pool: List<TonightTitle>,
    taste: TonightTaste,
    clock: TonightClock,
    minutesUntilBed: Int,
    loading: Boolean,
    onPlay: (TonightTitle) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val order = remember(pool.isEmpty()) { tonightFlickOrder(pool, taste, clock.night.hashCode().toLong()) }
    val gone = remember { mutableStateListOf<String>() }
    val channels = order.filter { it.key !in gone }
    var index by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val focus = rememberScreenFocus(Unit)
    val current = if (channels.isEmpty()) null else channels[index % channels.size]

    fun next() {
        note = null
        if (channels.isNotEmpty()) index = (index + 1) % channels.size
    }

    fun never() {
        val title = current ?: return
        DiscoverRecommendationsRepository.markNotInterested(title.toMetaPreview())
        TonightPool.forget(title.key)
        gone += title.key
        note = "${title.name} won't come up again"
    }

    fun save() {
        val title = current ?: return
        if (TonightOrigin.Library !in title.origins) {
            LibraryRepository.addToAllLibraries(title.toMetaPreview().toLibraryItem(System.currentTimeMillis()))
        }
        note = "${title.name} saved for later"
        if (channels.isNotEmpty()) index = (index + 1) % channels.size
    }

    // fork:phone-remote tonight - ◀ never, OK play, ▲ save, ▶ next channel; up next as the other cards.
    MirrorOnPhone(
        com.nuvio.app.features.remote.RemoteTonightScreen(
            view = com.nuvio.app.features.remote.RemoteTonightScreen.FLICK,
            loading = loading && current == null,
            number = index + 1,
            total = channels.size,
            cards = (0..3).mapNotNull { i ->
                if (channels.isEmpty() || (i > 0 && channels.size <= i)) null
                else channels[(index + i) % channels.size].remoteCard(if (i == 0) "CH ${index + 1}" else "UP NEXT", "", clock, minutesUntilBed)
            },
            extra = note ?: current?.flickOriginLine(),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                when (key) {
                    TonightKey.Right -> next()
                    TonightKey.Left -> never()
                    TonightKey.Up -> save()
                    TonightKey.Ok -> current?.let(onPlay)
                    TonightKey.Back -> onBack()
                    TonightKey.Down -> Unit
                }
                true
            }
            .padding(horizontal = Side, vertical = 28.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CH " + (index + 1).toString().padStart(2, '0'),
                color = Color.White.copy(alpha = 0.85f), fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(150.dp),
            )
            Text("CHANNEL FLICK", color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text("UP NEXT", color = TextDim, fontSize = 13.sp, letterSpacing = 1.8.sp, modifier = Modifier.padding(end = 12.dp))
            // Always three fixed slots, empty ones dim, so the header never shifts.
            for (i in 1..3) {
                val upcoming = if (channels.size > i) channels[(index + i) % channels.size] else null
                val art = upcoming?.backdrop ?: upcoming?.poster
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(width = 72.dp, height = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .smoothBorder(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
                ) {
                    if (art != null) {
                        NuvioAsyncImage(model = art, contentDescription = upcoming?.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            val frameHeight = min(maxWidth * 9f / 16f, maxHeight - 150.dp)
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.width(frameHeight * 16f / 9f)) {
                if (current == null) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.width(frameHeight * 16f / 9f).height(frameHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (loading) "Still gathering tonight's titles…" else "No channels left tonight.",
                            color = TextDim, fontSize = 22.sp,
                        )
                    }
                } else {
                    TonightTrailerFrame(
                        title = current,
                        modifier = Modifier.width(frameHeight * 16f / 9f).height(frameHeight),
                        // A channel with no trailer leaves the line-up for this visit (not Not interested).
                        onFinished = { played -> if (played) next() else gone += current.key },
                        onReclaimFocus = { focus.requestFocusLogged() },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    note ?: current?.flickOriginLine().orEmpty(),
                    color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
                    maxLines = 1, modifier = Modifier.height(20.dp),
                )
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(
                        current?.name.orEmpty(), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(
                        current?.tonightMetaLine(clock, minutesUntilBed).orEmpty(),
                        color = TextDim, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    // The way back for the mouse now that the ✕ is gone (Back on the remote does the same).
                    TonightBackPill(onBack = onBack)
                    GlassPill("◀ Never show me this", selected = false, accent = accent, onClick = ::never, enabled = current != null)
                    GlassPill("OK That one, play it", selected = true, accent = accent, onClick = { current?.let(onPlay) }, enabled = current != null)
                    GlassPill("▲ Save for later", selected = false, accent = accent, onClick = ::save, enabled = current != null)
                    GlassPill("Next channel ▶", selected = false, accent = accent, onClick = ::next, enabled = channels.size > 1)
                }
            }
        }
    }
}
