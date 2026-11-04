package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.library.LINEAGE_SYSTEM_PROMPT
import com.nuvio.app.features.music.library.Lineage
import com.nuvio.app.features.music.library.lineagePrompt
import com.nuvio.app.features.music.library.parseLineage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * fork:lenses - the Lineage lens's records: one AI request per album with the person's own key, kept with the other AI facts
 * (the reply's JSON as a booklet intro: never expires, used by any model, ahead of the filler like the rest). A failure is
 * remembered for the usual two hours.
 */
internal object LineagePicks {
    private val log = Logger.withTag("LineagePicks")
    private val lock = Mutex()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun key(album: String, model: String) = "lineage1|$album|$model"

    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletCache.get(key))

    /** True when there is a key to ask with. */
    fun available(): Boolean = DiscoverAiSettingsRepository.snapshot().isConfigured

    /** What is kept for [albumId], never making a request. */
    fun peek(albumId: String, artist: String, year: Int): Lineage? {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val entry = read(key(albumId, settings.effectiveModel))?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) } ?: return null
        return entry.booklet?.intro?.let { parseLineage(it, year, artist) }
    }

    /** The album's lineage; null without a key, when the AI fails, or when its answer names nothing usable. */
    suspend fun load(albumId: String, artist: String, album: String, year: Int): Lineage? {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val key = key(albumId, settings.effectiveModel)
        return FactsPriority.foreground { lock.withLock {
            read(key)?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.let { kept ->
                return@withLock kept.booklet?.intro?.let { parseLineage(it, year, artist) }
            }
            val reply = DiscoverAiClient.complete(settings, DiscoverAiPromptText(LINEAGE_SYSTEM_PROMPT, lineagePrompt(artist, album, year))).getOrElse {
                log.w { "lineage for $album failed: $it" }
                BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), null)))
                return@withLock null
            }
            val lineage = parseLineage(reply, year, artist)
            val json = lineage?.let { reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1) }
            BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), json?.let { AlbumBooklet(it, emptyList(), emptyMap(), BookletSource.Ai) })))
            log.i { "lineage for $album: ${lineage?.before?.size ?: 0} before, ${lineage?.after?.size ?: 0} after" }
            lineage
        } }
    }
}
