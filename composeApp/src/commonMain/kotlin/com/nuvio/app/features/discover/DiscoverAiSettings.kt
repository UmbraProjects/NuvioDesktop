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

    /** Anthropic's native Messages API. */
    Anthropic(defaultModel = "claude-opus-5"),
}

const val DISCOVER_AI_DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
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
) {
    val effectiveModel: String get() = model.trim().ifBlank { provider.defaultModel }

    val effectiveBaseUrl: String
        get() = when (provider) {
            DiscoverAiProvider.Anthropic -> DISCOVER_AI_ANTHROPIC_BASE_URL
            DiscoverAiProvider.OpenAiCompat ->
                baseUrl.trim().trimEnd('/').ifBlank { DISCOVER_AI_DEFAULT_OPENAI_BASE_URL }
        }

    /** Everything needed to make a request is present and the user has agreed to it being made. */
    val isReady: Boolean
        get() = enabled && consentGiven && apiKey.isNotBlank() && effectiveModel.isNotBlank()
}
