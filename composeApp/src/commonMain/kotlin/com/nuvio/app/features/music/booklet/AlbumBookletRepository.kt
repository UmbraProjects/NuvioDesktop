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

    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletCache.get(key))

    private fun write(key: String, booklet: AlbumBooklet?) {
        BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), booklet)))
        // Only the capped store is trimmed (Wikipedia text, "no page" and failures); the AI's facts live in their own store.
        val keys = BookletCache.cappedKeys()
        if (keys.size > BOOKLET_CACHE_MAX + BOOKLET_CACHE_SLACK) {
            val ages = keys.associateWith { decodeBookletEntry(BookletCache.cappedGet(it))?.atEpochMs ?: 0L }
            bookletKeysToEvict(ages, BOOKLET_CACHE_MAX).forEach { BookletCache.drop(it) }
        }
    }

    /** The AI settings when notes may be written by the AI (a key, a model and the person's consent), else null. */
    private fun aiSettingsOrNull() = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured }

    /**
     * What the cache already holds for [request], never making a request: the AI notes if kept, else the Wikipedia ones,
     * with the person's pins laid over them.
     */
    fun peek(request: BookletRequest): AlbumBooklet? = peekKept(request).withBlurb(request).withPins(Pins.forAlbum(request))

    /** The booklet for [request] (see [bookletForKept]) with the person's pins laid over it; pinned text stands even with no page. */
    suspend fun bookletFor(request: BookletRequest): AlbumBooklet? =
        FactsPriority.foreground { bookletForKept(request) }.withBlurb(request).withPins(Pins.forAlbum(request))

    /**
     * Whether the AI's notes for [request] are kept ([FactState.Have]), failed a moment ago ([FactState.Missed]) or have not
     * been asked for ([FactState.Missing]); the background filler goes by this. Never makes a request. With no AI key set,
     * nothing can be filled, so everything reads as Missed (not worth asking).
     */
    fun factState(request: BookletRequest): FactState {
        val settings = aiSettingsOrNull() ?: return FactState.Missed
        val entry = read(aiCacheKey(request, settings.effectiveModel))?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }
        return when {
            entry == null -> FactState.Missing
            entry.booklet != null -> FactState.Have
            else -> FactState.Missed
        }
    }

    /** Forgets a failed ask for [request]'s AI notes so the next lookup asks again; a kept answer is left alone. */
    fun forgetMiss(request: BookletRequest) {
        val settings = aiSettingsOrNull() ?: return
        val key = aiCacheKey(request, settings.effectiveModel)
        if (read(key)?.booklet == null) BookletCache.put(key, null)
    }

    /** What the cache holds, without pins: the cache's own answer, which the lookup rules below go by. */
    private fun peekKept(request: BookletRequest): AlbumBooklet? {
        val at = now()
        aiSettingsOrNull()?.let { settings ->
            read(aiCacheKey(request, settings.effectiveModel))
                ?.takeIf { it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.booklet?.let { return it }
        }
        return read(wikiCacheKey(request))?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }?.booklet
    }

    /** The booklet for [request], null when Wikipedia has no page for the album. Waits for a lookup already under way. */
    private suspend fun bookletForKept(request: BookletRequest): AlbumBooklet? {
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
        // (YouTube Music's own blurb, when it has one, is text enough to write from, so a "no page" answer does not stop it.)
        val blurb = request.blurb?.takeIf { it.isNotBlank() }
        if (cachedWiki != null && cachedWiki.booklet == null && blurb == null) return null
        // A recent AI failure is not retried at once: the Wikipedia notes stand in.
        val aiRecentlyFailed = aiKey != null &&
            read(aiKey)?.let { it.booklet == null && it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) } == true

        val text = WikipediaClient.albumText(request.album, request.artist) ?: blurb
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
        val cards = DiscoverAiClient.complete(settings, DiscoverAiPromptText(FACTS_SYSTEM_PROMPT, factsPrompt(request, request.blurb?.let { cutAtSentence(it, 700) } ?: parsed.intro, text)))
            .getOrNull()?.let(::parseAiFacts).orEmpty()
        val facts = dropRepeats(cards, request.blurb?.let { cutAtSentence(it, 700) } ?: parsed.intro).ifEmpty { wiki.facts }
        val ai = parsed.copy(facts = facts)
        write(aiKey, ai)
        log.i { "notes for ${request.album}: ${ai.credits.size} credits, ${ai.facts.size} cards, ${ai.trackNotes.size} track notes" }
        return ai
    }
}
