package com.nuvio.app.features.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.nuvio.app.features.tmdb.TmdbService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * fork:live-tv-stage - a programme's picture and words, best first: a film's TMDB backdrop, the guide's own picture,
 * a series' TMDB backdrop (only on an exact name match), then nothing (the stage draws the channel's logo, unless the
 * logo is a placeholder the provider puts on many channels). TMDB also fills in a description and a year the guide
 * did not give.
 */
internal class IptvProgrammeArt(val picture: String?, val caption: String?, val overview: String?, val year: Int?)

/** Series art from TMDB, matched by exact name, remembered for the session. */
internal object IptvShowArt {
    private val none = IptvProgrammeArt(null, null, null, null)
    private val found = ConcurrentHashMap<String, IptvProgrammeArt>()

    private fun key(title: String) = IptvDecide.normalise(title)

    fun peek(title: String): IptvProgrammeArt? = found[key(title)]?.takeIf { it !== none }

    suspend fun find(title: String): IptvProgrammeArt? {
        val key = key(title)
        if (key.length < 4) return null
        found[key]?.let { return it.takeIf { art -> art !== none } }
        val results = try {
            withContext(Dispatchers.Default) { TmdbService.searchTitles(title, mediaType = "tv") }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return null
        }
        // Only the same name: a near match on a guide title ("Breakfast") is usually another programme.
        val best = results.firstOrNull { IptvDecide.normalise(it.name ?: it.title.orEmpty()) == key && (it.backdropPath != null || it.posterPath != null) }
        val art = if (best == null) {
            none
        } else {
            IptvProgrammeArt(
                picture = (best.backdropPath ?: best.posterPath)?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" },
                caption = "Series art",
                overview = best.overview?.takeIf { it.isNotBlank() }?.let { IptvGuideText.shortDesc(it, 220) },
                year = best.firstAirDate?.take(4)?.toIntOrNull(),
            )
        }
        found[key] = art
        return art.takeIf { it !== none }
    }
}

/** Logos the provider puts on many channels ("Content not viewable in your region") are no logo at all. */
internal object IptvLogos {
    @Volatile
    var placeholders: Set<String> = emptySet()

    fun placeholdersOf(channels: List<IptvChannel>): Set<String> =
        channels.mapNotNull { it.logo?.takeIf { url -> url.isNotBlank() } }.groupingBy { it }.eachCount().filterValues { it >= 15 }.keys

    fun good(url: String?): String? = url?.takeIf { it.isNotBlank() && it !in placeholders }
}

private fun wantsShowArt(entry: IptvEpgEntry): Boolean =
    entry.kind != IptvProgrammeKind.NEWS && entry.kind != IptvProgrammeKind.SPORT && !IptvGuideText.isShopping(entry.title)

/** What the stage shows for [programme]; null while nothing better than the logo is known. */
@Composable
internal fun rememberProgrammeArt(programme: IptvWallProgramme?): IptvProgrammeArt? {
    val entry = programme?.entry
    val title = entry?.title.orEmpty()
    val film = entry?.kind == IptvProgrammeKind.FILM
    val guidePicture = entry?.image
    val initial = when {
        entry == null -> null
        film -> IptvFilmArt.peek(title)?.let { IptvProgrammeArt(it.backdrop ?: it.poster, "Backdrop", it.overview, it.year?.toIntOrNull()) }
        guidePicture != null -> IptvProgrammeArt(guidePicture, null, null, null)
        else -> IptvShowArt.peek(title)
    }
    return produceState(initial, title, film, guidePicture) {
        if (entry == null) return@produceState
        value = when {
            film -> IptvFilmArt.find(title)?.let { IptvProgrammeArt(it.backdrop ?: it.poster, "Backdrop", it.overview, it.year?.toIntOrNull()) }
            guidePicture != null -> {
                // The guide's picture, with TMDB's words when the guide has none.
                val words = if (entry.desc == null && wantsShowArt(entry)) IptvShowArt.find(title) else null
                IptvProgrammeArt(guidePicture, null, words?.overview, words?.year)
            }
            wantsShowArt(entry) -> IptvShowArt.find(title)
            else -> null
        }
    }.value
}
