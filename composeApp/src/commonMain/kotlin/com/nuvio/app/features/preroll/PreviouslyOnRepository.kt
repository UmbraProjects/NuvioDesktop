package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Produces the "previously on" pack for one episode: cache, or one request to the viewer's own AI
 * provider, or (no key, or the request failed or was late) the addon's overview as plain cards.
 *
 * Same billing rules as the other AI features: one request per key, started only when the viewer
 * plays an eligible episode, and an unusable reply is never cached.
 */
internal object PreviouslyOnRepository {
    private val log = Logger.withTag("PreviouslyOn")

    /** Generation runs here, so leaving the screen cannot waste a paid request. */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = mutableMapOf<String, Deferred<TriviaPack?>>()
    private val inFlightLock = Mutex()

    private const val MAX_RETRIES = 3

    private fun cacheKey(meta: MetaDetails, target: PreviouslyOnTarget, source: TriviaSource): String {
        val settings = DiscoverAiSettingsRepository.snapshot()
        return listOf(
            "previouslyon", "v$PREVIOUSLY_ON_PROMPT_VERSION", meta.id, "s${target.season}e${target.episode}",
            settings.effectiveModel, ThemeSettingsRepository.selectedAppLanguage.value.code, target.fingerprint(source),
        ).joinToString("|")
    }

    suspend fun packFor(meta: MetaDetails, target: PreviouslyOnTarget, waitMs: Long): TriviaPack? {
        val source = meta.toTriviaSource()
        val plain = { previouslyOnFromOverview(target, source) }
        if (!DiscoverAiSettingsRepository.snapshot().isConfigured || source.title.isBlank()) return plain()
        val key = cacheKey(meta, target, source)
        TriviaCache.get(key)?.pack?.takeUnless { it.isEmpty }?.let { return it }
        val deferred = inFlightLock.withLock {
            inFlight[key]?.takeIf { it.isActive } ?: workScope.async {
                try {
                    generate(meta, source, target, key)
                } finally {
                    inFlightLock.withLock { if (inFlight[key] === coroutineContext[Job]) inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return withTimeoutOrNull(waitMs) { deferred.await() } ?: plain()
    }

    private suspend fun generate(meta: MetaDetails, source: TriviaSource, target: PreviouslyOnTarget, key: String): TriviaPack? {
        val settings = DiscoverAiSettingsRepository.snapshot()
        val languageTag = ThemeSettingsRepository.selectedAppLanguage.value.code
        log.i { "recap ${meta.name} ${target.label}: requesting, model=${settings.effectiveModel}" }
        val prompt = buildPreviouslyOnPrompt(source, target, languageTag)
        var attempt = 0
        var raw: String? = null
        while (raw == null) {
            val outcome = DiscoverAiClient.complete(settings, prompt)
            raw = outcome.getOrNull()
            if (raw != null) break
            val wait = retryDelayMs(outcome.exceptionOrNull(), attempt)
            if (wait == null || attempt >= MAX_RETRIES) {
                log.w { "recap ${meta.name} failed after ${attempt + 1} attempt(s): ${outcome.exceptionOrNull()}" }
                return null
            }
            delay(wait)
            attempt++
        }
        val pack = parsePreviouslyOnPack(raw.orEmpty(), target.lastTimeHeading()).withFallbackTheme(source.genres)
        log.i { "recap ${meta.name} ${target.label}: ${pack.slides.size} cards, ${pack.questions.size} questions" }
        if (pack.slides.isEmpty()) return null
        TriviaCache.put(
            key,
            TriviaCacheEntry(pack = pack, builtAtEpochMs = System.currentTimeMillis(), model = settings.effectiveModel, mode = "previously_on"),
        )
        return pack
    }
}
