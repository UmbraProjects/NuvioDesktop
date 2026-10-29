package com.nuvio.app.features.music.library

/*
 * Is the YouTube Music channel the person opened really the artist the other sources found by name?
 *
 * MusicBrainz, Deezer and TheAudioDB are all searched by name, so two artists who share one ("Carlos
 * Santana" the guitarist and a Brazilian singer of the same name) get their facts and artwork joined
 * to whichever channel was opened. This is the check that stops that: it compares the channel with
 * MusicBrainz's own links to YouTube channels and with the titles of both discographies. Pure, so it
 * is tested without a network.
 */

/** What the check concluded. */
internal sealed interface ChannelVerdict {
    /** The channel is one MusicBrainz lists, or the two discographies share titles. */
    data object Confirmed : ChannelVerdict

    /** Too little to compare either way: the name match is trusted, as before. */
    data object Unconfirmed : ChannelVerdict

    /** The channel's releases do not belong to the artist the name matched. [betterChannelId] is the channel MusicBrainz lists, if it lists one. */
    data class Mismatch(val betterChannelId: String?) : ChannelVerdict
}

private val CHANNEL_URL = Regex("youtube\\.com/channel/(UC[A-Za-z0-9_-]{20,30})")

/** Channel ids in [links], channels on music.youtube.com first. Handles and legacy user URLs carry no id and are skipped. */
internal fun youTubeChannelIds(links: List<ArtistLink>): List<String> =
    links.sortedByDescending { "music.youtube.com" in it.url }
        .mapNotNull { CHANNEL_URL.find(it.url)?.groupValues?.get(1) }
        .distinct()

/** The share of [youTubeTitles] (edition-blind, accent-blind) that [musicBrainzTitles] also has, 0.0 to 1.0. */
internal fun releaseTitleOverlap(youTubeTitles: List<String>, musicBrainzTitles: List<String>): Double {
    val known = musicBrainzTitles.map(::looseTitleKey).filter { it.isNotEmpty() }.toSet()
    val ours = youTubeTitles.map(::looseTitleKey).filter { it.isNotEmpty() }.distinct()
    if (ours.isEmpty()) return 0.0
    return ours.count { it in known }.toDouble() / ours.size
}

/** At least this share of shared titles settles it as the same artist. */
internal const val CHANNEL_SAME_OVERLAP = 0.25

/** With no channel link to go by, this little overlap (over a real discography on both sides) says it is someone else. */
internal const val CHANNEL_OTHER_OVERLAP = 0.1

/** How many releases each side needs before an absence of shared titles means anything. */
internal const val CHANNEL_MIN_TITLES = 3
internal const val CHANNEL_MIN_TITLES_NO_LINK = 5

internal fun verifyChannel(
    channelId: String,
    musicBrainzLinks: List<ArtistLink>,
    youTubeTitles: List<String>,
    musicBrainzTitles: List<String>,
): ChannelVerdict {
    val listed = youTubeChannelIds(musicBrainzLinks)
    if (channelId in listed) return ChannelVerdict.Confirmed
    if (youTubeTitles.size < CHANNEL_MIN_TITLES || musicBrainzTitles.size < CHANNEL_MIN_TITLES) return ChannelVerdict.Unconfirmed
    val overlap = releaseTitleOverlap(youTubeTitles, musicBrainzTitles)
    return when {
        overlap >= CHANNEL_SAME_OVERLAP -> ChannelVerdict.Confirmed
        // MusicBrainz names a channel, this is not it, and the titles agree with nothing: a namesake.
        listed.isNotEmpty() -> ChannelVerdict.Mismatch(listed.first())
        youTubeTitles.size >= CHANNEL_MIN_TITLES_NO_LINK &&
            musicBrainzTitles.size >= CHANNEL_MIN_TITLES_NO_LINK &&
            overlap < CHANNEL_OTHER_OVERLAP -> ChannelVerdict.Mismatch(null)
        else -> ChannelVerdict.Unconfirmed
    }
}

/**
 * Every date in a stored library made sane: placeholder years ("0000", "0") and dates ("0000-00-00") that
 * earlier versions kept are dropped, so they cannot stretch the era ruler back to year zero.
 */
internal fun LibraryArtist.withSaneDates(): LibraryArtist {
    if (releases.none { it.year != sanePartialDate(it.year) || it.releaseDate != sanePartialDate(it.releaseDate) }) return this
    return copy(
        releases = releases.map { r ->
            val year = sanePartialDate(r.year)?.take(4)
            r.copy(year = year, releaseDate = sanePartialDate(r.releaseDate) ?: year)
        },
    )
}
