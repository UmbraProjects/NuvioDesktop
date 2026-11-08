package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.booklet.AlbumBooklet
import com.nuvio.app.features.music.booklet.BookletCache
import com.nuvio.app.features.music.booklet.BookletEntry
import com.nuvio.app.features.music.booklet.BookletSource
import com.nuvio.app.features.music.booklet.decodeBookletEntry
import com.nuvio.app.features.music.booklet.encodeBookletEntry
import com.nuvio.app.features.music.booklet.isFresh
import com.nuvio.app.features.music.defaultMusicCatalog
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * fork:lenses - Lineage without the AI. When no AI key is set, or the AI cannot answer, the Lineage tab fills from open
 * data instead:
 *
 *  - Wikidata's "influenced by" (P737): the artists this one names as influences give Before, the artists that name
 *    this one give After. Their albums and release dates come from the same query, and Wikidata's sitelink count (how
 *    many Wikipedias have a page for the album) stands in for how well known each album is.
 *  - YouTube Music's "Fans might also like" on the artist's page fills what Wikidata leaves empty: each related artist's
 *    nearest album before the year goes in Before, the nearest after in After. That is shared listeners, not influence,
 *    and the panel says so.
 *
 * Neither needs a key. The result is kept for a month (a day when nothing was found) in the booklet cache, in the same
 * JSON shape the AI answers with, so [parseLineage] reads both.
 */

private val log = Logger.withTag("OpenLineage")

private val WIKIDATA_JSON = Json { ignoreUnknownKeys = true }

/** One album of an influence (or of an artist influenced), as the query returns it. */
internal data class WikidataLineageRow(val side: LineageSide, val artist: String, val album: String, val year: Int, val links: Int)

internal enum class LineageSide { Before, After }

/** Wikidata's policy asks every client to name itself. */
private const val WIKIDATA_USER_AGENT = "NuvioDesktop/1.0 (https://github.com/UmbraProjects/NuvioDesktop) lineage"
private const val WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"

/** How many related artists' pages are read, at most, to fill the gaps. */
private const val RELATED_LOOKUPS = 10

private const val OPEN_LINEAGE_TTL_MS = 30L * 24 * 60 * 60 * 1000
private const val OPEN_LINEAGE_MISS_TTL_MS = 24L * 60 * 60 * 1000

/**
 * The query for [artist] (matched by its English label among entries with a MusicBrainz artist id, so a film or a
 * person who is not a musician never matches): the albums of everyone it names as an influence, and of everyone who
 * names it, with their release dates and sitelink counts.
 */
internal fun wikidataLineageQuery(artist: String): String {
    val name = artist.trim().replace("\\", "\\\\").replace("\"", "\\\"")
    return """
        SELECT ?dir ?infName ?albumName ?date ?links WHERE {
          ?artist rdfs:label "$name"@en ; wdt:P434 ?mbid .
          { ?artist wdt:P737 ?inf . BIND("before" AS ?dir) } UNION { ?inf wdt:P737 ?artist . BIND("after" AS ?dir) }
          ?inf rdfs:label ?infName . FILTER(LANG(?infName) = "en")
          VALUES ?type { wd:Q482994 wd:Q208569 }
          ?album wdt:P175 ?inf ; wdt:P31 ?type ; wdt:P577 ?date ; wikibase:sitelinks ?links .
          ?album rdfs:label ?albumName . FILTER(LANG(?albumName) = "en")
        } LIMIT 3000
    """.trimIndent()
}

/** The query service's JSON answer as rows; rows missing a name, title or year are dropped. */
internal fun parseWikidataLineage(body: String): List<WikidataLineageRow> {
    val root = runCatching { WIKIDATA_JSON.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
    val bindings = ((root["results"] as? JsonObject)?.get("bindings") as? JsonArray).orEmpty()
    return bindings.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        fun value(key: String): String? =
            ((row[key] as? JsonObject)?.get("value") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        val side = when (value("dir")) {
            "before" -> LineageSide.Before
            "after" -> LineageSide.After
            else -> return@mapNotNull null
        }
        WikidataLineageRow(
            side = side,
            artist = value("infName") ?: return@mapNotNull null,
            album = value("albumName") ?: return@mapNotNull null,
            year = lineageYear(value("date")) ?: return@mapNotNull null,
            links = value("links")?.toIntOrNull() ?: 0,
        )
    }
}

/**
 * Up to [take] records for [side]: each artist's best-known album on the right side of [albumYear] (most sitelinks,
 * nearer in time on a tie), the best-known artists first, then ordered nearest in time first as the panels list them.
 * The album's own artist is left out.
 */
internal fun pickWikidataLineage(
    rows: List<WikidataLineageRow>,
    side: LineageSide,
    albumYear: Int,
    ownArtist: String,
    take: Int,
    why: String,
): List<LineageRecord> {
    val own = lineageArtistKey(ownArtist)
    return rows.asSequence()
        .filter { it.side == side && lineageArtistKey(it.artist) != own }
        .filter { if (side == LineageSide.Before) it.year < albumYear else it.year > albumYear }
        .groupBy { lineageArtistKey(it.artist) }
        .values
        .mapNotNull { albums -> albums.maxWithOrNull(compareBy<WikidataLineageRow> { it.links }.thenBy { -kotlin.math.abs(it.year - albumYear) }) }
        .sortedByDescending { it.links }
        .take(take)
        .sortedBy { kotlin.math.abs(it.year - albumYear) }
        .map { LineageRecord(it.artist, it.album, it.year, why) }
}

/**
 * The related artist's album nearest to [albumYear] on [side] (albums and EPs; singles are left out), from the
 * releases its YouTube Music page lists; null when it has none dated on that side.
 */
internal fun pickRelatedRecord(artist: String, albums: List<MusicAlbumSummary>, side: LineageSide, albumYear: Int, why: String): LineageRecord? {
    val dated = albums
        .filter { !(it.kind ?: "").equals("Single", ignoreCase = true) }
        .mapNotNull { album -> lineageYear(album.year)?.let { album to it } }
    val pick = when (side) {
        LineageSide.Before -> dated.filter { it.second < albumYear }.maxByOrNull { it.second }
        LineageSide.After -> dated.filter { it.second > albumYear }.minByOrNull { it.second }
    } ?: return null
    return LineageRecord(artist, pick.first.title, pick.second, why)
}

/** The lineage as the AI's JSON shape, so it is kept and read back by the same code. */
internal fun encodeLineageJson(lineage: Lineage): String {
    fun records(list: List<LineageRecord>) = buildJsonArray {
        list.forEach { record ->
            add(buildJsonObject {
                put("artist", record.artist)
                put("title", record.title)
                put("year", record.year)
                put("why", record.why)
            })
        }
    }
    return buildJsonObject {
        put("before", records(lineage.before))
        put("after", records(lineage.after))
        lineage.beforeThread?.let { put("before_thread", it) }
        lineage.afterThread?.let { put("after_thread", it) }
        lineage.sources?.let { put("sources", it) }
    }.toString()
}

/** Which sources a kept open lineage came from, for the panels' footer. */
internal fun lineageSources(json: String): String? = runCatching {
    (WIKIDATA_JSON.parseToJsonElement(json).jsonObject["sources"] as? JsonPrimitive)?.content
}.getOrNull()

/** A plain line for the thread box, where the AI would write one: what the records on this side have in common. */
internal fun openLineageThread(side: LineageSide, artist: String, albumYear: Int, fromWikidata: Int, fromRelated: Int): String? {
    if (fromWikidata == 0 && fromRelated == 0) return null
    val influence = when (side) {
        LineageSide.Before -> "Artists $artist names as influences, with a record they made before $albumYear"
        LineageSide.After -> "Artists who name $artist as an influence, with a record they made after $albumYear"
    }
    val related = when (side) {
        LineageSide.Before -> "artists YouTube Music's listeners pair with $artist, with their nearest album before it"
        LineageSide.After -> "artists YouTube Music's listeners pair with $artist, with their nearest album after it"
    }
    return when {
        fromRelated == 0 -> "$influence."
        fromWikidata == 0 -> related.replaceFirstChar { it.uppercase() } + ". Shared listeners, not a claim of influence."
        else -> "$influence, then $related."
    }
}

internal fun lineageArtistKey(name: String): String = songKey(name.trim().removePrefix("The ").removePrefix("the "))

internal object OpenLineage {
    private val lock = Mutex()

    private fun key(albumId: String) = "lineage-open1|$albumId"

    private fun now(): Long = LibraryClock.nowEpochMs()

    /** What is kept for [albumId], never making a request. */
    fun peek(albumId: String, artist: String, year: Int): Lineage? {
        val entry = decodeBookletEntry(BookletCache.get(key(albumId)))
            ?.takeIf { it.isFresh(now(), OPEN_LINEAGE_TTL_MS, OPEN_LINEAGE_MISS_TTL_MS) } ?: return null
        return entry.booklet?.intro?.let { json -> parseLineage(json, year, artist)?.copy(byAi = false, sources = lineageSources(json)) }
    }

    /**
     * The album's lineage from Wikidata, filled out from the artist's related artists on YouTube Music
     * ([artistBrowseId], when known). Null when neither names anything.
     */
    suspend fun load(albumId: String, artist: String, artistBrowseId: String?, year: Int): Lineage? = lock.withLock {
        decodeBookletEntry(BookletCache.get(key(albumId)))
            ?.takeIf { it.isFresh(now(), OPEN_LINEAGE_TTL_MS, OPEN_LINEAGE_MISS_TTL_MS) }
            ?.let { kept -> return@withLock kept.booklet?.intro?.let { json -> parseLineage(json, year, artist)?.copy(byAi = false, sources = lineageSources(json)) } }

        val wikidata = wikidataRows(artist)
        val before = pickWikidataLineage(wikidata, LineageSide.Before, year, artist, LINEAGE_ROWS, "Named as an influence").toMutableList()
        val after = pickWikidataLineage(wikidata, LineageSide.After, year, artist, LINEAGE_ROWS, "Names $artist as an influence").toMutableList()
        val wikiBefore = before.size
        val wikiAfter = after.size

        if (artistBrowseId != null && (before.size < LINEAGE_ROWS || after.size < LINEAGE_ROWS)) {
            fillFromRelated(artistBrowseId, artist, year, before, after)
        }
        val relatedBefore = before.size - wikiBefore
        val relatedAfter = after.size - wikiAfter

        val sources = listOfNotNull(
            "Wikidata".takeIf { wikiBefore + wikiAfter > 0 },
            "YouTube Music".takeIf { relatedBefore + relatedAfter > 0 },
        ).joinToString(" and ").takeIf { it.isNotEmpty() }?.let { "From $it" }
        val lineage = Lineage(
            before = before.sortedBy { kotlin.math.abs(it.year - year) },
            after = after.sortedBy { kotlin.math.abs(it.year - year) },
            beforeThread = openLineageThread(LineageSide.Before, artist, year, wikiBefore, relatedBefore),
            afterThread = openLineageThread(LineageSide.After, artist, year, wikiAfter, relatedAfter),
            byAi = false,
            sources = sources,
        ).takeIf { it.before.isNotEmpty() || it.after.isNotEmpty() }
        val kept = lineage?.let { AlbumBooklet(encodeLineageJson(it), emptyList(), emptyMap(), BookletSource.Wikipedia) }
        BookletCache.put(key(albumId), encodeBookletEntry(BookletEntry(now(), kept)))
        log.i { "open lineage for $artist ($year): before $wikiBefore wikidata + $relatedBefore related, after $wikiAfter + $relatedAfter" }
        lineage
    }

    private suspend fun wikidataRows(artist: String): List<WikidataLineageRow> {
        val url = "$WIKIDATA_SPARQL?format=json&query=" + wikidataLineageQuery(artist).encodeURLParameter()
        val body = try {
            withTimeoutOrNull(20_000L) {
                httpGetTextWithHeaders(url, mapOf("User-Agent" to WIKIDATA_USER_AGENT, "Accept" to "application/sparql-results+json"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.d { "Wikidata lineage for $artist failed: ${error.message}" }
            null
        } ?: return emptyList()
        return parseWikidataLineage(body)
    }

    /** Tops up both sides from the related artists on [artistBrowseId]'s page, each artist at most once a side. */
    private suspend fun fillFromRelated(
        artistBrowseId: String,
        artist: String,
        year: Int,
        before: MutableList<LineageRecord>,
        after: MutableList<LineageRecord>,
    ) {
        val catalog = defaultMusicCatalog()
        val related = runCatching { catalog.artist(artistBrowseId) }.getOrNull()?.related.orEmpty()
        val why = "Fans of $artist play them"
        for (other in related.take(RELATED_LOOKUPS)) {
            if (before.size >= LINEAGE_ROWS && after.size >= LINEAGE_ROWS) break
            val otherId = other.browseId ?: continue
            val page = try {
                catalog.artist(otherId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue
            val who = lineageArtistKey(page.name)
            if (before.size < LINEAGE_ROWS && before.none { lineageArtistKey(it.artist) == who }) {
                pickRelatedRecord(page.name, page.albums, LineageSide.Before, year, why)?.let(before::add)
            }
            if (after.size < LINEAGE_ROWS && after.none { lineageArtistKey(it.artist) == who }) {
                pickRelatedRecord(page.name, page.albums, LineageSide.After, year, why)?.let(after::add)
            }
        }
    }
}
