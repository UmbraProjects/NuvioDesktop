package com.nuvio.app.features.music.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.Normalizer
import java.util.UUID

/*
 * fork:music
 *
 * The MusicBrainz offline copy's file format, its builder and its reader. The JSON dumps are one artist (or release
 * group) per line, about 18 GB each unpacked, mostly recording and work relations nothing here reads. [JsonBytes]
 * walks a line's bytes and decodes only the fields kept, so a line is never turned into a tree.
 *
 * Files in the copy's folder (all big-endian):
 *  - `artists.dat` / `groups.dat`: one pruned record per artist / release group, in the web service's JSON shape.
 *  - `artists.off` / `groups.off`: N+1 offsets into the .dat file.
 *  - `artists.ids`: (mbid high, mbid low, ordinal) entries, 20 bytes, sorted by the high half.
 *  - `artist-groups.ids`: (artist mbid high, low, group ordinal), 20 bytes, sorted by the artist's high half.
 *  - `names.idx`: longs, 38-bit hash of a name key | alias bit | 24-bit ordinal, sorted.
 *  - `tags.idx`: longs, 29-bit hash of a tag | 10 bits of (1023 - votes) | 24-bit ordinal, sorted: a tag's artists
 *    come most voted first.
 *  - `meta.json`: the dump stamp and counts. Written last, so a folder without it is an unfinished build.
 */

internal const val MB_INDEX_VERSION = 1
private const val ORDINAL_BITS = 24
private const val ORDINAL_MASK = (1L shl ORDINAL_BITS) - 1
private const val MAX_ORDINAL = ORDINAL_MASK.toInt()
// Packed keys stay under 63 bits so a signed sort orders them as the unsigned searches expect.
private const val NAME_HASH_BITS = 38
private const val TAG_HASH_BITS = 29
private const val TAG_VOTE_BITS = 10
private const val TAG_VOTE_MAX = (1 shl TAG_VOTE_BITS) - 1
private const val KEPT_TAGS = 15

private val INDEX_JSON = Json { ignoreUnknownKeys = true }

// region keys

/** "Beyoncé", "BEYONCE" and "Beyonce!" all read "beyonce". A name of symbols only ("!!!") keeps its symbols. */
internal fun mbNameKey(name: String): String {
    val folded = Normalizer.normalize(name, Normalizer.Form.NFKD)
    val out = StringBuilder(folded.length)
    for (c in folded) {
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
        if (Character.isLetterOrDigit(c)) out.append(c.lowercaseChar())
    }
    return if (out.isNotEmpty()) out.toString() else name.trim().lowercase()
}

internal fun mbTagKey(tag: String): String = tag.trim().lowercase()

private fun fnv64(text: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (c in text) {
        h = (h xor c.code.toLong()) * 0x100000001b3L
    }
    return h
}

private fun nameHash(key: String): Long = fnv64(key) ushr (64 - NAME_HASH_BITS)
private fun tagHash(key: String): Long = fnv64(key) ushr (64 - TAG_HASH_BITS)

private fun uuidHalves(id: String): Pair<Long, Long>? = runCatching {
    val u = UUID.fromString(id)
    u.mostSignificantBits to u.leastSignificantBits
}.getOrNull()

// endregion

// region JSON scanning

/** A forward-only reader over one JSON line's bytes. Strings are decoded only when asked for. */
internal class JsonBytes(private val b: ByteArray, private val end: Int) {
    private var i = 0

    private fun ws() {
        while (i < end) {
            val c = b[i].toInt()
            if (c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09) i++ else return
        }
    }

    fun peek(): Int {
        ws()
        return if (i < end) b[i].toInt() and 0xFF else -1
    }

    private fun take(c: Char) {
        ws()
        if (i >= end || b[i].toInt() != c.code) error("expected '$c' at $i")
        i++
    }

    /** Calls [field] with each key of the object at the cursor; [field] must read or [skip] the value. */
    inline fun fields(field: (String) -> Unit) {
        beginObject()
        while (nextField()) field(string())
    }

    /** Calls [element] for each value of the array at the cursor; [element] must read or [skip] it. */
    inline fun elements(element: () -> Unit) {
        beginArray()
        while (nextElement()) element()
    }

    fun beginObject() = take('{')

    /** True when another key follows (the cursor is then on it); false at the closing brace, which is consumed. */
    fun nextField(): Boolean = next('}')

    fun beginArray() = take('[')

    fun nextElement(): Boolean = next(']')

    // The dumps are valid JSON, so a comma is simply stepped over; no state is kept between calls (they nest).
    private fun next(close: Char): Boolean {
        ws()
        if (i < end && b[i].toInt() == ','.code) {
            i++
            ws()
        }
        if (i >= end || b[i].toInt() == close.code) {
            i++
            return false
        }
        return true
    }

    /** The key or string at the cursor, and the colon after a key. */
    fun string(): String {
        take('"')
        val sb = StringBuilder()
        var runStart = i
        while (true) {
            val c = b[i].toInt()
            if (c == '"'.code) {
                if (i > runStart) sb.append(String(b, runStart, i - runStart, Charsets.UTF_8))
                i++
                break
            }
            if (c == '\\'.code) {
                if (i > runStart) sb.append(String(b, runStart, i - runStart, Charsets.UTF_8))
                val e = b[i + 1].toInt().toChar()
                i += 2
                when (e) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        sb.append(String(b, i, 4, Charsets.US_ASCII).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                runStart = i
                continue
            }
            i++
        }
        ws()
        if (i < end && b[i].toInt() == ':'.code) i++
        return sb.toString()
    }

    /** A string, or null for `null` or any other kind of value (which is skipped). */
    fun stringOrNull(): String? = if (peek() == '"'.code) string() else {
        skip()
        null
    }

    /** Steps over the value at the cursor without decoding it. */
    fun skip() {
        when (peek()) {
            '"'.code -> skipString()
            '{'.code, '['.code -> {
                var depth = 0
                while (i < end) {
                    when (b[i].toInt()) {
                        '"'.code -> {
                            skipString()
                            continue
                        }
                        '{'.code, '['.code -> depth++
                        '}'.code, ']'.code -> {
                            depth--
                            if (depth == 0) {
                                i++
                                return
                            }
                        }
                    }
                    i++
                }
            }
            else -> while (i < end) {
                val c = b[i].toInt()
                if (c == ','.code || c == '}'.code || c == ']'.code || c == 0x20 || c == 0x0A) return
                i++
            }
        }
    }

    private fun skipString() {
        i++ // opening quote
        while (i < end) {
            when (b[i].toInt()) {
                '\\'.code -> i += 2
                '"'.code -> {
                    i++
                    return
                }
                else -> i++
            }
        }
    }

    /** The value at the cursor as a tree. For small values only. */
    fun value(): JsonElement = when (peek()) {
        '"'.code -> JsonPrimitive(string())
        '{'.code -> {
            val map = LinkedHashMap<String, JsonElement>()
            fields { key -> map[key] = value() }
            JsonObject(map)
        }
        '['.code -> {
            val list = ArrayList<JsonElement>()
            elements { list += value() }
            JsonArray(list)
        }
        else -> {
            val start = i
            skip()
            val text = String(b, start, i - start, Charsets.US_ASCII).trim()
            when (text) {
                "null" -> JsonNull
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> text.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text.toDoubleOrNull() ?: 0.0)
            }
        }
    }
}

// endregion

// region pruning

/** URL relation types the artist page reads (see `linkLabelFor`); the rest of an artist's links are dropped. */
private val KEPT_URL_TYPES = setOf(
    "official homepage", "wikipedia", "wikidata", "discogs", "allmusic", "bandcamp", "last.fm", "social network",
    "streaming", "free streaming", "youtube", "youtube music", "soundcloud", "imdb", "IMDb", "songkick",
)

/** One artist line pruned to what [parseMbArtist] and the searches read, with its name keys and tags for the indexes. */
internal class PrunedArtist(
    val id: String,
    val json: String,
    val nameKeys: Set<String>,
    val aliasKeys: Set<String>,
    val tagVotes: Map<String, Int>,
)

private fun JsonElement?.text(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }

private fun JsonElement?.count(): Int = ((this as? JsonObject)?.get("count") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

/** Tag (or genre) list of the dump, as {name, count}, most votes first, cut to [KEPT_TAGS]. */
private fun votes(list: JsonElement?): List<Pair<String, Int>> =
    (list as? JsonArray).orEmpty().mapNotNull { t -> t.text("name")?.let { it to t.count() } }
        .sortedByDescending { it.second }.take(KEPT_TAGS)

private fun voteArray(list: List<Pair<String, Int>>): JsonArray = buildJsonArray {
    list.forEach { (name, count) -> add(buildJsonObject { put("name", name); put("count", count) }) }
}

internal fun pruneArtistLine(line: ByteArray, length: Int): PrunedArtist? {
    val r = JsonBytes(line, length)
    var id: String? = null
    var name: String? = null
    var sortName: String? = null
    var type: String? = null
    var country: String? = null
    var disambiguation: String? = null
    var area: String? = null
    var span: JsonElement? = null
    var genres: JsonElement? = null
    var tags: JsonElement? = null
    var rating: JsonElement? = null
    val aliases = ArrayList<String>()
    val urls = ArrayList<Pair<String, String>>()
    r.fields { key ->
        when (key) {
            "id" -> id = r.stringOrNull()
            "name" -> name = r.stringOrNull()
            "sort-name" -> sortName = r.stringOrNull()
            "type" -> type = r.stringOrNull()
            "country" -> country = r.stringOrNull()
            "disambiguation" -> disambiguation = r.stringOrNull()
            "area" -> if (r.peek() == '{'.code) r.fields { k -> if (k == "name") area = r.stringOrNull() else r.skip() } else r.skip()
            "life-span" -> span = r.value()
            "genres" -> genres = r.value()
            "tags" -> tags = r.value()
            "rating" -> rating = r.value()
            "aliases" -> if (r.peek() == '['.code) r.elements {
                if (r.peek() == '{'.code) r.fields { k -> if (k == "name") r.stringOrNull()?.let(aliases::add) else r.skip() } else r.skip()
            } else r.skip()
            "relations" -> if (r.peek() == '['.code) r.elements {
                var targetType: String? = null
                var relType: String? = null
                var resource: String? = null
                if (r.peek() == '{'.code) r.fields { k ->
                    when (k) {
                        "target-type" -> targetType = r.stringOrNull()
                        "type" -> relType = r.stringOrNull()
                        "url" -> if (r.peek() == '{'.code) r.fields { u -> if (u == "resource") resource = r.stringOrNull() else r.skip() } else r.skip()
                        else -> r.skip()
                    }
                } else r.skip()
                val t = relType
                val url = resource
                if (targetType == "url" && t != null && url != null && t in KEPT_URL_TYPES &&
                    (t != "wikipedia" || "en.wikipedia.org/wiki/" in url)
                ) {
                    urls += t to url
                }
            } else r.skip()
            else -> r.skip()
        }
    }
    val artistId = id ?: return null
    val artistName = name ?: return null
    val genreVotes = votes(genres)
    val tagVotes = votes(tags)
    val ratingVotes = ((rating as? JsonObject)?.get("votes-count") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    // How well known the artist is, to order namesakes: links, votes and ratings all grow with attention.
    val weight = urls.size * 3 + genreVotes.sumOf { it.second } + tagVotes.sumOf { it.second } + ratingVotes
    val json = buildJsonObject {
        put("id", artistId)
        put("name", artistName)
        sortName?.let { put("sort-name", it) }
        type?.let { put("type", it) }
        country?.let { put("country", it) }
        disambiguation?.let { put("disambiguation", it) }
        area?.let { a -> put("area", buildJsonObject { put("name", a) }) }
        (span as? JsonObject)?.let { put("life-span", it) }
        if (genreVotes.isNotEmpty()) put("genres", voteArray(genreVotes))
        if (tagVotes.isNotEmpty()) put("tags", voteArray(tagVotes))
        (rating as? JsonObject)?.get("value")?.takeIf { it !is JsonNull }?.let { v -> put("rating", buildJsonObject { put("value", v) }) }
        if (urls.isNotEmpty()) {
            put("relations", buildJsonArray {
                urls.forEach { (t, u) -> add(buildJsonObject { put("type", t); put("url", buildJsonObject { put("resource", u) }) }) }
            })
        }
        put("w", weight)
    }.toString()
    val own = setOf(mbNameKey(artistName))
    // Every tag the artist carries (genres are tags too), for the tag index.
    val allVotes = LinkedHashMap<String, Int>()
    for ((t, c) in tagVotes + genreVotes) if (c > 0) allVotes[mbTagKey(t)] = maxOf(allVotes[mbTagKey(t)] ?: 0, c)
    return PrunedArtist(
        id = artistId,
        json = json,
        nameKeys = own,
        aliasKeys = aliases.map(::mbNameKey).toSet() - own,
        tagVotes = allVotes,
    )
}

/** One release group line pruned to what [parseMbReleaseGroups] reads, and the ids of the artists it credits. */
internal class PrunedGroup(val json: String, val artistIds: List<String>)

internal fun pruneGroupLine(line: ByteArray, length: Int): PrunedGroup? {
    val r = JsonBytes(line, length)
    var id: String? = null
    var title: String? = null
    var primary: String? = null
    var date: String? = null
    var secondary: JsonElement? = null
    val credited = ArrayList<String>()
    r.fields { key ->
        when (key) {
            "id" -> id = r.stringOrNull()
            "title" -> title = r.stringOrNull()
            "primary-type" -> primary = r.stringOrNull()
            "first-release-date" -> date = r.stringOrNull()
            "secondary-types" -> secondary = r.value()
            "artist-credit" -> if (r.peek() == '['.code) r.elements {
                if (r.peek() == '{'.code) r.fields { k ->
                    if (k == "artist" && r.peek() == '{'.code) {
                        r.fields { a -> if (a == "id") r.stringOrNull()?.let(credited::add) else r.skip() }
                    } else {
                        r.skip()
                    }
                } else r.skip()
            } else r.skip()
            else -> r.skip()
        }
    }
    val groupId = id ?: return null
    val groupTitle = title ?: return null
    val json = buildJsonObject {
        put("id", groupId)
        put("title", groupTitle)
        primary?.let { put("primary-type", it) }
        (secondary as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("secondary-types", it) }
        date?.let { put("first-release-date", it) }
    }.toString()
    return PrunedGroup(json, credited.distinct())
}

// endregion

// region primitive lists and sorting

private class LongList(capacity: Int = 1 shl 16) {
    var data = LongArray(capacity)
    var size = 0
    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
}

private class IntList(capacity: Int = 1 shl 16) {
    var data = IntArray(capacity)
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
}

/** Sorts the first [n] [keys] ascending (signed), moving [values] with them. */
private fun sortPairs(keys: LongArray, values: IntArray, n: Int) {
    val stack = IntArray(128)
    var top = 0
    stack[top++] = 0
    stack[top++] = n - 1
    while (top > 0) {
        var hi = stack[--top]
        var lo = stack[--top]
        while (hi - lo > 16) {
            val mid = (lo + hi) ushr 1
            val pivot = keys[mid]
            var a = lo
            var b = hi
            while (a <= b) {
                while (keys[a] < pivot) a++
                while (keys[b] > pivot) b--
                if (a <= b) {
                    val k = keys[a]; keys[a] = keys[b]; keys[b] = k
                    val v = values[a]; values[a] = values[b]; values[b] = v
                    a++
                    b--
                }
            }
            // Recurse into the smaller side by pushing the larger one, so the stack stays shallow.
            if (b - lo < hi - a) {
                stack[top++] = a; stack[top++] = hi
                hi = b
            } else {
                stack[top++] = lo; stack[top++] = b
                lo = a
            }
        }
        for (x in lo + 1..hi) {
            val k = keys[x]
            val v = values[x]
            var y = x - 1
            while (y >= lo && keys[y] > k) {
                keys[y + 1] = keys[y]
                values[y + 1] = values[y]
                y--
            }
            keys[y + 1] = k
            values[y + 1] = v
        }
    }
}

private fun writeLongs(path: Path, data: LongArray, n: Int) {
    DataOutputStream(BufferedOutputStream(Files.newOutputStream(path), 1 shl 20)).use { out ->
        for (x in 0 until n) out.writeLong(data[x])
    }
}

// endregion

// region builder

/**
 * Builds a copy into [dir] from the two dumps' lines. Artists first, then release groups (the group pass needs
 * nothing from the artist pass, so they could run in either order). [finish] writes the indexes and `meta.json`.
 */
internal class MusicBrainzIndexBuilder(private val dir: Path) : Closeable {
    private val artistOut = BufferedOutputStream(Files.newOutputStream(dir.resolve("artists.dat")), 1 shl 20)
    private val groupOut = BufferedOutputStream(Files.newOutputStream(dir.resolve("groups.dat")), 1 shl 20)
    // Every list is dropped as soon as its index is on disk (the build runs inside the app's 1 GB heap).
    private var artistOffsets = LongList(1 shl 20)
    private var groupOffsets = LongList(1 shl 20)
    private var artistBytes = 0L
    private var groupBytes = 0L
    private var idHigh = LongList(1 shl 20)
    private var idLow = LongList(1 shl 20)
    private var names = LongList(1 shl 20)
    private var tags = LongList(1 shl 20)
    private var creditHigh = LongList(1 shl 20)
    private var creditLow = LongList(1 shl 20)
    private var creditGroup = IntList(1 shl 20)
    private var artistsWritten = false

    var artists = 0
        private set
    var groups = 0
        private set

    fun addArtistLine(line: ByteArray, length: Int) {
        check(!artistsWritten) { "artists come before release groups" }
        if (artists >= MAX_ORDINAL) return
        val artist = runCatching { pruneArtistLine(line, length) }.getOrNull() ?: return
        val (high, low) = uuidHalves(artist.id) ?: return
        val ordinal = artists.toLong()
        val bytes = artist.json.toByteArray(Charsets.UTF_8)
        artistOffsets.add(artistBytes)
        artistOut.write(bytes)
        artistBytes += bytes.size
        artists++
        idHigh.add(high)
        idLow.add(low)
        for (key in artist.nameKeys) names.add((nameHash(key) shl (ORDINAL_BITS + 1)) or ordinal)
        for (key in artist.aliasKeys) names.add((nameHash(key) shl (ORDINAL_BITS + 1)) or (1L shl ORDINAL_BITS) or ordinal)
        for ((tag, votes) in artist.tagVotes) {
            val inverted = (TAG_VOTE_MAX - votes.coerceAtMost(TAG_VOTE_MAX)).toLong()
            tags.add((tagHash(tag) shl (TAG_VOTE_BITS + ORDINAL_BITS)) or (inverted shl ORDINAL_BITS) or ordinal)
        }
    }

    fun addGroupLine(line: ByteArray, length: Int) {
        if (!artistsWritten) writeArtists()
        val group = runCatching { pruneGroupLine(line, length) }.getOrNull() ?: return
        val ordinal = groups
        val bytes = group.json.toByteArray(Charsets.UTF_8)
        groupOffsets.add(groupBytes)
        groupOut.write(bytes)
        groupBytes += bytes.size
        groups++
        for (artistId in group.artistIds) {
            val (high, low) = uuidHalves(artistId) ?: continue
            creditHigh.add(high)
            creditLow.add(low)
            creditGroup.add(ordinal)
        }
    }

    /** The artist pass's files, written when the first release group arrives so its lists are freed for that pass. */
    private fun writeArtists() {
        artistsWritten = true
        artistOut.close()
        artistOffsets.add(artistBytes)
        writeLongs(dir.resolve("artists.off"), artistOffsets.data, artistOffsets.size)
        artistOffsets = LongList(1)
        // Sorted by the id's high half, in place; the low half and the ordinal follow through a permutation.
        val order = IntArray(artists) { it }
        sortPairs(idHigh.data, order, artists)
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(dir.resolve("artists.ids")), 1 shl 20)).use { out ->
            for (x in 0 until artists) {
                out.writeLong(idHigh.data[x])
                out.writeLong(idLow.data[order[x]])
                out.writeInt(order[x])
            }
        }
        idHigh = LongList(1)
        idLow = LongList(1)
        names.data.sort(0, names.size)
        writeLongs(dir.resolve("names.idx"), names.data, names.size)
        names = LongList(1)
        tags.data.sort(0, tags.size)
        writeLongs(dir.resolve("tags.idx"), tags.data, tags.size)
        tags = LongList(1)
    }

    fun finish(dump: String) {
        if (!artistsWritten) writeArtists()
        groupOut.close()
        groupOffsets.add(groupBytes)
        writeLongs(dir.resolve("groups.off"), groupOffsets.data, groupOffsets.size)
        groupOffsets = LongList(1)
        val n = creditGroup.size
        val order = IntArray(n) { it }
        sortPairs(creditHigh.data, order, n)
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(dir.resolve("artist-groups.ids")), 1 shl 20)).use { out ->
            for (x in 0 until n) {
                out.writeLong(creditHigh.data[x])
                out.writeLong(creditLow.data[order[x]])
                out.writeInt(creditGroup.data[order[x]])
            }
        }
        creditHigh = LongList(1)
        creditLow = LongList(1)
        creditGroup = IntList(1)

        val meta = buildJsonObject {
            put("version", MB_INDEX_VERSION)
            put("dump", dump)
            put("artists", artists)
            put("releaseGroups", groups)
        }
        Files.writeString(dir.resolve("meta.json"), meta.toString())
    }

    override fun close() {
        runCatching { artistOut.close() }
        runCatching { groupOut.close() }
    }
}

/**
 * Reads [input] line by line into [onLine] (the buffer is reused; only its first `length` bytes are the line).
 * [onBytes] gets the running count of bytes read, for progress.
 */
internal fun forEachJsonLine(input: InputStream, onLine: (ByteArray, Int) -> Unit) {
    var buf = ByteArray(1 shl 20)
    var len = 0
    val chunk = ByteArray(1 shl 16)
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        var start = 0
        for (x in 0 until n) {
            if (chunk[x] == '\n'.code.toByte()) {
                val part = x - start
                if (len + part > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + part))
                System.arraycopy(chunk, start, buf, len, part)
                len += part
                if (len > 0) onLine(buf, len)
                len = 0
                start = x + 1
            }
        }
        val rest = n - start
        if (rest > 0) {
            if (len + rest > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + rest))
            System.arraycopy(chunk, start, buf, len, rest)
            len += rest
        }
    }
    if (len > 0) onLine(buf, len)
}

/**
 * Moves [input], a tar stream, to the start of the entry whose name ends with [suffix] and returns a stream of just
 * that entry. Handles the base-256 sizes GNU tar writes for entries over 8 GB. Null when there is no such entry.
 */
internal fun openTarEntry(input: InputStream, suffix: String): InputStream? {
    val header = ByteArray(512)
    while (true) {
        if (input.readNBytes(header, 0, 512) < 512) return null
        if (header.all { it == 0.toByte() }) return null
        val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
        val size = tarSize(header)
        if (name.endsWith(suffix)) return LimitedStream(input, size)
        var toSkip = (size + 511) / 512 * 512
        while (toSkip > 0) {
            val skipped = input.skip(toSkip)
            if (skipped <= 0) {
                if (input.read() < 0) return null
                toSkip--
            } else {
                toSkip -= skipped
            }
        }
    }
}

private fun tarSize(header: ByteArray): Long {
    if (header[124].toInt() and 0x80 != 0) {
        var v = 0L
        for (x in 125 until 136) v = (v shl 8) or (header[x].toLong() and 0xFF)
        return v
    }
    val text = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
    return if (text.isEmpty()) 0 else text.toLong(8)
}

private class LimitedStream(private val inner: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val v = inner.read()
        if (v >= 0) left--
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }
}

// endregion

// region reader

/** A built copy in [dir], open for lookups. Thread-safe: every read is positional. */
internal class MusicBrainzIndexReader(val dir: Path) : MusicBrainzOfflineIndex, Closeable {
    private val artistsDat = FileChannel.open(dir.resolve("artists.dat"), StandardOpenOption.READ)
    private val groupsDat = FileChannel.open(dir.resolve("groups.dat"), StandardOpenOption.READ)
    private val artistOff = map("artists.off")
    private val groupOff = map("groups.off")
    private val ids = map("artists.ids")
    private val credits = map("artist-groups.ids")
    private val names = map("names.idx")
    private val tags = map("tags.idx")

    val meta: JsonObject = INDEX_JSON.parseToJsonElement(Files.readString(dir.resolve("meta.json"))) as JsonObject
    val dump: String = meta.text("dump").orEmpty()
    val artistCount: Int = meta.text("artists")?.toIntOrNull() ?: 0
    val groupCount: Int = meta.text("releaseGroups")?.toIntOrNull() ?: 0

    private fun map(name: String): MappedByteBuffer =
        FileChannel.open(dir.resolve(name), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

    private fun record(channel: FileChannel, offsets: MappedByteBuffer, ordinal: Int): String {
        val start = offsets.getLong(ordinal * 8)
        val end = offsets.getLong((ordinal + 1) * 8)
        val buf = ByteBuffer.allocate((end - start).toInt())
        var pos = start
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) break
            pos += n
        }
        return String(buf.array(), 0, buf.position(), Charsets.UTF_8)
    }

    private fun artistRecord(ordinal: Int): JsonObject? =
        runCatching { INDEX_JSON.parseToJsonElement(record(artistsDat, artistOff, ordinal)) as JsonObject }.getOrNull()

    /** First entry index in a sorted file of [width]-byte entries whose leading long is >= [key]. */
    private fun lowerBound(buf: MappedByteBuffer, width: Int, key: Long, shift: Int = 0): Int {
        var lo = 0
        var hi = buf.capacity() / width
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if ((buf.getLong(mid * width) ushr shift) < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun ordinalOf(mbid: String): Int? {
        val (high, low) = uuidHalves(mbid) ?: return null
        var x = lowerBound(ids, 20, high)
        val count = ids.capacity() / 20
        while (x < count && ids.getLong(x * 20) == high) {
            if (ids.getLong(x * 20 + 8) == low) return ids.getInt(x * 20 + 16)
            x++
        }
        return null
    }

    override fun artist(mbid: String): String? {
        val ordinal = ordinalOf(mbid) ?: return null
        return record(artistsDat, artistOff, ordinal)
    }

    override fun releaseGroups(mbid: String): String? {
        ordinalOf(mbid) ?: return null
        val (high, low) = uuidHalves(mbid) ?: return null
        val count = credits.capacity() / 20
        var x = lowerBound(credits, 20, high)
        val groups = ArrayList<JsonElement>()
        while (x < count && credits.getLong(x * 20) == high) {
            if (credits.getLong(x * 20 + 8) == low) {
                val ordinal = credits.getInt(x * 20 + 16)
                runCatching { INDEX_JSON.parseToJsonElement(record(groupsDat, groupOff, ordinal)) }.getOrNull()?.let(groups::add)
            }
            x++
        }
        return buildJsonObject {
            put("release-groups", JsonArray(groups))
            put("release-group-count", groups.size)
        }.toString()
    }

    /** A search hit in the web service's shape, from a stored record. */
    private fun hit(record: JsonObject, score: Int): JsonObject = buildJsonObject {
        for (key in listOf("id", "name", "sort-name", "type", "country", "disambiguation", "life-span")) record[key]?.let { put(key, it) }
        put("score", score)
        // The web search carries tag votes; genres are tags too, so both go in.
        val votes = ((record["tags"] as? JsonArray).orEmpty() + (record["genres"] as? JsonArray).orEmpty())
        if (votes.isNotEmpty()) put("tags", JsonArray(votes))
    }

    override fun searchArtist(name: String, limit: Int): String? {
        val key = nameHash(mbNameKey(name))
        val count = names.capacity() / 8
        var x = lowerBound(names, 8, key, shift = ORDINAL_BITS + 1)
        val found = ArrayList<Pair<Int, Boolean>>()
        while (x < count) {
            val entry = names.getLong(x * 8)
            if (entry ushr (ORDINAL_BITS + 1) != key) break
            found += (entry and ORDINAL_MASK).toInt() to (entry and (1L shl ORDINAL_BITS) != 0L)
            x++
        }
        if (found.isEmpty()) return null
        // Own names before aliases, then the better known first; scores fall from 100 (aliases from 90).
        val ranked = found.distinctBy { it.first }
            .mapNotNull { (ordinal, alias) -> artistRecord(ordinal)?.let { Triple(it, alias, it.text("w")?.toIntOrNull() ?: 0) } }
            .sortedWith(compareBy<Triple<JsonObject, Boolean, Int>> { it.second }.thenByDescending { it.third })
            .take(limit)
        return buildJsonObject {
            put("artists", JsonArray(ranked.mapIndexed { rank, (record, alias) -> hit(record, (if (alias) 90 else 100) - rank) }))
        }.toString()
    }

    override fun searchByTag(tag: String, limit: Int): String {
        val wanted = mbTagKey(tag)
        val key = tagHash(wanted)
        val count = tags.capacity() / 8
        var x = lowerBound(tags, 8, key, shift = TAG_VOTE_BITS + ORDINAL_BITS)
        val hits = ArrayList<JsonObject>()
        while (x < count && hits.size < limit) {
            val entry = tags.getLong(x * 8)
            if (entry ushr (TAG_VOTE_BITS + ORDINAL_BITS) != key) break
            val record = artistRecord((entry and ORDINAL_MASK).toInt())
            // A different tag that shares the hash is told apart by the record's own tags.
            val carries = record != null && ((record["tags"] as? JsonArray).orEmpty() + (record["genres"] as? JsonArray).orEmpty())
                .any { mbTagKey(it.text("name").orEmpty()) == wanted }
            if (carries) hits += hit(record!!, 100)
            x++
        }
        return buildJsonObject { put("artists", JsonArray(hits)) }.toString()
    }

    override fun close() {
        runCatching { artistsDat.close() }
        runCatching { groupsDat.close() }
    }
}

// endregion
