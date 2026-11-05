package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * fork:credits - the Who made it lens: everyone MusicBrainz credits on an album (writers, producers, players and singers,
 * mixing and engineering), read from the same release lookup the song notes use, and where those people turn up on the
 * other albums of the library (whose credits the facts filler looks up in the background). Pure, so it is tested without
 * Compose or storage.
 */

/** The kinds of credit, in the order the panel lists them. */
internal enum class CreditKind(val label: String, val plural: String) {
    Writer("Writer", "Writers"),
    Producer("Producer", "Producers"),
    Player("Player", "Players"),
    Engineer("Engineer", "Engineers"),
}

/** One credit: [person] did [kind] ([detail]: "guitar", "lead vocals", "mix") on track [track] (1 up; 0 = the whole album). */
internal data class Credit(val person: String, val mbid: String?, val kind: CreditKind, val detail: String?, val track: Int)

private val PEOPLE_JSON = Json { ignoreUnknownKeys = true }

private fun JsonElement?.o() = this as? JsonObject
private fun JsonElement?.a() = this as? JsonArray
private fun JsonObject.t(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

private val WRITERS = setOf("writer", "composer", "lyricist", "librettist")
private val ENGINEERS = setOf("mix", "engineer", "recording", "mastering", "audio", "sound", "balance", "mix-dj")
private val PLAYERS = setOf("instrument", "vocal", "performer", "programming", "performing orchestra", "conductor", "chorus master")

/** The credit kind of a MusicBrainz relation type, or null for one the lens leaves out (a photo, a design credit). */
private fun kindOf(type: String): CreditKind? = when (type) {
    in WRITERS -> CreditKind.Writer
    "producer", "co-producer", "executive producer" -> CreditKind.Producer
    in PLAYERS -> CreditKind.Player
    in ENGINEERS -> CreditKind.Engineer
    else -> null
}

/** The relation's detail: its attributes ("guitar", "lead vocals"), else a word for the type ("mix", "mastering"). */
private fun detailOf(type: String, rel: JsonObject): String? {
    val attrs = rel["attributes"].a().orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .filter { it !in setOf("additional", "guest", "solo", "co", "assistant", "executive") }
    return when {
        attrs.isNotEmpty() -> attrs.joinToString(", ")
        type == "vocal" -> "vocals"
        type in ENGINEERS || type == "programming" || type == "conductor" -> type
        else -> null
    }
}

private fun JsonObject.credits(track: Int, into: MutableList<Credit>) {
    for (rel in this["relations"].a().orEmpty().mapNotNull { it.o() }) {
        val type = rel.t("type")?.lowercase() ?: continue
        val artist = rel["artist"].o()
        if (artist != null) {
            val kind = kindOf(type) ?: continue
            if (kind == CreditKind.Writer) continue // writers come through the work
            artist.t("name")?.let { into += Credit(it, artist.t("id"), kind, detailOf(type, rel), track) }
        }
        val work = rel["work"].o() ?: continue
        for (wr in work["relations"].a().orEmpty().mapNotNull { it.o() }) {
            val wt = wr.t("type")?.lowercase() ?: continue
            if (wt !in WRITERS) continue
            val who = wr["artist"].o() ?: continue
            who.t("name")?.let { into += Credit(it, who.t("id"), CreditKind.Writer, wt.takeIf { w -> w != "writer" }, track) }
        }
    }
}

/**
 * Every credit on a MusicBrainz release looked up with its recordings and their relationships: per track (counted 1 up
 * across discs) and for the whole release (track 0). Repeats are dropped.
 */
internal fun parseMbPeople(body: String): List<Credit> {
    val release = runCatching { PEOPLE_JSON.parseToJsonElement(body) }.getOrNull().o() ?: return emptyList()
    val out = ArrayList<Credit>()
    release.credits(0, out)
    var number = 0
    for (medium in release["media"].a().orEmpty()) {
        for (track in medium.o()?.get("tracks").a().orEmpty()) {
            number++
            track.o()?.get("recording").o()?.credits(number, out)
        }
    }
    return out.distinct()
}

/** A person as the panel lists them: all their credits on the album, summed up. */
internal data class CreditPerson(
    val name: String,
    val mbid: String?,
    val kinds: Set<CreditKind>,
    /** "Guitar, backing vocals", "Producer", "Mix": what they did, most common first. */
    val what: String,
    /** The tracks (1 up) they are on; empty with [wholeAlbum]. */
    val tracks: Set<Int>,
    val wholeAlbum: Boolean,
) {
    /** "6 songs", "1 song", "All songs". */
    fun songsText(trackCount: Int): String = when {
        wholeAlbum || (trackCount > 0 && tracks.size >= trackCount) -> "All songs"
        else -> "${tracks.size} song${if (tracks.size == 1) "" else "s"}"
    }

    /** The key a person is matched by across albums: their MusicBrainz id, else their name. */
    val key: String get() = mbid ?: "name:${songKey(name)}"
}

/** The people on an album, most credited first (then by kind, then name). */
internal fun creditPeople(credits: List<Credit>): List<CreditPerson> =
    credits.groupBy { it.mbid ?: "name:${songKey(it.person)}" }.map { (_, cs) ->
        val details = cs.groupingBy { it.detail ?: it.kind.label }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        CreditPerson(
            name = cs.first().person,
            mbid = cs.first().mbid,
            kinds = cs.map { it.kind }.distinct().sortedBy { it.ordinal }.toSet(),
            what = details.take(3).joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } },
            tracks = cs.map { it.track }.filter { it > 0 }.distinct().sorted().toSet(),
            wholeAlbum = cs.any { it.track == 0 },
        )
    }.sortedWith(
        compareByDescending<CreditPerson> { if (it.wholeAlbum) Int.MAX_VALUE else it.tracks.size }
            .thenBy { it.kinds.minOf { k -> k.ordinal } }
            .thenBy { it.name.lowercase() },
    )

/** How many people have each kind of credit. */
internal fun creditCounts(people: List<CreditPerson>): Map<CreditKind, Int> =
    CreditKind.entries.associateWith { k -> people.count { k in it.kinds } }

/** One album's kept credits: where it is in the library, and its credits (empty when MusicBrainz has none). */
internal data class AlbumCredits(val atMs: Long, val albumId: String?, val artist: String, val album: String, val credits: List<Credit>, val found: Boolean)

internal fun encodeAlbumCredits(a: AlbumCredits): String = buildJsonObject {
    put("at", a.atMs)
    a.albumId?.let { put("id", it) }
    put("ar", a.artist)
    put("al", a.album)
    put("f", a.found)
    put("c", buildJsonArray {
        a.credits.forEach { c ->
            add(buildJsonObject {
                put("p", c.person)
                c.mbid?.let { put("m", it) }
                put("k", c.kind.ordinal)
                c.detail?.let { put("d", it) }
                put("t", c.track)
            })
        }
    })
}.toString()

internal fun decodeAlbumCredits(raw: String?): AlbumCredits? {
    val o = raw?.let { runCatching { PEOPLE_JSON.parseToJsonElement(it) }.getOrNull() }.o() ?: return null
    val credits = o["c"].a().orEmpty().mapNotNull { el ->
        val c = el.o() ?: return@mapNotNull null
        val kind = (c["k"] as? JsonPrimitive)?.intOrNull?.let { CreditKind.entries.getOrNull(it) } ?: return@mapNotNull null
        Credit(c.t("p") ?: return@mapNotNull null, c.t("m"), kind, c.t("d"), (c["t"] as? JsonPrimitive)?.intOrNull ?: 0)
    }
    return AlbumCredits(
        atMs = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
        albumId = o.t("id"),
        artist = o.t("ar") ?: return null,
        album = o.t("al") ?: return null,
        credits = credits,
        found = (o["f"] as? JsonPrimitive)?.content == "true",
    )
}

/** The key an album's credits are kept under: its artist's and title's names, brackets and case aside. */
internal fun creditsKey(artist: String, album: String): String = "people3|${songKey(artist)}|${songKey(album)}"

/** Another record [person] is credited on: its title, artist, what they did on it, and the album id to open it with. */
internal data class ElsewhereRecord(val album: String, val artist: String, val what: String, val albumId: String?)

/**
 * The other albums of the library [person] is credited on, from [kept] (every album's kept credits), [here] (this album's
 * key) left out; sorted by artist then album.
 */
internal fun elsewhere(person: CreditPerson, kept: Map<String, AlbumCredits>, here: String): List<ElsewhereRecord> =
    kept.filterKeys { it != here }.values.mapNotNull { a ->
        val theirs = a.credits.filter { (it.mbid ?: "name:${songKey(it.person)}") == person.key }
        if (theirs.isEmpty()) return@mapNotNull null
        val what = theirs.groupingBy { it.detail ?: it.kind.label }.eachCount().entries.sortedByDescending { it.value }
            .take(2).joinToString(", ") { it.key.replaceFirstChar { c -> c.uppercase() } }
        ElsewhereRecord(a.album, a.artist, what, a.albumId)
    }.sortedWith(compareBy<ElsewhereRecord> { it.artist.lowercase() }.thenBy { it.album.lowercase() })

/** How long a "MusicBrainz has no such release" answer is kept before asking again. */
internal const val CREDITS_NONE_TTL_MS = 30L * 24 * 3_600_000
