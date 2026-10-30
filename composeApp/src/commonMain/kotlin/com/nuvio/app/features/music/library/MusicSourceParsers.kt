package com.nuvio.app.features.music.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Parsers for the metadata sources the music library reads. Pure (string in, plain data out) so
 * they can be tested against saved answers. Anything that does not parse is skipped, never thrown,
 * and an empty string or the literal "null" some of these APIs send for "no value" reads as null.
 */

private val SOURCE_JSON = Json { ignoreUnknownKeys = true }

private fun parse(body: String): JsonElement? = runCatching { SOURCE_JSON.parseToJsonElement(body) }.getOrNull()

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()
private fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()
private fun JsonObject.double(key: String): Double? = text(key)?.toDoubleOrNull()
private fun JsonObject.flag(key: String): Boolean? = text(key)?.let { it == "true" || it == "1" }

private fun JsonObject.objects(key: String): List<JsonObject> = this[key].arr()?.mapNotNull { it.obj() }.orEmpty()

private fun yearOf(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

// region MusicBrainz

internal data class MbArtistHit(
    val id: String,
    val name: String,
    val sortName: String?,
    val score: Int,
    val type: String?,
    val country: String?,
    val disambiguation: String?,
)

internal data class MbArtist(
    val id: String,
    val name: String,
    val sortName: String?,
    val type: String?,
    val country: String?,
    val area: String?,
    val beginYear: String?,
    val endYear: String?,
    val ended: Boolean,
    val disambiguation: String?,
    val genres: List<String>,
    val tags: List<String>,
    val rating: Double?,
    val links: List<ArtistLink>,
    val website: String?,
    /** Title of the English Wikipedia article, when MusicBrainz links one. */
    val wikipediaTitle: String?,
)

internal data class MbReleaseGroup(
    val id: String,
    val title: String,
    val primaryType: String?,
    val secondaryTypes: List<String>,
    val firstReleaseDate: String?,
)

internal fun parseMbArtistSearch(body: String): List<MbArtistHit> =
    parse(body).obj()?.objects("artists").orEmpty().mapNotNull { a ->
        MbArtistHit(
            id = a.text("id") ?: return@mapNotNull null,
            name = a.text("name") ?: return@mapNotNull null,
            sortName = a.text("sort-name"),
            score = a.int("score") ?: 0,
            type = a.text("type"),
            country = a.text("country"),
            disambiguation = a.text("disambiguation"),
        )
    }

/**
 * The artists of one MusicBrainz tag search (`artist?query=tag:"shoegaze"`), as ladder candidates: id, name
 * and the tag votes the search returned with each hit. Hits without an id or name are skipped, and so are
 * tags with no votes. An answer that is not JSON, or has no artist list, gives an empty list.
 */
internal fun parseMbTagSearch(body: String): List<LadderCandidate> =
    parse(body).obj()?.objects("artists").orEmpty().mapNotNull { a ->
        val id = a.text("id") ?: return@mapNotNull null
        val name = a.text("name") ?: return@mapNotNull null
        val votes = LinkedHashMap<String, Int>()
        for (tag in a.objects("tags")) {
            val tagName = tag.text("name") ?: continue
            val count = tag.int("count") ?: continue
            if (count > 0) votes[tagName] = (votes[tagName] ?: 0) + count
        }
        val begin = yearOf(a["life-span"].obj()?.text("begin"))?.toIntOrNull()
        LadderCandidate(id = id, name = name, votes = votes, beginYear = begin)
    }

private fun linkLabelFor(type: String, url: String): String? = when {
    type == "official homepage" -> "Website"
    type == "wikipedia" -> "Wikipedia"
    type == "wikidata" -> "Wikidata"
    type == "discogs" -> "Discogs"
    type == "allmusic" -> "AllMusic"
    type == "bandcamp" -> "Bandcamp"
    type == "last.fm" -> "Last.fm"
    type == "lyrics" -> null
    type == "social network" -> when {
        "facebook.com" in url -> "Facebook"
        "twitter.com" in url || "x.com/" in url -> "X"
        "instagram.com" in url -> "Instagram"
        "tiktok.com" in url -> "TikTok"
        "threads.net" in url -> "Threads"
        "bsky.app" in url -> "Bluesky"
        else -> "Social"
    }
    type == "streaming" || type == "free streaming" -> when {
        "spotify.com" in url -> "Spotify"
        "music.apple.com" in url -> "Apple Music"
        "deezer.com" in url -> "Deezer"
        "tidal.com" in url -> "Tidal"
        "music.youtube.com" in url -> "YouTube Music"
        else -> null
    }
    type == "youtube" || type == "youtube music" -> "YouTube"
    type == "soundcloud" -> "SoundCloud"
    type == "imdb" -> "IMDb"
    type == "songkick" -> "Songkick"
    else -> null
}

internal fun parseMbArtist(body: String): MbArtist? {
    val a = parse(body).obj() ?: return null
    val id = a.text("id") ?: return null
    val name = a.text("name") ?: return null
    val span = a["life-span"].obj()
    val links = ArrayList<ArtistLink>()
    var wikipedia: String? = null
    for (rel in a.objects("relations")) {
        val type = rel.text("type") ?: continue
        val url = rel["url"].obj()?.text("resource") ?: continue
        if (type == "wikipedia" && "en.wikipedia.org/wiki/" in url) {
            wikipedia = url.substringAfter("/wiki/").substringBefore('#').substringBefore('?')
        }
        val label = linkLabelFor(type, url) ?: continue
        links += ArtistLink(label, url)
    }
    val distinctLinks = links.distinctBy { it.url }
    return MbArtist(
        id = id,
        name = name,
        sortName = a.text("sort-name"),
        type = a.text("type"),
        country = a.text("country"),
        area = a["area"].obj()?.text("name"),
        beginYear = yearOf(span?.text("begin")),
        endYear = yearOf(span?.text("end")),
        ended = span?.flag("ended") ?: false,
        disambiguation = a.text("disambiguation"),
        // Genres are MusicBrainz's curated list; tags are the user free-for-all, kept only when popular.
        genres = a.objects("genres").sortedByDescending { it.int("count") ?: 0 }.mapNotNull { it.text("name") },
        tags = a.objects("tags").filter { (it.int("count") ?: 0) >= 1 }
            .sortedByDescending { it.int("count") ?: 0 }.mapNotNull { it.text("name") }.take(12),
        rating = a["rating"].obj()?.double("value"),
        links = distinctLinks,
        website = distinctLinks.firstOrNull { it.label == "Website" }?.url,
        wikipediaTitle = wikipedia,
    )
}

/** One page of release groups, and the total the artist has (so the caller knows whether to page on). */
internal fun parseMbReleaseGroups(body: String): Pair<List<MbReleaseGroup>, Int> {
    val root = parse(body).obj() ?: return emptyList<MbReleaseGroup>() to 0
    val groups = root.objects("release-groups").mapNotNull { g ->
        MbReleaseGroup(
            id = g.text("id") ?: return@mapNotNull null,
            title = g.text("title") ?: return@mapNotNull null,
            primaryType = g.text("primary-type"),
            secondaryTypes = g["secondary-types"].arr()?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            firstReleaseDate = g.text("first-release-date"),
        )
    }
    return groups to (root.int("release-group-count") ?: groups.size)
}

// endregion

// region Deezer

internal data class DeezerArtistHit(
    val id: Long,
    val name: String,
    val picture: String?,
    val fans: Long?,
    val albumCount: Int?,
)

internal data class DeezerAlbum(
    val id: Long,
    val title: String,
    val cover: String?,
    /** "album", "single", "ep" or "compile". */
    val recordType: String?,
    val releaseDate: String?,
    val explicit: Boolean?,
)

internal data class DeezerAlbumDetail(
    val label: String?,
    val trackCount: Int?,
    val durationSeconds: Int?,
    val genres: List<String>,
    val upc: String?,
    val releaseDate: String?,
)

internal fun parseDeezerArtistSearch(body: String): List<DeezerArtistHit> =
    parse(body).obj()?.objects("data").orEmpty().mapNotNull { a ->
        DeezerArtistHit(
            id = a.long("id") ?: return@mapNotNull null,
            name = a.text("name") ?: return@mapNotNull null,
            picture = a.text("picture_xl") ?: a.text("picture_big") ?: a.text("picture_medium"),
            fans = a.long("nb_fan"),
            albumCount = a.int("nb_album"),
        )
    }

/** A page of an artist's albums and the url of the next page, if any. */
internal fun parseDeezerAlbums(body: String): Pair<List<DeezerAlbum>, String?> {
    val root = parse(body).obj() ?: return emptyList<DeezerAlbum>() to null
    val albums = root.objects("data").mapNotNull { a ->
        DeezerAlbum(
            id = a.long("id") ?: return@mapNotNull null,
            title = a.text("title") ?: return@mapNotNull null,
            cover = a.text("cover_xl") ?: a.text("cover_big") ?: a.text("cover_medium"),
            recordType = a.text("record_type")?.lowercase(),
            releaseDate = a.text("release_date"),
            explicit = a.flag("explicit_lyrics"),
        )
    }
    return albums to root.text("next")
}

/** One of an artist's most played tracks. [rank] is Deezer's popularity number: bigger is more played. */
internal data class DeezerTopTrack(val albumId: Long, val rank: Long)

internal fun parseDeezerTopTracks(body: String): List<DeezerTopTrack> =
    parse(body).obj()?.takeIf { it["error"] == null }?.objects("data").orEmpty().mapNotNull { t ->
        DeezerTopTrack(
            albumId = t["album"].obj()?.long("id") ?: return@mapNotNull null,
            rank = t.long("rank") ?: 0L,
        )
    }

internal fun parseDeezerAlbumDetail(body: String): DeezerAlbumDetail? {
    val a = parse(body).obj()?.takeIf { it["error"] == null } ?: return null
    return DeezerAlbumDetail(
        label = a.text("label"),
        trackCount = a.int("nb_tracks"),
        durationSeconds = a.int("duration"),
        genres = a["genres"].obj()?.objects("data").orEmpty().mapNotNull { it.text("name") },
        upc = a.text("upc"),
        releaseDate = a.text("release_date"),
    )
}

// endregion

// region TheAudioDB

internal data class AudioDbArtist(
    val id: String,
    val name: String,
    val musicBrainzId: String?,
    val biography: String?,
    val genre: String?,
    val style: String?,
    val mood: String?,
    val country: String?,
    val formedYear: String?,
    val disbandedYear: String?,
    val website: String?,
    val links: List<ArtistLink>,
    val art: ArtistArtwork,
    /** A person's birth year. Their "formed" year can be the first group they sang in (Michael Jackson: 1964, the Jackson 5). */
    val bornYear: String? = null,
)

internal data class AudioDbAlbum(
    val id: String,
    val title: String,
    val musicBrainzId: String?,
    val year: String?,
    /** "Album", "Single", "EP", "Compilation"... */
    val format: String?,
    val genre: String?,
    val style: String?,
    val mood: String?,
    val label: String?,
    val description: String?,
    val art: ReleaseArtwork,
)

private fun httpsUrl(raw: String?): String? = raw?.trim()?.takeIf { it.startsWith("http") }?.replace("http://", "https://")

/** TheAudioDB stores links with or without a scheme ("www.example.com"); make them openable. */
private fun fullLink(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (value.startsWith("http")) value.replace("http://", "https://") else "https://$value"
}

internal fun parseAudioDbArtists(body: String): List<AudioDbArtist> =
    parse(body).obj()?.objects("artists").orEmpty().mapNotNull { a ->
        val name = a.text("strArtist") ?: return@mapNotNull null
        val links = listOfNotNull(
            a.text("strWebsite")?.let { fullLink(it) }?.let { ArtistLink("Website", it) },
            a.text("strFacebook")?.let { fullLink(it) }?.let { ArtistLink("Facebook", it) },
            a.text("strTwitter")?.let { fullLink(it) }?.let { ArtistLink("X", it) },
            a.text("strLastFMChart")?.let { fullLink(it) }?.let { ArtistLink("Last.fm", it) },
        )
        AudioDbArtist(
            id = a.text("idArtist") ?: return@mapNotNull null,
            name = name,
            musicBrainzId = a.text("strMusicBrainzID"),
            biography = a.text("strBiographyEN"),
            genre = a.text("strGenre"),
            style = a.text("strStyle"),
            mood = a.text("strMood"),
            country = a.text("strCountry"),
            formedYear = a.text("intFormedYear") ?: a.text("intBornYear"),
            bornYear = yearOf(a.text("intBornYear")),
            disbandedYear = a.text("strDisbanded")?.let { yearOf(it) } ?: a.text("intDiedYear"),
            website = links.firstOrNull { it.label == "Website" }?.url,
            links = links,
            art = ArtistArtwork(
                thumb = httpsUrl(a.text("strArtistThumb")),
                logo = httpsUrl(a.text("strArtistLogo")),
                clearArt = httpsUrl(a.text("strArtistClearart")),
                cutout = httpsUrl(a.text("strArtistCutout")),
                banner = httpsUrl(a.text("strArtistBanner")),
                wideThumb = httpsUrl(a.text("strArtistWideThumb")),
                backgrounds = listOf("strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4")
                    .mapNotNull { httpsUrl(a.text(it)) },
            ),
        )
    }

internal fun parseAudioDbAlbums(body: String): List<AudioDbAlbum> =
    parse(body).obj()?.objects("album").orEmpty().mapNotNull { a ->
        AudioDbAlbum(
            id = a.text("idAlbum") ?: return@mapNotNull null,
            title = a.text("strAlbum") ?: return@mapNotNull null,
            musicBrainzId = a.text("strMusicBrainzID"),
            year = a.text("intYearReleased"),
            format = a.text("strReleaseFormat"),
            genre = a.text("strGenre"),
            style = a.text("strStyle"),
            mood = a.text("strMood"),
            label = a.text("strLabel"),
            description = a.text("strDescriptionEN"),
            art = ReleaseArtwork(
                covers = listOfNotNull(httpsUrl(a.text("strAlbumThumbHQ")), httpsUrl(a.text("strAlbumThumb"))),
                cdArt = httpsUrl(a.text("strAlbumCDart")),
                // TheAudioDB calls the back cover strAlbumThumbBack; strAlbumBack is kept as a second name in case a response uses it.
                back = httpsUrl(a.text("strAlbumThumbBack") ?: a.text("strAlbumBack")),
                spine = httpsUrl(a.text("strAlbumSpine")),
                case3d = httpsUrl(a.text("strAlbum3DCase")),
            ),
        )
    }

// endregion

// region Fanart.tv

internal data class FanartRelease(val covers: List<String>, val cdArt: String?)

internal data class FanartArtist(
    val thumbs: List<String>,
    val logos: List<String>,
    val hdLogos: List<String>,
    val banners: List<String>,
    val backgrounds: List<String>,
    /** Keyed by MusicBrainz release-group id. */
    val releases: Map<String, FanartRelease>,
)

private fun JsonObject.images(key: String): List<String> =
    objects(key).sortedByDescending { it.int("likes") ?: 0 }.mapNotNull { httpsUrl(it.text("url")) }

internal fun parseFanartArtist(body: String): FanartArtist? {
    val root = parse(body).obj() ?: return null
    if (root["status"]?.let { (it as? JsonPrimitive)?.content } == "error") return null
    val releases = HashMap<String, FanartRelease>()
    root["albums"].obj()?.forEach { (rgId, value) ->
        val album = value.obj() ?: return@forEach
        // Several discs: the first disc's print is the one a one-disc spinner should show.
        val cdArt = album.objects("cdart").sortedWith(
            compareBy<JsonObject> { it.int("disc") ?: 1 }.thenByDescending { it.int("likes") ?: 0 },
        ).firstNotNullOfOrNull { httpsUrl(it.text("url")) }
        val covers = album.images("albumcover")
        if (cdArt != null || covers.isNotEmpty()) releases[rgId] = FanartRelease(covers, cdArt)
    }
    return FanartArtist(
        thumbs = root.images("artistthumb"),
        logos = root.images("musiclogo"),
        hdLogos = root.images("hdmusiclogo"),
        banners = root.images("musicbanner"),
        backgrounds = root.images("artistbackground"),
        releases = releases,
    )
}

// endregion

// region Wikipedia

/** The plain-text lead of a Wikipedia REST summary, or null for a missing page or a disambiguation page. */
internal fun parseWikipediaSummary(body: String): String? {
    val root = parse(body).obj() ?: return null
    if (root.text("type") == "disambiguation") return null
    return root.text("extract")?.takeIf { it.length > 40 }
}

// endregion
