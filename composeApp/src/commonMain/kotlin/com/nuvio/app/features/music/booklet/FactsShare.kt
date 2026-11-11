package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * fork:facts-share - the rules for sharing facts as a JSON file, free of storage and file dialogs so they can be tested.
 * An export holds the AI-written facts and the person's pins as text values under their cache keys. An import is a gap
 * fill: it adds only what this computer does not have, never replaces a pin or a fact, and is written down as an import
 * (its id and the keys it added) so it can be undone. Nothing is trusted: a file is size-limited, every value must read
 * back as what its key says, and anything else is skipped and counted.
 */

internal const val SHARE_FORMAT = "nuvio-music-facts"
internal const val SHARE_VERSION = 1
internal const val SHARE_MAX_FILE_CHARS = 25_000_000
internal const val SHARE_MAX_ENTRIES = 100_000
internal const val SHARE_MAX_VALUE_CHARS = 64_000

/** Shown in the file itself: the notes are written from Wikipedia text. */
internal const val SHARE_NOTE = "Facts written by an AI from Wikipedia text (CC BY-SA 4.0, https://en.wikipedia.org) and pins written by hand. Import them with Nuvio: Settings > Music > Music facts."

/** The kinds of entry a file may hold, told apart by their key. */
internal enum class ShareKind(val label: String) {
    Notes("Liner Notes"), TrackNotes("track notes"), Story("song stories"), Start("Start here picks"), Pin("pins")
}

/** What kind of entry [key] is, or null when it is not one a file may carry (Cut to fit picks are personal and stay out). */
internal fun shareKindOf(key: String): ShareKind? = when {
    key.startsWith("v4|ai:") -> ShareKind.Notes
    key.startsWith("tnotes1|") -> ShareKind.TrackNotes
    key.startsWith("story1|") && "|ai:" in key -> ShareKind.Story
    key.startsWith("start1|") -> ShareKind.Start
    key.startsWith("pin1|") -> ShareKind.Pin
    else -> null
}

/** One entry of a file: its cache [key] and its stored text [value]. */
internal data class ShareEntry(val key: String, val value: String)

private val SHARE_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun encodeShare(entries: List<ShareEntry>, exportedAtMs: Long): String = buildJsonObject {
    put("format", SHARE_FORMAT)
    put("version", SHARE_VERSION)
    put("exportedAt", exportedAtMs)
    put("note", SHARE_NOTE)
    put("entries", buildJsonArray {
        entries.forEach { add(buildJsonObject { put("k", it.key); put("v", it.value) }) }
    })
}.toString()

/** What a file held: the entries that passed [checkShareEntry], and how many did not. */
internal data class ParsedShare(val entries: List<ShareEntry>, val rejected: Int)

/** Null when [text] is not a facts file of a version this Nuvio reads (or is too big to be trusted). */
internal fun parseShare(text: String?): ParsedShare? {
    if (text == null || text.length > SHARE_MAX_FILE_CHARS) return null
    val root = runCatching { SHARE_JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    if ((root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content != SHARE_FORMAT) return null
    val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return null
    if (version != SHARE_VERSION) return null
    val raw = root["entries"] as? JsonArray ?: return null
    val seen = HashSet<String>()
    val good = ArrayList<ShareEntry>()
    var rejected = 0
    for (element in raw.take(SHARE_MAX_ENTRIES)) {
        val obj = element as? JsonObject
        val key = (obj?.get("k") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val value = (obj?.get("v") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (key == null || value == null || !checkShareEntry(key, value) || !seen.add(key)) { rejected++; continue }
        good += ShareEntry(key, value)
    }
    rejected += (raw.size - SHARE_MAX_ENTRIES).coerceAtLeast(0)
    return ParsedShare(good, rejected)
}

/** Whether [value] reads back as what [key] says it is: a kept AI fact for a fact key, a pin with text for a pin key. */
internal fun checkShareEntry(key: String, value: String): Boolean {
    if (key.length > 400 || value.length > SHARE_MAX_VALUE_CHARS) return false
    return when (shareKindOf(key)) {
        null -> false
        ShareKind.Pin -> decodePin(value)?.let { cleanPinText(it.text) != null } == true
        else -> decodeBookletEntry(value)?.booklet?.source == BookletSource.Ai
    }
}

/** What applying a file would do on this computer. */
internal data class ImportPlan(
    val add: List<ShareEntry>,
    /** Entries this computer already has (a fact kept under any model, a pin of its own). */
    val alreadyHave: Int,
    val rejected: Int,
) {
    val counts: Map<ShareKind, Int> get() = add.groupingBy { shareKindOf(it.key)!! }.eachCount()
    val isEmpty: Boolean get() = add.isEmpty()
}

/** The gap fill: an entry is added only when [haveFact] (for a fact key) or [havePin] (for a pin key) says nothing is there. */
internal fun planImport(parsed: ParsedShare, haveFact: (String) -> Boolean, havePin: (String) -> Boolean): ImportPlan {
    val add = ArrayList<ShareEntry>()
    var have = 0
    for (entry in parsed.entries) {
        val has = if (shareKindOf(entry.key) == ShareKind.Pin) havePin(entry.key) else haveFact(entry.key)
        if (has) have++ else add += entry
    }
    return ImportPlan(add, have, parsed.rejected)
}

/** The preview line: "12 Liner Notes, 80 song stories and 3 pins", in a fixed order. */
internal fun describeCounts(counts: Map<ShareKind, Int>): String {
    val parts = ShareKind.entries.mapNotNull { kind -> counts[kind]?.takeIf { it > 0 }?.let { "$it ${kind.label}" } }
    return when (parts.size) {
        0 -> "nothing new"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}

/** The origin tag a pin written by an import carries. */
internal fun importOrigin(id: String) = "import:$id"

/** A record that an import happened, so it can be undone: when, what the file was called, and the keys it added. */
internal data class ImportRecord(val id: String, val atMs: Long, val fileName: String, val keys: List<String>)

internal fun encodeImportRecord(record: ImportRecord): String = buildJsonObject {
    put("id", record.id)
    put("at", record.atMs)
    put("file", record.fileName)
    put("keys", buildJsonArray { record.keys.forEach { add(JsonPrimitive(it)) } })
}.toString()

internal fun decodeImportRecord(text: String?): ImportRecord? {
    val obj = text?.let { runCatching { SHARE_JSON.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: 0L
    val file = (obj["file"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val keys = (obj["keys"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    return ImportRecord(id, at, file, keys)
}

/**
 * The keys of [record] an undo may remove: every fact it added that is still there, and every pin it added that is still
 * the import's own (a pin the person has since edited is theirs, with their origin, and stays).
 */
internal fun keysToUndo(record: ImportRecord, valueOf: (String) -> String?): List<String> = record.keys.filter { key ->
    val value = valueOf(key) ?: return@filter false
    if (shareKindOf(key) == ShareKind.Pin) decodePin(value)?.origin == importOrigin(record.id) else true
}
