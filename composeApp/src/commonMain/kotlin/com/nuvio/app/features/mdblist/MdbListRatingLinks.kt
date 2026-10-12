package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_IMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_LETTERBOXD
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_MAL
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_METACRITIC
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TRAKT

/**
 * Where a rating badge opens: the title on that site. Ported from the Nuvio Tracker site's
 * `ratings.ts`. MDBList gives a site path for Rotten Tomatoes, Metacritic and Letterboxd and
 * the ids for the rest; [ratingLinkFromMdbList] builds from those at fetch time.
 */
internal fun ratingLinkFromMdbList(
    providerId: String,
    path: String?,
    ids: MdbListIds?,
    isMovie: Boolean,
): String? {
    val sitePath = path?.trim()?.takeIf { it.startsWith("/") }
    val kind = if (isMovie) "movie" else "tv"
    return when (providerId) {
        PROVIDER_IMDB -> ids?.imdb?.let { "https://www.imdb.com/title/$it/" }
        PROVIDER_TOMATOES, PROVIDER_AUDIENCE -> sitePath?.let { "https://www.rottentomatoes.com$it" }
        PROVIDER_METACRITIC -> sitePath?.let { "https://www.metacritic.com/$kind${it.trimEnd('/')}/" }
        PROVIDER_LETTERBOXD -> sitePath?.let { "https://letterboxd.com$it" }
            ?: ids?.imdb?.let { "https://letterboxd.com/imdb/$it/" }
        PROVIDER_TRAKT -> ids?.trakt?.let { "https://trakt.tv/${if (isMovie) "movies" else "shows"}/$it" }
        PROVIDER_TMDB -> ids?.tmdb?.let { "https://www.themoviedb.org/$kind/$it" }
        PROVIDER_MAL -> ids?.mal?.let { "https://myanimelist.net/anime/$it" }
        else -> null
    }
}

/**
 * The link for a rating on a title page: MDBList's when the cached entry has one, else one built
 * from the title's own ids (ratings cached before links existed have none), else that site's
 * search for the title.
 */
internal fun MetaExternalRating.linkFor(meta: MetaDetails): String? {
    url?.let { return it }
    val isMovie = meta.type.trim().lowercase() == "movie"
    val kind = if (isMovie) "movie" else "tv"
    val imdb = meta.imdbId?.takeIf { it.startsWith("tt") } ?: meta.id.takeIf { it.startsWith("tt") }?.substringBefore(':')
    val query = encodeQuery(meta.name)
    return when (source) {
        PROVIDER_IMDB -> imdb?.let { "https://www.imdb.com/title/$it/" }
        PROVIDER_TMDB -> meta.tmdbId?.let { "https://www.themoviedb.org/$kind/$it" }
        PROVIDER_TRAKT -> imdb?.let { "https://trakt.tv/search/imdb/$it" }
        PROVIDER_LETTERBOXD -> imdb?.let { "https://letterboxd.com/imdb/$it/" }
        PROVIDER_MAL -> meta.malId?.let { "https://myanimelist.net/anime/$it" }
        PROVIDER_TOMATOES, PROVIDER_AUDIENCE -> "https://www.rottentomatoes.com/search?search=$query"
        PROVIDER_METACRITIC -> "https://www.metacritic.com/search/$query/"
        else -> null
    }
}

private fun encodeQuery(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = byte.toInt().toChar()
        when {
            c.isLetterOrDigit() && byte >= 0 -> append(c)
            c == '-' || c == '_' || c == '.' -> append(c)
            c == ' ' -> append("%20")
            else -> append('%').append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}
