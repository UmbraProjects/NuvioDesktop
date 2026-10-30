package com.nuvio.app.features.discover

/**
 * Which service Discover's AI rows call — plan §5.
 *
 * Two backends cover the field: nearly everything speaks OpenAI's chat-completions shape, and
 * Anthropic does not. There is deliberately no third for "Gemini" or "Groq" — those are reached
 * through [OpenAiCompat] with their own base URL, which is why that option carries one.
 */
enum class DiscoverAiProvider(val defaultModel: String) {
    /** OpenAI, OpenRouter, Groq, Ollama, LM Studio, Gemini's compat endpoint — base URL + key. */
    OpenAiCompat(defaultModel = "gpt-4o-mini"),

    /**
     * Groq's hosted models. Speaks the OpenAI chat-completions shape, so it shares that code path;
     * it exists as its own choice so the base URL and a sensible model are preset and the user only
     * pastes a key. Groq's free tier is fast, which suits a pre-show that only waits a few seconds.
     */
    Groq(defaultModel = "llama-3.3-70b-versatile"),

    /** Anthropic's native Messages API. */
    Anthropic(defaultModel = "claude-opus-5"),
}

const val DISCOVER_AI_DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
const val DISCOVER_AI_GROQ_BASE_URL = "https://api.groq.com/openai/v1"
const val DISCOVER_AI_ANTHROPIC_BASE_URL = "https://api.anthropic.com"

data class DiscoverAiSettings(
    val provider: DiscoverAiProvider = DiscoverAiProvider.OpenAiCompat,
    val apiKey: String = "",
    /** Only meaningful for [DiscoverAiProvider.OpenAiCompat]; Anthropic's host is not configurable. */
    val baseUrl: String = DISCOVER_AI_DEFAULT_OPENAI_BASE_URL,
    /** Blank means the provider's own default — see [effectiveModel]. */
    val model: String = "",
    /**
     * The privacy dialog was shown and accepted. Separate from [enabled] on purpose: turning the
     * feature off and on again must not re-ask, and must not silently re-consent either.
     */
    val consentGiven: Boolean = false,
    val enabled: Boolean = false,
    /**
     * Re-generate every row once a day without being asked. Default off, and it stays default off:
     * every refresh spends the user's own money, so the opt-in has to be theirs.
     */
    val dailyRefresh: Boolean = false,
    /**
     * Season recaps on the details screen are on — a second consumer of the same credential.
     *
     * Its own switch rather than a reuse of [enabled], because the two features cost different
     * things and are wanted independently: recaps are one small request on an explicit click, AI
     * rows are a browse feed. Sharing one toggle would mean anyone who wants a recap has to turn on
     * a Discover feed they never asked for, and the reverse.
     */
    val recapEnabled: Boolean = false,
    /**
     * Recaps may draw on the model's own knowledge of the series for events before the cut-off.
     *
     * Off by default and deliberately not the same switch as [recapEnabled]: it changes what a
     * recap is allowed to be *wrong about*, not whether the feature exists. Shipped to be judged in
     * the field by people who know the shows, because the failure it risks — a later season's event
     * reported as already having happened — is only visible to someone who has seen the show.
     */
    val recapUseModelKnowledge: Boolean = false,
    /**
     * The pre-show (trivia slides and quiz before a film starts) may ask the provider for trivia.
     *
     * A third consumer of the same credential, with its own switch for the same reason recaps
     * have one: it costs one small request per film, on playback, and is wanted independently of
     * Discover rows. Off by default; every request is billed to the user's own key.
     */
    val triviaEnabled: Boolean = false,
) {
    val effectiveModel: String get() = model.trim().ifBlank { provider.defaultModel }

    val effectiveBaseUrl: String
        get() = when (provider) {
            DiscoverAiProvider.Anthropic -> DISCOVER_AI_ANTHROPIC_BASE_URL
            DiscoverAiProvider.Groq -> DISCOVER_AI_GROQ_BASE_URL
            DiscoverAiProvider.OpenAiCompat ->
                baseUrl.trim().trimEnd('/').ifBlank { DISCOVER_AI_DEFAULT_OPENAI_BASE_URL }
        }

    /**
     * A request could be made: credential, model and consent are all present.
     *
     * Deliberately says nothing about *which* feature wants to make it. This is the half
     * [DiscoverAiClient] checks, so that adding a consumer does not mean teaching the client about
     * another feature flag; each consumer gates on its own switch below.
     */
    val isConfigured: Boolean
        get() = consentGiven && apiKey.isNotBlank() && effectiveModel.isNotBlank()

    /** Discover's AI rows may generate. */
    val isReady: Boolean
        get() = enabled && isConfigured

    /** The details screen may build a recap. */
    val isRecapReady: Boolean
        get() = recapEnabled && isConfigured

    /** The pre-show may ask the AI provider for trivia. Wikipedia and metadata slides only need [triviaEnabled]. */
    val isTriviaReady: Boolean
        get() = triviaEnabled && isConfigured
}
