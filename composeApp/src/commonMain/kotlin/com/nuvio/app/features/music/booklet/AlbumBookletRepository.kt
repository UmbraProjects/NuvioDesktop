package com.nuvio.app.features.music.booklet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.library.WikipediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * fork:booklet - one album's sleeve notes: from the disk cache, or a Wikipedia lookup and, with the person's AI key, one
 * request that writes the notes from that Wikipedia text (and nothing else, so there is nothing to invent from).
 *
 * Billing discipline as for the post-show cards: a request is made only for the album being played, once, and its answer
 * (or its failure, for two hours) is kept. Without a key, or when the AI fails, the Wikipedia opening stands in.
 */
internal object AlbumBookletRepository {
    private val log = Logger.withTag("AlbumBooklet")

    /** Work runs here, not in the caller's scope, so leaving the Stage cannot waste a paid request. */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = mutableMapOf<String, Deferred<AlbumBooklet?>>()
    private val inFlightLock = Mutex()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun aiCacheKey(request: BookletRequest, model: String) = bookletKey(request, "ai:$model")
    // "wiki2": the edition-word fix (Nov 3, 2026) asks again for albums the older matcher had marked as having no page.
    private fun wikiCacheKey(request: BookletRequest) = bookletKey(request, "wiki2")

    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletStorage.get(key))

    private fun write(key: String, booklet: AlbumBooklet?) {
        BookletStorage.put(key, encodeBookletEntry(BookletEntry(now(), booklet)))
        val keys = BookletStorage.keys()
        if (keys.size > BOOKLET_CACHE_MAX + BOOKLET_CACHE_SLACK) {
            val ages = keys.associateWith { read(it)?.atEpochMs ?: 0L }
            bookletKeysToEvict(ages, BOOKLET_CACHE_MAX).forEach { BookletStorage.put(it, null) }
        }
    }

    /** The AI settings when notes may be written by the AI (a key, a model and the person's consent), else null. */
    private fun aiSettingsOrNull() = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured }

    /** What the cache already holds for [request], never making a request: the AI notes if kept, else the Wikipedia ones. */
    fun peek(request: BookletRequest): AlbumBooklet? {
        val at = now()
        aiSettingsOrNull()?.let { settings ->
            read(aiCacheKey(request, settings.effectiveModel))
                ?.takeIf { it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.booklet?.let { return it }
        }
        return read(wikiCacheKey(request))?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }?.booklet
    }

    /** The booklet for [request], null when Wikipedia has no page for the album. Waits for a lookup already under way. */
    suspend fun bookletFor(request: BookletRequest): AlbumBooklet? {
        val at = now()
        val settings = aiSettingsOrNull()
        val aiEntry = settings
            ?.let { read(aiCacheKey(request, it.effectiveModel)) }
            ?.takeIf { it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }
        aiEntry?.booklet?.let { return it }
        val wiki = read(wikiCacheKey(request))?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }
        // Nothing more to ask: no AI to write the notes (or it failed a moment ago) and Wikipedia has been asked.
        if (wiki != null && (settings == null || aiEntry != null)) return wiki.booklet
        val key = settings?.let { aiCacheKey(request, it.effectiveModel) } ?: wikiCacheKey(request)
        val job = inFlightLock.withLock {
            inFlight.getOrPut(key) {
                workScope.async {
                    try {
                        generate(request)
                    } finally {
                        inFlightLock.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    private suspend fun generate(request: BookletRequest): AlbumBooklet? {
        val at = now()
        val wikiKey = wikiCacheKey(request)
        val settings = aiSettingsOrNull()
        val aiKey = settings?.let { aiCacheKey(request, it.effectiveModel) }

        // A recent "no page" is believed, for the AI path too: with no text there is nothing to write from.
        val cachedWiki = read(wikiKey)?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }
        if (cachedWiki != null && cachedWiki.booklet == null) return null
        // A recent AI failure is not retried at once: the Wikipedia notes stand in.
        val aiRecentlyFailed = aiKey != null &&
            read(aiKey)?.let { it.booklet == null && it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) } == true

        val text = WikipediaClient.albumText(request.album, request.artist)
        if (text == null) {
            if (cachedWiki?.booklet == null) write(wikiKey, null)
            log.i { "no Wikipedia page for ${request.artist} - ${request.album}" }
            return null
        }
        // The artist's own page and what the library knows of them feed the artist cards. A miss here only means fewer cards.
        val artistText = listOfNotNull(
            runCatching { WikipediaClient.artistText(request.artist) }.getOrNull(),
            request.artistInfo?.biography?.takeIf { it.isNotBlank() },
        ).distinct().joinToString("\n\n").takeIf { it.isNotBlank() }
        val wiki = wikipediaBooklet(text, request, artistText)
        if (wiki == null) {
            write(wikiKey, null)
            return null
        }
        write(wikiKey, wiki)

        if (settings == null || aiKey == null || aiRecentlyFailed) return wiki
        val reply = DiscoverAiClient.complete(settings, DiscoverAiPromptText(BOOKLET_SYSTEM_PROMPT, bookletPrompt(request, text, artistText))).getOrElse {
            log.w { "notes for ${request.album} failed: $it" }
            write(aiKey, null)
            return wiki
        }
        val parsed = parseAiBooklet(reply, request.tracks.size)
        if (parsed == null) {
            log.w { "notes for ${request.album}: reply unreadable" }
            write(aiKey, null)
            return wiki
        }
        // The trivia is its own request, written to be new to the reader (the intro is told to it as already shown). A
        // failure or an album the model does not know leaves the three plain cards, so the deck is never empty.
        val cards = DiscoverAiClient.complete(settings, DiscoverAiPromptText(FACTS_SYSTEM_PROMPT, factsPrompt(request, parsed.intro, text)))
            .getOrNull()?.let(::parseAiFacts).orEmpty()
        val facts = dropRepeats(cards, parsed.intro).ifEmpty { wiki.facts }
        val ai = parsed.copy(facts = facts)
        write(aiKey, ai)
        log.i { "notes for ${request.album}: ${ai.credits.size} credits, ${ai.facts.size} cards, ${ai.trackNotes.size} track notes" }
        return ai
    }
}
