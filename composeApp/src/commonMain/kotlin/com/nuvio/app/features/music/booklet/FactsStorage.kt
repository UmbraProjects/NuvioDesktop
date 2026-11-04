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
internal class SplitCache(private val capped: TextStore, private val kept: TextStore) {
    private var migrated = false

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

    fun get(key: String): String? { migrate(); return kept.get(key) ?: capped.get(key) }

    fun put(key: String, value: String?) {
        migrate()
        if (value != null && isAiHit(value)) {
            kept.put(key, value)
            if (capped.get(key) != null) capped.put(key, null)
        } else {
            capped.put(key, value)
            if (value == null && kept.get(key) != null) kept.put(key, null)
        }
    }

    /** The keys in the capped store: what [AlbumBookletRepository] trims. */
    fun cappedKeys(): Set<String> { migrate(); return capped.keys() }

    fun cappedGet(key: String): String? = capped.get(key)

    /** Removes [key] from the capped store only. */
    fun drop(key: String) = capped.put(key, null)

    fun keptKeys(): Set<String> { migrate(); return kept.keys() }
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
)
