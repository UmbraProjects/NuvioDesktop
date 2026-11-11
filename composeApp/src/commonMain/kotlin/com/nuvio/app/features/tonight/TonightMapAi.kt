package com.nuvio.app.features.tonight

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * fork:tonight - phase 5: the AI's reading of each title's tone for The Map. Asked only while The
 * Map is open, only with Settings > Random Play > Tonight picker > "Let the AI refine The Map" on
 * (which needs the shared AI consent), twelve titles to a request, a few seconds apart, within a
 * daily allowance of requests. Answers are kept per title and never expire; a title the AI
 * skipped is asked again after a day.
 */
object TonightMapAi {
    private val log = Logger.withTag("Tonight")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private const val DAILY_REQUESTS = 25
    private const val PAUSE_MS = 8_000L
    private const val MISS_RETRY_MS = 24 * 60 * 60_000L

    private val _tones = MutableStateFlow<Map<String, Float>>(emptyMap())
    val tones: StateFlow<Map<String, Float>> = _tones.asStateFlow()
    private var misses: Map<String, Long> = emptyMap()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val (t, m) = runCatching { decodeTones(TonightStorage.getString("tones")) }.getOrDefault(emptyMap<String, Float>() to emptyMap())
        _tones.value = t
        misses = m
    }

    /** The kept tones, read from disk the first time; never asks the AI. */
    fun ensureLoaded() = load()

    /** Whether asking is allowed now: the switch, a key and consent. */
    fun allowed(): Boolean {
        val ai = DiscoverAiSettingsRepository.snapshot()
        return TonightSettingsRepository.state.value.mapAiEnabled && ai.isConfigured && ai.consentGiven
    }

    /** Asks about the titles in [pool] that have no tone yet, in the background. Call while The Map is open. */
    fun fill(pool: List<TonightTitle>, todayIsoDate: String) {
        load()
        if (!allowed() || job?.isActive == true) return
        val now = System.currentTimeMillis()
        val todo = pool.distinctBy { it.key }.filter { t ->
            t.key !in _tones.value && (misses[t.key]?.let { now - it > MISS_RETRY_MS } ?: true)
        }
        if (todo.isEmpty()) return
        job = scope.launch {
            for (batch in todo.chunked(TONIGHT_TONE_BATCH)) {
                if (!allowed()) break
                val used = requestsToday(todayIsoDate)
                if (used >= DAILY_REQUESTS) {
                    log.i { "Tonight map: daily allowance of $DAILY_REQUESTS requests used" }
                    break
                }
                val settings = DiscoverAiSettingsRepository.snapshot()
                if (DiscoverAiClient.rateLimitedForSeconds(settings) > 0) break
                countRequest(todayIsoDate, used + 1)
                val result = DiscoverAiClient.complete(
                    settings,
                    DiscoverAiPromptText(TONIGHT_TONE_SYSTEM_PROMPT, tonightTonePrompt(batch)),
                )
                val reply = result.getOrNull()
                if (reply == null) {
                    log.w { "Tonight map: tone request failed: ${result.exceptionOrNull()?.message}" }
                    break
                }
                val parsed = parseToneReply(reply, batch.size)
                val stamp = System.currentTimeMillis()
                val nextTones = _tones.value.toMutableMap()
                val nextMisses = misses.toMutableMap()
                batch.forEachIndexed { i, t ->
                    val tone = parsed[i]
                    if (tone != null) {
                        nextTones[t.key] = tone
                        nextMisses.remove(t.key)
                    } else {
                        nextMisses[t.key] = stamp
                    }
                }
                _tones.value = nextTones
                misses = nextMisses
                runCatching { TonightStorage.putString("tones", encodeTones(nextTones, nextMisses)) }
                log.i { "Tonight map: AI placed ${parsed.size} of ${batch.size} titles" }
                delay(PAUSE_MS)
            }
        }
    }

    /** Stops asking (The Map closed). Answers already kept stay. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun requestsToday(today: String): Int {
        val stored = TonightStorage.getString("toneDay")?.split('|') ?: return 0
        return if (stored.getOrNull(0) == today) stored.getOrNull(1)?.toIntOrNull() ?: 0 else 0
    }

    private fun countRequest(today: String, count: Int) {
        TonightStorage.putString("toneDay", "$today|$count")
    }
}
