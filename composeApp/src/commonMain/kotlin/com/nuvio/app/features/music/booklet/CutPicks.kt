package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.library.CUT_SYSTEM_PROMPT
import com.nuvio.app.features.music.library.CutMood
import com.nuvio.app.features.music.library.MoodPick
import com.nuvio.app.features.music.library.cutPrompt
import com.nuvio.app.features.music.library.parseCutPick
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * fork:lenses - the Cut to fit lens's mood picks: one AI request per album and mood, with the person's own key, kept on disk
 * with the booklets (the reply's JSON as a booklet intro, so the cache's size limit and ageing apply). Changing the time
 * never asks again: the cut is worked out from the kept pick. A failure is remembered for the booklets' usual time.
 */
internal object CutPicks {
    private val log = Logger.withTag("CutPicks")
    private val lock = Mutex()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun key(album: String, mood: CutMood, model: String) = "cut1|$album|${mood.name}|$model"

    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletCache.get(key))

    /** True when there is a key to ask with. */
    fun available(): Boolean = DiscoverAiSettingsRepository.snapshot().isConfigured

    /** What is kept for [albumId] and [mood], never making a request. */
    fun peek(albumId: String, mood: CutMood, trackCount: Int): MoodPick? {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val entry = read(key(albumId, mood, settings.effectiveModel))?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) } ?: return null
        return entry.booklet?.intro?.let { parseCutPick(it, trackCount) }
    }

    /** The mood's pick for the album; null without a key, when the AI fails, or when its answer names no track. */
    suspend fun load(albumId: String, artist: String, album: String, titles: List<String>, durations: List<Int>, mood: CutMood): MoodPick? {
        if (!mood.ai || titles.isEmpty()) return null
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return null
        val key = key(albumId, mood, settings.effectiveModel)
        return FactsPriority.foreground { lock.withLock {
            read(key)?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.let { kept ->
                return@withLock kept.booklet?.intro?.let { parseCutPick(it, titles.size) }
            }
            val reply = DiscoverAiClient.complete(settings, DiscoverAiPromptText(CUT_SYSTEM_PROMPT, cutPrompt(artist, album, titles, durations, mood))).getOrElse {
                log.w { "cut for $album ($mood) failed: $it" }
                BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), null)))
                return@withLock null
            }
            val pick = parseCutPick(reply, titles.size)
            val json = pick?.let { reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1) }
            BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), json?.let { AlbumBooklet(it, emptyList(), emptyMap(), BookletSource.Ai) })))
            log.i { "cut for $album ($mood): ${pick?.order?.size ?: 0} songs" }
            pick
        } }
    }
}
