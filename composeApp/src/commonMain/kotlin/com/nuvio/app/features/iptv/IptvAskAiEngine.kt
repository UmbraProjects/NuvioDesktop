package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the AI side of Just ask is doing for the question on screen. */
enum class IptvAskAiState { Off, Asking, Answered, Failed }

/**
 * fork:live-tv-ask-ai - asks the viewer's own AI (the key and consent under Settings > Discover) to answer Just ask.
 * One request per question, within [DAILY_ASKS] a day; an answer is kept for [KEEP_MS] for the same question.
 */
object IptvAskAiEngine {
    private val log = Logger.withTag("LiveTvAsk")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private const val DAILY_ASKS = 40
    private const val KEEP_MS = 15 * 60_000L

    private val _pick = MutableStateFlow<IptvAiPick?>(null)
    val pick: StateFlow<IptvAiPick?> = _pick.asStateFlow()
    private val _state = MutableStateFlow(IptvAskAiState.Off)
    val state: StateFlow<IptvAskAiState> = _state.asStateFlow()

    private var pickedAt = 0L
    private var day = -1L
    private var asksToday = 0

    /** The switch, a key and consent. */
    fun allowed(): Boolean {
        val ai = DiscoverAiSettingsRepository.snapshot()
        return IptvRepository.settings.value.askAi && ai.isConfigured && ai.consentGiven
    }

    private fun key(question: String) = question.trim().lowercase()

    /** The kept answer for [question], if it is still fresh. */
    fun answerFor(question: String, now: Long = System.currentTimeMillis()): IptvAiPick? =
        _pick.value?.takeIf { key(it.question) == key(question) && now - pickedAt < KEEP_MS }

    /** Asks about [question] with [candidates] (see IptvAskAiRules.candidates), unless it was just answered. */
    fun ask(question: String, candidates: List<IptvWallProgramme>, now: Long = System.currentTimeMillis()) {
        if (question.isBlank() || candidates.isEmpty() || !allowed()) {
            if (!allowed()) _state.value = IptvAskAiState.Off
            return
        }
        if (answerFor(question, now) != null) {
            _state.value = IptvAskAiState.Answered
            return
        }
        val today = IptvDecide.nightOf(now)
        if (day != today) {
            day = today
            asksToday = 0
        }
        val settings = DiscoverAiSettingsRepository.snapshot()
        if (asksToday >= DAILY_ASKS || DiscoverAiClient.rateLimitedForSeconds(settings) > 0) {
            _state.value = IptvAskAiState.Failed
            return
        }
        asksToday++
        job?.cancel()
        _state.value = IptvAskAiState.Asking
        job = scope.launch {
            val result = DiscoverAiClient.complete(
                settings,
                DiscoverAiPromptText(IptvAskAiRules.SYSTEM, IptvAskAiRules.prompt(question, candidates, now)),
            )
            val reply = result.getOrNull()
            val choices = reply?.let { IptvAskAiRules.choices(IptvAskAiRules.parse(it, candidates.size), candidates) }.orEmpty()
            if (choices.isEmpty()) {
                log.w { "Just ask: no usable answer (${result.exceptionOrNull()?.message ?: "nothing picked from the list"})" }
                _state.value = IptvAskAiState.Failed
                return@launch
            }
            pickedAt = System.currentTimeMillis()
            _pick.value = IptvAiPick(question, choices)
            _state.value = IptvAskAiState.Answered
        }
    }
}
