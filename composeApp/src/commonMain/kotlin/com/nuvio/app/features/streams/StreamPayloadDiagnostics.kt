package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Opt-in dump of what an addon actually sends, for answering "why didn't trait X fire?".
 *
 * Off unless `NUVIO_STREAM_PAYLOAD_LOG` is set (wired in desktop `main()`), because it logs whole
 * stream objects. It exists because the interesting questions are usually about fields the app does
 * *not* parse — a census of what we did parse can only ever confirm what we already believe.
 *
 * Aimed at cache status in particular: whether a stream is cached on debrid is only knowable from a
 * structured field, since every addon renders it differently in its display text and those templates
 * are user-configurable.
 */
object StreamPayloadDiagnostics {

    private val log = Logger.withTag("StreamPayload")

    @Volatile
    var enabled: Boolean = false

    /** Keys the app reads off a stream object; anything else is reported as unparsed. */
    private val KNOWN_STREAM_KEYS = setOf(
        "name", "title", "description", "url", "infoHash", "fileIdx", "externalUrl", "sources",
        "languages", "language", "type", "clientResolve", "streamData", "behaviorHints", "ytId",
    )

    fun logPayload(addonName: String, root: JsonObject) {
        if (!enabled) return
        runCatching { dump(addonName, root) }
            .onFailure { log.w(it) { "payload diagnostics failed for $addonName" } }
    }

    private fun dump(addonName: String, root: JsonObject) {
        val streams = (root["streams"] as? JsonArray).orEmpty()
        if (streams.isEmpty()) {
            log.i { "[$addonName] 0 streams" }
            return
        }

        var withStreamData = 0
        var withClientResolve = 0
        val unparsedKeys = mutableSetOf<String>()
        // Union of shapes rather than a guess at one: report every key seen anywhere inside
        // streamData, and every key of any nested object, so the real structure is visible without
        // having to assume it. Assuming `service.cached` and counting only that produced 0/0 and
        // told us nothing about why.
        val streamDataKeys = mutableSetOf<String>()
        val nestedKeys = mutableMapOf<String, MutableSet<String>>()
        // Anything whose key or value looks cache-related, wherever it sits in the object.
        val cacheLikeEntries = mutableSetOf<String>()
        var firstStreamData: JsonObject? = null

        fun harvest(prefix: String, obj: JsonObject, depth: Int) {
            obj.forEach { (key, value) ->
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when (value) {
                    is JsonObject -> {
                        nestedKeys.getOrPut(path) { mutableSetOf() } += value.keys
                        if (depth < 3) harvest(path, value, depth + 1)
                    }
                    is JsonPrimitive -> {
                        val looksCacheRelated = key.contains("cach", ignoreCase = true) ||
                            key.contains("instant", ignoreCase = true) ||
                            value.content.equals("cached", ignoreCase = true)
                        if (looksCacheRelated) cacheLikeEntries += "$path=${value.content}"
                    }
                    else -> Unit
                }
            }
        }

        // Key censuses rather than whole objects: compact enough to survive being copied out of a
        // log, unlike the verbatim dump which gets truncated mid-object.
        val topLevelKeys = mutableSetOf<String>()
        val behaviorHintKeys = mutableSetOf<String>()
        // Whatever the addon marks cached with, its own text is the one place we know it appears.
        // Recording the first line of a couple of descriptions shows the marker without us having to
        // guess which glyph it is.
        val descriptionFirstLines = mutableListOf<String>()

        streams.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            unparsedKeys += obj.keys - KNOWN_STREAM_KEYS
            topLevelKeys += obj.keys
            (obj["behaviorHints"] as? JsonObject)?.let { behaviorHintKeys += it.keys }
            if (descriptionFirstLines.size < 4) {
                (obj["description"] as? JsonPrimitive)?.content
                    ?.lineSequence()?.firstOrNull()
                    ?.let { descriptionFirstLines += it }
            }
            (obj["streamData"] as? JsonObject)?.let { data ->
                withStreamData++
                if (firstStreamData == null) firstStreamData = data
                streamDataKeys += data.keys
                harvest("", data, 0)
            }
            if (obj["clientResolve"] != null) withClientResolve++
        }

        log.i {
            "[$addonName] streams=${streams.size} clientResolve=$withClientResolve " +
                "streamData=$withStreamData unparsedTopLevelKeys=${unparsedKeys.sorted()}"
        }
        log.i { "[$addonName] topLevelKeys=${topLevelKeys.sorted()} behaviorHintKeys=${behaviorHintKeys.sorted()}" }
        descriptionFirstLines.forEachIndexed { index, line ->
            log.i { "[$addonName] description[$index] line1=$line" }
        }
        log.i { "[$addonName] streamData keys=${streamDataKeys.sorted()}" }
        nestedKeys.toSortedMap().forEach { (path, keys) ->
            log.i { "[$addonName] streamData.$path keys=${keys.sorted()}" }
        }
        log.i { "[$addonName] cache-like entries=${cacheLikeEntries.sorted()}" }
        // The first stream that actually HAS streamData — not simply the first stream, which in a
        // mixed result set (usenet/torrent/debrid) may well not carry one.
        firstStreamData?.let { log.i { "[$addonName] first streamData verbatim: $it" } }
    }
}
