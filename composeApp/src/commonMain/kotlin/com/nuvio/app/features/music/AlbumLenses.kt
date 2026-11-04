package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.booklet.SongStory
import com.nuvio.app.features.music.booklet.cutAtSentence
import com.nuvio.app.features.music.library.LibraryAlbumInfo
import com.nuvio.app.features.music.library.SongVersion
import com.nuvio.app.features.music.library.VersionCounts
import com.nuvio.app.features.music.library.LensStoryText
import com.nuvio.app.features.music.library.lensStoryText
import com.nuvio.app.features.music.library.wholeSentences
import com.nuvio.app.features.music.library.musicKeyFocusRing

/*
 * fork:lenses - the redesigned album page (Music settings, "New album page"). The cover sits small at the left of the header,
 * mirrored by the artist on the right; under the buttons a row of tabs (lenses) changes what the two side panels show. The
 * two panels are the same width, the same structure row for row, and every box in them has a fixed size: what a lens writes
 * fills a box, it never moves or resizes one. Only Song by song is built; the other lenses show a "Coming soon" panel pair.
 */

/** The lenses, in tab order. [built] lenses have panels; the rest show "Coming soon". */
internal enum class AlbumLens(val label: String, val built: Boolean) {
    SongBySong("Song by song", true),
    History("Your history", false),
    CutToFit("Cut to fit", true),
    SonicMap("Sonic map", false),
    Lineage("Lineage", false),
    Ask("Ask", false),
}

/** The tint of what comes from the person's own library, opposite the accent's AI side. */
internal val LENS_LIBRARY_TINT = Color(0xFFC08AF0)

/** The side panels' width for a window [width]: 440 dp at 1920 dp, never under 360 dp, and leaving the middle at least 640 dp. */
internal fun lensPanelWidth(width: Dp): Dp {
    val room = (width - LENS_OUTER * 2 - LENS_GAP * 2 - 640.dp) / 2
    return room.coerceIn(360.dp, 440.dp)
}

/** The window's edge to each panel, and each panel to the middle: the same on both sides. */
internal val LENS_OUTER = 40.dp
internal val LENS_GAP = 40.dp

/** The height of the box that holds the album's title block: fixed, so nothing under it moves from album to album. */
private val LENS_HEADER_HEIGHT = 176.dp
private val LENS_HEADER_ART = 104.dp

/**
 * The header: the cover at the left, the kicker, title, artist line and genres centred, the artist's photo at the right,
 * both the same size and the same distance from the edges.
 */
@Composable
internal fun LensAlbumHeader(album: MusicAlbum, info: LibraryAlbumInfo?, onArtistClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val artistId = album.artists.firstOrNull()?.browseId
    val minutes = album.tracks.sumOf { it.durationSeconds } / 60
    val kicker = listOfNotNull(
        album.kind,
        album.year,
        "${album.tracks.size} track${if (album.tracks.size == 1) "" else "s"}",
        "$minutes min".takeIf { minutes > 0 },
    ).joinToString(" · ").uppercase()
    val notes = buildLinerNotes(info?.release, album.year)
    val credit = listOfNotNull(notes.label, notes.date, "Explicit".takeIf { notes.explicit }).joinToString(" · ")
    val chips = (listOfNotNull(notes.mood) + notes.genres).take(4)
    Row(modifier.fillMaxWidth().height(LENS_HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        MusicCover(album.thumbnailUrl, album.title, size = LENS_HEADER_ART)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kicker, style = MaterialTheme.typography.labelLarge, color = colors.accent, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, maxLines = 1)
            Text(
                album.title,
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Serif,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    album.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (artistId != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (artistId != null) {
                        Modifier.clip(RoundedCornerShape(8.dp)).musicKeyFocusRing(RoundedCornerShape(8.dp)).clickable { onArtistClick(artistId) }.padding(horizontal = 4.dp)
                    } else Modifier,
                )
                if (credit.isNotEmpty()) {
                    Text("· $credit", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEachIndexed { i, chip ->
                        Text(
                            chip,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (i == 0 && notes.mood != null) colors.accent else colors.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        // The artist's photo, the cover's mirror: a circle of the same size, a letter when there is no photo.
        val thumb = info?.artist?.art?.thumb
        Box(
            Modifier.size(LENS_HEADER_ART).clip(CircleShape).background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .then(if (artistId != null) Modifier.musicKeyFocusRing(CircleShape).clickable { onArtistClick(artistId) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) MusicCover(thumb, description = album.artists.firstOrNull()?.name, size = LENS_HEADER_ART, circle = true)
            else Text(
                album.artists.firstOrNull()?.name?.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Serif,
                color = colors.textSecondary,
            )
        }
    }
}

/** The lens tabs: one glass pill, equal slots, the chosen one in the accent. Left and Right move between them. */
@Composable
internal fun AlbumLensTabs(selected: AlbumLens, onSelect: (AlbumLens) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val pill = RoundedCornerShape(24.dp)
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(pill).background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), pill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AlbumLens.entries.forEach { lens ->
            val on = lens == selected
            val tab = RoundedCornerShape(20.dp)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(tab)
                    .background(if (on) colors.accent.copy(alpha = 0.20f) else Color.Transparent)
                    .musicKeyFocusRing(tab)
                    .semantics { role = Role.Tab; this.selected = on }
                    .clickable { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lens.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One side panel's glass: the same on both sides, filling its column's height (window size only, never content). */
@Composable
internal fun LensPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxHeight().clip(shape).background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape).padding(24.dp),
        content = content,
    )
}

/** A panel's first line: icon and label in [tint], and a pill naming where the panel's words come from. */
@Composable
private fun LensPanelHeader(icon: ImageVector, label: String, pill: String?, tint: Color) {
    val colors = MaterialTheme.nuvio.colors
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = tint, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (pill != null) {
            Text(
                pill,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** The panel's title and the line under it, each one line high whatever they say. */
@Composable
private fun LensPanelTitle(title: String, subtitle: String) {
    val colors = MaterialTheme.nuvio.colors
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
}

/** One fact tile's content: a short value, or a bar (0..1) when [bar] is set. */
internal class LensFact(val label: String, val value: String, val bar: Float? = null)

/** Four facts in two rows of two equal tiles, 76 dp high. */
@Composable
private fun LensFactGrid(tiles: List<LensFact>) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { LensFactTile(it) } }
        }
    }
}

@Composable
private fun RowScope.LensFactTile(fact: LensFact) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.weight(1f).height(76.dp).clip(shape).background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(fact.label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
            val bar = fact.bar
            if (bar != null) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(bar.coerceIn(0f, 1f)).background(colors.accent))
                }
            } else {
                Text(fact.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A section label with a note at its end, on one line. */
@Composable
private fun LensSectionLabel(label: String, note: String) {
    val colors = MaterialTheme.nuvio.colors
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = colors.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
        Text(note, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
    }
}

/** The small card above the panel's button: fixed height, a label in [tint] and up to three lines. */
@Composable
private fun LensNoteCard(label: String, text: String, tint: Color, footer: String?) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().height(LENS_NOTE_HEIGHT).clip(shape).background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = tint, maxLines = 1)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
    }
}

private val LENS_NOTE_HEIGHT = 136.dp

/** The panel's last line: one wide glass pill, the same on both sides, edged in the side's [tint]. */
@Composable
private fun LensButton(label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.padding(top = 16.dp).fillMaxWidth().height(56.dp).clip(shape)
            .background(tint.copy(alpha = if (enabled) 0.16f else 0.05f))
            .border(1.dp, tint.copy(alpha = if (enabled) 0.55f else 0.18f), shape)
            .musicKeyFocusRing(shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.textPrimary else colors.textDisabled, maxLines = 1)
    }
}

/** The big box in the middle of a panel: it takes what the window leaves, so its size depends on the window only. */
@Composable
private fun ColumnScope.LensBody(tint: Color, highlighted: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().weight(1f).clip(shape)
            .background(if (highlighted) tint.copy(alpha = 0.07f) else Color.Transparent)
            .then(if (highlighted) Modifier.border(1.dp, tint.copy(alpha = 0.3f), shape) else Modifier)
            .padding(if (highlighted) 20.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** What the left panel needs to know about the song in focus. */
internal class LensSong(
    val title: String,
    val trackNumber: Int,
    val trackCount: Int,
    val durationSeconds: Int,
    val single: Boolean,
    /** 0..1 next to the artist's other songs, null when Deezer does not rank it. */
    val popularity: Float?,
    /** Its place among the artist's most played songs, null when it is not among them. */
    val rank: Int?,
    val artistName: String,
)

/** A line about the song from the album's own notes: the AI's (one request for the album) or MusicBrainz's credits. */
internal class LensNote(val text: String, val ai: Boolean)

/** A count tile's value: "None" rather than a bare 0, which reads like a fault. */
private fun countText(n: Int): String = if (n == 0) "None" else n.toString()

/**
 * Song by song, left: the song in focus, its four facts, and its story. The story is the song's Wikipedia article (written
 * up by the AI when a key is set); a song with no article gets the album's own note on it instead, so the box is never empty.
 */
@Composable
internal fun SongBySongLeft(song: LensSong?, story: SongStory?, note: LensNote?, storyLoading: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    // The box gets whole sentences only: how it was made, then what it means when both fit (never a cut mid-quote).
    val told = lensStoryText(story?.made, story?.meaning) ?: story?.after?.let { LensStoryText(wholeSentences(it), usedMeaning = false) }
    val main = told?.text
    // Where the words in this panel come from, always named (the right panel always names its own).
    val source = when {
        main != null && story?.ai == true -> "AI"
        main != null -> "Wikipedia"
        note != null && note.ai -> "AI"
        note != null -> "MusicBrainz"
        else -> "Library"
    }
    LensPanel(modifier) {
        LensPanelHeader(Icons.Rounded.AutoAwesome, "ABOUT THIS SONG", pill = source, tint = colors.accent)
        LensPanelTitle(song?.title ?: "Pick a song", song?.let { "Track ${it.trackNumber} on this album" } ?: "Move down the track list")
        LensFactGrid(
            listOf(
                LensFact("Length", song?.durationSeconds?.takeIf { it > 0 }?.let { formatMusicTime(it * 1000L) } ?: "–"),
                LensFact("Position", song?.let { "${it.trackNumber} of ${it.trackCount}" } ?: "–"),
                LensFact("Released as a single", when (song?.single) { true -> "Yes"; false -> "No"; null -> "–" }),
                LensFact("Popularity", if (song != null) "Not ranked" else "–", bar = song?.popularity),
            ),
        )
        LensSectionLabel(
            "THE STORY",
            when {
                main != null && story?.ai == true -> "Written from Wikipedia"
                main != null -> "From Wikipedia"
                note != null && note.ai -> "From the album's notes"
                note != null -> "From MusicBrainz"
                storyLoading -> "Looking…"
                else -> ""
            },
        )
        val body = main ?: note?.text
        LensBody(colors.accent, highlighted = true) {
            Text(
                body ?: when {
                    song == null -> "Move down the track list to read about each song."
                    storyLoading -> "Looking for this song's story…"
                    else -> "Nothing has been written about this song yet."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (body != null) colors.textPrimary else colors.textMuted,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            story?.quotes?.firstOrNull()?.let { q ->
                Text(
                    "“${q.text}”" + q.who.takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The small card: the article's other half when there is one, else the album's note (when the story box took the
        // article), else what is known of the song.
        val afterInCard = story?.let { st -> st.after?.takeIf { st.made != null || st.meaning != null } }?.let(::wholeSentences)
        val meaningInCard = story?.meaning?.takeIf { told?.usedMeaning == false }?.let(::wholeSentences)
        val second = afterInCard ?: meaningInCard ?: note?.text?.takeIf { main != null }
        val fallback = song?.let { s ->
            listOfNotNull(
                s.rank?.let { "No. $it of ${s.artistName}'s most played songs." },
                "Released as a single.".takeIf { s.single },
            ).joinToString(" ").ifEmpty { null }
        }
        Spacer(Modifier.height(16.dp))
        LensNoteCard(
            label = when {
                second != null && second == afterInCard -> "WHAT CAME AFTER"
                second != null && second == meaningInCard -> "WHAT IT MEANS"
                else -> "WHY IT MATTERS"
            },
            text = second ?: fallback ?: "Nothing more is known about this song yet.",
            tint = colors.accent,
            footer = when {
                main != null && story?.ai == true -> "Written by AI, may contain mistakes"
                main != null -> "Text from Wikipedia, CC BY-SA 4.0"
                note != null && note.ai -> "Written by AI, may contain mistakes"
                else -> null
            },
        )
        LensButton("Play the song", tint = colors.accent, enabled = song != null, onClick = onPlay)
    }
}

/** A song offered when the one in focus has no other copies: the artist's best songs, so the box always plays something. */
internal class LensSuggestion(val title: String, val where: String, val tag: String, val onClick: (() -> Unit)?)

/**
 * Song by song, right: every other copy of the song found in the album, the singles and the artist's other records. A song
 * with none gets the artist's best songs in the same box instead, so the panel is never four zeros over nothing.
 */
@Composable
internal fun SongBySongRight(
    title: String?,
    versions: List<SongVersion>,
    counts: VersionCounts,
    suggestions: List<LensSuggestion>,
    onVersion: (SongVersion) -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val none = versions.isEmpty()
    LensPanel(modifier) {
        LensPanelHeader(Icons.Rounded.LibraryMusic, "IN YOUR LIBRARY", pill = "Library", tint = LENS_LIBRARY_TINT)
        LensPanelTitle("Other versions", title?.let { "Every copy of $it we found" } ?: "Pick a song to find its other copies")
        LensFactGrid(
            listOf(
                LensFact("Versions found", countText(counts.found)),
                LensFact("On this album", countText(counts.onThisAlbum)),
                LensFact("On other records", countText(counts.elsewhere)),
                LensFact("Remixes and demos", countText(counts.reworks)),
            ),
        )
        LensSectionLabel(
            if (none && suggestions.isNotEmpty()) "NONE FOUND · TRY THESE" else "FOUND",
            when {
                !none && counts.onThisAlbum > 0 -> "Marked in the list"
                none && suggestions.isNotEmpty() -> "The artist's best songs"
                else -> ""
            },
        )
        LensBody(LENS_LIBRARY_TINT, highlighted = false) {
            when {
                !none -> versions.forEach { v -> VersionRow(v, onClick = { onVersion(v) }) }
                suggestions.isNotEmpty() -> suggestions.forEach { s -> LensRow(s.title, s.where, s.tag, s.onClick) }
                else -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (title == null) "Move down the track list." else "No other versions of this song are in your library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        LensNoteCard(
            label = "HOW THESE WERE MATCHED",
            text = "By the song's title with its brackets left out, across this album, the artist's singles and the records Deezer lists it on.",
            tint = LENS_LIBRARY_TINT,
            footer = null,
        )
        LensButton("Queue the versions here", tint = LENS_LIBRARY_TINT, enabled = counts.onThisAlbum > 0, onClick = onQueue)
    }
}

/** One found copy: where it is and its kind. */
@Composable
private fun VersionRow(v: SongVersion, onClick: () -> Unit) {
    val where = when {
        v.onThisAlbum -> listOfNotNull("This album · track ${v.trackIndex!! + 1}", v.durationSeconds.takeIf { it > 0 }?.let { formatMusicTime(it * 1000L) }).joinToString(" · ")
        else -> v.recordTitle ?: "Another record"
    }
    LensRow(v.title, where, v.kind.label, onClick.takeIf { v.onThisAlbum || v.release?.ytBrowseId != null })
}

/** A 64 dp glass row in the library tint: a title, a line under it, and a tag at the end. Not clickable without [onClick]. */
@Composable
private fun LensRow(title: String, where: String, tag: String, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(shape).background(LENS_LIBRARY_TINT.copy(alpha = 0.07f))
            .border(1.dp, LENS_LIBRARY_TINT.copy(alpha = 0.4f), shape)
            .then(if (onClick != null) Modifier.musicKeyFocusRing(shape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(where, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            tag,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = LENS_LIBRARY_TINT,
            maxLines = 1,
            modifier = Modifier.border(1.dp, LENS_LIBRARY_TINT.copy(alpha = 0.55f), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** One choice in a Cut to fit grid. */
internal class CutChoice(val label: String, val selected: Boolean, val enabled: Boolean, val onClick: () -> Unit)

/** What a Cut to fit panel's dial shows: [segments] of the ring (one lit at [lit]) or one arc filled to [fraction]. */
internal class CutDialState(val big: String, val small: String, val fraction: Float? = null, val segments: Int = 0, val lit: Int = -1)

/**
 * One Cut to fit panel. Both sides are this one composable with different words, so they are mirrors row for row: header,
 * title, the dial (in the box that takes the window's spare height), a 3 by 2 grid of choices, two facts, a note, a button.
 */
@Composable
internal fun CutSidePanel(
    icon: ImageVector,
    label: String,
    pill: String,
    tint: Color,
    title: String,
    subtitle: String,
    dial: CutDialState,
    choices: List<CutChoice>,
    facts: List<Pair<String, String>>,
    noteLabel: String,
    note: String,
    noteFooter: String?,
    button: String,
    buttonEnabled: Boolean,
    onButton: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    LensPanel(modifier) {
        LensPanelHeader(icon, label, pill = pill, tint = tint)
        LensPanelTitle(title, subtitle)
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            CutDial(dial, tint)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier.weight(1f).height(48.dp).clip(shape)
                                .background(if (c.selected) tint.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (c.selected) tint else Color.White.copy(alpha = 0.14f), shape)
                                .musicKeyFocusRing(shape)
                                .semantics { this.selected = c.selected }
                                .clickable(enabled = c.enabled, onClick = c.onClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                c.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    !c.enabled -> colors.textDisabled
                                    c.selected -> colors.textPrimary
                                    else -> colors.textSecondary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(top = 12.dp)) {
            facts.forEachIndexed { i, (k, v) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LensNoteCard(label = noteLabel, text = note, tint = tint, footer = noteFooter)
        LensButton(button, tint = tint, enabled = buttonEnabled, onClick = onButton)
    }
}

/** The dial: a ring with its big word in the middle; a filled arc for the time, or one lit segment of six for the mood. */
@Composable
private fun CutDial(state: CutDialState, tint: Color) {
    val colors = MaterialTheme.nuvio.colors
    val track = Color.White.copy(alpha = 0.10f)
    // As big as the box allows up to 200 dp, always round: the window decides its size, never what it says.
    Box(Modifier.sizeIn(maxWidth = CUT_DIAL, maxHeight = CUT_DIAL).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            if (state.segments > 0) {
                val gap = 10f
                val sweep = 360f / state.segments - gap
                repeat(state.segments) { i ->
                    drawArc(if (i == state.lit) tint else track, -90f + i * (360f / state.segments) + gap / 2, sweep, false, topLeft, arcSize, style = style)
                }
            } else {
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = style)
                val f = (state.fraction ?: 0f).coerceIn(0f, 1f)
                if (f > 0f) drawArc(tint, -90f, 360f * f, false, topLeft, arcSize, style = style)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.big, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 28.dp))
            Text(state.small, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1)
        }
    }
}

private val CUT_DIAL = 200.dp

/** A lens that is not built yet: the same panel on each side, saying so. */
@Composable
internal fun LensComingSoon(lens: AlbumLens, left: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val tint = if (left) colors.accent else LENS_LIBRARY_TINT
    LensPanel(modifier) {
        LensPanelHeader(if (left) Icons.Rounded.AutoAwesome else Icons.Rounded.LibraryMusic, lens.label.uppercase(), pill = null, tint = tint)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Coming soon", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                Text("This lens is not built yet.", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
            }
        }
    }
}
