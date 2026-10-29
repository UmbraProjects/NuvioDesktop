package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary

/*
 * Joins what the sources say about one artist's releases into a single list. Pure, so it can be
 * tested without a network. The sources disagree about spelling ("Deluxe Edition", "(Remastered)"),
 * about what counts as an EP, and about which releases exist at all, so the join is by a normalised
 * title, with the MusicBrainz release-group id as the exact key wherever a source carries it.
 */

private val EDITION_WORDS = Regex(
    "\\b(deluxe|expanded|remaster(ed)?|anniversary|bonus|special|collector'?s?|super|limited|" +
        "standard|extended|edition|version|reissue|digital|explicit|clean|mono|stereo)\\b",
)
private val BRACKETS = Regex("[(\\[][^)\\]]*[)\\]]")

/** Letters and digits only, lower-cased, accents folded for the common Latin cases. */
internal fun strictTitleKey(title: String): String =
    title.lowercase().map(::foldAccent).filter { it.isLetterOrDigit() }.joinToString("")

/**
 * Abbreviations the sources disagree on, spelled out: "Vol. II" and "Volume II", "Pt. 2" and "Part 2",
 * "&" and "and" are the same title.
 */
private val SPELLED_OUT = listOf(
    Regex("""\bvol\b\.?""") to "volume",
    Regex("""\bpt\b\.?""") to "part",
    Regex("&") to " and ",
)

private fun spelledOut(title: String): String =
    SPELLED_OUT.fold(title.lowercase()) { text, (pattern, word) -> text.replace(pattern, word) }

/** [strictTitleKey] after dropping bracketed edition notes and edition words, so a deluxe edition meets its album. */
internal fun looseTitleKey(title: String): String {
    val withoutBrackets = spelledOut(title).replace(BRACKETS) { m ->
        if (EDITION_WORDS.containsMatchIn(m.value)) " " else m.value
    }
    val withoutWords = withoutBrackets.replace(EDITION_WORDS, " ")
    val key = strictTitleKey(withoutWords)
    return key.ifEmpty { strictTitleKey(title) }
}

/**
 * Bracket notes that make a different recording, which must never be joined to the original:
 * "Infinity (Acoustic)" is not "Infinity", "Legends Never Die (Alan Walker Remix)" is not the song.
 */
private val VERSION_WORDS = Regex(
    """\b(acoustic|remix(es)?|mix|live|stripped|demo|instrumental|reimagined|unplugged|sessions?|karaoke|""" +
        """a ?cappella|piano|orchestral|sped|slowed|rework|re-?recorded|taylor'?s version|edit|dub|vip|cover version|""" +
        // A cover band's restyles ("Die With a Smile (Metal / Rockified)") are their own recordings too.
        """metal|rockified|punk|pop ?punk|emo|lo-?fi|jazz|swing|8-?bit|cinematic|epic)\b""",
)

/**
 * A credit written after the title rather than in brackets: "Song feat. Someone", "Song - Single". Not a
 * bare "with", which is part of real titles ("Dancing with Myself"); in brackets it is a credit.
 */
private val TRAILING_CREDIT = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$|\s+-\s+(single|ep)$""")

/**
 * [looseTitleKey] with the credit notes taken off too: YouTube Music titles a cover with the original
 * artist ("All Too Well (Taylor Swift)", "Ain't It Fun (Cover)") and a collaboration with its guests
 * ("Barely Breathing (feat. Against The Current)") where MusicBrainz has the plain title. Brackets that
 * name a different recording ([VERSION_WORDS]) stay, so an acoustic take or a remix keeps its own slot.
 * Only used once the exact and edition-blind keys have found nothing.
 */
internal fun baseTitleKey(title: String): String {
    val withoutCredits = spelledOut(title).replace(BRACKETS) { m ->
        if (VERSION_WORDS.containsMatchIn(m.value)) m.value else " "
    }.replace(TRAILING_CREDIT, "")
    return looseTitleKey(withoutCredits).ifEmpty { looseTitleKey(title) }
}

private fun foldAccent(c: Char): Char = when (c) {
    'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
    'é', 'è', 'ê', 'ë' -> 'e'
    'í', 'ì', 'î', 'ï' -> 'i'
    'ó', 'ò', 'ô', 'ö', 'õ', 'ø' -> 'o'
    'ú', 'ù', 'û', 'ü' -> 'u'
    'ñ' -> 'n'
    'ç' -> 'c'
    else -> c
}

/** True when two artist names are the same name as far as spelling goes ("Beyoncé" / "Beyonce", "The Xx" / "the xx"). */
internal fun sameArtistName(a: String, b: String): Boolean {
    val x = strictTitleKey(a)
    val y = strictTitleKey(b)
    return x.isNotEmpty() && x == y
}

/** Years outside this range are placeholders ("0000", "0"), not dates; they would stretch the era ruler back to year 0. */
internal const val MIN_RELEASE_YEAR = 1900
internal const val MAX_RELEASE_YEAR = 2100

/** The four-digit year at the start of [raw] ("2014", "2014-05-20"), or null when there is none or it is a placeholder like "0000". */
internal fun releaseYearOrNull(raw: String?): Int? =
    raw?.trim()?.takeIf { it.length >= 4 && it.take(4).all(Char::isDigit) && (it.length == 4 || it[4] == '-') }
        ?.take(4)?.toIntOrNull()?.takeIf { it in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR }

/** [raw] unchanged when it starts with a real year ("2014", "2014-05", "2014-05-20"), else null ("0000-00-00" from Deezer, "0"). */
internal fun sanePartialDate(raw: String?): String? = raw?.trim()?.takeIf { releaseYearOrNull(it) != null }

/**
 * fork:original-date — the date a release is placed by is its ORIGINAL one. Streaming catalogues list a
 * remaster or reissue under the reissue's date (a 1970 album as 2005), so when two sources disagree the
 * earlier year wins; when they agree on the year, the fuller date ("1970-03-12" over "1970") wins.
 * [current] and [candidate] may be null or placeholders; the result is a sane date or null.
 */
internal fun earlierReleaseDate(current: String?, candidate: String?): String? {
    val a = sanePartialDate(current)
    val b = sanePartialDate(candidate)
    if (a == null) return b
    if (b == null) return a
    val ya = releaseYearOrNull(a)!!
    val yb = releaseYearOrNull(b)!!
    return when {
        yb < ya -> b
        yb > ya -> a
        b.length > a.length && b.startsWith(a) -> b
        else -> a
    }
}

// region kinds

internal fun kindFromMusicBrainz(primary: String?, secondary: List<String>): ReleaseKind {
    if (secondary.any { it.equals("Compilation", true) }) return ReleaseKind.Compilation
    if (secondary.any { it.equals("Live", true) }) return ReleaseKind.Live
    return when (primary?.lowercase()) {
        "album" -> ReleaseKind.Album
        "ep" -> ReleaseKind.EP
        "single" -> ReleaseKind.Single
        else -> ReleaseKind.Other
    }
}

/** YouTube Music's, TheAudioDB's and Deezer's words for a release kind. Null when the word says nothing. */
internal fun kindFromLabel(label: String?): ReleaseKind? = when (label?.trim()?.lowercase()) {
    "album" -> ReleaseKind.Album
    "ep" -> ReleaseKind.EP
    "single" -> ReleaseKind.Single
    "compile", "compilation" -> ReleaseKind.Compilation
    "live" -> ReleaseKind.Live
    else -> null
}

// endregion

// region artist matching

/**
 * The MusicBrainz artist that is [name], or null when none is clearly it. A hit must carry the same
 * name and a search score of at least 85; among those the best score wins. Guessing a different
 * artist would file the wrong discography, so "no match" is the safe answer.
 */
internal fun pickMbArtist(hits: List<MbArtistHit>, name: String): MbArtistHit? =
    hits.filter { it.score >= 85 && sameArtistName(it.name, name) }.maxByOrNull { it.score }

/** The Deezer artist that is [name]; with namesakes, the one with the most fans. */
internal fun pickDeezerArtist(hits: List<DeezerArtistHit>, name: String): DeezerArtistHit? =
    hits.filter { sameArtistName(it.name, name) }.maxByOrNull { it.fans ?: 0L }

// endregion

/** YouTube Music thumbnails end in a size (`=w226-h226-l90-rj`); ask for a bigger one. */
internal fun enlargeYtThumbnail(url: String?, size: Int = 600): String? {
    if (url == null) return null
    return url.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")
}

internal fun coverArtArchiveUrl(releaseGroupId: String): String =
    "https://coverartarchive.org/release-group/$releaseGroupId/front-500"

/** Everything the sources returned for one artist's releases. Any list may be empty. */
internal data class ReleaseSources(
    val youTubeMusic: List<MusicAlbumSummary> = emptyList(),
    val musicBrainz: List<MbReleaseGroup> = emptyList(),
    val deezer: List<DeezerAlbum> = emptyList(),
    val audioDb: List<AudioDbAlbum> = emptyList(),
    val fanart: FanartArtist? = null,
)

private class Draft(var title: String, var kind: ReleaseKind) {
    var secondary: List<String> = emptyList()
    /** The best (earliest) date any source gave; the year is its first four digits. */
    var date: String? = null
    var mbid: String? = null
    var yt: MusicAlbumSummary? = null
    var deezer: DeezerAlbum? = null
    var audioDb: AudioDbAlbum? = null
    var kindFromMb = false
    var kindFromDeezer = false
}

internal fun mergeReleases(sources: ReleaseSources): List<LibraryRelease> {
    val drafts = ArrayList<Draft>()

    /**
     * The draft that is the same release as [title] and whose [free] says it can take one more
     * source. The exact spelling is tried before the edition-blind one; with [sameKindOnly] a draft
     * of another kind (a single and an album that share a name) is not a match.
     */
    fun find(title: String, wanted: ReleaseKind?, sameKindOnly: Boolean, free: (Draft) -> Boolean): Draft? {
        fun ok(d: Draft) = free(d) && (!sameKindOnly || wanted == null || d.kind == wanted)
        val strict = strictTitleKey(title)
        val loose = looseTitleKey(title)
        val base = baseTitleKey(title)
        return drafts.firstOrNull { ok(it) && strictTitleKey(it.title) == strict && (wanted == null || it.kind == wanted) }
            ?: drafts.firstOrNull { ok(it) && strictTitleKey(it.title) == strict }
            ?: drafts.firstOrNull { ok(it) && looseTitleKey(it.title) == loose && (wanted == null || it.kind == wanted) }
            ?: drafts.firstOrNull { ok(it) && looseTitleKey(it.title) == loose }
            // Last: the same title once credits are set aside (a cover named with its original artist).
            ?: drafts.firstOrNull { ok(it) && baseTitleKey(it.title) == base && (wanted == null || it.kind == wanted) }
            ?: drafts.firstOrNull { ok(it) && baseTitleKey(it.title) == base }
    }

    /**
     * Binds every item to a draft. Two passes: first only to a draft of the item's own kind, then,
     * for what is left, to any free draft of that title (a source that files the release under
     * another kind), and only then as a release of its own. That order keeps a single from taking
     * the slot of the album it shares a name with.
     */
    fun <T> attach(
        items: List<T>,
        title: (T) -> String,
        kind: (T) -> ReleaseKind?,
        free: (Draft) -> Boolean,
        create: (T) -> Draft,
        bind: (Draft, T) -> Unit,
    ) {
        val leftovers = ArrayList<T>()
        for (item in items) {
            val draft = find(title(item), kind(item), sameKindOnly = true, free = free)
            if (draft != null) bind(draft, item) else leftovers += item
        }
        for (item in leftovers) {
            val draft = find(title(item), kind(item), sameKindOnly = false, free = free)
                ?: create(item).also { drafts += it }
            bind(draft, item)
        }
    }

    for (group in sources.musicBrainz) {
        drafts += Draft(group.title, kindFromMusicBrainz(group.primaryType, group.secondaryTypes)).apply {
            secondary = group.secondaryTypes
            date = sanePartialDate(group.firstReleaseDate)
            mbid = group.id
            kindFromMb = true
        }
    }

    attach(
        items = sources.youTubeMusic,
        title = { it.title },
        kind = { kindFromLabel(it.kind) },
        free = { it.yt == null },
        create = { Draft(it.title, kindFromLabel(it.kind) ?: ReleaseKind.Album) },
        bind = { draft, album ->
            draft.yt = album
            draft.date = earlierReleaseDate(draft.date, album.year)
            // MusicBrainz's kind is the authority; without it, YouTube Music's shelf is the next best.
            val kind = kindFromLabel(album.kind)
            if (!draft.kindFromMb && kind != null) draft.kind = kind
        },
    )

    attach(
        items = sources.deezer,
        title = { it.title },
        kind = { kindFromLabel(it.recordType) },
        free = { it.deezer == null },
        create = { Draft(it.title, kindFromLabel(it.recordType) ?: ReleaseKind.Album) },
        bind = { draft, album ->
            draft.deezer = album
            draft.date = earlierReleaseDate(draft.date, album.releaseDate)
            val kind = kindFromLabel(album.recordType)
            if (!draft.kindFromMb && kind != null) {
                draft.kind = kind
                draft.kindFromDeezer = true
            }
        },
    )

    // TheAudioDB names the MusicBrainz release group, which is a better join than any title.
    val audioLeft = ArrayList<AudioDbAlbum>()
    for (album in sources.audioDb) {
        val byId = album.musicBrainzId?.let { id -> drafts.firstOrNull { it.mbid == id && it.audioDb == null } }
        if (byId != null) byId.audioDb = album.also { byId.date = earlierReleaseDate(byId.date, it.year) } else audioLeft += album
    }
    attach(
        items = audioLeft,
        title = { it.title },
        kind = { kindFromLabel(it.format) },
        free = { it.audioDb == null },
        create = { Draft(it.title, kindFromLabel(it.format) ?: ReleaseKind.Album) },
        bind = { draft, album ->
            draft.audioDb = album
            draft.date = earlierReleaseDate(draft.date, album.year)
            if (draft.mbid == null) draft.mbid = album.musicBrainzId
            val kind = kindFromLabel(album.format)
            if (!draft.kindFromMb && !draft.kindFromDeezer && kind != null) draft.kind = kind
        },
    )

    return drafts.map { it.toRelease(sources.fanart) }
        .sortedWith(
            compareBy<LibraryRelease> { it.kind.ordinal }
                .thenByDescending { it.releaseDate ?: it.year ?: "" }
                .thenBy { it.title.lowercase() },
        )
}

private fun Draft.toRelease(fanart: FanartArtist?): LibraryRelease {
    val fan = mbid?.let { fanart?.releases?.get(it) }
    val covers = buildList {
        enlargeYtThumbnail(yt?.thumbnailUrl)?.let(::add)
        deezer?.cover?.let(::add)
        audioDb?.art?.covers?.forEach(::add)
        fan?.covers?.forEach(::add)
        mbid?.let { add(coverArtArchiveUrl(it)) }
    }.distinct()
    val genres = listOfNotNull(audioDb?.genre).filter { it.isNotBlank() }
    val key = mbid ?: yt?.browseId ?: deezer?.id?.let { "deezer:$it" } ?: "t:${looseTitleKey(title)}:${kind.name}"
    return LibraryRelease(
        key = key,
        title = yt?.title ?: title,
        kind = kind,
        secondaryTypes = secondary,
        year = sanePartialDate(date)?.take(4),
        releaseDate = sanePartialDate(date),
        ytBrowseId = yt?.browseId,
        musicBrainzId = mbid,
        deezerId = deezer?.id,
        audioDbId = audioDb?.id,
        label = audioDb?.label,
        genres = genres,
        style = audioDb?.style,
        mood = audioDb?.mood,
        description = audioDb?.description,
        explicit = deezer?.explicit,
        art = ReleaseArtwork(
            covers = covers,
            cdArt = fan?.cdArt ?: audioDb?.art?.cdArt,
            back = audioDb?.art?.back,
            spine = audioDb?.art?.spine,
            case3d = audioDb?.art?.case3d,
        ),
    )
}

/** Fills the per-album details Deezer only gives on the album's own page. */
internal fun LibraryRelease.withDeezerDetail(detail: DeezerAlbumDetail): LibraryRelease {
    val date = earlierReleaseDate(releaseDate ?: year, detail.releaseDate)
    return copy(
        trackCount = detail.trackCount ?: trackCount,
        durationSeconds = detail.durationSeconds ?: durationSeconds,
        label = label ?: detail.label,
        genres = (genres + detail.genres).distinct(),
        upc = detail.upc ?: upc,
        // fork:original-date — Deezer's page dates the edition it lists, which can be a reissue; never move a release later.
        releaseDate = date,
        year = date?.take(4) ?: year,
    )
}

/** A YouTube Music album page knows its tracks; use that count and length when Deezer does not. */
internal fun LibraryRelease.withTrackFacts(trackCount: Int, durationSeconds: Int): LibraryRelease = copy(
    trackCount = this.trackCount ?: trackCount.takeIf { it > 0 },
    durationSeconds = this.durationSeconds ?: durationSeconds.takeIf { it > 0 },
)

// region artist

/** Everything the sources said about one artist, before it is folded into a [LibraryArtist]. */
internal data class ArtistSources(
    val musicBrainz: MbArtist? = null,
    val deezer: DeezerArtistHit? = null,
    val audioDb: AudioDbArtist? = null,
    val fanart: FanartArtist? = null,
    val wikipedia: String? = null,
)

/**
 * Folds the sources into one artist. Where two sources have the same thing the better one wins:
 * Fanart.tv's art over TheAudioDB's, TheAudioDB's biography over Wikipedia's, MusicBrainz's facts
 * (type, dates, country, links) over TheAudioDB's. Pure.
 */
internal fun assembleArtist(
    id: String,
    name: String,
    youTubeThumbnail: String?,
    sources: ArtistSources,
    releases: List<LibraryRelease>,
    addedAtEpochMs: Long,
    nowEpochMs: Long,
): LibraryArtist {
    val mb = sources.musicBrainz
    val deezer = sources.deezer
    val audio = sources.audioDb
    val fan = sources.fanart
    val audioArt = audio?.art ?: ArtistArtwork()
    val genres = (mb?.genres.orEmpty() + listOfNotNull(audio?.genre)).map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    val tags = mb?.tags.orEmpty().filter { tag -> genres.none { it.equals(tag, ignoreCase = true) } }
    val biography = audio?.biography ?: sources.wikipedia
    val links = (mb?.links.orEmpty() + audio?.links.orEmpty()).distinctBy { it.url.trimEnd('/').lowercase() }
    val usedSources = buildList {
        add("YouTube Music")
        if (mb != null) add("MusicBrainz")
        if (deezer != null) add("Deezer")
        if (audio != null) add("TheAudioDB")
        if (fan != null) add("Fanart.tv")
        if (sources.wikipedia != null && audio?.biography == null) add("Wikipedia")
    }
    return LibraryArtist(
        id = id,
        name = name,
        sortName = mb?.sortName,
        musicBrainzId = mb?.id ?: audio?.musicBrainzId,
        deezerId = deezer?.id,
        audioDbId = audio?.id,
        type = mb?.type,
        country = mb?.country ?: audio?.country,
        area = mb?.area,
        beginYear = mb?.beginYear ?: audio?.bornYear ?: audio?.formedYear,
        endYear = mb?.endYear ?: audio?.disbandedYear,
        ended = mb?.ended ?: (audio?.disbandedYear != null),
        disambiguation = mb?.disambiguation,
        genres = genres,
        tags = tags,
        style = audio?.style,
        mood = audio?.mood,
        biography = biography,
        biographySource = when {
            audio?.biography != null -> "TheAudioDB"
            sources.wikipedia != null -> "Wikipedia"
            else -> null
        },
        website = mb?.website ?: audio?.website,
        links = links,
        fans = deezer?.fans,
        rating = mb?.rating,
        art = ArtistArtwork(
            thumb = audioArt.thumb ?: fan?.thumbs?.firstOrNull() ?: deezer?.picture ?: youTubeThumbnail,
            logo = fan?.logos?.firstOrNull() ?: audioArt.logo,
            logoHd = fan?.hdLogos?.firstOrNull(),
            clearArt = audioArt.clearArt,
            cutout = audioArt.cutout,
            banner = fan?.banners?.firstOrNull() ?: audioArt.banner,
            wideThumb = audioArt.wideThumb,
            backgrounds = (fan?.backgrounds.orEmpty() + audioArt.backgrounds).distinct(),
        ),
        releases = releases,
        sources = usedSources,
        addedAtEpochMs = addedAtEpochMs,
        updatedAtEpochMs = nowEpochMs,
    )
}

// endregion
