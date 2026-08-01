package com.nuvio.app.features.player

/**
 * In-memory map of `metaId -> original language code`, populated when a meta is loaded (detail
 * screen, or the player's own lightweight fetch) and read wherever the "Original" language
 * preference has to become a concrete code.
 *
 * The language itself comes from TMDB's `original_language`, so this only ever fills in for titles
 * whose meta went through TMDB — which is why the setting is documented as needing a TMDB key. An
 * unknown original language is not an error: the preference simply falls through to the secondary
 * choice, exactly as "Default" already does.
 *
 * [current] exists because two of the readers have no meta id to ask with. mpv's `alang`/`slang`
 * options are built at initialise time from settings alone, and the player's subtitle-list filter
 * runs off a settings snapshot; both concern whatever is playing right now, which is what
 * [setCurrent] records. Follows the same object-store pattern as [AnimeContentCache].
 */
object OriginalLanguageCache {

    private val languageByMetaId = mutableMapOf<String, String>()

    @Volatile
    private var currentMetaId: String? = null

    fun record(metaId: String?, language: String?) {
        val id = metaId?.takeIf { it.isNotBlank() } ?: return
        val code = normalizeLanguageCode(language)?.takeIf { it.isNotBlank() } ?: return
        synchronized(languageByMetaId) { languageByMetaId[id] = code }
    }

    fun languageFor(metaId: String?): String? {
        val id = metaId?.takeIf { it.isNotBlank() } ?: return null
        return synchronized(languageByMetaId) { languageByMetaId[id] }
    }

    /** Marks which title playback is about, for the readers that cannot name one themselves. */
    fun setCurrent(metaId: String?) {
        currentMetaId = metaId?.takeIf { it.isNotBlank() }
    }

    val current: String?
        get() = languageFor(currentMetaId)

    fun clear() {
        synchronized(languageByMetaId) { languageByMetaId.clear() }
        currentMetaId = null
    }
}
