package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.booklet.AlbumBooklet
import com.nuvio.app.features.music.booklet.BookletSource
import com.nuvio.app.features.music.booklet.cutAtSentence

// fork:essentials - the card under the album's case: what the album's notes say, in brief. It reads the same booklet the
// Now Playing stage shows (the AI's, or Wikipedia's when there is no key), so no new request is made for it.

/** The glass the page's cards share: dark, translucent, a hairline edge. */
@Composable
internal fun AlbumGlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Sparkle, the card's label in the accent, and an "AI" pill at the end when the AI wrote it. */
@Composable
internal fun BriefHeader(label: String, ai: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = colors.accent)
        Spacer(Modifier.weight(1f))
        if (ai) {
            Text(
                "AI",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The album in brief: the notes' opening, a few credits, and a line about the song playing ([playingNote]): the notes' own
 * word on it when they have one, else what is known of it (a single, how played, its place on the record). That slot has a
 * fixed height, so the card does not change size from song to song.
 */
@Composable
internal fun AlbumInBriefCard(booklet: AlbumBooklet, playingNote: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val credits = (
        booklet.credits.take(2).map { it.role to it.names } +
            listOfNotNull(booklet.guests.takeIf { it.isNotEmpty() }?.let { "Guests" to it.take(4).joinToString(", ") { guest -> guest.name } })
        ).take(3)
    AlbumGlassCard(modifier) {
        BriefHeader("IN BRIEF", ai = booklet.source == BookletSource.Ai)
        Text(cutAtSentence(booklet.intro, 300), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 8, overflow = TextOverflow.Ellipsis)
        if (credits.isNotEmpty()) {
            CardRule()
            credits.forEach { (role, names) ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(role, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, modifier = Modifier.width(84.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(names, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
        CardRule()
        Text("ABOUT THIS SONG", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = colors.textMuted)
        Box(Modifier.fillMaxWidth().height(60.dp)) {
            Text(
                playingNote ?: "Play a song to read about it.",
                style = MaterialTheme.typography.bodySmall,
                color = if (playingNote != null) colors.textPrimary else colors.textMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            when (booklet.source) {
                BookletSource.Ai -> "Written by AI, may contain mistakes"
                BookletSource.Wikipedia -> "Text from Wikipedia, CC BY-SA 4.0"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun CardRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
}
