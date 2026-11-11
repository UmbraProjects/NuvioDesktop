package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.library.SONIC_SYSTEM_PROMPT
import com.nuvio.app.features.music.library.SonicGuess
import com.nuvio.app.features.music.library.parseSonicGuesses
import com.nuvio.app.features.music.library.sonicPrompt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * fork:sonic-map - the AI's estimate of where an album's songs sit on the Sonic map: one request per album with the person's
 * own key, asked only when the tab is opened (not by the facts filler, by the user's choice), kept with the other AI facts
 * (never expires, used by any model, ahead of the filler). A failure is remembered for the usual two hours.
 */
internal object SonicGuesses {
    private val log = Logger.withTag("SonicGuesses")
    private val lock = Mutex()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun key(album: String, model: String) = "sonic1|$album|$model"

    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletCache.get(key))

    /** True when there is a key to ask with. */
    fun available(): Boolean = DiscoverAiSettingsRepository.snapshot().isConfigured

    /** What is kept for [albumId], never making a request. */
    fun peek(albumId: String, trackCount: Int): List<SonicGuess?>? {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val entry = read(key(albumId, settings.effectiveModel))?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) } ?: return null
        return entry.booklet?.intro?.let { parseSonicGuesses(it, trackCount) }
    }

    /** The album's estimates, one per track; null without a key, when the AI fails, or when its answer names no track. */
    suspend fun load(albumId: String, artist: String, album: String, titles: List<String>): List<SonicGuess?>? {
        if (titles.isEmpty()) return null
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val key = key(albumId, settings.effectiveModel)
        return FactsPriority.foreground { lock.withLock {
            read(key)?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.let { kept ->
                return@withLock kept.booklet?.intro?.let { parseSonicGuesses(it, titles.size) }
            }
            val reply = DiscoverAiClient.complete(settings, DiscoverAiPromptText(SONIC_SYSTEM_PROMPT, sonicPrompt(artist, album, titles))).getOrElse {
                log.w { "sonic map for $album failed: $it" }
                BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), null)))
                return@withLock null
            }
            val guesses = parseSonicGuesses(reply, titles.size)
            val json = guesses?.let { reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1) }
            BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), json?.let { AlbumBooklet(it, emptyList(), emptyMap(), BookletSource.Ai) })))
            log.i { "sonic map for $album: ${guesses?.count { it != null } ?: 0} of ${titles.size} songs estimated" }
            guesses
        } }
    }
}
