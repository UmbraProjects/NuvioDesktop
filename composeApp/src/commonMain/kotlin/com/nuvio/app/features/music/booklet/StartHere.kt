package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.library.START_HERE_SYSTEM_PROMPT
import com.nuvio.app.features.music.library.cleanStartHere
import com.nuvio.app.features.music.library.startHerePrompt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * fork:essentials - the "Start here" card on the album page: one short AI recommendation per artist, kept on disk with the
 * album booklets (as a booklet whose intro is the text, so the cache's size limit and ageing apply to it too). Asked once,
 * only with the person's own AI key, and never again within the booklet lifetime; a failure is remembered for two hours.
 */
internal object StartHere {
    private val log = Logger.withTag("StartHere")
    private val lock = Mutex()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun key(artist: String, model: String) = "start1|${artist.lowercase().filter { it.isLetterOrDigit() }}|$model"

    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletCache.get(key))

    /** What is kept for [artist], never making a request; null when nothing is kept or there is no AI key. */
    fun peek(artist: String): String? {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val entry = read(key(artist, settings.effectiveModel)) ?: return null
        return if (entry.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS)) entry.booklet?.intro else null
    }

    /** The recommendation for [artist] from [albums] (best first) and [songs]; null without an AI key or when the AI fails. */
    suspend fun forArtist(artist: String, albums: List<String>, songs: List<String>): String? {
        if (albums.isEmpty()) return null
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val key = key(artist, settings.effectiveModel)
        return lock.withLock {
            val kept = read(key)?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }
            if (kept != null) return@withLock kept.booklet?.intro
            val reply = DiscoverAiClient.complete(settings, DiscoverAiPromptText(START_HERE_SYSTEM_PROMPT, startHerePrompt(artist, albums, songs))).getOrElse {
                log.w { "start here for $artist failed: $it" }
                BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), null)))
                return@withLock null
            }
            val text = cleanStartHere(reply)
            val booklet = text?.let { AlbumBooklet(it, emptyList(), emptyMap(), BookletSource.Ai) }
            BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), booklet)))
            text
        }
    }
}
