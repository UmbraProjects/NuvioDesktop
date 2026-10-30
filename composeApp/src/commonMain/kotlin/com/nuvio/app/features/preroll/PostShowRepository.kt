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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inFlight = mutableMapOf<String, Deferred<Result<PostShowPack>>>()
    private val inFlightLock = Mutex()

    /** The post-show has its own switch, but it still needs a key and consent to ask anything. */
    fun isReady(): Boolean =
        PreshowSettings.postShowEnabled.value && DiscoverAiSettingsRepository.snapshot().isConfigured

    private fun keyFor(meta: MetaDetails, scope: PostShowScope): Pair<String, TriviaSource> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        val source = meta.toTriviaSource()
        val key = postShowCacheKey(
            metaId = meta.id,
            scope = scope,
            model = settings.effectiveModel,
            languageTag = ThemeSettingsRepository.selectedAppLanguage.value.code,
            fingerprint = source.fingerprint(),
        )
        return key to source
    }

    /** Cache-only lookup. Never makes a request. */
    fun peek(meta: MetaDetails, scope: PostShowScope = PostShowScope.Film): PostShowPack? {
        if (!isReady()) return null
        val (key, source) = keyFor(meta, scope)
        if (!source.isUsable) return null
        return PostShowCache.get(key)?.pack?.takeUnless { it.isEmpty }
    }

    /** Fire-and-forget: starts generation if nothing is cached or in flight. Errors are logged only. */
    fun prefetchFor(type: String, metaId: String, scope: PostShowScope = PostShowScope.Film) {
        if (!isReady()) return
        scope.launch {
            val meta = MetaDetailsRepository.peekAny(type, metaId)
                ?: runCatching { MetaDetailsRepository.fetch(type, metaId) }.getOrNull()
                ?: return@launch
            if (peek(meta, scope) == null) pack(meta, scope)
        }
    }

    /**
     * The pack for the credits, waiting up to [waitMs] for one still being generated. Past that it
     * gives up (the credits are short) and the request still completes into the cache.
     */
    suspend fun packFor(
        type: String,
        metaId: String,
        scope: PostShowScope = PostShowScope.Film,
        waitMs: Long = 8_000,
    ): Result<PostShowPack> {
        if (!isReady()) return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnabled))
        val meta = MetaDetailsRepository.peekAny(type, metaId)
            ?: withTimeoutOrNull(waitMs) {
                runCatching { MetaDetailsRepository.fetch(type, metaId) }.getOrNull()
            }
            ?: return Result.failure(TriviaUnavailableException(TriviaUnavailable.MetadataNotFound))
        peek(meta, scope)?.let { return Result.success(it) }
        return withTimeoutOrNull(waitMs) { pack(meta, scope) }
            ?: Result.failure(TriviaUnavailableException(TriviaUnavailable.TimedOut))
    }

    suspend fun pack(
        meta: MetaDetails,
        scope: PostShowScope = PostShowScope.Film,
        forceRefresh: Boolean = false,
    ): Result<PostShowPack> {
        if (!isReady()) return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnabled))
        val (key, source) = keyFor(meta, scope)
        if (!source.isUsable) {
            return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnoughMetadata))
        }
        if (!forceRefresh) {
            PostShowCache.get(key)?.pack?.takeUnless { it.isEmpty }?.let { return Result.success(it) }
        }
        val deferred = inFlightLock.withLock {
            inFlight[key]?.takeIf { it.isActive } ?: scope.async {
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
