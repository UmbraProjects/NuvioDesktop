package com.nuvio.app.features.details.filler

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.anime_episode_badge_filler
import nuvio.composeapp.generated.resources.anime_episode_badge_mixed
import org.jetbrains.compose.resources.stringResource

/** Label and accent colour for an AnimeFillerList verdict; canon episodes get none. */
data class AnimeEpisodeKindLabel(val text: String, val color: Color)

@Composable
fun AnimeEpisodeKind?.label(): AnimeEpisodeKindLabel? = when (this) {
    AnimeEpisodeKind.Filler -> AnimeEpisodeKindLabel(stringResource(Res.string.anime_episode_badge_filler), FillerColor)
    AnimeEpisodeKind.MixedCanonFiller -> AnimeEpisodeKindLabel(stringResource(Res.string.anime_episode_badge_mixed), MixedColor)
    AnimeEpisodeKind.MangaCanon, AnimeEpisodeKind.AnimeCanon, null -> null
}

/** "S5E2 · Filler", with the verdict in its accent colour; just [code] when there is no verdict. */
fun episodeCodeWithKind(code: String, kind: AnimeEpisodeKindLabel?): AnnotatedString = buildAnnotatedString {
    append(code)
    if (kind == null) return@buildAnnotatedString
    if (code.isNotEmpty()) append(" · ")
    withStyle(SpanStyle(color = kind.color, fontWeight = FontWeight.Bold)) { append(kind.text) }
}

private val FillerColor = Color(0xFFE8833A)
private val MixedColor = Color(0xFFD9BC72)
