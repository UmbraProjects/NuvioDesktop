package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/** Where the facts the AI wrote are kept for good: no expiry and no cap (see [BookletCache]). Spread over several files so one write stays small. */
internal expect object FactsStorage {
    fun keys(): Set<String>
    fun get(key: String): String?

    /** Stores [value]; null removes the key. */
    fun put(key: String, value: String?)
}

/** The three calls the cache needs of a store of text values. */
internal interface TextStore {
    fun keys(): Set<String>
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * fork:facts-store - the booklet cache in two stores. An AI-written fact cost a request and never expires, so it goes to the
 * [kept] store, which is never trimmed; everything else (Wikipedia text, "no page" answers, failures) goes to the [capped]
 * store, which [AlbumBookletRepository] trims to [BOOKLET_CACHE_MAX]. Reads look in both, so the loaders do not know there
 * are two. A fact found in the capped store (from before the split) is moved over by [migrate].
 */
internal class SplitCache(
    private val capped: TextStore,
    private val kept: TextStore,
    /** fork:facts-store - the AI model in use now; a fact written by another model is used when this one has none. */
    private val model: () -> String? = { null },
) {
    private var migrated = false

    /** The kept store's keys, sorted, for finding another model's fact by prefix; dropped on every kept write. */
    @Volatile private var keptSorted: List<String>? = null
    /** What [otherModelHit] found for a key (null: nothing); dropped on every write. Replaced whole, never changed in place: the filler and the page read it at once. */
    @Volatile private var otherFound: Map<String, String?> = emptyMap()

    private fun isAiHit(raw: String?): Boolean = decodeBookletEntry(raw)?.booklet?.source == BookletSource.Ai

    /** Moves the AI facts the capped store still holds over to the kept one; runs once, on first use. */
    fun migrate() {
        if (migrated) return
        migrated = true
        for (key in capped.keys()) {
            val raw = capped.get(key)
            if (isAiHit(raw)) {
                kept.put(key, raw)
                capped.put(key, null)
            }
        }
    }

    fun get(key: String): String? {
        migrate()
        val raw = kept.get(key) ?: capped.get(key)
        if (isAiHit(raw)) return raw
        return otherModelHit(key) ?: raw
    }

    /**
     * fork:facts-store - an AI fact for the same thing written by another model: the facts are keyed by model, so without
     * this, switching models would leave every fact unused and ask for all of them again. [key] holds the current model's
     * name as one segment (after "|" or ":"); a kept key that differs from it only in that segment is the same fact.
     */
    fun otherModelHit(key: String): String? {
        val m = model()?.takeIf { it.isNotEmpty() } ?: return null
        val at = modelSegmentAt(key, m) ?: return null
        val memo = otherFound
        if (memo.containsKey(key)) return memo[key]
        val prefix = key.substring(0, at)
        val tail = key.substring(at + m.length).substringAfter('|', "")
        val sorted = keptSorted ?: kept.keys().sorted().also { keptSorted = it }
        var i = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        var found: String? = null
        while (i < sorted.size && sorted[i].startsWith(prefix)) {
            val k = sorted[i++]
            if (k == key || k.substring(prefix.length).substringAfter('|', "") != tail) continue
            val raw = kept.get(k)
            if (isAiHit(raw)) { found = raw; break }
        }
        otherFound = memo + (key to found)
        return found
    }

    fun put(key: String, value: String?) {
        migrate()
        otherFound = emptyMap()
        if (value != null && isAiHit(value)) {
            if (kept.get(key) == null) keptSorted = null
            kept.put(key, value)
            if (capped.get(key) != null) capped.put(key, null)
        } else {
            capped.put(key, value)
            if (value == null && kept.get(key) != null) { kept.put(key, null); keptSorted = null }
        }
    }

    /** The keys in the capped store: what [AlbumBookletRepository] trims. */
    fun cappedKeys(): Set<String> { migrate(); return capped.keys() }

    fun cappedGet(key: String): String? = capped.get(key)

    /** Removes [key] from the capped store only. */
    fun drop(key: String) = capped.put(key, null)

    fun keptKeys(): Set<String> { migrate(); return kept.keys() }
}

/** Where [model] stands in [key] as a whole segment (after "|" or ":", before "|", ":" or the end), or null. */
internal fun modelSegmentAt(key: String, model: String): Int? {
    var from = 0
    while (true) {
        val at = key.indexOf(model, from)
        if (at < 0) return null
        val before = key.getOrNull(at - 1)
        val after = key.getOrNull(at + model.length)
        if ((before == '|' || before == ':') && (after == null || after == '|' || after == ':')) return at
        from = at + 1
    }
}

/** The one cache the booklet loaders read and write. */
internal val BookletCache = SplitCache(
    capped = object : TextStore {
        override fun keys() = BookletStorage.keys()
        override fun get(key: String) = BookletStorage.get(key)
        override fun put(key: String, value: String?) = BookletStorage.put(key, value)
    },
    kept = object : TextStore {
        override fun keys() = FactsStorage.keys()
        override fun get(key: String) = FactsStorage.get(key)
        override fun put(key: String, value: String?) = FactsStorage.put(key, value)
    },
    model = { com.nuvio.app.features.discover.DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured }?.effectiveModel },
)
