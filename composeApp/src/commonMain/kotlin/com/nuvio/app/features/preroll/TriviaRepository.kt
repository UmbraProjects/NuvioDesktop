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
 * Produces one film's trivia pack: cache, or a single request to the user's own provider.
 *
 * Follows `RecapRepository` on purpose, and reuses [DiscoverAiClient] for the same reasons: it
 * already handles an Anthropic refusal arriving as HTTP 200, rate-limit hints from either provider
 * family, and the timeout that actually cancels. Every OpenAI-compatible endpoint (Gemini's compat
 * endpoint, xAI, GitHub Models, OpenRouter, Ollama) is reached through that client's base URL.
 *
 * **Billing discipline.** Nothing here generates on its own schedule. [prefetch] is meant to be
 * called when the viewer commits to playing a film, never while browsing, because browsing a
 * hundred titles must not cost a hundred requests on someone's own key.
 */
object TriviaRepository {
    private val log = Logger.withTag("Trivia")

    /**
     * Where a generation actually runs — **not the caller's scope**. The caller is a composable;
     * leaving the screen mid-request would cancel it after the provider had already been asked and
     * billed. Run here instead: the answer lands in the cache and the next caller gets it free.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One request per key at a time; callers arriving mid-flight await the same answer. */
    private val inFlight = mutableMapOf<String, Deferred<Result<TriviaPack>>>()
    private val inFlightLock = Mutex()

    private fun keyFor(meta: MetaDetails, mode: TriviaMode): Pair<String, TriviaSource> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        val source = meta.toTriviaSource()
        val key = triviaCacheKey(
            metaId = meta.id,
            model = settings.effectiveModel,
            languageTag = ThemeSettingsRepository.selectedAppLanguage.value.code,
            fingerprint = source.fingerprint(),
            mode = mode,
        )
        return key to source
    }

    /**
     * Cache-only lookup. Never makes a request. The pre-show calls this first so a film whose
     * trivia was already fetched starts instantly.
     */
    fun peek(meta: MetaDetails, mode: TriviaMode = TriviaMode.ModelKnowledge): TriviaPack? {
        if (!DiscoverAiSettingsRepository.snapshot().isTriviaReady) return null
        val (key, source) = keyFor(meta, mode)
        if (!source.isUsable) return null
        return TriviaCache.get(key)?.pack?.takeUnless { it.isEmpty }
    }

    /**
     * "Not right?" in the pre-show. An AI pack is dropped from the cache so the next play asks
     * the provider again (one request); a Wikipedia pack is blocked for this film, since a new
     * fetch would find the same article. Details-only packs cannot be wrong, so nothing happens.
     */
    fun discard(meta: MetaDetails, origin: TriviaOrigin) {
        when (origin) {
            TriviaOrigin.Ai -> listOf(TriviaMode.ModelKnowledge, TriviaMode.MetadataOnly)
                .forEach { TriviaCache.remove(keyFor(meta, it).first) }
            TriviaOrigin.Wikipedia -> TriviaWikipedia.block(meta)
            TriviaOrigin.Metadata -> Unit
        }
    }

    /** Fire-and-forget: starts generation if nothing is cached or in flight. Errors are logged only. */
    fun prefetch(meta: MetaDetails, mode: TriviaMode = TriviaMode.ModelKnowledge) {
        if (peek(meta, mode) != null) return
        scope.launch { trivia(meta, mode) }
    }

    /**
     * The hook for the moment the viewer commits to playing (the stream picker opening).
     *
     * Takes the title's type and id rather than a [MetaDetails] because that is all the playback
     * path has. The record is almost always already cached by the details screen, so this reads
     * the cache first and only fetches when nothing is there. Returns immediately and does nothing
     * at all when trivia is off, so calling it unconditionally costs a flag read.
     */
    fun prefetchFor(type: String, metaId: String, mode: TriviaMode = TriviaMode.ModelKnowledge) {
        if (!DiscoverAiSettingsRepository.snapshot().isTriviaReady) return
        scope.launch {
            val meta = MetaDetailsRepository.peekAny(type, metaId)
                ?: runCatching { MetaDetailsRepository.fetch(type, metaId) }.getOrNull()
                ?: return@launch
            prefetch(meta, mode)
        }
    }

    /**
     * The pack for the pre-show, waiting up to [waitMs] for one that is still being generated.
     *
     * Cache first, so a film whose trivia was fetched earlier (or by the prefetch when the stream
     * picker opened) shows instantly. Past [waitMs] this gives up and returns null: the pre-show
     * only exists to fill the wait for the stream, so a pack that arrives after playback has
     * started is worth nothing, and the request still completes into the cache for next time.
     */
    suspend fun packFor(
        type: String,
        metaId: String,
        waitMs: Long = 6_000,
        mode: TriviaMode = TriviaMode.ModelKnowledge,
    ): Result<TriviaPack> {
        if (!DiscoverAiSettingsRepository.snapshot().isTriviaReady) {
            return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnabled))
        }
        val meta = MetaDetailsRepository.peekAny(type, metaId)
            ?: withTimeoutOrNull(waitMs) {
                runCatching { MetaDetailsRepository.fetch(type, metaId) }.getOrNull()
            }
            ?: return Result.failure(TriviaUnavailableException(TriviaUnavailable.MetadataNotFound))
        peek(meta, mode)?.let { return Result.success(it) }
        return withTimeoutOrNull(waitMs) { trivia(meta, mode) }
            ?: Result.failure(TriviaUnavailableException(TriviaUnavailable.TimedOut))
    }

    suspend fun trivia(
        meta: MetaDetails,
        mode: TriviaMode = TriviaMode.ModelKnowledge,
        forceRefresh: Boolean = false,
    ): Result<TriviaPack> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        if (!settings.isTriviaReady) {
            return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnabled))
        }
        val (key, source) = keyFor(meta, mode)
        if (!source.isUsable) {
            return Result.failure(TriviaUnavailableException(TriviaUnavailable.NotEnoughMetadata))
        }

        if (!forceRefresh) {
            TriviaCache.get(key)?.pack?.takeUnless { it.isEmpty }?.let { return Result.success(it) }
        }

        val deferred = inFlightLock.withLock {
            inFlight[key]?.takeIf { it.isActive } ?: scope.async {
                try {
                    runCatching { generate(meta, source, key, mode) }.getOrElse { Result.failure(it) }
                } finally {
                    // Identity-checked: a fast generation must not remove a key it no longer owns,
                    // or a later "refresh" would be handed this completed answer instead.
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
        mode: TriviaMode,
    ): Result<TriviaPack> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        val languageTag = ThemeSettingsRepository.selectedAppLanguage.value.code
        log.i { "trivia ${meta.name}: requesting, mode=$mode, model=${settings.effectiveModel}" }

        val prompt = buildTriviaPrompt(source, languageTag, mode)
        var raw: String? = null
        var attempt = 0
        while (raw == null) {
            val outcome = DiscoverAiClient.complete(settings, prompt)
            raw = outcome.getOrNull()
            if (raw != null) break
            val failure = outcome.exceptionOrNull()
            val wait = retryDelayMs(failure, attempt)
            if (wait == null || attempt >= MAX_RETRIES) {
                log.w { "trivia ${meta.name} failed after ${attempt + 1} attempt(s): $failure" }
                return Result.failure(failure ?: DiscoverAiException(DiscoverAiError.Unreadable))
            }
            // "High demand" 503s are routine on shared Gemini capacity and clear in seconds. The
            // trailers give the pre-show a minute or more, so waiting here costs the viewer nothing.
            log.i { "trivia ${meta.name}: provider busy, retry ${attempt + 1}/$MAX_RETRIES in ${wait}ms" }
            delay(wait)
            attempt++
        }

        val text = raw.orEmpty()
        val pack = parseTriviaPack(text).withFallbackTheme(source.genres)
        log.i {
            "trivia ${meta.name}: ${pack.slides.size} slides, ${pack.questions.size} questions " +
                "from ${text.length} chars"
        }
        // An unusable reply is not cached: caching it would pin the failure until the film's
        // metadata changed. The next play simply asks again.
        if (pack.isEmpty) return Result.failure(DiscoverAiException(DiscoverAiError.Unreadable))

        // Cached before it is shown, for the same reason recaps are: the answer is already paid for.
        TriviaCache.put(
            cacheKey,
            TriviaCacheEntry(
                pack = pack,
                builtAtEpochMs = System.currentTimeMillis(),
                model = settings.effectiveModel,
                mode = mode.name,
            ),
        )
        return Result.success(pack)
    }
}

private const val MAX_RETRIES = 5
private const val MAX_RETRY_DELAY_MS = 20_000L

/**
 * How long to wait before retrying [failure], or null when retrying cannot help: a rejected key,
 * a refusal or an unreadable reply will fail identically. Overload (5xx), rate limits, timeouts and
 * dropped connections are transient. Backoff 3s, 6s, 12s, 20s, 20s unless the provider named a time.
 */
internal fun retryDelayMs(failure: Throwable?, attempt: Int): Long? {
    val error = (failure as? DiscoverAiException)?.error ?: return null
    val backoff = (3_000L shl attempt.coerceAtMost(3)).coerceAtMost(MAX_RETRY_DELAY_MS)
    return when {
        error is DiscoverAiError.Http && error.status in 500..599 -> backoff
        error is DiscoverAiError.RateLimited ->
            ((error.retryAfterSeconds ?: 0) * 1000L).coerceIn(backoff, MAX_RETRY_DELAY_MS)
        error is DiscoverAiError.Timeout -> backoff
        else -> null
    }
}

/** Trivia was not attempted, and why. Nothing was sent and nothing was billed. */
enum class TriviaUnavailable { NotEnabled, NotEnoughMetadata, MetadataNotFound, TimedOut }

class TriviaUnavailableException(val reason: TriviaUnavailable) : Exception(reason.toString())
