package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiError
import com.nuvio.app.features.discover.DiscoverAiException
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Produces one film's post-show cards: cache, or a single request to the user's own provider.
 *
 * Same billing discipline as [TriviaRepository]: nothing generates on its own schedule. [prefetchFor]
 * is called by the player once playback passes the halfway point, never while browsing, and does
 * nothing at all unless the post-show switch is on and an AI provider is configured.
 */
object PostShowRepository {
    private val log = Logger.withTag("PostShow")

    /** Generation runs here, not in the caller's scope, so leaving the player cannot waste a paid request. */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inFlight = mutableMapOf<String, Deferred<Result<PostShowPack>>>()
    private val inFlightLock = Mutex()

    /** The post-show has its own switch. Writing cards with AI also needs a key and consent. */
    private fun enabled(): Boolean = PreshowSettings.postShowEnabled.value

    private fun aiReady(): Boolean = enabled() && DiscoverAiSettingsRepository.snapshot().isConfigured

    /**
     * Whether cards can be produced for [scope]: a film can always fall back to Wikipedia; a
     * finale needs the AI provider, because Wikipedia cannot honour the per-season spoiler gate.
     */
    fun isReady(scope: PostShowScope = PostShowScope.Film): Boolean =
        if (scope == PostShowScope.Film) enabled() else aiReady()

    private fun keyFor(meta: MetaDetails, scope: PostShowScope, model: String? = null): Pair<String, TriviaSource> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        val source = meta.toTriviaSource()
        val key = postShowCacheKey(
            metaId = meta.id,
            scope = scope,
            model = model ?: settings.effectiveModel,
            languageTag = ThemeSettingsRepository.selectedAppLanguage.value.code,
            fingerprint = source.fingerprint(),
        )
        return key to source
    }

    private fun wikiKeyFor(meta: MetaDetails): String =
        // English only, whatever the app language, so the language slot is fixed.
        postShowCacheKey(meta.id, PostShowScope.Film, PostShowWikipedia.MODEL, "en", meta.toTriviaSource().fingerprint())

    private fun peekWiki(meta: MetaDetails): PostShowPack? =
        PostShowCache.get(wikiKeyFor(meta))?.pack?.takeUnless { it.isEmpty }

    /** Cache-only lookup. Never makes a request. */
    fun peek(meta: MetaDetails, scope: PostShowScope = PostShowScope.Film): PostShowPack? {
        if (!isReady(scope)) return null
        val (key, source) = keyFor(meta, scope)
        if (!source.isUsable) return null
        if (aiReady()) PostShowCache.get(key)?.pack?.takeUnless { it.isEmpty }?.let { return it }
        return if (scope == PostShowScope.Film) peekWiki(meta) else null
    }

    /** Fire-and-forget: starts generation if nothing is cached or in flight. Errors are logged only. */
    fun prefetchFor(type: String, metaId: String, scope: PostShowScope = PostShowScope.Film) {
        if (!isReady(scope)) return
        workScope.launch {
            val meta = MetaDetailsRepository.peekAny(type, metaId)
                ?: runCatching { MetaDetailsRepository.fetch(type, metaId) }.getOrNull()
                ?: return@launch
            if (peek(meta, scope) == null) pack(meta, scope)
        }
    }

    /**
     * The pack for the credits, waiting up to [waitMs] for one still being generated. Past that it
     * gives up (the credits are short) and the request still completes into the cache. A film
     * whose AI pack is late gets its Wikipedia cards instead, if they can be had in a few seconds.
     */
    suspend fun packFor(
        type: String,
        metaId: String,
        scope: PostShowScope = PostShowScope.Film,
        waitMs: Long = 8_000,
    ): Result<PostShowPack> {
        if (!isReady(scope)) return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnabled))
        val meta = MetaDetailsRepository.peekAny(type, metaId)
            ?: withTimeoutOrNull(waitMs) {
                runCatching { MetaDetailsRepository.fetch(type, metaId) }.getOrNull()
            }
            ?: return Result.failure(TriviaUnavailableException(TriviaUnavailable.MetadataNotFound))
        peek(meta, scope)?.let { return Result.success(it) }
        withTimeoutOrNull(waitMs) { pack(meta, scope) }?.let { return it }
        if (scope == PostShowScope.Film) {
            withTimeoutOrNull(WIKI_LATE_WAIT_MS) { wikipedia(meta) }?.let { return Result.success(it) }
        }
        return Result.failure(TriviaUnavailableException(TriviaUnavailable.TimedOut))
    }

    private suspend fun wikipedia(meta: MetaDetails): PostShowPack? {
        peekWiki(meta)?.let { return it }
        val pack = PostShowWikipedia.packFor(meta) ?: return null
        PostShowCache.put(
            wikiKeyFor(meta),
            PostShowCacheEntry(pack = pack, builtAtEpochMs = System.currentTimeMillis(), model = PostShowWikipedia.MODEL),
        )
        return pack
    }

    suspend fun pack(
        meta: MetaDetails,
        scope: PostShowScope = PostShowScope.Film,
        forceRefresh: Boolean = false,
    ): Result<PostShowPack> {
        if (!isReady(scope)) return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnabled))
        val source = meta.toTriviaSource()
        if (!source.isUsable) {
            return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnoughMetadata))
        }
        val isFilm = scope == PostShowScope.Film
        if (!aiReady()) {
            return wikipedia(meta)?.let { Result.success(it) }
                ?: Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnoughMetadata))
        }
        val aiResult = aiPack(meta, scope, forceRefresh)
        if (aiResult.isSuccess || !isFilm) return aiResult
        return wikipedia(meta)?.let { Result.success(it) } ?: aiResult
    }

    private suspend fun aiPack(
        meta: MetaDetails,
        scope: PostShowScope,
        forceRefresh: Boolean,
    ): Result<PostShowPack> {
        val (key, source) = keyFor(meta, scope)
        if (!forceRefresh) {
            PostShowCache.get(key)?.pack?.takeUnless { it.isEmpty }?.let { return Result.success(it) }
        }
        val deferred = inFlightLock.withLock {
            inFlight[key]?.takeIf { it.isActive } ?: workScope.async {
                try {
                    runCatching { generate(meta, source, key, scope) }.getOrElse { Result.failure(it) }
                } finally {
                    inFlightLock.withLock { if (inFlight[key] === coroutineContext[Job]) inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    private suspend fun generate(
        meta: MetaDetails,
        source: TriviaSource,
        cacheKey: String,
        scope: PostShowScope,
    ): Result<PostShowPack> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        val languageTag = ThemeSettingsRepository.selectedAppLanguage.value.code
        log.i { "post-show ${meta.name}: requesting, model=${settings.effectiveModel}" }

        val prompt = buildPostShowPrompt(source, languageTag, scope)
        var raw: String? = null
        var attempt = 0
        while (raw == null) {
            val outcome = DiscoverAiClient.complete(settings, prompt)
            raw = outcome.getOrNull()
            if (raw != null) break
            val failure = outcome.exceptionOrNull()
            val wait = retryDelayMs(failure, attempt)
            if (wait == null || attempt >= MAX_RETRIES) {
                log.w { "post-show ${meta.name} failed after ${attempt + 1} attempt(s): $failure" }
                return Result.failure(failure ?: DiscoverAiException(DiscoverAiError.Unreadable))
            }
            log.i { "post-show ${meta.name}: provider busy, retry ${attempt + 1}/$MAX_RETRIES in ${wait}ms" }
            delay(wait)
            attempt++
        }

        val text = raw.orEmpty()
        val pack = parsePostShowPack(text)
        log.i { "post-show ${meta.name}: ${pack.cards.size} cards, ${pack.questions.size} questions from ${text.length} chars" }
        // An unusable reply is not cached, so the next attempt simply asks again.
        if (pack.isEmpty) return Result.failure(DiscoverAiException(DiscoverAiError.Unreadable))
        PostShowCache.put(
            cacheKey,
            PostShowCacheEntry(pack = pack, builtAtEpochMs = System.currentTimeMillis(), model = settings.effectiveModel),
        )
        return Result.success(pack)
    }
}

private const val MAX_RETRIES = 3
private const val WIKI_LATE_WAIT_MS = 6_000L
